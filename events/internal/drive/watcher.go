package drive

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"time"

	"github.com/rokrokss/recly/events/internal/state"
)

// transcriptName is Recly's `{base}.transcript.txt`, with
// base = `{yyyyMMdd}T{HHmmss}Z_{source}_{first 8 of recordingId}` (docs/recly.md §3
// "Naming rules", core MetaWriter.baseName). Recly always writes it; the folder a user
// configured does not matter.
var transcriptName = regexp.MustCompile(`^((\d{8}T\d{6}Z)_(watch|phone|desktop)_([0-9A-Z]{8}))\.transcript\.txt$`)

// Recording is the event data: where the transcript is, never what it says.
type Recording struct {
	// Recording is Recly's base name, the stable key of the recording on Drive.
	Recording         string `json:"recording"`
	RecordingIDPrefix string `json:"recordingIdPrefix"`
	// RecordingID is the full ID, known when the folder's appProperties are readable.
	RecordingID string   `json:"recordingId,omitempty"`
	Title       *string  `json:"title"`
	StartedAt   string   `json:"startedAt"`
	Device      string   `json:"device"`
	Drive       DriveRef `json:"drive"`
}

// DriveRef locates the recording's files for the agent's Google Drive connector.
type DriveRef struct {
	FolderID             string `json:"folderId"`
	FolderURL            string `json:"folderUrl,omitempty"`
	TranscriptTxtFileID  string `json:"transcriptTxtFileId"`
	TranscriptTxtURL     string `json:"transcriptTxtUrl,omitempty"`
	TranscriptJSONFileID string `json:"transcriptJsonFileId,omitempty"`
}

// Emit announces one transcript; it returns false when the event ID was already announced.
type Emit func(eventID string, data Recording) (bool, error)

// Watcher polls the Drive changes feed and announces new or rewritten Recly transcripts.
type Watcher struct {
	API   *API
	Store *state.Store
	Emit  Emit
	Log   *slog.Logger
	Every time.Duration
	// SignedInAt is the obtainedAt of the Google sign-in the API runs with; the account read
	// with it is kept against it.
	SignedInAt time.Time
}

// Run polls until ctx is done.
func (w *Watcher) Run(ctx context.Context) {
	t := time.NewTicker(w.Every)
	defer t.Stop()
	lastErr := ""
	for {
		err := w.Poll(ctx)
		msg := ""
		if err != nil {
			msg = err.Error()
		}
		if msg != lastErr {
			if err != nil {
				w.Log.Warn("drive.poll.failed", "error", msg)
			} else if lastErr != "" {
				w.Log.Info("drive.poll.recovered")
			}
			lastErr = msg
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Poll reads every change since the stored cursor. The cursor advances only after the whole
// page set was handled, so a crash or a failed lookup reads the same changes again; the Seen
// versions keep that from announcing anything twice.
func (w *Watcher) Poll(ctx context.Context) error {
	now := time.Now()
	var token string
	w.Store.View(func(s *state.State) { token = s.Drive.PageToken })
	err := w.poll(ctx, token)
	_ = w.Store.Update(func(s *state.State) error {
		s.Drive.LastPollAt = now
		if err != nil {
			s.Drive.LastError = err.Error()
		} else {
			s.Drive.LastSuccessAt = now
			s.Drive.LastError = ""
		}
		return nil
	})
	if err == nil {
		w.recordAccount(ctx)
	}
	return err
}

// recordAccount reads the account once per sign-in, after a poll proved the sign-in works. A
// failure only waits for the next successful poll: the account is for the settings row, and
// announcing does not depend on it.
func (w *Watcher) recordAccount(ctx context.Context) {
	known := false
	w.Store.View(func(s *state.State) {
		known = s.Drive.AccountID != "" && s.Drive.AccountSignedInAt.Equal(w.SignedInAt)
	})
	if known {
		return
	}
	id, err := w.API.AccountID(ctx)
	if err != nil {
		w.Log.Warn("drive.account.failed", "error", err.Error())
		return
	}
	_ = w.Store.Update(func(s *state.State) error {
		s.Drive.AccountID, s.Drive.AccountSignedInAt = id, w.SignedInAt
		return nil
	})
}

func (w *Watcher) poll(ctx context.Context, token string) error {
	if token == "" {
		start, err := w.API.StartPageToken(ctx)
		if err != nil {
			return err
		}
		w.Log.Info("drive.cursor.start")
		return w.Store.Update(func(s *state.State) error { s.Drive.PageToken = start; return nil })
	}
	var found []File
	next := token
	for {
		page, err := w.API.Changes(ctx, next)
		if err != nil {
			return err
		}
		for _, c := range page.Changes {
			if c.Removed || c.File == nil || c.File.Trashed || !transcriptName.MatchString(c.File.Name) {
				continue
			}
			found = append(found, *c.File)
		}
		if page.NextPageToken != "" {
			next = page.NextPageToken
			continue
		}
		if page.NewStartPageToken == "" {
			return errors.New("drive: changes page without a next or new start token")
		}
		next = page.NewStartPageToken
		break
	}
	for _, f := range found {
		if err := w.announce(ctx, f); err != nil {
			return err
		}
	}
	return w.Store.Update(func(s *state.State) error { s.Drive.PageToken = next; return nil })
}

func version(f File) string {
	if f.MD5 != "" {
		return f.MD5
	}
	return f.Modified
}

func (w *Watcher) announce(ctx context.Context, f File) error {
	v := version(f)
	seen := false
	w.Store.View(func(s *state.State) { seen = s.Drive.Seen[f.ID] == v })
	if seen {
		return nil
	}
	// A recording with no recognized speech publishes an empty transcript; there is nothing for
	// the agent to read. A re-transcription that finds speech changes the version and is announced.
	if f.Size == "0" {
		w.Log.Info("drive.transcript.empty", "file", f.ID)
		return w.Store.Update(func(s *state.State) error { s.Drive.Seen[f.ID] = v; return nil })
	}
	rec, err := Describe(ctx, w.API, f)
	if err != nil {
		return err
	}
	if _, err := w.Emit(EventID(f), rec); err != nil {
		return err
	}
	return w.Store.Update(func(s *state.State) error { s.Drive.Seen[f.ID] = v; return nil })
}

// EventID is stable for one version of one transcript: Drive repeating a change, or a restart
// reading it again, yields the same ID; a re-transcription yields a new one.
func EventID(f File) string {
	m := transcriptName.FindStringSubmatch(f.Name)
	sum := sha256.Sum256([]byte(f.ID + "\x00" + version(f)))
	return "evt_" + m[1] + "_" + hex.EncodeToString(sum[:4])
}

// Describe builds the event data for a transcript file: its folder (whose description is the
// recording's title, docs/recly.md §3) and the sibling transcript.json, if it is there yet.
func Describe(ctx context.Context, api *API, f File) (Recording, error) {
	m := transcriptName.FindStringSubmatch(f.Name)
	if m == nil {
		return Recording{}, fmt.Errorf("%s is not a Recly transcript", f.Name)
	}
	started, err := time.Parse("20060102T150405Z", m[2])
	if err != nil {
		return Recording{}, err
	}
	rec := Recording{
		Recording: m[1], RecordingIDPrefix: m[4], StartedAt: started.UTC().Format(time.RFC3339), Device: m[3],
		Drive: DriveRef{TranscriptTxtFileID: f.ID, TranscriptTxtURL: f.WebViewLink},
	}
	if len(f.Parents) == 0 {
		return rec, nil
	}
	folder, err := api.Get(ctx, f.Parents[0])
	if err != nil {
		return Recording{}, err
	}
	rec.Drive.FolderID, rec.Drive.FolderURL = folder.ID, folder.WebViewLink
	rec.RecordingID = folder.AppProperties["recordingId"]
	if folder.Description != "" {
		title := folder.Description
		rec.Title = &title
	}
	siblings, err := api.List(ctx, fmt.Sprintf("%s in parents and name = %s and trashed = false", quote(folder.ID), quote(m[1]+".transcript.json")), 1)
	if err != nil {
		return Recording{}, err
	}
	if len(siblings) > 0 {
		rec.Drive.TranscriptJSONFileID = siblings[0].ID
	}
	return rec, nil
}

// Latest returns the newest Recly transcript in Drive, for `recly-events test`.
func Latest(ctx context.Context, api *API) (File, error) {
	files, err := api.List(ctx, "name contains '.transcript.txt' and trashed = false", 50)
	if err != nil {
		return File{}, err
	}
	for _, f := range files {
		if transcriptName.MatchString(f.Name) {
			return f, nil
		}
	}
	return File{}, errors.New("no Recly transcript found in Google Drive")
}
