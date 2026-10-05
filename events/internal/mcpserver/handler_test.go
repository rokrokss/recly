package mcpserver

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"path/filepath"
	"testing"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/rokrokss/recly/events/internal/state"
	"github.com/rokrokss/recly/events/internal/webhook"
)

func newHandler(t *testing.T) *Handler {
	t.Helper()
	store, err := state.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	hub := &webhook.Hub{Store: store, Guard: &webhook.Guard{Hosts: []string{"connectors.api.openai.com"}}, Log: log}
	return &Handler{Hub: hub, Log: log, Version: "test"}
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
	var names []string
	for _, tool := range call(t, h, "tools/list", map[string]any{})["tools"].([]any) {
		names = append(names, tool.(map[string]any)["name"].(string))
	}
	if len(names) != 2 || names[0] != "get_pending_events" || names[1] != "acknowledge_events" {
		t.Fatalf("tools = %v", names)
	}
	ev := call(t, h, "events/list", map[string]any{})["events"].([]any)[0].(map[string]any)
	if ev["name"] != webhook.EventName {
		t.Fatalf("event = %v", ev)
	}
}

func TestInboxToolsRoundTrip(t *testing.T) {
	h := newHandler(t)
	if _, err := h.Hub.Emit("evt_1", map[string]string{"recording": "r1"}); err != nil {
		t.Fatal(err)
	}
	got := call(t, h, "tools/call", map[string]any{"name": "get_pending_events", "arguments": map[string]any{}})
	events := got["structuredContent"].(map[string]any)["events"].([]any)
	if len(events) != 1 || events[0].(map[string]any)["eventId"] != "evt_1" {
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
	if err != nil || len(tools.Tools) != 2 {
		t.Fatalf("tools = %+v %v", tools, err)
	}
}
