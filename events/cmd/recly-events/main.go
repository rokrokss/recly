// recly-events tells the user's ChatGPT agent (a dot or a Work chat) when Recly finishes a
// transcript. It watches Google Drive for new Recly transcripts by their metadata, publishes a
// `recording.transcribed` MCP event to ChatGPT through an OpenAI Secure MCP Tunnel, and reads a
// transcript when the agent asks for it. Nothing listens on the network; every connection it
// makes goes out.
//
// `recly-events mcp` is a separate, local MCP server on standard input and output for recordings
// in folders on this computer: it makes no connection at all.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
	"golang.org/x/oauth2"
	"golang.org/x/term"

	"github.com/rokrokss/recly/events/internal/app"
	"github.com/rokrokss/recly/events/internal/drive"
	"github.com/rokrokss/recly/events/internal/library"
	"github.com/rokrokss/recly/events/internal/mcpserver"
	"github.com/rokrokss/recly/events/internal/state"
	"github.com/rokrokss/recly/events/internal/webhook"
)

var version = "dev"

// Recly's desktop OAuth client, the Windows app's, set at build time (`make events` reads it from
// local.properties). Google treats a desktop client's secret as not secret, but it is
// per-developer, so it is never committed.
var googleClientID, googleClientSecret string

const usage = `recly-events — tell your ChatGPT agent when Recly finishes a transcript

Usage:
  recly-events init [--google | --google-client FILE] [--no-browser] [--tunnel-id ID]
                    [--tunnel-key-file FILE | --tunnel-key-stdin] [--no-check]
  recly-events serve
  recly-events status [--json]
  recly-events test
  recly-events service install|uninstall
  recly-events mcp --folder DIR [--folder DIR …] [--print-config]
  recly-events version

Files live in %s (RECLY_EVENTS_HOME overrides it).
`

func main() {
	home, err := app.DefaultHome()
	if err != nil {
		fatal(err)
	}
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, usage, home.Dir)
		os.Exit(2)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	switch os.Args[1] {
	case "init":
		err = cmdInit(ctx, home, os.Args[2:])
	case "serve":
		err = cmdServe(ctx, home, os.Args[2:])
	case "status":
		err = cmdStatus(home, os.Args[2:])
	case "test":
		err = cmdTest(home)
	case "service":
		err = cmdService(home, os.Args[2:])
	case "mcp":
		err = cmdMCP(ctx, os.Args[2:])
	case "version":
		fmt.Println(version)
	default:
		fmt.Fprintf(os.Stderr, usage, home.Dir)
		os.Exit(2)
	}
	if err != nil {
		fatal(err)
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "recly-events:", err)
	os.Exit(1)
}

func logger(w io.Writer) *slog.Logger {
	return slog.New(slog.NewTextHandler(w, &slog.HandlerOptions{Level: slog.LevelInfo}))
}

// ---------------------------------------------------------------- init

func cmdInit(ctx context.Context, home app.Home, args []string) error {
	fs := flag.NewFlagSet("init", flag.ContinueOnError)
	connectGoogle := fs.Bool("google", false, "connect Google Drive with Recly's sign-in (sees only the files Recly created)")
	googleClient := fs.String("google-client", "", "connect with a desktop OAuth client JSON from your own Google Cloud project instead")
	noBrowser := fs.Bool("no-browser", false, "sign in to Google in a browser on another computer and paste back the address it ends on (the default on Linux without a graphical session)")
	tunnelID := fs.String("tunnel-id", "", "OpenAI Secure MCP Tunnel ID (tunnel_…)")
	keyFile := fs.String("tunnel-key-file", "", "file holding the tunnel runtime API key (otherwise you are asked for it)")
	keyStdin := fs.Bool("tunnel-key-stdin", false, "read the tunnel runtime API key from standard input")
	noCheck := fs.Bool("no-check", false, "save the settings without checking Google Drive and the tunnel")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if err := home.Ensure(); err != nil {
		return err
	}
	cfg, err := app.LoadConfig(home)
	if err != nil {
		return err
	}
	log := logger(io.Discard)

	if *connectGoogle || *googleClient != "" {
		if *googleClient != "" {
			b, err := os.ReadFile(*googleClient)
			if err != nil {
				return err
			}
			if err := app.WriteSecret(home.GoogleClient(), b); err != nil {
				return err
			}
			cfg.GoogleClient = "file"
		} else {
			cfg.GoogleClient = "recly"
		}
		oc, err := oauthConfig(home, cfg)
		if err != nil {
			return err
		}
		var tok *oauth2.Token
		if *noBrowser || !hasBrowser() {
			tok, err = signInElsewhere(ctx, oc, cfg.GoogleClient == "file")
		} else {
			fmt.Println("Opening Google sign-in in your browser. Sign in with the account Recly uploads to.")
			if cfg.GoogleClient == "file" {
				fmt.Println("If Google says the app is not verified: Advanced → Go to (your app), then allow it to see your Drive files.")
			}
			fmt.Println("If no browser opens, stop with Ctrl-C and run init again with --no-browser.")
			tok, err = drive.Login(ctx, oc, openBrowser)
		}
		if err != nil {
			return err
		}
		now := time.Now()
		if err := drive.SaveToken(home.GoogleToken(), drive.TokenFile{Token: tok, ObtainedAt: now, RefreshedAt: now}, app.WriteSecret); err != nil {
			return err
		}
	}
	if *tunnelID != "" {
		if !strings.HasPrefix(*tunnelID, "tunnel_") {
			return errors.New("--tunnel-id must look like tunnel_…")
		}
		cfg.TunnelID = *tunnelID
	}
	switch {
	case *keyFile != "":
		b, err := os.ReadFile(*keyFile)
		if err != nil {
			return err
		}
		if err := app.WriteSecret(home.TunnelKey(), []byte(strings.TrimSpace(string(b)))); err != nil {
			return err
		}
	case *keyStdin:
		b, err := io.ReadAll(io.LimitReader(os.Stdin, 4096))
		if err != nil {
			return err
		}
		key := strings.TrimSpace(string(b))
		if key == "" {
			return errors.New("empty key on standard input")
		}
		if err := app.WriteSecret(home.TunnelKey(), []byte(key)); err != nil {
			return err
		}
	case *tunnelID != "":
		if _, err := app.ReadTunnelKey(home); err != nil {
			key, err := askSecret("OpenAI tunnel runtime key (restricted: Tunnels Read + Use; input hidden): ")
			if err != nil {
				return err
			}
			if err := app.WriteSecret(home.TunnelKey(), []byte(key)); err != nil {
				return err
			}
		}
	}
	if err := app.SaveConfig(home, cfg); err != nil {
		return err
	}
	if *noCheck {
		return nil
	}

	// Check what is configured now.
	ok := true
	if api, err := driveAPI(ctx, home, cfg); err != nil {
		fmt.Println("Google Drive:  not connected —", err)
		ok = false
	} else if _, err := api.AccountID(ctx); err != nil {
		fmt.Println("Google Drive:  error —", err)
		ok = false
	} else {
		// Google's consent screen has just shown which account it is; no email is read to say it again.
		fmt.Println("Google Drive:  connected")
	}
	key, keyErr := app.ReadTunnelKey(home)
	switch {
	case cfg.TunnelID == "":
		fmt.Println("Tunnel:        not configured — run init --tunnel-id tunnel_…")
		ok = false
	case keyErr != nil:
		fmt.Println("Tunnel:        no runtime key —", keyErr)
		ok = false
	default:
		cctx, cancel := context.WithTimeout(ctx, 45*time.Second)
		err := mcpserver.Check(cctx, cfg.TunnelID, key, log)
		cancel()
		if err != nil {
			fmt.Println("Tunnel:        error —", err)
			ok = false
		} else {
			fmt.Println("Tunnel:        ready", cfg.TunnelID)
		}
	}
	if ok {
		fmt.Println(`
Next:
  1. recly-events service install      (or: recly-events serve, to run it in this terminal)
  2. ChatGPT → Plugins → + → Create custom MCP server: Connection "Tunnel" (pick this tunnel),
     Authentication "No authentication". ChatGPT reaches the server while you create it.
  3. Ask your dot to subscribe to recording.transcribed, then: recly-events test`)
	}
	return nil
}

func askSecret(prompt string) (string, error) {
	if !term.IsTerminal(int(os.Stdin.Fd())) {
		return "", errors.New("no terminal to ask for the key; pass --tunnel-key-file")
	}
	fmt.Print(prompt)
	b, err := term.ReadPassword(int(os.Stdin.Fd()))
	fmt.Println()
	if err != nil {
		return "", err
	}
	key := strings.TrimSpace(string(b))
	if key == "" {
		return "", errors.New("empty key")
	}
	return key, nil
}

func openBrowser(url string) {
	fmt.Println(url)
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("open", url)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

// hasBrowser reports whether a browser can open on this computer: on macOS and Windows always,
// elsewhere only inside a graphical session.
func hasBrowser() bool {
	switch runtime.GOOS {
	case "darwin", "windows":
		return true
	}
	return os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != ""
}

// signInElsewhere signs in to Google from a computer without a browser, such as a server reached
// over SSH: the consent page is opened in a browser on another computer, and the address that
// browser is sent back to, which does not load there, is pasted here. Nothing listens for the
// redirect, and the PKCE verifier never leaves this computer.
func signInElsewhere(ctx context.Context, oc *oauth2.Config, ownClient bool) (*oauth2.Token, error) {
	// A random port, so that the redirect does not land on a server the other computer runs.
	c, err := drive.NewConsent(oc, 49152+rand.IntN(16384))
	if err != nil {
		return nil, err
	}
	fmt.Printf("Open this address in a browser on any computer and sign in with the account Recly uploads to:\n\n%s\n\n", c.URL())
	if ownClient {
		fmt.Println("If Google says the app is not verified: Advanced → Go to (your app), then allow it to see your Drive files.")
	}
	fmt.Println("The browser then goes to a 127.0.0.1 page that does not load. Copy that page's whole address from the address bar and paste it here.")
	for {
		fmt.Print("Address: ")
		line, err := readLine(ctx)
		if errors.Is(err, io.EOF) {
			return nil, errors.New("no address was pasted")
		}
		if err != nil {
			return nil, err
		}
		if strings.TrimSpace(line) == "" {
			continue
		}
		code, err := c.Pasted(line)
		if err != nil {
			fmt.Printf("That does not work (%v). Paste the whole address of the page that did not load, starting with http://127.0.0.1.\n", err)
			continue
		}
		return c.Exchange(ctx, code)
	}
}

// readLine reads one line from standard input, a byte at a time so that nothing after it is taken
// from the tunnel key prompt or --tunnel-key-stdin. It gives up when ctx ends, because
// signal.NotifyContext keeps Ctrl-C from ending a blocked read.
func readLine(ctx context.Context) (string, error) {
	type result struct {
		line string
		err  error
	}
	done := make(chan result, 1)
	go func() {
		var line []byte
		b := make([]byte, 1)
		for {
			n, err := os.Stdin.Read(b)
			if n == 1 && b[0] == '\n' {
				done <- result{string(line), nil}
				return
			}
			line = append(line, b[:n]...)
			if err != nil {
				if errors.Is(err, io.EOF) && len(line) > 0 {
					err = nil
				}
				done <- result{string(line), err}
				return
			}
		}
	}()
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	case r := <-done:
		return r.line, r.err
	}
}

// oauthConfig is Recly's compiled-in client, or the user's own from google-client.json.
func oauthConfig(home app.Home, cfg app.Config) (*oauth2.Config, error) {
	if cfg.GoogleClient == "file" {
		return drive.LoadClient(home.GoogleClient())
	}
	if googleClientID == "" || googleClientSecret == "" {
		return nil, errors.New("this build has no Recly Google client (build with `make events`, which reads local.properties), or pass --google-client with your own")
	}
	return drive.ReclyClient(googleClientID, googleClientSecret), nil
}

func driveAPI(ctx context.Context, home app.Home, cfg app.Config) (*drive.API, error) {
	oc, err := oauthConfig(home, cfg)
	if err != nil {
		return nil, err
	}
	tf, err := drive.LoadToken(home.GoogleToken())
	if err != nil {
		return nil, err
	}
	return &drive.API{Client: drive.HTTPClient(ctx, oc, tf, home.GoogleToken(), app.WriteSecret)}, nil
}

// ---------------------------------------------------------------- serve

type runtimeStatus struct {
	PID         int       `json:"pid"`
	TunnelReady bool      `json:"tunnelReady"`
	TunnelError string    `json:"tunnelError,omitempty"`
	StartedAt   time.Time `json:"startedAt"`
	Version     string    `json:"version"`
}

// liveStatus is the runtimeStatus shared between the tunnel loop and the admin socket.
type liveStatus struct {
	mu sync.Mutex
	rt runtimeStatus
}

func (l *liveStatus) tunnel(ready bool, errMsg string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.rt.TunnelReady, l.rt.TunnelError = ready, errMsg
}

func (l *liveStatus) snapshot() runtimeStatus {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.rt
}

func cmdServe(ctx context.Context, home app.Home, args []string) error {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	if err := fs.Parse(args); err != nil {
		return err
	}
	cfg, err := app.LoadConfig(home)
	if err != nil {
		return err
	}
	if cfg.TunnelID == "" {
		return errors.New("no tunnel configured; run `recly-events init --tunnel-id …`")
	}
	key, err := app.ReadTunnelKey(home)
	if err != nil {
		return err
	}
	api, err := driveAPI(ctx, home, cfg)
	if err != nil {
		return err
	}
	store, err := state.Open(home.State())
	if err != nil {
		return err
	}
	log := logger(os.Stderr)
	hub := &webhook.Hub{Store: store, Guard: &webhook.Guard{Hosts: cfg.AllowedCallbackHosts()}, Log: log}
	tools := &library.Tools{Source: &drive.Source{API: api}}
	handler := &mcpserver.Handler{Hub: hub, Tools: tools, Log: log, Version: version}
	watcher := &drive.Watcher{
		API: api, Store: store, Log: log, Every: time.Duration(cfg.PollInterval()) * time.Second,
		Emit: func(id string, rec drive.Recording) (bool, error) { return hub.Emit(id, rec) },
	}
	rt := &liveStatus{rt: runtimeStatus{PID: os.Getpid(), StartedAt: time.Now(), Version: version}}
	stopAdmin, err := serveAdmin(home, api, hub, rt, log)
	if err != nil {
		return err
	}
	defer stopAdmin()

	log.Info("serve.start", "version", version, "tunnel", cfg.TunnelID, "pollSeconds", cfg.PollInterval())
	go hub.Run(ctx)
	go watcher.Run(ctx)
	for ctx.Err() == nil {
		ready := make(chan struct{})
		go func() {
			select {
			case <-ready:
				rt.tunnel(true, "")
			case <-ctx.Done():
			}
		}()
		err := (&mcpserver.Tunnel{ID: cfg.TunnelID, Key: key, Log: log}).Run(ctx, handler, ready)
		if ctx.Err() != nil {
			break
		}
		rt.tunnel(false, fmt.Sprint(err))
		log.Warn("tunnel.stopped", "error", fmt.Sprint(err), "retryIn", "30s")
		select {
		case <-ctx.Done():
		case <-time.After(30 * time.Second):
		}
	}
	log.Info("serve.stop")
	return nil
}

// serveAdmin answers `status` and `test` on a Unix socket in the home directory, which only
// the owner can reach.
func serveAdmin(home app.Home, api *drive.API, hub *webhook.Hub, rt *liveStatus, log *slog.Logger) (func(), error) {
	// A second server on the same home would fight the first over the tunnel and state.json.
	if c, err := net.DialTimeout("unix", home.AdminSocket(), time.Second); err == nil {
		_ = c.Close()
		return nil, fmt.Errorf("already running for %s", home.Dir)
	}
	_ = os.Remove(home.AdminSocket())
	ln, err := net.Listen("unix", home.AdminSocket())
	if err != nil {
		return nil, err
	}
	_ = os.Chmod(home.AdminSocket(), 0o600)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /status", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, rt.snapshot())
	})
	mux.HandleFunc("POST /test", func(w http.ResponseWriter, r *http.Request) {
		f, err := drive.Latest(r.Context(), api)
		if err != nil {
			writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
			return
		}
		rec, err := drive.Describe(r.Context(), api, f)
		if err != nil {
			writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
			return
		}
		id := drive.EventID(f) + fmt.Sprintf("_test%d", time.Now().Unix())
		if _, err := hub.Emit(id, rec); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"eventId": id, "recording": rec.Recording})
	})
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Warn("admin.stopped", "error", err.Error())
		}
	}()
	return func() { _ = srv.Close(); _ = os.Remove(home.AdminSocket()) }, nil
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func adminClient(home app.Home) *http.Client {
	return &http.Client{Timeout: 60 * time.Second, Transport: &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "unix", home.AdminSocket())
		},
	}}
}

// ---------------------------------------------------------------- status / test / service

// statusReport is `status --json`, for a script or a monitor that watches the server (docs/recly.md
// §15 §9). It names no subscription or event.
type statusReport struct {
	Home           string         `json:"home"`
	TunnelID       string         `json:"tunnelId,omitempty"`
	TunnelKey      bool           `json:"tunnelKey"`
	GoogleSignedIn bool           `json:"googleSignedIn"`
	Server         *runtimeStatus `json:"server"`
	Drive          driveReport    `json:"drive"`
	// SubscriptionsEnded says there were subscriptions and none is left: ask the agent again.
	Subscriptions      int             `json:"subscriptions"`
	SubscriptionsEnded bool            `json:"subscriptionsEnded"`
	Pending            int             `json:"pending"`
	LastDelivery       *deliveryReport `json:"lastDelivery,omitempty"`
}

type driveReport struct {
	PollSeconds   int        `json:"pollSeconds"`
	LastPollAt    *time.Time `json:"lastPollAt,omitempty"`
	LastSuccessAt *time.Time `json:"lastSuccessAt,omitempty"`
	LastError     string     `json:"lastError,omitempty"`
	Announced     int        `json:"announced"`
}

type deliveryReport struct {
	At     time.Time `json:"at"`
	Status int       `json:"status,omitempty"`
	Error  string    `json:"error,omitempty"`
}

func cmdStatus(home app.Home, args []string) error {
	asJSON := len(args) == 1 && args[0] == "--json"
	if len(args) > 0 && !asJSON {
		return errors.New("usage: recly-events status [--json]")
	}
	cfg, err := app.LoadConfig(home)
	if err != nil {
		return err
	}
	st, err := state.Read(home.State())
	if err != nil {
		return err
	}
	var rt *runtimeStatus
	if resp, err := adminClient(home).Get("http://admin/status"); err == nil {
		var r runtimeStatus
		_ = json.NewDecoder(resp.Body).Decode(&r)
		resp.Body.Close()
		rt = &r
	}
	tf, tokenErr := drive.LoadToken(home.GoogleToken())
	ended := st.SubscriptionsEnded()
	pending := 0
	for _, e := range st.Inbox {
		if e.AckedAt == nil {
			pending++
		}
	}
	if asJSON {
		_, keyErr := app.ReadTunnelKey(home)
		r := statusReport{
			Home: home.Dir, TunnelID: cfg.TunnelID, TunnelKey: keyErr == nil, GoogleSignedIn: tokenErr == nil,
			Server: rt, Subscriptions: len(st.Subscriptions), SubscriptionsEnded: ended, Pending: pending,
			Drive: driveReport{PollSeconds: cfg.PollInterval(), LastError: st.Drive.LastError, Announced: len(st.Drive.Seen)},
		}
		if !st.Drive.LastPollAt.IsZero() {
			r.Drive.LastPollAt = &st.Drive.LastPollAt
		}
		if !st.Drive.LastSuccessAt.IsZero() {
			r.Drive.LastSuccessAt = &st.Drive.LastSuccessAt
		}
		if l := st.LastDelivery; l != nil {
			r.LastDelivery = &deliveryReport{At: l.At, Status: l.Status, Error: l.Error}
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(r)
	}

	fmt.Println("Home:          ", home.Dir)
	if rt == nil {
		fmt.Println("Server:         not running")
	} else {
		fmt.Printf("Server:         running since %s (version %s)\n", rt.StartedAt.Local().Format(time.DateTime), rt.Version)
		switch {
		case rt.TunnelReady:
			fmt.Println("Tunnel:        ", cfg.TunnelID, "ready")
		case rt.TunnelError != "":
			fmt.Println("Tunnel:        ", cfg.TunnelID, "error —", rt.TunnelError)
		default:
			fmt.Println("Tunnel:        ", cfg.TunnelID, "connecting")
		}
	}
	if tokenErr != nil {
		fmt.Println("Google:         not connected —", tokenErr)
	} else {
		fmt.Printf("Google:         signed in %s, token last refreshed %s\n", ago(tf.ObtainedAt), ago(tf.RefreshedAt))
	}
	d := st.Drive
	switch {
	case d.LastPollAt.IsZero():
		fmt.Println("Drive:          not polled yet")
	case d.LastError != "":
		fmt.Printf("Drive:          last poll %s failed — %s (last success %s)\n", ago(d.LastPollAt), d.LastError, ago(d.LastSuccessAt))
	default:
		fmt.Printf("Drive:          polled %s, every %ds; %d transcripts announced\n", ago(d.LastPollAt), cfg.PollInterval(), len(d.Seen))
	}
	if ended {
		fmt.Println("Subscriptions:  0 — the subscription ended; ask your agent to subscribe again")
	} else {
		fmt.Printf("Subscriptions:  %d\n", len(st.Subscriptions))
	}
	for _, s := range st.Subscriptions {
		fmt.Printf("  %s  since %s, refreshed %s\n", s.ID, s.CreatedAt.Local().Format(time.DateTime), ago(s.RefreshedAt))
	}
	fmt.Printf("Inbox:          %d events, %d not acknowledged\n", len(st.Inbox), pending)
	fmt.Printf("Delivery queue: %d\n", len(st.Outbox))
	if l := st.LastDelivery; l != nil {
		res := fmt.Sprintf("HTTP %d", l.Status)
		if l.Error != "" {
			res = l.Error
		}
		fmt.Printf("Last delivery:  %s %s → %s\n", ago(l.At), l.EventID, res)
	}
	return nil
}

func ago(t time.Time) string {
	if t.IsZero() {
		return "never"
	}
	d := time.Since(t).Round(time.Second)
	switch {
	case d < time.Minute:
		return fmt.Sprintf("%ds ago", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%dm ago", int(d.Minutes()))
	case d < 48*time.Hour:
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	default:
		return fmt.Sprintf("%dd ago", int(d.Hours()/24))
	}
}

func cmdTest(home app.Home) error {
	resp, err := adminClient(home).Post("http://admin/test", "application/json", nil)
	if err != nil {
		return errors.New("the server is not running; start it with `recly-events serve` or `recly-events service install`")
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("test failed: %v", out["error"])
	}
	fmt.Printf("Announced %v as %v. Your subscribed agent should write about it shortly.\n", out["recording"], out["eventId"])
	return nil
}

func cmdService(home app.Home, args []string) error {
	if len(args) != 1 {
		return errors.New("usage: recly-events service install|uninstall")
	}
	switch args[0] {
	case "install":
		exe, err := os.Executable()
		if err != nil {
			return err
		}
		where, err := app.InstallService(home, exe)
		if err != nil {
			return err
		}
		fmt.Println("Installed:", where)
		return nil
	case "uninstall":
		if err := app.UninstallService(); err != nil {
			return err
		}
		fmt.Println("Uninstalled.")
		return nil
	default:
		return errors.New("usage: recly-events service install|uninstall")
	}
}

// ---------------------------------------------------------------- mcp

type folderFlags []string

func (f *folderFlags) String() string     { return strings.Join(*f, ", ") }
func (f *folderFlags) Set(v string) error { *f = append(*f, v); return nil }

// cmdMCP is the local MCP server for recordings in folders on this computer — a local folder Recly
// stores recordings in, or the Mac's iCloud folder — on standard input and output, for agents on
// this computer such as Claude Desktop, Claude Code and Codex. It needs no sign-in, sends nothing
// anywhere, and never writes to the folders (docs/recly.md §15 §9).
func cmdMCP(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("mcp", flag.ContinueOnError)
	var folders folderFlags
	fs.Var(&folders, "folder", "a folder Recly stores recordings in: a local folder, or the Mac's iCloud folder; repeat for more")
	printConfig := fs.Bool("print-config", false, "print the mcpServers JSON that starts this server with these folders, and exit")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() > 0 || len(folders) == 0 {
		return errors.New("usage: recly-events mcp --folder DIR [--folder DIR …] [--print-config]")
	}
	var roots []string
	for _, f := range folders {
		abs, err := filepath.Abs(f)
		if err != nil {
			return err
		}
		if info, err := os.Stat(abs); err != nil || !info.IsDir() {
			return fmt.Errorf("--folder %s: not a folder", f)
		}
		roots = append(roots, abs)
	}
	if *printConfig {
		exe, err := os.Executable()
		if err != nil {
			return err
		}
		mcpArgs := []string{"mcp"}
		for _, r := range roots {
			mcpArgs = append(mcpArgs, "--folder", r)
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetEscapeHTML(false)
		enc.SetIndent("", "  ")
		type server struct {
			Command string   `json:"command"`
			Args    []string `json:"args"`
		}
		return enc.Encode(map[string]any{"mcpServers": map[string]server{"recly": {Command: exe, Args: mcpArgs}}})
	}
	folder := &library.Folder{Roots: roots}
	err := mcpserver.ServeLocal(ctx, &library.Tools{Source: folder, Folder: folder}, version, &mcp.StdioTransport{})
	if ctx.Err() != nil || errors.Is(err, io.EOF) {
		return nil
	}
	return err
}
