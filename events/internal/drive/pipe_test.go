package drive

import (
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestThePipeSendsTheLatestTokenWithEveryRequest(t *testing.T) {
	r, w := io.Pipe()
	defer w.Close()
	p := ReadTokens(r, func() {})
	var mu sync.Mutex
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(rw http.ResponseWriter, req *http.Request) {
		mu.Lock()
		seen = append(seen, req.Header.Get("Authorization"))
		mu.Unlock()
	}))
	defer srv.Close()
	client := p.Client()

	go func() { _, _ = io.WriteString(w, "tok-1\n") }()
	if _, err := client.Get(srv.URL); err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(w, "tok-2\n"); err != nil {
		t.Fatal(err)
	}
	// The reader takes the line on its own goroutine; the next request carries it.
	deadline := time.Now().Add(2 * time.Second)
	for {
		if _, err := client.Get(srv.URL); err != nil {
			t.Fatal(err)
		}
		mu.Lock()
		last := seen[len(seen)-1]
		mu.Unlock()
		if last == "Bearer tok-2" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("a newer token was never sent: %v", seen)
		}
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	if seen[0] != "Bearer tok-1" {
		t.Fatalf("first request sent %q", seen[0])
	}
}

func TestThePipeEndingStopsTheServer(t *testing.T) {
	r, w := io.Pipe()
	stopped := make(chan struct{})
	ReadTokens(r, func() { close(stopped) })
	_ = w.Close()
	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("the parent closing its end did not stop the server")
	}
}

func TestNoTokenYetIsAnErrorAfterAShortWait(t *testing.T) {
	r, w := io.Pipe()
	defer w.Close()
	p := ReadTokens(r, func() {})
	p.wait = 20 * time.Millisecond
	if _, err := p.Token(); err == nil {
		t.Fatal("a token before the app sent one")
	}
}
