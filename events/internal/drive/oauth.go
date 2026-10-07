// Package drive finds new Recly transcripts in the user's Google Drive by their metadata — names,
// folders, IDs and links — and reads a recording's meta and transcript only when the agent asks
// for them through the read tools (Source).
package drive

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

const (
	// ScopeRecly goes with Recly's own desktop OAuth client, the one the Windows app signs in
	// with: under drive.file, Drive shows a client of Recly's Google Cloud project only the
	// files Recly's apps created (docs/recly.md §6), which is exactly what is watched.
	ScopeRecly = "https://www.googleapis.com/auth/drive.file"
	// ScopeReadonly goes with a client from the user's own Google Cloud project, which cannot see
	// Recly's files under drive.file. Read-only, but every file: the agent reads transcripts
	// through get_transcript, so names alone are not enough.
	ScopeReadonly = "https://www.googleapis.com/auth/drive.readonly"
)

// ReclyClient is Recly's desktop OAuth client, compiled in at build time.
func ReclyClient(id, secret string) *oauth2.Config {
	return &oauth2.Config{ClientID: id, ClientSecret: secret, Endpoint: google.Endpoint, Scopes: []string{ScopeRecly}}
}

// TokenFile is google-token.json.
type TokenFile struct {
	Token      *oauth2.Token `json:"token"`
	ObtainedAt time.Time     `json:"obtainedAt"`
	// RefreshedAt is the last time a new access token was minted from the refresh token.
	RefreshedAt time.Time `json:"refreshedAt,omitzero"`
}

// LoadClient parses a desktop OAuth client JSON from the user's own Google Cloud project.
func LoadClient(path string) (*oauth2.Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	cfg, err := google.ConfigFromJSON(b, ScopeReadonly)
	if err != nil {
		return nil, fmt.Errorf("%s is not a Google OAuth client file: %w", path, err)
	}
	return cfg, nil
}

// Consent is one sign-in through the installed-app flow: PKCE, the user's consent in a
// browser, and Google's redirect to 127.0.0.1 with the code.
type Consent struct {
	cfg      oauth2.Config
	state    string
	verifier string
}

// NewConsent prepares a sign-in whose redirect goes to port on 127.0.0.1.
func NewConsent(cfg *oauth2.Config, port int) (*Consent, error) {
	stateBytes := make([]byte, 24)
	if _, err := rand.Read(stateBytes); err != nil {
		return nil, err
	}
	c := &Consent{cfg: *cfg, state: base64.RawURLEncoding.EncodeToString(stateBytes), verifier: oauth2.GenerateVerifier()}
	c.cfg.RedirectURL = fmt.Sprintf("http://127.0.0.1:%d/callback", port)
	return c, nil
}

// URL is Google's consent page.
func (c *Consent) URL() string {
	return c.cfg.AuthCodeURL(c.state, oauth2.AccessTypeOffline, oauth2.ApprovalForce, oauth2.S256ChallengeOption(c.verifier))
}

// Code is the code in the query Google redirected the browser with, once the query is checked
// to answer this sign-in.
func (c *Consent) Code(q url.Values) (string, error) {
	switch {
	case q.Get("state") != c.state:
		return "", errors.New("state mismatch")
	case q.Get("error") != "":
		return "", fmt.Errorf("google refused: %s", q.Get("error"))
	}
	return q.Get("code"), nil
}

// Pasted is Code for an address pasted by hand. On a computer without a browser, the consent
// page is opened on another one, whose browser then cannot load the 127.0.0.1 page, and the
// user copies that page's address. Only its query counts: a browser may show the address with
// https:// or localhost.
func (c *Consent) Pasted(addr string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(addr))
	if err != nil || u.Query().Get("code") == "" && u.Query().Get("error") == "" {
		return "", errors.New("not the address Google sent the browser to")
	}
	return c.Code(u.Query())
}

// Exchange trades the code for a token, which must carry a refresh token.
func (c *Consent) Exchange(ctx context.Context, code string) (*oauth2.Token, error) {
	tok, err := c.cfg.Exchange(ctx, code, oauth2.VerifierOption(c.verifier))
	if err != nil {
		return nil, err
	}
	if tok.RefreshToken == "" {
		return nil, errors.New("google returned no refresh token; remove recly-events from https://myaccount.google.com/permissions and run init again")
	}
	return tok, nil
}

// Login signs in with a browser on this computer: a loopback listener on 127.0.0.1 receives
// the redirect. open is called with the consent URL.
func Login(ctx context.Context, cfg *oauth2.Config, open func(url string)) (*oauth2.Token, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer ln.Close()
	c, err := NewConsent(cfg, ln.Addr().(*net.TCPAddr).Port)
	if err != nil {
		return nil, err
	}

	type result struct {
		code string
		err  error
	}
	done := make(chan result, 1)
	var once sync.Once
	srv := &http.Server{ReadHeaderTimeout: 10 * time.Second, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/callback" {
			http.NotFound(w, r)
			return
		}
		var res result
		res.code, res.err = c.Code(r.URL.Query())
		if res.err != nil {
			http.Error(w, "recly-events: sign-in failed. Return to the terminal.", http.StatusBadRequest)
		} else {
			fmt.Fprintln(w, "recly-events: Google Drive is connected. You can close this tab.")
		}
		once.Do(func() { done <- res })
	})}
	go srv.Serve(ln)
	defer srv.Close()

	open(c.URL())
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case res := <-done:
		if res.err != nil {
			return nil, res.err
		}
		return c.Exchange(ctx, res.code)
	}
}

// SaveToken writes google-token.json with owner-only permissions.
func SaveToken(path string, tf TokenFile, write func(string, []byte) error) error {
	b, err := json.MarshalIndent(tf, "", "  ")
	if err != nil {
		return err
	}
	return write(path, b)
}

// LoadToken reads google-token.json.
func LoadToken(path string) (TokenFile, error) {
	var tf TokenFile
	b, err := os.ReadFile(path)
	if err != nil {
		return tf, fmt.Errorf("google token: %w (run `recly-events init --google`)", err)
	}
	if err := json.Unmarshal(b, &tf); err != nil {
		return tf, err
	}
	if tf.Token == nil || tf.Token.RefreshToken == "" {
		return tf, errors.New("google token has no refresh token; run init again")
	}
	return tf, nil
}

// savingSource persists each newly minted access token, so status can show when the refresh
// token last worked.
type savingSource struct {
	mu    sync.Mutex
	base  oauth2.TokenSource
	last  string
	tf    TokenFile
	path  string
	write func(string, []byte) error
}

func (s *savingSource) Token() (*oauth2.Token, error) {
	tok, err := s.base.Token()
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if tok.AccessToken != s.last {
		s.last = tok.AccessToken
		s.tf.Token = tok
		s.tf.RefreshedAt = time.Now()
		_ = SaveToken(s.path, s.tf, s.write)
	}
	return tok, nil
}

// HTTPClient returns an authorized client that refreshes and persists its token.
func HTTPClient(ctx context.Context, cfg *oauth2.Config, tf TokenFile, path string, write func(string, []byte) error) *http.Client {
	src := &savingSource{base: cfg.TokenSource(ctx, tf.Token), tf: tf, path: path, write: write}
	if tf.Token != nil {
		src.last = tf.Token.AccessToken
	}
	return oauth2.NewClient(ctx, oauth2.ReuseTokenSource(tf.Token, src))
}
