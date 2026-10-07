package mcpserver

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/rokrokss/recly/events/internal/library"
	"github.com/rokrokss/recly/events/internal/state"
	"github.com/rokrokss/recly/events/internal/webhook"
)

const (
	base  = "20261001T064503Z_watch_01M3V3B6"
	recID = "01M3V3B6P5NF6ZM7X1QDFERRWT"
)

// newHandler reads recordings from a folder: the read tools render the same over any source, and
// internal/drive tests the Drive one.
func newHandler(t *testing.T) *Handler {
	t.Helper()
	store, err := state.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	dir := filepath.Join(root, "recly", base)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, body := range map[string]string{
		base + ".meta.json":       `{"recordingId":"` + recID + `","title":"Weekly sync","highlights":[{"atSec":30}]}`,
		base + ".transcript.json": `{"recordingId":"` + recID + `","language":"en","speakers":[{"id":"S1","name":"Kim"}],"segments":[{"start":1,"end":2,"speaker":"S1","text":"Ship it on Friday."}]}`,
	} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	hub := &webhook.Hub{Store: store, Guard: &webhook.Guard{Hosts: []string{"connectors.api.openai.com"}}, Log: log}
	tools := &library.Tools{Source: &library.Folder{Roots: []string{root}}, Nonce: func() string { return "n" }}
	return &Handler{Hub: hub, Tools: tools, Log: log, Version: "test"}
}

func callTool(t *testing.T, h *Handler, name string, args any) (map[string]any, bool) {
	t.Helper()
	got := call(t, h, "tools/call", map[string]any{"name": name, "arguments": args})
	if got["isError"] == true {
		return map[string]any{"text": got["content"].([]any)[0].(map[string]any)["text"]}, true
	}
	text := got["content"].([]any)[0].(map[string]any)["text"].(string)
	var fromText map[string]any
	if err := json.Unmarshal([]byte(text), &fromText); err != nil {
		t.Fatalf("%s: text content is not the JSON: %v", name, err)
	}
	return got["structuredContent"].(map[string]any), false
}

func call(t *testing.T, h *Handler, method string, params any) map[string]any {
	t.Helper()
	b, _ := json.Marshal(params)
	res, err := h.Handle(context.Background(), method, b)
	if err != nil {
		t.Fatalf("%s: %v", method, err)
	}
	out, _ := json.Marshal(res)
	var m map[string]any
	_ = json.Unmarshal(out, &m)
	return m
}

func TestDiscoverAdvertisesEventsAndTools(t *testing.T) {
	h := newHandler(t)
	d := call(t, h, "server/discover", map[string]any{})
	caps := d["capabilities"].(map[string]any)
	if _, ok := caps["events"]; !ok {
		t.Fatalf("capabilities = %v", caps)
	}
	if v := d["supportedVersions"].([]any); len(v) != 1 || v[0] != ProtocolVersion {
		t.Fatalf("versions = %v", v)
	}
	if in := d["instructions"].(string); !strings.Contains(in, "get_pending_events") || !strings.Contains(in, "get_transcript") ||
		!strings.Contains(in, "acknowledge_events") || strings.Contains(in, "Google Drive app") {
		t.Fatalf("instructions = %q", in)
	}
	var names []string
	for _, tool := range call(t, h, "tools/list", map[string]any{})["tools"].([]any) {
		names = append(names, tool.(map[string]any)["name"].(string))
	}
	if strings.Join(names, ",") != "get_pending_events,get_transcript,acknowledge_events,list_recordings" {
		t.Fatalf("tools = %v", names)
	}
	ev := call(t, h, "events/list", map[string]any{})["events"].([]any)[0].(map[string]any)
	if ev["name"] != webhook.EventName {
		t.Fatalf("event = %v", ev)
	}
	device := ev["payloadSchema"].(map[string]any)["properties"].(map[string]any)["device"].(map[string]any)
	if enum := device["enum"].([]any); len(enum) != 4 || enum[3] != "import" {
		t.Fatalf("device = %v", device)
	}
}

// A dot's run gets the event without its data: everything it needs comes from the tools,
// get_pending_events → get_transcript → acknowledge_events.
func TestDotSequenceWorksFromToolsAlone(t *testing.T) {
	h := newHandler(t)
	title := "Weekly sync"
	if _, err := h.Hub.Emit("evt_1", map[string]any{"recording": base, "recordingIdPrefix": "01M3V3B6", "recordingId": recID,
		"title": title, "startedAt": "2026-10-01T06:45:03Z", "device": "watch"}); err != nil {
		t.Fatal(err)
	}
	// A client of the user's own cannot see the recordingId: the base name stands in for it.
	if _, err := h.Hub.Emit("evt_2", map[string]any{"recording": base, "title": nil, "startedAt": "2026-10-01T06:45:03Z"}); err != nil {
		t.Fatal(err)
	}
	pending, _ := callTool(t, h, "get_pending_events", map[string]any{})
	events := pending["events"].([]any)
	e1, e2 := events[0].(map[string]any), events[1].(map[string]any)
	if e1["recordingId"] != recID || e1["title"] != title || e1["startedAt"] != "2026-10-01T06:45:03Z" || e1["data"] == nil {
		t.Fatalf("event 1 = %v", e1)
	}
	if e2["recordingId"] != base || e2["title"] != nil {
		t.Fatalf("event 2 = %v", e2)
	}
	for _, args := range []map[string]any{{"recordingId": recID}, {"recordingId": base}, {"eventId": "evt_1"}, {"eventId": "evt_2"}} {
		got, isErr := callTool(t, h, "get_transcript", args)
		if isErr {
			t.Fatalf("%v: %v", args, got)
		}
		text := got["transcript"].(string)
		if got["recordingId"] != recID || got["title"] != title || !strings.HasPrefix(text, "<<<recly-transcript-n UNTRUSTED") ||
			!strings.Contains(text, "[00:00:01] Kim: Ship it on Friday.") || !strings.HasSuffix(text, "<<<end recly-transcript-n>>>") {
			t.Fatalf("%v: %v", args, got)
		}
	}
	ack, _ := callTool(t, h, "acknowledge_events", map[string]any{"eventIds": []string{"evt_1", "evt_2"}})
	if ack["acknowledged"] != float64(2) || ack["pending"] != float64(0) {
		t.Fatalf("ack = %v", ack)
	}
	// An acknowledged event can still be read again.
	if got, isErr := callTool(t, h, "get_transcript", map[string]any{"eventId": "evt_1"}); isErr {
		t.Fatalf("acknowledged event: %v", got)
	}
	list, _ := callTool(t, h, "list_recordings", map[string]any{"limit": 5})
	recs := list["recordings"].([]any)
	if len(recs) != 1 || recs[0].(map[string]any)["recordingId"] != recID || recs[0].(map[string]any)["highlightCount"] != float64(1) {
		t.Fatalf("list = %v", list)
	}
}

func TestReadToolErrorsAreToolErrors(t *testing.T) {
	h := newHandler(t)
	for _, c := range []struct {
		args map[string]any
		want string
	}{
		{map[string]any{}, "recordingId or eventId is required"},
		{map[string]any{"eventId": "evt_gone"}, "no event evt_gone"},
		{map[string]any{"recordingId": "01ZZZZZZP5NF6ZM7X1QDFERRWT"}, "was found"},
		{map[string]any{"recordingId": "drop table"}, "not a Recly recording ID"},
		{map[string]any{"recordingId": recID, "cursor": "7"}, "past the end"},
	} {
		got, isErr := callTool(t, h, "get_transcript", c.args)
		if !isErr || !strings.Contains(got["text"].(string), c.want) {
			t.Errorf("%v: %v", c.args, got)
		}
	}
	if got, isErr := callTool(t, h, "list_recordings", map[string]any{"cursor": "nope"}); !isErr || !strings.Contains(got["text"].(string), "cursor") {
		t.Errorf("list: %v", got)
	}
}

func TestInboxToolsRoundTrip(t *testing.T) {
	h := newHandler(t)
	if _, err := h.Hub.Emit("evt_1", map[string]string{"recording": "r1"}); err != nil {
		t.Fatal(err)
	}
	got := call(t, h, "tools/call", map[string]any{"name": "get_pending_events", "arguments": map[string]any{}})
	events := got["structuredContent"].(map[string]any)["events"].([]any)
	if len(events) != 1 || events[0].(map[string]any)["eventId"] != "evt_1" || events[0].(map[string]any)["recordingId"] != "r1" {
		t.Fatalf("pending = %v", events)
	}
	ack := call(t, h, "tools/call", map[string]any{"name": "acknowledge_events", "arguments": map[string]any{"eventIds": []string{"evt_1"}}})
	if sc := ack["structuredContent"].(map[string]any); sc["acknowledged"] != float64(1) || sc["pending"] != float64(0) {
		t.Fatalf("ack = %v", sc)
	}
	empty := call(t, h, "tools/call", map[string]any{"name": "get_pending_events", "arguments": map[string]any{}})
	if n := len(empty["structuredContent"].(map[string]any)["events"].([]any)); n != 0 {
		t.Fatalf("still pending: %d", n)
	}
}

func TestRefusesInitializeAndUnknownMethods(t *testing.T) {
	h := newHandler(t)
	for _, m := range []string{"initialize", "resources/list"} {
		if _, err := h.Handle(context.Background(), m, json.RawMessage(`{}`)); err == nil {
			t.Errorf("%s accepted", m)
		}
	}
	_, err := h.Handle(context.Background(), "events/subscribe", json.RawMessage(`{"name":"recording.transcribed","delivery":{"mode":"webhook","url":"https://evil.example/x","secret":"whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"}}`))
	re, ok := err.(*RPCError)
	if !ok || re.Code != -32015 || re.Reason != "invalid_url" {
		t.Fatalf("subscribe to a foreign host: %#v", err)
	}
}

// The tunnel hands requests over an in-memory connection; a go-sdk client on the other end
// sees an MCP 2026-07-28 server.
func TestServeOverInMemoryTransport(t *testing.T) {
	h := newHandler(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	serverT, clientT := mcp.NewInMemoryTransports()
	conn, err := serverT.Connect(ctx)
	if err != nil {
		t.Fatal(err)
	}
	go h.Serve(ctx, conn)
	session, err := mcp.NewClient(&mcp.Implementation{Name: "test", Version: "0"}, nil).Connect(ctx, clientT, nil)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	if v := session.InitializeResult().ProtocolVersion; v != ProtocolVersion {
		t.Fatalf("protocol = %s", v)
	}
	tools, err := session.ListTools(ctx, nil)
	if err != nil || len(tools.Tools) != 4 {
		t.Fatalf("tools = %+v %v", tools, err)
	}
}
