package app

import (
	"context"
	"io"
)

// StopWhenClosed cancels once r reaches its end. The Recly desktop apps run `serve` with a pipe on
// standard input and keep the other end, so the server goes when the app does, even when the app
// is killed (docs/recly.md §12 "Agent connection").
func StopWhenClosed(r io.Reader, cancel context.CancelFunc) {
	go func() {
		_, _ = io.Copy(io.Discard, r)
		cancel()
	}()
}
