package drive

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/rokrokss/recly/events/internal/state"
)

// fakeDrive serves the four endpoints the watcher uses from in-memory data.
type fakeDrive struct {
	mu        sync.Mutex
	start     string
	pages     map[string]ChangePage
	files     map[string]File
	failGet   bool
	listCalls []string
	// account is the permissionId /about answers; empty answers 404.
	account    string
	aboutCalls int
}

func (d *fakeDrive) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	d.mu.Lock()
	defer d.mu.Unlock()
	switch {
	case r.URL.Path == "/changes/startPageToken":
		_ = json.NewEncoder(w).Encode(map[string]string{"startPageToken": d.start})
	case r.URL.Path == "/about":
		d.aboutCalls++
		if d.account == "" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"user": map[string]string{"permissionId": d.account}})
	case r.URL.Path == "/changes":
		_ = json.NewEncoder(w).Encode(d.pages[r.URL.Query().Get("pageToken")])
	case r.URL.Path == "/files":
		q := r.URL.Query().Get("q")
		d.listCalls = append(d.listCalls, q)
		var out []File
		for _, f := range d.files {
			if strings.Contains(q, "name = '"+f.Name+"'") || (strings.Contains(q, "name contains '.transcript.txt'") && strings.HasSuffix(f.Name, ".transcript.txt")) {
				out = append(out, f)
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"files": out})
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
			"folder1": {ID: "folder1", Name: "20261001T064503Z_watch_01M3V3B6", Description: "Weekly sync", WebViewLink: "https://drive.google.com/drive/folders/folder1",
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
	ID: "txt1", Name: "20261001T064503Z_watch_01M3V3B6.transcript.txt", Parents: []string{"folder1"},
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

func TestRecordsTheAccountOncePerSignIn(t *testing.T) {
	w, fd, _, store := setup(t)
	fd.pages["t1"] = ChangePage{NewStartPageToken: "t1"}
	fd.account = "perm-1"
	first := time.Date(2026, 10, 6, 9, 0, 0, 0, time.UTC)
	w.SignedInAt = first
	for range 2 {
		if err := w.Poll(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	store.View(func(s *state.State) {
		if s.Drive.AccountID != "perm-1" || !s.Drive.AccountSignedInAt.Equal(first) {
			t.Fatalf("account = %q at %v", s.Drive.AccountID, s.Drive.AccountSignedInAt)
		}
	})
	if fd.aboutCalls != 1 {
		t.Fatalf("about asked %d times for one sign-in", fd.aboutCalls)
	}

	// A new sign-in may be another account: it is read again, not assumed.
	fd.account = "perm-2"
	w.SignedInAt = first.Add(time.Hour)
	if err := w.Poll(context.Background()); err != nil {
		t.Fatal(err)
	}
	store.View(func(s *state.State) {
		if s.Drive.AccountID != "perm-2" || !s.Drive.AccountSignedInAt.Equal(w.SignedInAt) {
			t.Fatalf("account = %q at %v", s.Drive.AccountID, s.Drive.AccountSignedInAt)
		}
	})
}

func TestAnAccountLookupFailureDoesNotFailThePoll(t *testing.T) {
	w, fd, _, store := setup(t)
	fd.pages["t1"] = ChangePage{NewStartPageToken: "t1"}
	if err := w.Poll(context.Background()); err != nil {
		t.Fatal(err)
	}
	store.View(func(s *state.State) {
		if s.Drive.AccountID != "" || s.Drive.LastError != "" {
			t.Fatalf("account = %q, error = %q", s.Drive.AccountID, s.Drive.LastError)
		}
	})
	fd.account = "perm-1"
	if err := w.Poll(context.Background()); err != nil {
		t.Fatal(err)
	}
	store.View(func(s *state.State) {
		if s.Drive.AccountID != "perm-1" {
			t.Fatalf("account not read on the next poll: %q", s.Drive.AccountID)
		}
	})
}
