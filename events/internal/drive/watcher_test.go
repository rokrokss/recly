package drive

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"

	"github.com/rokrokss/recly/events/internal/state"
)

// fakeDrive serves the Drive endpoints recly-events uses from in-memory data: the changes feed,
// file metadata, queries (see matches), paging, and content (`alt=media`).
type fakeDrive struct {
	mu          sync.Mutex
	start       string
	pages       map[string]ChangePage
	files       map[string]File
	content     map[string]string
	failGet     bool
	failContent int
	listCalls   []string
}

// matches evaluates the part of the Drive query language recly-events writes — terms joined by
// `and`, a parenthesized group joined by `or` — the way Drive does. `name contains` matches a prefix
// only: "The contains operator only performs prefix matching for a name term"
// (https://developers.google.com/workspace/drive/api/guides/ref-search-terms, last updated
// 2026-09-03, read 2026-10-07), so `name contains '.meta.json'` finds no Recly file. A term it does
// not know is an error, answered 400 like Drive's "Invalid Value".
func matches(q string, f File) (bool, error) {
	for _, clause := range splitTop(q, " and ") {
		ok := false
		if strings.HasPrefix(clause, "(") && strings.HasSuffix(clause, ")") {
			for _, term := range splitTop(clause[1:len(clause)-1], " or ") {
				m, err := matchTerm(term, f)
				if err != nil {
					return false, err
				}
				ok = ok || m
			}
		} else {
			m, err := matchTerm(clause, f)
			if err != nil {
				return false, err
			}
			ok = m
		}
		if !ok {
			return false, nil
		}
	}
	return true, nil
}

func matchTerm(term string, f File) (bool, error) {
	term = strings.TrimSpace(term)
	if term == "trashed = false" {
		return !f.Trashed, nil
	}
	if v, ok := strings.CutSuffix(term, " in parents"); ok {
		return slices.Contains(f.Parents, unquote(v)), nil
	}
	for _, op := range []struct {
		prefix string
		match  func(string) bool
	}{
		{"name = ", func(v string) bool { return f.Name == v }},
		{"name contains ", func(v string) bool { return strings.HasPrefix(f.Name, v) }},
		{"mimeType = ", func(v string) bool { return f.MimeType == v }},
	} {
		if v, ok := strings.CutPrefix(term, op.prefix); ok {
			return op.match(unquote(v)), nil
		}
	}
	return false, fmt.Errorf("fake drive: unsupported query term %q", term)
}

// splitTop splits s at sep where sep is outside quotes and parentheses.
func splitTop(s, sep string) []string {
	var out []string
	depth, quoted, from := 0, false, 0
	for i := 0; i < len(s); i++ {
		switch c := s[i]; {
		case quoted && c == '\\':
			i++
		case c == '\'':
			quoted = !quoted
		case !quoted && c == '(':
			depth++
		case !quoted && c == ')':
			depth--
		case !quoted && depth == 0 && strings.HasPrefix(s[i:], sep):
			out = append(out, s[from:i])
			from = i + len(sep)
			i += len(sep) - 1
		}
	}
	return append(out, s[from:])
}

func unquote(v string) string {
	v = strings.TrimSpace(v)
	v = strings.TrimSuffix(strings.TrimPrefix(v, "'"), "'")
	return strings.ReplaceAll(strings.ReplaceAll(v, `\'`, `'`), `\\`, `\`)
}

func (d *fakeDrive) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	d.mu.Lock()
	defer d.mu.Unlock()
	switch {
	case r.URL.Path == "/changes/startPageToken":
		_ = json.NewEncoder(w).Encode(map[string]string{"startPageToken": d.start})
	case r.URL.Path == "/changes":
		_ = json.NewEncoder(w).Encode(d.pages[r.URL.Query().Get("pageToken")])
	case r.URL.Path == "/files":
		q := r.URL.Query().Get("q")
		d.listCalls = append(d.listCalls, q)
		var out []File
		for _, f := range d.files {
			ok, err := matches(q, f)
			if err != nil {
				http.Error(w, `{"error":{"code":400,"message":"Invalid Value"}}`, http.StatusBadRequest)
				return
			}
			if ok {
				out = append(out, f)
			}
		}
		// Every order the code asks for (name, createdTime) is newest first for these names.
		slices.SortFunc(out, func(a, b File) int { return strings.Compare(b.Name, a.Name) })
		from, _ := strconv.Atoi(r.URL.Query().Get("pageToken"))
		size, _ := strconv.Atoi(r.URL.Query().Get("pageSize"))
		res := map[string]any{"files": out[min(from, len(out)):]}
		if size > 0 && from+size < len(out) {
			res["files"], res["nextPageToken"] = out[from:from+size], strconv.Itoa(from+size)
		}
		_ = json.NewEncoder(w).Encode(res)
	case strings.HasPrefix(r.URL.Path, "/files/") && r.URL.Query().Get("alt") == "media":
		if d.failContent != 0 {
			http.Error(w, `{"error":{"errors":[{"reason":"insufficientPermissions"}],"message":"Request had insufficient authentication scopes."}}`, d.failContent)
			return
		}
		c, ok := d.content[strings.TrimPrefix(r.URL.Path, "/files/")]
		if !ok {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, c)
	case strings.HasPrefix(r.URL.Path, "/files/"):
		if d.failGet {
			http.Error(w, `{"error":"backend"}`, http.StatusServiceUnavailable)
			return
		}
		f, ok := d.files[strings.TrimPrefix(r.URL.Path, "/files/")]
		if !ok {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(f)
	default:
		http.NotFound(w, r)
	}
}

type emitted struct {
	id  string
	rec Recording
}

func setup(t *testing.T) (*Watcher, *fakeDrive, *[]emitted, *state.Store) {
	t.Helper()
	fd := &fakeDrive{
		start: "t1",
		pages: map[string]ChangePage{},
		files: map[string]File{
			"folder1": {ID: "folder1", Name: "20261001T064503Z_watch_01M3V3B6", MimeType: "application/vnd.google-apps.folder", Description: "Weekly sync", WebViewLink: "https://drive.google.com/drive/folders/folder1",
				AppProperties: map[string]string{"recordingId": "01M3V3B6P5NF6ZM7X1QDFERRWT"}},
			"json1": {ID: "json1", Name: "20261001T064503Z_watch_01M3V3B6.transcript.json", Parents: []string{"folder1"}},
		},
	}
	srv := httptest.NewServer(fd)
	t.Cleanup(srv.Close)
	store, err := state.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	var got []emitted
	w := &Watcher{
		API: &API{Client: srv.Client(), Base: srv.URL}, Store: store,
		Log:  slog.New(slog.NewTextHandler(io.Discard, nil)),
		Emit: func(id string, rec Recording) (bool, error) { got = append(got, emitted{id, rec}); return true, nil },
	}
	return w, fd, &got, store
}

var txt = File{
	ID: "txt1", Name: "20261001T064503Z_watch_01M3V3B6.transcript.txt", MimeType: "text/plain", Parents: []string{"folder1"},
	MD5: "aaa", WebViewLink: "https://drive.google.com/file/d/txt1/view",
}

func TestFirstPollRecordsCursorWithoutAnnouncing(t *testing.T) {
	w, _, got, store := setup(t)
	if err := w.Poll(context.Background()); err != nil {
		t.Fatal(err)
	}
	store.View(func(s *state.State) {
		if s.Drive.PageToken != "t1" {
			t.Fatalf("cursor = %q", s.Drive.PageToken)
		}
	})
	if len(*got) != 0 {
		t.Fatalf("announced %+v", *got)
	}
}

func TestAnnouncesNewTranscriptOnceAcrossPages(t *testing.T) {
	w, fd, got, store := setup(t)
	_ = w.Poll(context.Background())
	other := File{ID: "x", Name: "notes.txt", Parents: []string{"folder1"}}
	fd.pages["t1"] = ChangePage{NextPageToken: "t1b", Changes: []Change{{FileID: "x", File: &other}}}
	fd.pages["t1b"] = ChangePage{NewStartPageToken: "t2", Changes: []Change{{FileID: "txt1", File: &txt}}}
	if err := w.Poll(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(*got) != 1 {
		t.Fatalf("announced %d", len(*got))
	}
	rec := (*got)[0].rec
	if rec.Recording != "20261001T064503Z_watch_01M3V3B6" || rec.RecordingIDPrefix != "01M3V3B6" || rec.RecordingID != "01M3V3B6P5NF6ZM7X1QDFERRWT" || rec.Device != "watch" ||
		rec.StartedAt != "2026-10-01T06:45:03Z" || rec.Title == nil || *rec.Title != "Weekly sync" ||
		rec.Drive.FolderID != "folder1" || rec.Drive.TranscriptTxtFileID != "txt1" || rec.Drive.TranscriptJSONFileID != "json1" {
		t.Fatalf("recording = %+v", rec)
	}
	if !strings.HasPrefix((*got)[0].id, "evt_20261001T064503Z_watch_01M3V3B6_") {
		t.Fatalf("event id = %s", (*got)[0].id)
	}
	store.View(func(s *state.State) {
		if s.Drive.PageToken != "t2" {
			t.Fatalf("cursor = %q", s.Drive.PageToken)
		}
	})

	// The same version reported again (a metadata-only change) is not announced again.
	fd.pages["t2"] = ChangePage{NewStartPageToken: "t3", Changes: []Change{{FileID: "txt1", File: &txt}}}
	_ = w.Poll(context.Background())
	if len(*got) != 1 {
		t.Fatalf("re-announced the same version: %d", len(*got))
	}

	// A re-transcription rewrites the file: a new version is a new event.
	rewritten := txt
	rewritten.MD5 = "bbb"
	fd.pages["t3"] = ChangePage{NewStartPageToken: "t4", Changes: []Change{{FileID: "txt1", File: &rewritten}}}
	_ = w.Poll(context.Background())
	if len(*got) != 2 || (*got)[1].id == (*got)[0].id {
		t.Fatalf("re-transcription: %+v", *got)
	}
}

func TestFailedLookupKeepsCursorForRetry(t *testing.T) {
	w, fd, got, store := setup(t)
	_ = w.Poll(context.Background())
	fd.pages["t1"] = ChangePage{NewStartPageToken: "t2", Changes: []Change{{FileID: "txt1", File: &txt}}}
	fd.failGet = true
	if err := w.Poll(context.Background()); err == nil {
		t.Fatal("expected an error")
	}
	store.View(func(s *state.State) {
		if s.Drive.PageToken != "t1" || s.Drive.LastError == "" {
			t.Fatalf("cursor = %q error = %q", s.Drive.PageToken, s.Drive.LastError)
		}
	})
	fd.failGet = false
	if err := w.Poll(context.Background()); err != nil || len(*got) != 1 {
		t.Fatalf("retry: %v %d", err, len(*got))
	}
}

func TestSkipsEmptyTranscriptUntilItHasContent(t *testing.T) {
	w, fd, got, _ := setup(t)
	_ = w.Poll(context.Background())
	empty := txt
	empty.MD5, empty.Size = "d41d8cd98f00b204e9800998ecf8427e", "0"
	fd.pages["t1"] = ChangePage{NewStartPageToken: "t2", Changes: []Change{{FileID: "txt1", File: &empty}}}
	if err := w.Poll(context.Background()); err != nil || len(*got) != 0 {
		t.Fatalf("empty transcript announced: %v %d", err, len(*got))
	}
	filled := txt
	filled.Size = "120"
	fd.pages["t2"] = ChangePage{NewStartPageToken: "t3", Changes: []Change{{FileID: "txt1", File: &filled}}}
	if err := w.Poll(context.Background()); err != nil || len(*got) != 1 {
		t.Fatalf("re-transcribed transcript: %v %d", err, len(*got))
	}
}

func TestEditedTranscriptIsNotAnnouncedAgain(t *testing.T) {
	w, fd, got, store := setup(t)
	_ = w.Poll(context.Background())
	first := txt
	first.AppProperties = map[string]string{"reclyTranscript": "transcribed"}
	fd.pages["t1"] = ChangePage{NewStartPageToken: "t2", Changes: []Change{{FileID: "txt1", File: &first}}}
	if err := w.Poll(context.Background()); err != nil || len(*got) != 1 {
		t.Fatalf("transcribed: %v %d", err, len(*got))
	}
	// The user renames a speaker in the app: a new version marked edited is noted, not announced.
	edited := txt
	edited.MD5, edited.AppProperties = "edit1", map[string]string{"reclyTranscript": "edited"}
	fd.pages["t2"] = ChangePage{NewStartPageToken: "t3", Changes: []Change{{FileID: "txt1", File: &edited}}}
	if err := w.Poll(context.Background()); err != nil || len(*got) != 1 {
		t.Fatalf("edited: %v %d", err, len(*got))
	}
	store.View(func(s *state.State) {
		if s.Drive.Seen["txt1"] != "edit1" {
			t.Fatalf("seen = %v", s.Drive.Seen)
		}
	})
	// Transcribing it again is announced, as is a version with no mark at all.
	again := txt
	again.MD5, again.AppProperties = "again", map[string]string{"reclyTranscript": "transcribed"}
	unmarked := txt
	unmarked.ID, unmarked.MD5 = "txt2", "other"
	fd.pages["t3"] = ChangePage{NewStartPageToken: "t4", Changes: []Change{{FileID: "txt1", File: &again}, {FileID: "txt2", File: &unmarked}}}
	if err := w.Poll(context.Background()); err != nil || len(*got) != 3 {
		t.Fatalf("re-transcribed: %v %d", err, len(*got))
	}
}

func TestAnnouncesImportedRecordings(t *testing.T) {
	w, fd, got, _ := setup(t)
	_ = w.Poll(context.Background())
	imported := File{ID: "imp", Name: "20261003T100000Z_import_01M5BBBB.transcript.txt", MD5: "i"}
	fd.pages["t1"] = ChangePage{NewStartPageToken: "t2", Changes: []Change{{File: &imported}}}
	if err := w.Poll(context.Background()); err != nil || len(*got) != 1 || (*got)[0].rec.Device != "import" {
		t.Fatalf("import: %v %+v", err, *got)
	}
}

func TestIgnoresTrashedAndForeignFiles(t *testing.T) {
	w, fd, got, _ := setup(t)
	_ = w.Poll(context.Background())
	trashed := txt
	trashed.Trashed = true
	lookalike := File{ID: "y", Name: "20261001T064503Z_laptop_01M3V3B6.transcript.txt", MD5: "c"}
	fd.pages["t1"] = ChangePage{NewStartPageToken: "t2", Changes: []Change{{File: &trashed}, {File: &lookalike}, {FileID: "z", Removed: true}}}
	if err := w.Poll(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(*got) != 0 {
		t.Fatalf("announced %+v", *got)
	}
}

func TestLatestFindsNewestTranscript(t *testing.T) {
	w, fd, _, _ := setup(t)
	fd.files["txt1"] = txt
	f, err := Latest(context.Background(), w.API)
	if err != nil || f.ID != "txt1" {
		t.Fatalf("latest = %+v %v", f, err)
	}
}
