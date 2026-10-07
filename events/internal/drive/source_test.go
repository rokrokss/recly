package drive

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/rokrokss/recly/events/internal/library"
)

const (
	baseA = "20261001T064503Z_watch_01M3V3B6"
	idA   = "01M3V3B6P5NF6ZM7X1QDFERRWT"
	baseB = "20261002T090000Z_desktop_01M4AAAA"
	idB   = "01M4AAAAP5NF6ZM7X1QDFERRWT"
	// baseC shares baseA's ID prefix: the search by recordingId must tell them apart.
	baseC = "20261005T000000Z_phone_01M3V3B6"
	idC   = "01M3V3B6ZZZZZZZZZZZZZZZZZZ"
)

func setupSource(t *testing.T) (*Source, *fakeDrive) {
	t.Helper()
	folder := "application/vnd.google-apps.folder"
	fd := &fakeDrive{
		files: map[string]File{
			"fA":    {ID: "fA", Name: baseA, MimeType: folder, Description: "Weekly sync", AppProperties: map[string]string{"recordingId": idA}},
			"metaA": {ID: "metaA", Name: baseA + ".meta.json", Parents: []string{"fA"}},
			"jsonA": {ID: "jsonA", Name: baseA + ".transcript.json", Parents: []string{"fA"}},
			"txtA":  {ID: "txtA", Name: baseA + ".transcript.txt", Parents: []string{"fA"}},
			// As a client of the user's own sees it: no appProperties, no description.
			"fB":    {ID: "fB", Name: baseB, MimeType: folder},
			"metaB": {ID: "metaB", Name: baseB + ".meta.json", Parents: []string{"fB"}},
			"txtB":  {ID: "txtB", Name: baseB + ".transcript.txt", Parents: []string{"fB"}},
			"fC":    {ID: "fC", Name: baseC, MimeType: folder},
			"metaC": {ID: "metaC", Name: baseC + ".meta.json", Parents: []string{"fC"}},
			// Another app's file that a client of the user's own also sees.
			"other": {ID: "other", Name: "notes.meta.json", Parents: []string{"root"}},
			// The newest folder is an upload still going: no meta yet, so not listed.
			"fUp":     {ID: "fUp", Name: "20261009T000000Z_phone_01M8UPLD", MimeType: folder},
			"partUp":  {ID: "partUp", Name: "20261009T000000Z_phone_01M8UPLD_p001_mono.m4a", Parents: []string{"fUp"}},
			"recly":   {ID: "recly", Name: "Recly", MimeType: folder},
			"month":   {ID: "month", Name: "2026-10", MimeType: folder, Parents: []string{"recly"}},
			"notMeta": {ID: "notMeta", Name: "2026 budget.meta.json", Parents: []string{"root"}},
		},
		content: map[string]string{
			"metaA": `{"recordingId":"` + idA + `","title":"Old title","durationSec":95.5,"highlights":[{"atSec":12.5}]}`,
			"jsonA": `{"recordingId":"` + idA + `","language":"en","speakers":[{"id":"S1","name":"Kim"}],"segments":[{"start":0,"end":2,"speaker":"S1","text":"Hello."}]}`,
			"txtA":  "[00:00:00] S1: not read, the JSON is there\n",
			"metaB": `{"recordingId":"` + idB + `","title":"Board"}`,
			"txtB":  "[00:00:01] S1: Budget first.\n",
			"metaC": `{"recordingId":"` + idC + `"}`,
		},
	}
	srv := httptest.NewServer(fd)
	t.Cleanup(srv.Close)
	return &Source{API: &API{Client: srv.Client(), Base: srv.URL}}, fd
}

func TestSourceListsRecordingsNewestFirstByPage(t *testing.T) {
	src, _ := setupSource(t)
	ctx := context.Background()
	page, next, err := src.Recordings(ctx, 2, "")
	if err != nil || next == "" {
		t.Fatalf("page 1: %v %q", err, next)
	}
	// The first page held a folder still being uploaded into, which is not listed yet.
	if len(page) != 1 || page[0].RecordingID != idC || page[0].Title != nil || page[0].HasTranscript || page[0].Source != "phone" {
		t.Fatalf("page 1 = %+v", page)
	}
	page, next, err = src.Recordings(ctx, 2, next)
	if err != nil || next == "" || len(page) != 2 {
		t.Fatalf("page 2: %v %q %+v", err, next, page)
	}
	b, a := page[0], page[1]
	if b.RecordingID != idB || b.Title == nil || *b.Title != "Board" || !b.HasTranscript || b.StartedAt != "2026-10-02T09:00:00Z" {
		t.Fatalf("B = %+v", b)
	}
	if a.RecordingID != idA || *a.Title != "Weekly sync" || *a.DurationSec != 95.5 || a.HighlightCount != 1 || !a.HasTranscript {
		t.Fatalf("A = %+v", a)
	}
	// The folder template's own folder starts with the year too; it is not a recording.
	page, next, err = src.Recordings(ctx, 2, next)
	if err != nil || next != "" || len(page) != 0 {
		t.Fatalf("page 3: %v %q %+v", err, next, page)
	}
}

func TestSourceReadsATranscriptByIDOrBaseName(t *testing.T) {
	src, _ := setupSource(t)
	ctx := context.Background()
	for _, id := range []string{idA, baseA} {
		tr, err := src.Transcript(ctx, id)
		if err != nil {
			t.Fatalf("%s: %v", id, err)
		}
		if tr.RecordingID != idA || *tr.Title != "Weekly sync" || tr.Language != "en" || len(tr.Lines) != 1 ||
			tr.Lines[0].Text != "[00:00:00] Kim: Hello." || len(tr.Highlights) != 1 || tr.StartedAt != "2026-10-01T06:45:03Z" {
			t.Fatalf("%s: %+v", id, tr)
		}
	}
	tr, err := src.Transcript(ctx, idB)
	if err != nil || tr.RecordingID != idB || *tr.Title != "Board" || tr.Lines[0].Text != "[00:00:01] S1: Budget first." {
		t.Fatalf("B: %+v %v", tr, err)
	}
	if _, err := src.Transcript(ctx, idC); !errors.Is(err, library.ErrNoTranscript) {
		t.Fatalf("C: %v", err)
	}
	if _, err := src.Transcript(ctx, "01M9ZZZZP5NF6ZM7X1QDFERRWT"); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("unknown: %v", err)
	}
	if _, err := src.Transcript(ctx, "20261009T000000Z_phone_01M9ZZZZ"); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("unknown base: %v", err)
	}
}

func TestSourceErrorsReachTheAgent(t *testing.T) {
	src, fd := setupSource(t)
	tools := &library.Tools{Source: src, Nonce: func() string { return "n" }}
	ctx := context.Background()
	res, _, err := tools.Call(ctx, "get_transcript", json.RawMessage(`{"recordingId":"`+idA+`"}`))
	if err != nil {
		t.Fatal(err)
	}
	b, _ := library.Marshal(res)
	if !strings.Contains(string(b), `<<<recly-transcript-n UNTRUSTED`) || !strings.Contains(string(b), "Kim: Hello.") {
		t.Fatalf("transcript = %s", b)
	}
	// A token without read access, as a client of the user's own signed in before drive.readonly.
	fd.failContent = http.StatusForbidden
	_, _, err = tools.Call(ctx, "get_transcript", json.RawMessage(`{"recordingId":"`+idA+`"}`))
	var te *library.ToolError
	if !errors.As(err, &te) || !strings.Contains(te.Message, "init --google-client") {
		t.Fatalf("403: %v", err)
	}
	_, _, err = tools.Call(ctx, "list_recordings", json.RawMessage(`{}`))
	if !errors.As(err, &te) || !strings.Contains(te.Message, "HTTP 403") {
		t.Fatalf("list 403: %v", err)
	}
}

func TestDownloadRefusesOversizedFiles(t *testing.T) {
	src, fd := setupSource(t)
	fd.content["big"] = strings.Repeat("x", 2048)
	if _, err := src.API.Download(context.Background(), "big", 1024); !errors.Is(err, library.ErrTooLarge) {
		t.Fatalf("oversized: %v", err)
	}
	b, err := src.API.Download(context.Background(), "big", 4096)
	if err != nil || len(b) != 2048 {
		t.Fatalf("download: %d %v", len(b), err)
	}
}
