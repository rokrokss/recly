package app

import (
	"errors"
	"fmt"
	"html"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

// Label is the launchd label and systemd unit name.
const Label = "dev.recly.events"

// InstallService registers `recly-events serve` to start at login and restart if it stops:
// a launchd agent on macOS, a systemd user unit on Linux.
func InstallService(h Home, exe string) (string, error) {
	if err := os.MkdirAll(h.Logs(), 0o700); err != nil {
		return "", err
	}
	switch runtime.GOOS {
	case "darwin":
		path, err := launchdPlist()
		if err != nil {
			return "", err
		}
		log := filepath.Join(h.Logs(), "serve.log")
		env := ""
		if v := os.Getenv("RECLY_EVENTS_HOME"); v != "" {
			env = fmt.Sprintf("  <key>EnvironmentVariables</key>\n  <dict><key>RECLY_EVENTS_HOME</key><string>%s</string></dict>\n", html.EscapeString(v))
		}
		plist := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>%s</string>
  <key>ProgramArguments</key>
  <array><string>%s</string><string>serve</string></array>
%s  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>%s</string>
  <key>StandardErrorPath</key><string>%s</string>
</dict>
</plist>
`, Label, html.EscapeString(exe), env, html.EscapeString(log), html.EscapeString(log))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			return "", err
		}
		if err := os.WriteFile(path, []byte(plist), 0o644); err != nil {
			return "", err
		}
		domain := "gui/" + strconv.Itoa(os.Getuid())
		_ = exec.Command("launchctl", "bootout", domain+"/"+Label).Run()
		if out, err := exec.Command("launchctl", "bootstrap", domain, path).CombinedOutput(); err != nil {
			return "", fmt.Errorf("launchctl bootstrap: %v: %s", err, strings.TrimSpace(string(out)))
		}
		return fmt.Sprintf("launchd agent %s (log: %s)", path, log), nil
	case "linux":
		path, err := systemdUnit()
		if err != nil {
			return "", err
		}
		env := ""
		if v := os.Getenv("RECLY_EVENTS_HOME"); v != "" {
			env = "Environment=RECLY_EVENTS_HOME=" + strconv.Quote(v) + "\n"
		}
		unit := fmt.Sprintf(`[Unit]
Description=recly-events (Recly recording events for ChatGPT)
After=network-online.target

[Service]
ExecStart=%s serve
%sRestart=always
RestartSec=30

[Install]
WantedBy=default.target
`, strconv.Quote(exe), env)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			return "", err
		}
		if err := os.WriteFile(path, []byte(unit), 0o644); err != nil {
			return "", err
		}
		for _, args := range [][]string{{"daemon-reload"}, {"enable", "--now", "recly-events.service"}} {
			if out, err := exec.Command("systemctl", append([]string{"--user"}, args...)...).CombinedOutput(); err != nil {
				return "", fmt.Errorf("systemctl --user %s: %v: %s", strings.Join(args, " "), err, strings.TrimSpace(string(out)))
			}
		}
		where := "systemd user unit " + path + " (log: journalctl --user -u recly-events)"
		// Without lingering, systemd runs a user's units only while the user is logged in: on a
		// server reached over SSH, the service would stop at logout and not start after a reboot.
		if out, err := exec.Command("loginctl", "show-user", strconv.Itoa(os.Getuid()), "--property=Linger", "--value").Output(); err == nil && strings.TrimSpace(string(out)) == "no" {
			where += "\nIt runs only while you are logged in. To keep it running after you log out, and start it at boot: loginctl enable-linger (or, where that is refused, sudo loginctl enable-linger $USER)"
		}
		return where, nil
	default:
		return "", errors.New("service install supports macOS and Linux; on Windows, start `recly-events serve` from Task Scheduler")
	}
}

// UninstallService stops and removes what InstallService registered.
func UninstallService() error {
	switch runtime.GOOS {
	case "darwin":
		path, err := launchdPlist()
		if err != nil {
			return err
		}
		_ = exec.Command("launchctl", "bootout", "gui/"+strconv.Itoa(os.Getuid())+"/"+Label).Run()
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	case "linux":
		path, err := systemdUnit()
		if err != nil {
			return err
		}
		_ = exec.Command("systemctl", "--user", "disable", "--now", "recly-events.service").Run()
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return exec.Command("systemctl", "--user", "daemon-reload").Run()
	default:
		return errors.New("service uninstall supports macOS and Linux")
	}
}

func launchdPlist() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", Label+".plist"), nil
}

func systemdUnit() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "systemd", "user", "recly-events.service"), nil
}
