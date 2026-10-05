package mcpserver

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"sync"

	"github.com/modelcontextprotocol/go-sdk/jsonrpc"
	"github.com/modelcontextprotocol/go-sdk/mcp"
	tunnelclient "github.com/openai/tunnel-client"
)

// Serve answers MCP requests arriving on conn until it closes or ctx is done.
func (h *Handler) Serve(ctx context.Context, conn mcp.Connection) error {
	var mu sync.Mutex
	for {
		msg, err := conn.Read(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		req, ok := msg.(*jsonrpc.Request)
		if !ok || !req.ID.IsValid() {
			continue // responses and notifications need no answer
		}
		go func() {
			resp := h.respond(ctx, req)
			mu.Lock()
			defer mu.Unlock()
			if err := conn.Write(ctx, resp); err != nil && ctx.Err() == nil {
				h.Log.Warn("mcp.write.failed", "method", req.Method, "error", err.Error())
			}
		}()
	}
}

func (h *Handler) respond(ctx context.Context, req *jsonrpc.Request) *jsonrpc.Response {
	resp := &jsonrpc.Response{ID: req.ID}
	result, err := h.Handle(ctx, req.Method, req.Params)
	if err != nil {
		var re *RPCError
		if !errors.As(err, &re) {
			h.Log.Warn("mcp.error", "method", req.Method, "error", err.Error())
			re = &RPCError{Code: -32603, Message: "request failed"}
		}
		werr := &jsonrpc.Error{Code: re.Code, Message: re.Message}
		if re.Reason != "" {
			werr.Data, _ = json.Marshal(map[string]string{"reason": re.Reason})
		}
		resp.Error = werr
		return resp
	}
	// MCP 2026-07-28 results carry resultType and the server info in _meta.
	b, err := json.Marshal(result)
	if err == nil {
		var obj map[string]json.RawMessage
		if err = json.Unmarshal(b, &obj); err == nil {
			obj["resultType"] = json.RawMessage(`"complete"`)
			meta, _ := json.Marshal(map[string]any{"io.modelcontextprotocol/serverInfo": h.serverInfo()})
			obj["_meta"] = meta
			resp.Result, err = json.Marshal(obj)
		}
	}
	if err != nil {
		resp.Result = nil
		resp.Error = &jsonrpc.Error{Code: -32603, Message: "request failed"}
	}
	return resp
}

// Tunnel connects the handler to ChatGPT through the OpenAI Secure MCP Tunnel: the embedded
// tunnel-client polls api.openai.com and hands requests over an in-memory connection, so
// nothing listens on the network.
type Tunnel struct {
	ID     string
	Key    string
	Log    *slog.Logger
	client *tunnelclient.Client
}

// Run starts the tunnel and serves h until ctx is done. ready is closed after the first
// successful control-plane poll.
func (t *Tunnel) Run(ctx context.Context, h *Handler, ready chan<- struct{}) error {
	serverT, clientT := mcp.NewInMemoryTransports()
	conn, err := serverT.Connect(ctx)
	if err != nil {
		return err
	}
	serveErr := make(chan error, 1)
	go func() { serveErr <- h.Serve(ctx, conn) }()

	c, err := tunnelclient.New(tunnelclient.Config{
		TunnelID: t.ID, APIKey: t.Key,
		LogLevel: slog.LevelWarn, LogWriter: logWriter{t.Log},
	}, clientT)
	if err != nil {
		return err
	}
	if err := c.Start(ctx); err != nil {
		return err
	}
	defer c.Stop(context.Background())
	if err := c.WaitUntilReady(ctx); err != nil {
		return err
	}
	t.Log.Info("tunnel.ready")
	if ready != nil {
		close(ready)
	}
	select {
	case <-ctx.Done():
		return nil
	case <-c.Done():
		return errors.New("tunnel runtime stopped")
	case err := <-serveErr:
		if err == nil {
			return nil
		}
		return err
	}
}

// Check proves the tunnel ID and key against the control plane, then disconnects.
func Check(ctx context.Context, id, key string, log *slog.Logger) error {
	h := &Handler{Log: log, Version: "check"}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	ready := make(chan struct{})
	errc := make(chan error, 1)
	go func() { errc <- (&Tunnel{ID: id, Key: key, Log: log}).Run(ctx, h, ready) }()
	select {
	case <-ready:
		return nil
	case err := <-errc:
		if err == nil {
			err = errors.New("tunnel stopped before it was ready")
		}
		return err
	}
}

// logWriter turns tunnel-client's log lines into warnings in our log.
type logWriter struct{ log *slog.Logger }

func (w logWriter) Write(p []byte) (int, error) {
	w.log.Warn("tunnel.log", "line", string(trimNewline(p)))
	return len(p), nil
}

func trimNewline(p []byte) []byte {
	for len(p) > 0 && (p[len(p)-1] == '\n' || p[len(p)-1] == '\r') {
		p = p[:len(p)-1]
	}
	return p
}
