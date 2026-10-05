package drive

import (
	"bufio"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"golang.org/x/oauth2"
)

// TokenPipe holds the Drive access token a parent process hands `serve`, one per line on standard
// input. The Recly desktop apps pass their own Drive connection's short-lived access token this
// way, so the copy they run needs no Google sign-in of its own (docs/recly.md §15 §9). The token is
// kept in memory only and never logged.
type TokenPipe struct {
	mu    sync.Mutex
	token string
	first chan struct{}
	once  sync.Once
	// wait is how long a request waits for the parent's first line.
	wait time.Duration
}

// ReadTokens reads tokens from r until it ends, then calls done: the parent going, or closing its
// end, is the signal to stop.
func ReadTokens(r io.Reader, done func()) *TokenPipe {
	p := &TokenPipe{first: make(chan struct{}), wait: 15 * time.Second}
	go func() {
		sc := bufio.NewScanner(r)
		for sc.Scan() {
			if t := strings.TrimSpace(sc.Text()); t != "" {
				p.mu.Lock()
				p.token = t
				p.mu.Unlock()
				p.once.Do(func() { close(p.first) })
			}
		}
		done()
	}()
	return p
}

// Token returns the latest token. Until the first line arrives a request waits for it briefly —
// the parent writes it right after starting the server, which polls right away.
func (p *TokenPipe) Token() (*oauth2.Token, error) {
	select {
	case <-p.first:
	case <-time.After(p.wait):
		return nil, errors.New("drive: no access token from the app yet")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return &oauth2.Token{AccessToken: p.token, TokenType: "Bearer"}, nil
}

// Client sends the latest token with every request. Not oauth2.NewClient: its ReuseTokenSource
// keeps the first token for ever, since a token without an expiry never looks expired to it.
func (p *TokenPipe) Client() *http.Client {
	return &http.Client{Transport: &oauth2.Transport{Source: p}}
}
