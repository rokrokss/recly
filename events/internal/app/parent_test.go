package app

import (
	"context"
	"io"
	"testing"
	"time"
)

func TestStopWhenClosedWaitsForTheEnd(t *testing.T) {
	r, w := io.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	StopWhenClosed(r, cancel)
	if _, err := w.Write([]byte("anything")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ctx.Done():
		t.Fatal("stopped while the parent still held the pipe")
	case <-time.After(50 * time.Millisecond):
	}
	_ = w.Close()
	select {
	case <-ctx.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("still running after the pipe closed")
	}
}
