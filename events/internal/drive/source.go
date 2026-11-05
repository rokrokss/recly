package drive

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/rokrokss/recly/events/internal/library"
)

// Source reads Recly recordings in Drive for the read tools: list_recordings, get_transcript and
// get_summary (docs/recly.md §15 §9). A recording's `{base}.meta.json`, transcript, summary and the
// user's notes are downloaded only when the agent calls one of them; nothing is cached.
//
// It finds recordings by their `{base}/` folders' names, which every client can query: the folder's
// appProperties, where Recly keeps the recordingId, are visible only to Recly's own client.
type Source struct {
	API *API
}

const (
	// folderQuery finds the recording folders. Drive's `name contains` matches a prefix only — "The
	// contains operator only performs prefix matching for a name term"
	// (https://developers.google.com/workspace/drive/api/guides/ref-search-terms, last updated
	// 2026-09-03, read 2026-10-07) — so a suffix such as '.meta.json' finds nothing. Every base name
	// starts with its year (docs/recly.md §3 "Naming rules") — an import keeps its file's own date, which
	// can be any year — so the folders whose name starts with a digit hold every recording; ParseBase
	// picks them out from the folder template's own (`2026-10`) and the user's.
	folderQuery = "mimeType = 'application/vnd.google-apps.folder' and " + digitPrefix + " and trashed = false"
	// digitPrefix matches a name that starts with a digit: `or` terms grouped in parentheses, as the
	// search-files guide's own example does (https://developers.google.com/workspace/drive/api/guides/search-files,
	// last updated 2026-09-09, read 2026-10-08).
	digitPrefix = "(name contains '0' or name contains '1' or name contains '2' or name contains '3' or name contains '4' or " +
		"name contains '5' or name contains '6' or name contains '7' or name contains '8' or name contains '9')"
	// scanPages bounds a search through the folders: 10 pages of 1000.
	scanPages = 10
	// orChunk keeps a query of names joined by `or` well inside Drive's URL limit.
	orChunk = 20
)

// errOther says a base name holds a different recording than the one asked for.
var errOther = errors.New("another recording")

// Recordings lists recordings by their folders, newest first: a base name starts with the start
// time, so Drive's name order is the start order. A folder whose `{base}.meta.json` is not there is
// an upload still going (docs/recly.md §3), and is not listed yet.
//
// Without a span, a call reads one Drive page of limit folders. With one, Drive cannot compare
// names, so the folders are read in pages of 1000, names and IDs only, and those outside the span
// skipped: a call stops at the first folder older than the span, after limit recordings, or after
// scanPages pages, and then may return fewer than limit with a cursor.
func (s *Source) Recordings(ctx context.Context, limit int, cursor string, span library.Span) ([]library.Recording, string, error) {
	c, ok := parseListCursor(cursor)
	if !ok {
		return nil, "", &library.ToolError{Message: "cursor is not one list_recordings returned"}
	}
	type item struct {
		base   library.Base
		folder File
		file   File
		meta   *library.Meta
	}
	pageSize := limit
	if span.Bounded() {
		pageSize = 1000
	}
	var candidates []item
	next := listCursor{}
	token, after := c.Token, c.After
scan:
	for page := 1; ; page++ {
		folders, more, err := s.API.ListPage(ctx, folderQuery, "name desc", pageSize, token)
		if err != nil {
			var se *StatusError
			if cursor != "" && errors.As(err, &se) && se.Status == 400 {
				return nil, "", &library.ToolError{Message: "cursor is not one list_recordings returned"}
			}
			return nil, "", explain(err)
		}
		for _, f := range folders {
			b, ok := library.ParseBase(f.Name)
			if !ok || after != "" && b.Name >= after || !span.To.IsZero() && b.StartedAt.After(span.To) {
				continue
			}
			if !span.From.IsZero() && b.StartedAt.Before(span.From) {
				break scan
			}
			if len(candidates) == limit {
				next = listCursor{Token: token, After: candidates[len(candidates)-1].base.Name}
				break scan
			}
			candidates = append(candidates, item{base: b, folder: f})
		}
		if more == "" {
			break
		}
		if !span.Bounded() || page == scanPages {
			next = listCursor{Token: more}
			break
		}
		token, after = more, ""
	}
	var metaNames, transcriptNames []string
	for _, it := range candidates {
		metaNames = append(metaNames, library.MetaName(it.base.Name))
		transcriptNames = append(transcriptNames, library.TranscriptTxtName(it.base.Name))
	}
	metas, err := s.byName(ctx, metaNames)
	if err != nil {
		return nil, "", explain(err)
	}
	transcripts, err := s.byName(ctx, transcriptNames)
	if err != nil {
		return nil, "", explain(err)
	}
	metaIn := map[string]File{}
	for _, m := range metas {
		for _, p := range m.Parents {
			if _, dup := metaIn[p]; !dup {
				metaIn[p] = m
			}
		}
	}
	withTranscript := map[string]bool{}
	for _, t := range transcripts {
		for _, p := range t.Parents {
			withTranscript[p] = true
		}
	}
	var items []item
	seen := map[string]bool{}
	for _, it := range candidates {
		m, ok := metaIn[it.folder.ID]
		if !ok || m.Name != library.MetaName(it.base.Name) || seen[it.base.Name] {
			continue
		}
		seen[it.base.Name] = true
		it.file = m
		items = append(items, it)
	}
	err = parallel(ctx, len(items), func(ctx context.Context, i int) error {
		body, err := s.API.Download(ctx, items[i].file.ID, library.MaxMetaBytes)
		if err != nil {
			return err
		}
		items[i].meta, _ = library.ParseMeta(body)
		return nil
	})
	if err != nil {
		return nil, "", explain(err)
	}
	out := []library.Recording{}
	for _, it := range items {
		folder := it.folder
		rec := library.Recording{
			StartedAt: library.StartedAtOf(it.base), Source: it.base.Source, HasTranscript: withTranscript[folder.ID],
		}
		metaID, metaTitle := "", ""
		if it.meta != nil {
			metaID, metaTitle = it.meta.RecordingID, it.meta.Title
			rec.DurationSec = it.meta.DurationSec
		}
		rec.RecordingID = library.RecordingIDOf(metaID, folder.AppProperties["recordingId"], it.base.Name)
		// The folder's description is the canonical title on Drive (docs/recly.md §3 "Titles").
		rec.Title = library.TitleOf(folder.Description, metaTitle)
		out = append(out, rec)
	}
	return out, next.String(), nil
}

// listCursor is where the next list_recordings page starts: a Drive page token and, when a call
// stopped inside that page, the base name of the last recording it gave.
type listCursor struct {
	Token string `json:"t,omitempty"`
	After string `json:"a,omitempty"`
}

func (c listCursor) String() string {
	if c == (listCursor{}) {
		return ""
	}
	b, _ := json.Marshal(c)
	return base64.RawURLEncoding.EncodeToString(b)
}

func parseListCursor(s string) (listCursor, bool) {
	var c listCursor
	if s == "" {
		return c, true
	}
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || json.Unmarshal(b, &c) != nil || c == (listCursor{}) {
		return listCursor{}, false
	}
	if _, ok := library.ParseBase(c.After); c.After != "" && !ok {
		return listCursor{}, false
	}
	return c, true
}

// byName returns the files with any of the names.
func (s *Source) byName(ctx context.Context, names []string) ([]File, error) {
	var out []File
	for start := 0; start < len(names); start += orChunk {
		var terms []string
		for _, n := range names[start:min(start+orChunk, len(names))] {
			terms = append(terms, "name = "+quote(n))
		}
		q := "(" + strings.Join(terms, " or ") + ") and trashed = false"
		files, err := s.API.List(ctx, q, 1000)
		if err != nil {
			return nil, err
		}
		out = append(out, files...)
	}
	return out, nil
}

// Transcript finds a recording (find) and reads its transcript: the JSON if it parses, else the .txt.
func (s *Source) Transcript(ctx context.Context, id string) (*library.Transcript, error) {
	var t *library.Transcript
	err := s.find(ctx, id, func(r *recording) error {
		if f, ok := r.files[library.TranscriptJSONName(r.base.Name)]; ok {
			body, err := s.API.Download(ctx, f.ID, library.MaxTranscriptBytes)
			if err != nil {
				return err
			}
			t, _ = library.FromJSON(body) // a JSON that does not parse falls back to the .txt
		}
		if f, ok := r.files[library.TranscriptTxtName(r.base.Name)]; ok && t == nil {
			body, err := s.API.Download(ctx, f.ID, library.MaxTranscriptBytes)
			if err != nil {
				return err
			}
			t = library.FromText(body)
		}
		if t == nil {
			return library.ErrNoTranscript
		}
		t.RecordingID = library.RecordingIDOf(r.knownID, t.RecordingID, r.base.Name)
		if r.want != "" && library.IsRecordingID(t.RecordingID) && t.RecordingID != r.want {
			t = nil
			return errOther
		}
		t.Title, t.StartedAt = r.title, library.StartedAtOf(r.base)
		return nil
	})
	return t, err
}

// Summary finds a recording (find) and reads its summary and the user's notes, and the speakers of
// its JSON transcript when a to-do names its owner.
func (s *Source) Summary(ctx context.Context, id string) (*library.SummaryFiles, error) {
	var out *library.SummaryFiles
	err := s.find(ctx, id, func(r *recording) error {
		out = &library.SummaryFiles{
			RecordingID: library.RecordingIDOf(r.knownID, r.want, r.base.Name), Title: r.title, StartedAt: library.StartedAtOf(r.base),
		}
		for _, file := range []struct {
			name string
			into *[]byte
		}{{library.NotesName(r.base.Name), &out.Notes}, {library.MemoName(r.base.Name), &out.Memo}} {
			if f, ok := r.files[file.name]; ok {
				b, err := s.API.Download(ctx, f.ID, library.MaxNotesBytes)
				if err != nil {
					return err
				}
				*file.into = b
			}
		}
		// Neither the meta nor the folder said whose folder this is: the summary and the notes do,
		// as the transcript does for Transcript, so that a recording that shares the ID prefix does
		// not answer for this one.
		if r.want != "" && r.knownID == "" {
			for _, b := range [][]byte{out.Notes, out.Memo} {
				var d struct {
					RecordingID string `json:"recordingId"`
				}
				if json.Unmarshal(b, &d) == nil && library.IsRecordingID(d.RecordingID) && d.RecordingID != r.want {
					out = nil
					return errOther
				}
			}
		}
		if f, ok := r.files[library.TranscriptJSONName(r.base.Name)]; ok && library.NeedsSpeakers(out.Notes) {
			b, err := s.API.Download(ctx, f.ID, library.MaxTranscriptBytes)
			if err != nil {
				return err
			}
			out.Speakers = library.SpeakersOf(b)
		}
		return nil
	})
	return out, err
}

// recording is a recording's folder in Drive and the files of it the read tools use.
type recording struct {
	base  library.Base
	files map[string]File
	title *string
	// knownID is the recordingId the meta or the folder gives, "" when neither does; want is the
	// recordingId asked for, "" when it was asked for by base name.
	knownID string
	want    string
}

// find calls read with the recording named by id: its base name, or its recordingId through the
// recording folders whose base name ends the same way. read answers errOther for a folder that
// holds another recording, and the search goes on.
func (s *Source) find(ctx context.Context, id string, read func(*recording) error) error {
	if b, ok := library.ParseBase(id); ok {
		r, err := s.open(ctx, b, "")
		if err == nil {
			err = read(r)
		}
		return explain(err)
	}
	if !library.IsRecordingID(id) {
		return library.ErrNotFound
	}
	prefix := id[:8]
	token := ""
	for range scanPages {
		folders, next, err := s.API.ListPage(ctx, folderQuery, "name desc", 1000, token)
		if err != nil {
			return explain(err)
		}
		for _, f := range folders {
			b, ok := library.ParseBase(f.Name)
			if !ok || b.Prefix != prefix {
				continue
			}
			r, err := s.open(ctx, b, id)
			if err == nil {
				err = read(r)
			}
			if errors.Is(err, errOther) {
				continue
			}
			return explain(err)
		}
		if next == "" {
			break
		}
		token = next
	}
	return library.ErrNotFound
}

// open finds the folder of the recording with base name b and reads its meta; want, when set, is
// the recordingId asked for.
func (s *Source) open(ctx context.Context, b library.Base, want string) (*recording, error) {
	metaName, jsonName, txtName := library.MetaName(b.Name), library.TranscriptJSONName(b.Name), library.TranscriptTxtName(b.Name)
	files, err := s.API.List(ctx, fmt.Sprintf("(name = %s or name = %s or name = %s or name = %s or name = %s) and trashed = false",
		quote(metaName), quote(jsonName), quote(txtName), quote(library.NotesName(b.Name)), quote(library.MemoName(b.Name))), 100)
	if err != nil {
		return nil, err
	}
	// A recording run again to another folder has two; the newest transcript's folder is the one.
	folderID := ""
	for _, f := range files {
		if len(f.Parents) > 0 && (f.Name == jsonName || f.Name == txtName) {
			folderID = f.Parents[0]
			break
		}
	}
	if folderID == "" {
		for _, f := range files {
			if len(f.Parents) > 0 {
				folderID = f.Parents[0]
				break
			}
		}
	}
	if folderID == "" {
		return nil, library.ErrNotFound
	}
	in := map[string]File{}
	for _, f := range files {
		if len(f.Parents) > 0 && f.Parents[0] == folderID {
			if _, dup := in[f.Name]; !dup {
				in[f.Name] = f
			}
		}
	}
	var meta *library.Meta
	if f, ok := in[metaName]; ok {
		body, err := s.API.Download(ctx, f.ID, library.MaxMetaBytes)
		if err != nil {
			return nil, err
		}
		meta, _ = library.ParseMeta(body)
	}
	folder, err := s.API.Get(ctx, folderID)
	if err != nil {
		return nil, err
	}
	metaID, metaTitle := "", ""
	if meta != nil {
		metaID, metaTitle = meta.RecordingID, meta.Title
	}
	known := library.RecordingIDOf(metaID, folder.AppProperties["recordingId"], "")
	if want != "" && known != "" && known != want {
		return nil, errOther
	}
	return &recording{base: b, files: in, title: library.TitleOf(folder.Description, metaTitle), knownID: known, want: want}, nil
}

// explain adds what to do to a Drive refusal the user can fix.
func explain(err error) error {
	var se *StatusError
	if errors.As(err, &se) && se.Status == 403 && strings.Contains(strings.ToLower(se.Body), "insufficient") {
		return fmt.Errorf("%w — Google did not allow reading the file; with a Google client of your own, run `recly-events init --google-client FILE` again and allow it to see your Drive files", err)
	}
	return err
}

// parallel runs fn for 0..n-1, four at a time, and returns the first error, which cancels the rest.
func parallel(ctx context.Context, n int, fn func(context.Context, int) error) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	sem := make(chan struct{}, 4)
	var wg sync.WaitGroup
	var once sync.Once
	var first error
	for i := range n {
		select {
		case sem <- struct{}{}:
		case <-ctx.Done():
		}
		if ctx.Err() != nil {
			break
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			if err := fn(ctx, i); err != nil {
				once.Do(func() { first = err; cancel() })
			}
		}()
	}
	wg.Wait()
	if first == nil {
		first = ctx.Err()
	}
	return first
}
