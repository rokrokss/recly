package drive

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"

	"golang.org/x/oauth2"
)

// tokenEndpoint stands in for Google's token endpoint and keeps the form of the last exchange.
type tokenEndpoint struct {
	*httptest.Server
	mu   sync.Mutex
	form url.Values
}

func newTokenEndpoint(t *testing.T) *tokenEndpoint {
	e := &tokenEndpoint{}
	e.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		e.mu.Lock()
		e.form = r.PostForm
		e.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "at", "refresh_token": "rt", "token_type": "Bearer", "expires_in": 3600})
	}))
	t.Cleanup(e.Close)
	return e
}

func (e *tokenEndpoint) config() *oauth2.Config {
	return &oauth2.Config{ClientID: "id", ClientSecret: "secret", Endpoint: oauth2.Endpoint{AuthURL: "https://accounts.example/auth", TokenURL: e.URL}}
}

// checkExchange checks that the exchange sent code with the consent page's redirect and the
// verifier behind its PKCE challenge.
func (e *tokenEndpoint) checkExchange(t *testing.T, consentURL, code string) {
	t.Helper()
	u, err := url.Parse(consentURL)
	if err != nil {
		t.Fatal(err)
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.form.Get("code") != code {
		t.Errorf("exchanged code %q, want %q", e.form.Get("code"), code)
	}
	if got, want := e.form.Get("redirect_uri"), u.Query().Get("redirect_uri"); got != want {
		t.Errorf("exchanged with redirect_uri %q, the consent page had %q", got, want)
	}
	if got := oauth2.S256ChallengeFromVerifier(e.form.Get("code_verifier")); got != u.Query().Get("code_challenge") {
		t.Errorf("the code_verifier does not match the consent page's code_challenge")
	}
}

func TestLoginTakesTheCodeFromTheRedirect(t *testing.T) {
	e := newTokenEndpoint(t)
	var consentURL string
	open := func(s string) {
		consentURL = s
		u, _ := url.Parse(s)
		redirect := u.Query().Get("redirect_uri") + "?" + url.Values{"state": {u.Query().Get("state")}, "code": {"the-code"}}.Encode()
		go func() {
			if resp, err := http.Get(redirect); err == nil {
				resp.Body.Close()
			}
		}()
	}
	tok, err := Login(context.Background(), e.config(), open)
	if err != nil {
		t.Fatal(err)
	}
	if tok.RefreshToken != "rt" {
		t.Fatalf("refresh token %q", tok.RefreshToken)
	}
	e.checkExchange(t, consentURL, "the-code")
}

func TestAPastedAddressSignsIn(t *testing.T) {
	e := newTokenEndpoint(t)
	c, err := NewConsent(e.config(), 51234)
	if err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(c.URL())
	if got := u.Query().Get("redirect_uri"); got != "http://127.0.0.1:51234/callback" {
		t.Fatalf("redirect_uri %q", got)
	}
	// What a browser shows after failing to load the page, here upgraded to https, with the
	// trailing newline of a paste.
	pasted := "https://127.0.0.1:51234/callback?state=" + url.QueryEscape(u.Query().Get("state")) + "&code=the-code&scope=x\n"
	code, err := c.Pasted(pasted)
	if err != nil {
		t.Fatal(err)
	}
	tok, err := c.Exchange(context.Background(), code)
	if err != nil {
		t.Fatal(err)
	}
	if tok.RefreshToken != "rt" {
		t.Fatalf("refresh token %q", tok.RefreshToken)
	}
	e.checkExchange(t, c.URL(), "the-code")
}

func TestPastedRefusesWhatIsNotThisSignInsRedirect(t *testing.T) {
	c, err := NewConsent(&oauth2.Config{ClientID: "id", Endpoint: oauth2.Endpoint{AuthURL: "https://accounts.example/auth"}}, 51234)
	if err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(c.URL())
	state := url.QueryEscape(u.Query().Get("state"))
	for _, tc := range []struct{ name, pasted, want string }{
		{"text", "hello", "not the address"},
		{"the consent page itself", c.URL(), "not the address"},
		{"another sign-in", "http://127.0.0.1:51234/callback?state=other&code=c", "state mismatch"},
		{"consent refused", "http://127.0.0.1:51234/callback?state=" + state + "&error=access_denied", "access_denied"},
	} {
		if _, err := c.Pasted(tc.pasted); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: error %v, want one containing %q", tc.name, err, tc.want)
		}
	}
}
