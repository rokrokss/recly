package mcpserver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/rokrokss/recly/events/internal/library"
)

// ServeLocal answers MCP on t — standard input and output under `recly-events mcp` — with the read
// tools over folders on this computer: no events, no tunnel, no network. Unlike the tunnel's
// handler it is the go-sdk server, which negotiates the protocol version desktop agents such as
// Claude and Codex speak (they start with `initialize`, which MCP 2026-07-28 dropped).
func ServeLocal(ctx context.Context, tools *library.Tools, version string, t mcp.Transport) error {
	s, err := LocalServer(tools, version)
	if err != nil {
		return err
	}
	return s.Run(ctx, t)
}

// LocalServer is the server ServeLocal runs.
func LocalServer(tools *library.Tools, version string) (*mcp.Server, error) {
	s := mcp.NewServer(&mcp.Implementation{Name: "recly", Title: "Recly Events", Version: version},
		&mcp.ServerOptions{Instructions: library.LocalInstructions})
	for _, def := range library.LocalTools() {
		b, err := json.Marshal(def)
		if err != nil {
			return nil, err
		}
		var tool mcp.Tool
		if err := json.Unmarshal(b, &tool); err != nil {
			return nil, fmt.Errorf("tool %v: %w", def["name"], err)
		}
		name := tool.Name
		s.AddTool(&tool, func(ctx context.Context, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			res, _, err := tools.Call(ctx, name, req.Params.Arguments)
			var te *library.ToolError
			if errors.As(err, &te) {
				return &mcp.CallToolResult{IsError: true, Content: []mcp.Content{&mcp.TextContent{Text: te.Message}}}, nil
			}
			if err != nil {
				return nil, err
			}
			out, err := library.Marshal(res)
			if err != nil {
				return nil, err
			}
			return &mcp.CallToolResult{
				Content:           []mcp.Content{&mcp.TextContent{Text: string(out)}},
				StructuredContent: json.RawMessage(out),
			}, nil
		})
	}
	return s, nil
}
