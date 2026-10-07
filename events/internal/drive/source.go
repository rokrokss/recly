package drive

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/rokrokss/recly/events/internal/library"
)

// Source reads Recly recordings in Drive for the read tools: list_recordings and get_transcript
// (docs/recly.md §15 §9). A recording's `{base}.meta.json` and transcript are downloaded only when
// the agent calls one of them; nothing is cached.
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
	// starts with its year (docs/recly.md §3 "Naming rules"), so the folders whose name starts with
	// '2' hold every recording; ParseBase picks them out from the folder template's own (`2026-10`)
	// and the user's.
	folderQuery = "mimeType = 'application/vnd.google-apps.folder' and name contains '2' and trashed = false"
	// scanPages bounds the search for a recordingId: 10 pages of 1000 folders.
	scanPages = 10
	// orChunk keeps a query of names joined by `or` well inside Drive's URL limit.
	orChunk = 20
)

// errOther says a base name holds a different recording than the one asked for.
var errOther = errors.New("another recording")

// Recordings lists recordings by their folders, newest first: a base name starts with the start
// time, so Drive's name order is the start order. A folder whose `{base}.meta.json` is not there is
// an upload still going (docs/recly.md §3), and is not listed yet. The cursor is Drive's page token.
func (s *Source) Recordings(ctx context.Context, limit int, cursor string) ([]library.Recording, string, error) {
	folders, next, err := s.API.ListPage(ctx, folderQuery, "name desc", limit, cursor)
	if err != nil {
		var se *StatusError
		if cursor != "" && errors.As(err, &se) && se.Status == 400 {
			return nil, "", &library.ToolError{Message: "cursor is not one list_recordings returned"}
		}
		return nil, "", explain(err)
	}
	type item struct {
		base   library.Base
		folder File
		file   File
		meta   *library.Meta
	}
	var candidates []item
	var metaNames, transcriptNames []string
	for _, f := range folders {
		b, ok := library.ParseBase(f.Name)
		if !ok {
			continue
		}
		candidates = append(candidates, item{base: b, folder: f})
		metaNames = append(metaNames, library.MetaName(b.Name))
		transcriptNames = append(transcriptNames, library.TranscriptTxtName(b.Name))
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
			rec.DurationSec, rec.HighlightCount = it.meta.DurationSec, len(it.meta.HighlightTimes())
		}
		rec.RecordingID = library.RecordingIDOf(metaID, folder.AppProperties["recordingId"], it.base.Name)
		// The folder's description is the canonical title on Drive (docs/recly.md §3 "Titles").
		rec.Title = library.TitleOf(folder.Description, metaTitle)
		out = append(out, rec)
	}
	return out, next, nil
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

// Transcript finds a recording by its base name, or by its recordingId through the recording
// folders whose base name ends the same way, and reads its transcript.
func (s *Source) Transcript(ctx context.Context, id string) (*library.Transcript, error) {
	if b, ok := library.ParseBase(id); ok {
		t, err := s.transcript(ctx, b, "")
		return t, explain(err)
	}
	if !library.IsRecordingID(id) {
		return nil, library.ErrNotFound
	}
	prefix := id[:8]
	token := ""
	for range scanPages {
		folders, next, err := s.API.ListPage(ctx, folderQuery, "name desc", 1000, token)
		if err != nil {
			return nil, explain(err)
		}
		for _, f := range folders {
			b, ok := library.ParseBase(f.Name)
			if !ok || b.Prefix != prefix {
				continue
			}
			t, err := s.transcript(ctx, b, id)
			if errors.Is(err, errOther) {
				continue
			}
			return t, explain(err)
		}
		if next == "" {
			break
		}
		token = next
	}
	return nil, library.ErrNotFound
}

// transcript reads the recording with base name b; want, when set, is the recordingId asked for.
func (s *Source) transcript(ctx context.Context, b library.Base, want string) (*library.Transcript, error) {
	metaName, jsonName, txtName := library.MetaName(b.Name), library.TranscriptJSONName(b.Name), library.TranscriptTxtName(b.Name)
	files, err := s.API.List(ctx, fmt.Sprintf("(name = %s or name = %s or name = %s) and trashed = false",
		quote(metaName), quote(jsonName), quote(txtName)), 100)
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
	var t *library.Transcript
	if f, ok := in[jsonName]; ok {
		body, err := s.API.Download(ctx, f.ID, library.MaxTranscriptBytes)
		if err != nil {
			return nil, err
		}
		t, _ = library.FromJSON(body) // a JSON that does not parse falls back to the .txt
	}
	if f, ok := in[txtName]; ok && t == nil {
		body, err := s.API.Download(ctx, f.ID, library.MaxTranscriptBytes)
		if err != nil {
			return nil, err
		}
		t = library.FromText(body)
	}
	if t == nil {
		return nil, library.ErrNoTranscript
	}
	t.RecordingID = library.RecordingIDOf(known, t.RecordingID, b.Name)
	if want != "" && library.IsRecordingID(t.RecordingID) && t.RecordingID != want {
		return nil, errOther
	}
	t.Title, t.StartedAt, t.Highlights = library.TitleOf(folder.Description, metaTitle), library.StartedAtOf(b), meta.HighlightTimes()
	return t, nil
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
