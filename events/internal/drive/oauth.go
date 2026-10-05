// Package drive finds new Recly transcripts in the user's Google Drive. It reads metadata
// only — names, folders, IDs and links, never file contents. The agent reads the transcript
// itself through its own Google Drive connector.
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
	"os"
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
	// ScopeMetadata goes with a client from the user's own Google Cloud project, which cannot
	// see Recly's files under drive.file.
	ScopeMetadata = "https://www.googleapis.com/auth/drive.metadata.readonly"
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
	cfg, err := google.ConfigFromJSON(b, ScopeMetadata)
	if err != nil {
		return nil, fmt.Errorf("%s is not a Google OAuth client file: %w", path, err)
	}
	return cfg, nil
}

// Login runs the installed-app flow: a loopback listener on 127.0.0.1, PKCE, and a browser
// for the user's consent. open is called with the consent URL.
func Login(ctx context.Context, cfg *oauth2.Config, open func(url string)) (*oauth2.Token, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer ln.Close()
	c := *cfg
	c.RedirectURL = fmt.Sprintf("http://127.0.0.1:%d/callback", ln.Addr().(*net.TCPAddr).Port)

	stateBytes := make([]byte, 24)
	if _, err := rand.Read(stateBytes); err != nil {
		return nil, err
	}
	st := base64.RawURLEncoding.EncodeToString(stateBytes)
	verifier := oauth2.GenerateVerifier()

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
		q := r.URL.Query()
		var res result
		switch {
		case q.Get("state") != st:
			res.err = errors.New("state mismatch")
		case q.Get("error") != "":
			res.err = fmt.Errorf("google refused: %s", q.Get("error"))
		default:
			res.code = q.Get("code")
		}
		if res.err != nil {
			http.Error(w, "recly-events: sign-in failed. Return to the terminal.", http.StatusBadRequest)
		} else {
			fmt.Fprintln(w, "recly-events: Google Drive is connected. You can close this tab.")
		}
		once.Do(func() { done <- res })
	})}
	go srv.Serve(ln)
	defer srv.Close()

	open(c.AuthCodeURL(st, oauth2.AccessTypeOffline, oauth2.ApprovalForce, oauth2.S256ChallengeOption(verifier)))
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case res := <-done:
		if res.err != nil {
			return nil, res.err
		}
		tok, err := c.Exchange(ctx, res.code, oauth2.VerifierOption(verifier))
		if err != nil {
			return nil, err
		}
		if tok.RefreshToken == "" {
			return nil, errors.New("google returned no refresh token; remove recly-events from https://myaccount.google.com/permissions and run init again")
		}
		return tok, nil
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
		return tf, fmt.Errorf("google token: %w (run `recly-events init --google-client …`)", err)
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
