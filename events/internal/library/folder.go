package library

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"unicode"
)

// Folder reads Recly recordings from folders on this computer: a local folder the Recly app stores
// recordings in, or the Mac's iCloud folder (docs/recly.md §3 "Storage location"). The folder
// template is the user's, so recording folders are found at any depth, by their base name. It only
// reads: nothing here creates, changes or deletes a file.
//
// A file still being written — a JSON that does not parse, a transcript iCloud has not brought down
// yet — counts as absent, and the recording is listed from what is there.
type Folder struct {
	// Roots are absolute paths, searched in order; a recording found under two roots is listed once.
	Roots []string
}

type folderRec struct {
	dir         string
	base        Base
	meta        *Meta
	title       *string
	recordingID string
	hasJSON     bool
	hasTxt      bool
}

func (r folderRec) recording() Recording {
	rec := Recording{
		RecordingID: r.recordingID, Title: r.title, StartedAt: StartedAtOf(r.base), Source: r.base.Source,
		HasTranscript: r.hasJSON || r.hasTxt, base: r.base.Name,
	}
	if r.meta != nil {
		rec.DurationSec = r.meta.DurationSec
		rec.HighlightCount = len(r.meta.HighlightTimes())
	}
	return rec
}

// scan finds every recording under the roots, newest first.
func (f *Folder) scan(ctx context.Context) ([]folderRec, error) {
	var out []folderRec
	seen := map[string]bool{}
	for _, root := range f.Roots {
		err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if ctxErr := ctx.Err(); ctxErr != nil {
				return ctxErr
			}
			if err != nil {
				if path == root {
					return err
				}
				return nil // a folder that cannot be read is skipped, not fatal
			}
			if !d.IsDir() || path == root {
				return nil
			}
			if strings.HasPrefix(d.Name(), ".") {
				return filepath.SkipDir
			}
			base, ok := ParseBase(d.Name())
			if !ok {
				return nil
			}
			if r, ok := loadRec(path, base); ok && !seen[r.recordingID] {
				seen[r.recordingID] = true
				out = append(out, r)
			}
			return filepath.SkipDir
		})
		if err != nil {
			return nil, fmt.Errorf("folder %s: %w", root, err)
		}
	}
	slices.SortStableFunc(out, func(a, b folderRec) int { return strings.Compare(b.base.Name, a.base.Name) })
	return out, nil
}

func loadRec(dir string, base Base) (folderRec, bool) {
	r := folderRec{dir: dir, base: base, recordingID: base.Name}
	if b, err := readCapped(filepath.Join(dir, MetaName(base.Name)), MaxMetaBytes); err == nil {
		r.meta, _ = ParseMeta(b)
	}
	folderTitle := ""
	if b, err := readCapped(filepath.Join(dir, FolderPropertiesName(base.Name)), MaxMetaBytes); err == nil {
		folderTitle = FolderTitle(b)
	}
	r.hasJSON = isFile(filepath.Join(dir, TranscriptJSONName(base.Name)))
	r.hasTxt = isFile(filepath.Join(dir, TranscriptTxtName(base.Name)))
	if r.meta == nil && !r.hasJSON && !r.hasTxt {
		return r, false // nothing readable yet: a recording still arriving
	}
	metaTitle := ""
	if r.meta != nil {
		metaTitle = r.meta.Title
		if IsRecordingID(r.meta.RecordingID) {
			r.recordingID = r.meta.RecordingID
		}
	}
	// iCloud keeps the canonical title on the folder (its .folder.json); a local folder in the meta.
	r.title = TitleOf(folderTitle, metaTitle)
	return r, true
}

// Recordings lists recordings newest first; the cursor is the base name of the last one returned.
func (f *Folder) Recordings(ctx context.Context, limit int, cursor string) ([]Recording, string, error) {
	if cursor != "" {
		if _, ok := ParseBase(cursor); !ok {
			return nil, "", &ToolError{Message: "cursor is not one list_recordings returned"}
		}
	}
	recs, err := f.scan(ctx)
	if err != nil {
		return nil, "", err
	}
	out := []Recording{}
	next := ""
	for _, r := range recs {
		if cursor != "" && r.base.Name >= cursor {
			continue
		}
		if len(out) == limit {
			next = out[len(out)-1].base
			break
		}
		out = append(out, r.recording())
	}
	return out, next, nil
}

func (f *Folder) find(ctx context.Context, id string) (folderRec, error) {
	recs, err := f.scan(ctx)
	if err != nil {
		return folderRec{}, err
	}
	for _, r := range recs {
		if r.recordingID == id || r.base.Name == id {
			return r, nil
		}
	}
	return folderRec{}, ErrNotFound
}

// Transcript reads a recording's transcript.
func (f *Folder) Transcript(ctx context.Context, id string) (*Transcript, error) {
	r, err := f.find(ctx, id)
	if err != nil {
		return nil, err
	}
	t, err := r.transcript()
	if err != nil {
		return nil, err
	}
	t.RecordingID = RecordingIDOf(r.recordingID, t.RecordingID, r.base.Name)
	t.Title, t.StartedAt, t.Highlights = r.title, StartedAtOf(r.base), r.meta.HighlightTimes()
	return t, nil
}

// transcript reads the JSON if it parses, else the .txt. A file over the size cap is an error, not
// a transcript that is not there yet.
func (r folderRec) transcript() (*Transcript, error) {
	if r.hasJSON {
		b, err := readCapped(filepath.Join(r.dir, TranscriptJSONName(r.base.Name)), MaxTranscriptBytes)
		if errors.Is(err, ErrTooLarge) {
			return nil, err
		}
		if err == nil {
			if t, err := FromJSON(b); err == nil {
				return t, nil
			}
		}
	}
	if r.hasTxt {
		b, err := readCapped(filepath.Join(r.dir, TranscriptTxtName(r.base.Name)), MaxTranscriptBytes)
		if errors.Is(err, ErrTooLarge) {
			return nil, err
		}
		if err == nil {
			return FromText(b), nil
		}
	}
	return nil, ErrNoTranscript
}

// RecordingIDOf is the first candidate that is a recording ID, else the last candidate (the base
// name).
func RecordingIDOf(candidates ...string) string {
	for _, c := range candidates {
		if IsRecordingID(c) {
			return c
		}
	}
	return candidates[len(candidates)-1]
}

// SearchHit is one recording search_recordings found.
type SearchHit struct {
	Recording  Recording
	TitleMatch bool
	Lines      []Line
}

// Search finds recordings whose title or transcript contains query, ignoring case, newest first,
// with up to three matching lines each.
func (f *Folder) Search(ctx context.Context, query string, limit int) ([]SearchHit, error) {
	q := []rune(strings.ToLower(query))
	recs, err := f.scan(ctx)
	if err != nil {
		return nil, err
	}
	var hits []SearchHit
	for _, r := range recs {
		if len(hits) == limit {
			break
		}
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		h := SearchHit{Recording: r.recording()}
		if r.title != nil && indexFold([]rune(*r.title), q) >= 0 {
			h.TitleMatch = true
		}
		if t, err := r.transcript(); err == nil {
			for _, l := range t.Lines {
				if len(h.Lines) == 3 {
					break
				}
				if indexFold([]rune(l.Text), q) >= 0 {
					h.Lines = append(h.Lines, l)
				}
			}
		}
		if h.TitleMatch || len(h.Lines) > 0 {
			hits = append(hits, h)
		}
	}
	return hits, nil
}

// indexFold is the rune index of q (already lower case) in s, ignoring case, or -1. Lowering rune by
// rune keeps the indexes of s and its lower-case form the same.
func indexFold(s, q []rune) int {
	if len(q) == 0 {
		return -1
	}
	for i := 0; i+len(q) <= len(s); i++ {
		match := true
		for j, c := range q {
			if unicode.ToLower(s[i+j]) != c {
				match = false
				break
			}
		}
		if match {
			return i
		}
	}
	return -1
}

// AudioFile is one audio part of a recording on this computer.
type AudioFile struct {
	Path           string   `json:"path"`
	Part           int      `json:"part"`
	Track          string   `json:"track"`
	StartOffsetSec *float64 `json:"startOffsetSec"`
	DurationSec    *float64 `json:"durationSec"`
	Bytes          int64    `json:"bytes"`
}

// AudioFiles lists a recording's audio parts that are on this computer, in the order the meta names
// them, and how many it names that are not.
func (f *Folder) AudioFiles(ctx context.Context, id string) (string, []AudioFile, int, error) {
	r, err := f.find(ctx, id)
	if err != nil {
		return "", nil, 0, err
	}
	files := []AudioFile{}
	missing := 0
	if r.meta != nil && len(r.meta.Parts) > 0 {
		for _, p := range r.meta.Parts {
			if !IsPartName(r.base.Name, p.File) {
				continue // a meta names its parts by the naming rules, never by a path
			}
			path := filepath.Join(r.dir, p.File)
			info, err := os.Stat(path)
			if err != nil || !info.Mode().IsRegular() {
				missing++
				continue
			}
			offset := p.StartOffsetSec
			files = append(files, AudioFile{Path: path, Part: p.Part, Track: p.Track, StartOffsetSec: &offset, DurationSec: p.DurationSec, Bytes: info.Size()})
		}
		return r.recordingID, files, missing, nil
	}
	entries, err := os.ReadDir(r.dir)
	if err != nil {
		return "", nil, 0, err
	}
	for _, e := range entries {
		if !e.Type().IsRegular() || !IsPartName(r.base.Name, e.Name()) {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		var part int
		var track string
		rest := strings.TrimSuffix(strings.TrimPrefix(e.Name(), r.base.Name+"_p"), ".m4a")
		if n, err := fmt.Sscanf(strings.Replace(rest, "_", " ", 1), "%d %s", &part, &track); n != 2 || err != nil {
			continue
		}
		files = append(files, AudioFile{Path: filepath.Join(r.dir, e.Name()), Part: part, Track: track, Bytes: info.Size()})
	}
	return r.recordingID, files, 0, nil
}

func isFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

// readCapped reads a regular file of at most max bytes.
func readCapped(path string, max int64) ([]byte, error) {
	fh, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer fh.Close()
	info, err := fh.Stat()
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("not a file")
	}
	if info.Size() > max {
		return nil, fmt.Errorf("%w: %s is larger than %d bytes", ErrTooLarge, filepath.Base(path), max)
	}
	b, err := io.ReadAll(io.LimitReader(fh, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > max {
		return nil, fmt.Errorf("%w: %s is larger than %d bytes", ErrTooLarge, filepath.Base(path), max)
	}
	return b, nil
}
