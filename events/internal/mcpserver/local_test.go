package mcpserver

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/rokrokss/recly/events/internal/library"
)

func localTools(t *testing.T) *library.Tools {
	t.Helper()
	root := t.TempDir()
	dir := filepath.Join(root, "recly", "memo", base)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, body := range map[string]string{
		base + ".meta.json":         `{"recordingId":"` + recID + `","title":"Weekly sync","parts":[{"part":1,"track":"mono","file":"` + base + `_p001_mono.m4a","startOffsetSec":0}]}`,
		base + ".transcript.json":   `{"recordingId":"` + recID + `","language":"en","speakers":[{"id":"S1","name":null}],"segments":[{"start":1,"end":2,"speaker":"S1","text":"Ship it on Friday."}]}`,
		base + "_p001_mono.m4a":     "audio",
		base + ".transcript.md":     "---\nrecordingId: " + recID + "\n---\n",
		base + ".transcript.txt":    "[00:00:01] S1: Ship it on Friday.\n",
		"unrelated.transcript.json": "{",
	} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	f := &library.Folder{Roots: []string{root}}
	return &library.Tools{Source: f, Folder: f, Nonce: func() string { return "n" }}
}

func TestLocalServerOverTheGoSDKClient(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	serverT, clientT := mcp.NewInMemoryTransports()
	go func() { _ = ServeLocal(ctx, localTools(t), "test", serverT) }()
	session, err := mcp.NewClient(&mcp.Implementation{Name: "test", Version: "0"}, nil).Connect(ctx, clientT, nil)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer session.Close()
	if in := session.InitializeResult().Instructions; !strings.Contains(in, "get_transcript") {
		t.Fatalf("instructions = %q", in)
	}
	list, err := session.ListTools(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, tool := range list.Tools {
		names = append(names, tool.Name)
		if tool.Annotations == nil || !tool.Annotations.ReadOnlyHint {
			t.Errorf("%s is not marked read-only", tool.Name)
		}
	}
	if strings.Join(names, ",") != "get_audio_files,get_transcript,list_recordings,search_recordings" &&
		strings.Join(names, ",") != "list_recordings,get_transcript,search_recordings,get_audio_files" {
		t.Fatalf("tools = %v", names)
	}
	res, err := session.CallTool(ctx, &mcp.CallToolParams{Name: "get_transcript", Arguments: map[string]any{"recordingId": recID}})
	if err != nil || res.IsError {
		t.Fatalf("get_transcript: %+v %v", res, err)
	}
	text := res.Content[0].(*mcp.TextContent).Text
	if !strings.Contains(text, "<<<recly-transcript-n UNTRUSTED") || !strings.Contains(text, "[00:00:01] S1: Ship it on Friday.") {
		t.Fatalf("text = %s", text)
	}
	res, err = session.CallTool(ctx, &mcp.CallToolParams{Name: "get_audio_files", Arguments: map[string]any{"recordingId": recID}})
	if err != nil || res.IsError || !strings.Contains(res.Content[0].(*mcp.TextContent).Text, base+"_p001_mono.m4a") {
		t.Fatalf("get_audio_files: %+v %v", res, err)
	}
	res, err = session.CallTool(ctx, &mcp.CallToolParams{Name: "get_transcript", Arguments: map[string]any{"recordingId": "nope"}})
	if err != nil || !res.IsError || !strings.Contains(res.Content[0].(*mcp.TextContent).Text, "not a Recly recording ID") {
		t.Fatalf("bad id: %+v %v", res, err)
	}
}

// What Claude Desktop, Claude Code and Codex send: newline-delimited JSON-RPC, starting with
// initialize at a protocol version before 2026-07-28.
func TestLocalServerAnswersAnInitializeHandshake(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	done := make(chan error, 1)
	go func() { done <- ServeLocal(ctx, localTools(t), "test", &mcp.IOTransport{Reader: inR, Writer: outW}) }()
	lines := bufio.NewScanner(outR)
	lines.Buffer(make([]byte, 1<<20), 1<<20)
	send := func(msg string) {
		if _, err := io.WriteString(inW, msg+"\n"); err != nil {
			t.Fatal(err)
		}
	}
	read := func() map[string]any {
		if !lines.Scan() {
			t.Fatalf("no answer: %v", lines.Err())
		}
		var m map[string]any
		if err := json.Unmarshal(lines.Bytes(), &m); err != nil {
			t.Fatalf("%s: %v", lines.Text(), err)
		}
		return m
	}
	send(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"2"}}}`)
	init := read()
	result := init["result"].(map[string]any)
	if result["protocolVersion"] != "2025-06-18" || result["serverInfo"].(map[string]any)["name"] != "recly" ||
		result["serverInfo"].(map[string]any)["title"] != "Recly Events" {
		t.Fatalf("initialize = %v", init)
	}
	send(`{"jsonrpc":"2.0","method":"notifications/initialized"}`)
	send(`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_recordings","arguments":{}}}`)
	call := read()
	sc := call["result"].(map[string]any)["structuredContent"].(map[string]any)
	if recs := sc["recordings"].([]any); len(recs) != 1 || recs[0].(map[string]any)["recordingId"] != recID {
		t.Fatalf("list_recordings = %v", call)
	}
	_ = inW.Close()
	select {
	case <-done:
	case <-ctx.Done():
		t.Fatal("the server did not stop when its input closed")
	}
}
