package drive

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

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
			"metaA": `{"recordingId":"` + idA + `","title":"Old title","durationSec":95.5}`,
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
	page, next, err := src.Recordings(ctx, 2, "", library.Span{})
	if err != nil || next == "" {
		t.Fatalf("page 1: %v %q", err, next)
	}
	// The first page held a folder still being uploaded into, which is not listed yet.
	if len(page) != 1 || page[0].RecordingID != idC || page[0].Title != nil || page[0].HasTranscript || page[0].Source != "phone" {
		t.Fatalf("page 1 = %+v", page)
	}
	page, next, err = src.Recordings(ctx, 2, next, library.Span{})
	if err != nil || next == "" || len(page) != 2 {
		t.Fatalf("page 2: %v %q %+v", err, next, page)
	}
	b, a := page[0], page[1]
	if b.RecordingID != idB || b.Title == nil || *b.Title != "Board" || !b.HasTranscript || b.StartedAt != "2026-10-02T09:00:00Z" {
		t.Fatalf("B = %+v", b)
	}
	if a.RecordingID != idA || *a.Title != "Weekly sync" || *a.DurationSec != 95.5 || !a.HasTranscript {
		t.Fatalf("A = %+v", a)
	}
	// The folder template's own folder starts with the year too; it is not a recording.
	page, next, err = src.Recordings(ctx, 2, next, library.Span{})
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
			tr.Lines[0].Text != "[00:00:00] Kim: Hello." || tr.StartedAt != "2026-10-01T06:45:03Z" {
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

// An import keeps its file's own date, so a base name can start with any year.
func TestSourceListsAnImportFromAnotherCentury(t *testing.T) {
	src, fd := setupSource(t)
	const base, id = "19991231T235959Z_import_01M9QXD0", "01M9QXD0ZZZZZZZZZZZZZZZZZZ"
	fd.files["fOld"] = File{ID: "fOld", Name: base, MimeType: "application/vnd.google-apps.folder"}
	fd.files["metaOld"] = File{ID: "metaOld", Name: base + ".meta.json", Parents: []string{"fOld"}}
	fd.content["metaOld"] = `{"recordingId":"` + id + `","title":"Tape"}`
	ctx := context.Background()
	var found bool
	cursor := ""
	for range 5 {
		page, next, err := src.Recordings(ctx, 10, cursor, library.Span{})
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range page {
			found = found || r.RecordingID == id
		}
		if next == "" {
			break
		}
		cursor = next
	}
	if !found {
		t.Fatal("the 1999 import was not listed")
	}
}

func TestSourceListsBetweenDates(t *testing.T) {
	src, _ := setupSource(t)
	ctx := context.Background()
	day := func(s string) time.Time {
		at, _ := time.Parse(time.RFC3339, s)
		return at
	}
	span := library.Span{From: day("2026-10-01T00:00:00Z"), To: day("2026-10-05T23:59:59Z")}
	// One at a time: each cursor goes on inside the same page of folders.
	var got []string
	cursor := ""
	for range 5 {
		page, next, err := src.Recordings(ctx, 1, cursor, span)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range page {
			got = append(got, r.RecordingID)
		}
		if next == "" {
			break
		}
		cursor = next
	}
	if strings.Join(got, ",") != idC+","+idB+","+idA {
		t.Fatalf("listed %v", got)
	}
	// The first folder older than from ends the list.
	page, next, err := src.Recordings(ctx, 10, "", library.Span{From: day("2026-10-02T00:00:00Z")})
	if err != nil || next != "" || len(page) != 2 || page[0].RecordingID != idC || page[1].RecordingID != idB {
		t.Fatalf("from only: %v %q %+v", err, next, page)
	}
	page, next, err = src.Recordings(ctx, 10, "", library.Span{To: day("2026-10-01T06:45:03Z")})
	if err != nil || next != "" || len(page) != 1 || page[0].RecordingID != idA {
		t.Fatalf("to only: %v %q %+v", err, next, page)
	}
	var te *library.ToolError
	if _, _, err := src.Recordings(ctx, 10, "bm90IGEgY3Vyc29y", span); !errors.As(err, &te) {
		t.Fatalf("bad cursor: %v", err)
	}
}

func TestSourceReadsASummaryAndTheUsersNotes(t *testing.T) {
	src, fd := setupSource(t)
	fd.files["notesA"] = File{ID: "notesA", Name: baseA + ".notes.json", Parents: []string{"fA"}}
	fd.files["memoA"] = File{ID: "memoA", Name: baseA + ".memo.json", Parents: []string{"fA"}}
	fd.content["notesA"] = `{"recordingId":"` + idA + `","stage":"final","overview":"Hello said.",
		"items":[{"kind":"todo","text":"Say hello again.","owner":"S1","evidence":{"atSec":0,"quote":"Hello."}}]}`
	fd.content["memoA"] = `{"recordingId":"` + idA + `","text":"Ask about Friday","updatedAt":"2026-10-01T06:40:00Z"}`
	ctx := context.Background()
	for _, id := range []string{idA, baseA} {
		f, err := src.Summary(ctx, id)
		if err != nil {
			t.Fatalf("%s: %v", id, err)
		}
		if f.RecordingID != idA || *f.Title != "Weekly sync" || f.Notes == nil || string(f.Memo) != fd.content["memoA"] ||
			len(f.Speakers) != 1 || *f.Speakers[0].Name != "Kim" {
			t.Fatalf("%s: %+v", id, f)
		}
	}
	tools := &library.Tools{Source: src}
	res, _, err := tools.Call(ctx, "get_summary", json.RawMessage(`{"recordingId":"`+idA+`"}`))
	if err != nil {
		t.Fatal(err)
	}
	b, _ := library.Marshal(res)
	if !strings.Contains(string(b), `"ownerName":"Kim"`) || !strings.Contains(string(b), `"userNotes":{"text":"Ask about Friday"`) {
		t.Fatalf("get_summary = %s", b)
	}
	// Neither file: the recording, and no summary or notes.
	f, err := src.Summary(ctx, idB)
	if err != nil || f.RecordingID != idB || f.Notes != nil || f.Memo != nil || f.Speakers != nil {
		t.Fatalf("B: %+v %v", f, err)
	}
	// A recording that shares A's ID prefix is told apart, as for get_transcript.
	if f, err := src.Summary(ctx, idC); err != nil || f.RecordingID != idC || f.Notes != nil {
		t.Fatalf("C: %+v %v", f, err)
	}
}

func TestSourceSummaryOfAFolderWithoutAnIDIsCheckedByItsFiles(t *testing.T) {
	src, fd := setupSource(t)
	// D shares C's ID prefix and is newer, so it is looked at first. Neither its half-written meta
	// nor its folder says whose it is; its summary and notes do.
	const (
		baseD = "20261006T000000Z_phone_01M3V3B6"
		idD   = "01M3V3B6DDDDDDDDDDDDDDDDDD"
	)
	fd.files["fD"] = File{ID: "fD", Name: baseD, MimeType: "application/vnd.google-apps.folder"}
	fd.files["metaD"] = File{ID: "metaD", Name: baseD + ".meta.json", Parents: []string{"fD"}}
	fd.files["notesD"] = File{ID: "notesD", Name: baseD + ".notes.json", Parents: []string{"fD"}}
	fd.files["memoD"] = File{ID: "memoD", Name: baseD + ".memo.json", Parents: []string{"fD"}}
	fd.content["metaD"] = `{"recordingId":"01M3V3`
	fd.content["notesD"] = `{"recordingId":"` + idD + `","overview":"D's summary.","items":[]}`
	fd.content["memoD"] = `{"recordingId":"` + idD + `","text":"D's notes"}`
	ctx := context.Background()
	if f, err := src.Summary(ctx, idC); err != nil || f.RecordingID != idC || f.Notes != nil || f.Memo != nil {
		t.Fatalf("C: %+v %v", f, err)
	}
	if f, err := src.Summary(ctx, idD); err != nil || f.RecordingID != idD || f.Notes == nil || f.Memo == nil {
		t.Fatalf("D: %+v %v", f, err)
	}
	// The user's notes (the memo) alone tell as well.
	delete(fd.files, "notesD")
	if f, err := src.Summary(ctx, idC); err != nil || f.RecordingID != idC || f.Memo != nil {
		t.Fatalf("C by D's notes: %+v %v", f, err)
	}
}
