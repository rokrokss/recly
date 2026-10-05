// Package app holds the files recly-events keeps in its home directory and the settings
// read from them.
package app

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// DefaultCallbackHost is the only host ChatGPT has been observed to send event callbacks to.
// The MCP Events guide names no host, so a different one is refused until it is added to
// callbackHosts in config.json.
const DefaultCallbackHost = "connectors.api.openai.com"

// Home is the directory with config, secrets, state and logs. RECLY_EVENTS_HOME overrides it.
type Home struct{ Dir string }

// DefaultHome returns the per-user home: ~/Library/Application Support/recly-events on macOS,
// $XDG_CONFIG_HOME/recly-events on Linux, %AppData%\recly-events on Windows.
func DefaultHome() (Home, error) {
	if dir := os.Getenv("RECLY_EVENTS_HOME"); dir != "" {
		return Home{Dir: dir}, nil
	}
	base, err := os.UserConfigDir()
	if err != nil {
		return Home{}, err
	}
	return Home{Dir: filepath.Join(base, "recly-events")}, nil
}

func (h Home) Config() string       { return filepath.Join(h.Dir, "config.json") }
func (h Home) State() string        { return filepath.Join(h.Dir, "state.json") }
func (h Home) GoogleClient() string { return filepath.Join(h.Dir, "google-client.json") }
func (h Home) GoogleToken() string  { return filepath.Join(h.Dir, "google-token.json") }
func (h Home) TunnelKey() string    { return filepath.Join(h.Dir, "tunnel-key") }
func (h Home) AdminSocket() string  { return filepath.Join(h.Dir, "admin.sock") }
func (h Home) Logs() string         { return filepath.Join(h.Dir, "logs") }

// Ensure creates the home directory, readable only by its owner.
func (h Home) Ensure() error {
	if err := os.MkdirAll(h.Dir, 0o700); err != nil {
		return err
	}
	return os.Chmod(h.Dir, 0o700)
}

// Config is config.json.
type Config struct {
	TunnelID string `json:"tunnelId,omitempty"`
	// GoogleClient is "recly" (Recly's own desktop client, compiled in) or "file" (a client from
	// the user's own Google Cloud project, google-client.json).
	GoogleClient  string   `json:"googleClient,omitempty"`
	PollSeconds   int      `json:"pollSeconds,omitempty"`
	CallbackHosts []string `json:"callbackHosts,omitempty"`
}

// PollInterval is how often Drive is asked for changes, in seconds (default 10).
func (c Config) PollInterval() int {
	if c.PollSeconds <= 0 {
		return 10
	}
	return c.PollSeconds
}

// AllowedCallbackHosts is the exact-host allowlist for event callbacks.
func (c Config) AllowedCallbackHosts() []string {
	if len(c.CallbackHosts) == 0 {
		return []string{DefaultCallbackHost}
	}
	return c.CallbackHosts
}

// LoadConfig reads config.json; a missing file is an empty config.
func LoadConfig(h Home) (Config, error) {
	var c Config
	b, err := os.ReadFile(h.Config())
	if errors.Is(err, fs.ErrNotExist) {
		return c, nil
	}
	if err != nil {
		return c, err
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return c, fmt.Errorf("read %s: %w", h.Config(), err)
	}
	return c, nil
}

// SaveConfig writes config.json with owner-only permissions.
func SaveConfig(h Home, c Config) error {
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return WriteSecret(h.Config(), b)
}

// WriteSecret writes a file readable only by its owner, replacing it atomically.
func WriteSecret(path string, b []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// ReadTunnelKey returns the runtime API key for the tunnel.
func ReadTunnelKey(h Home) (string, error) {
	b, err := os.ReadFile(h.TunnelKey())
	if err != nil {
		return "", fmt.Errorf("tunnel key: %w (run `recly-events init --tunnel-id …`)", err)
	}
	key := strings.TrimSpace(string(b))
	if key == "" {
		return "", errors.New("tunnel key file is empty")
	}
	return key, nil
}
