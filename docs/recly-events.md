# Running recly-events yourself

[한국어](recly-events.ko.md)

`recly-events` is the small program that tells your ChatGPT agent about each new Recly transcript,
so the agent writes the minutes by itself ([how it works](agent.md)). The Recly Mac and Windows apps
carry their own copy and run it for you: if you use one of them, follow
[Automatic minutes with a ChatGPT agent](agent.md) instead. Run recly-events yourself on a server,
on Linux, or on a computer without the Recly app.

It runs on that computer, listens on no network port, and makes only outgoing connections, to
Google and to OpenAI. It reads the names, IDs and links of new transcripts in your Drive, never
what was said.

## What you need

- **Recly storing recordings in Google Drive.** Recordings in iCloud or a local folder are not seen.
- **A ChatGPT agent that can subscribe to events**: a dot, or a Work chat on ChatGPT web. At the
  time of writing, dots need ChatGPT Business Premium, or ChatGPT Pro outside the EEA, Switzerland
  and the UK.
- **ChatGPT's [Google Drive app](https://help.openai.com/en/articles/10929079-google-drive-app-and-setup-in-chatgpt)**,
  connected to the Google account Recly uploads to.
- **An OpenAI Platform account** at [platform.openai.com](https://platform.openai.com), for the
  tunnel and its key.
- **A computer that stays on.** While it sleeps nothing is lost: it catches up when it wakes.

## 1. Install it

Download the file for your computer from the newest
[`events-v…` release](https://github.com/rokrokss/recly/releases?q=events-v&expanded=true):

| Computer | File |
|---|---|
| Mac (Apple silicon or Intel) | `recly-events_<version>_darwin_universal.pkg`, an installer signed with Developer ID and notarized |
| Linux | `recly-events_<version>_linux_amd64.tar.gz` or `…_linux_arm64.tar.gz` |
| Windows | `recly-events_<version>_windows_amd64.zip`, not code-signed and not yet tried on a Windows PC |

To check a download, put `SHA256SUMS` from the same release next to it and run
`shasum -a 256 -c SHA256SUMS --ignore-missing`.

- **Mac:** open the `.pkg` and follow the installer; it asks for your password. It puts
  `recly-events` in `/usr/local/bin`, which is on your `PATH`, and its licences in
  `/usr/local/share/doc/recly-events`. From the command line instead:

  ```sh
  sudo installer -pkg recly-events_*_darwin_universal.pkg -target /
  ```

- **Linux:** unpack it and put the program where it will stay, because the service records where it
  is:

  ```sh
  tar -xzf recly-events_*_linux_*.tar.gz
  mkdir -p ~/.local/bin && cp recly-events_*/recly-events ~/.local/bin/
  ```

- **Windows:** unpack the `.zip` and keep `recly-events.exe` in a folder that stays.

recly-events is a command-line program: run it from Terminal (or a shell). Check it with:

```sh
recly-events version
```

## 2. Create an OpenAI tunnel and its key

Do [step 1 of the agent guide](agent.md#1-create-an-openai-tunnel-and-its-key): it gives you a
tunnel ID, `tunnel_…`, and a restricted OpenAI API key, the tunnel key.

## 3. Connect Google Drive and the tunnel

```sh
recly-events init --google --tunnel-id tunnel_…
```

1. Your browser opens Google's sign-in. Sign in with the account Recly uploads to and allow Recly to
   see the Drive files it created, the same permission the Recly apps ask for.
2. Paste the tunnel key when asked; the input is hidden.
3. `init` checks both: `Google Drive: connected`, and about 30 seconds later `Tunnel: ready tunnel_…`.

**On a server without a browser**, such as one you reach over SSH, `init` prints Google's sign-in
address instead. Open it in a browser on any computer and sign in. That browser then goes to an
`http://127.0.0.1:…` page that does not load: copy the page's whole address from the address bar and
paste it into `init`. Linux without a desktop does this by itself; elsewhere, add `--no-browser`.

To change one part later, run `init` again with only that flag: `--google` to sign in again,
`--tunnel-id` for another tunnel, `--tunnel-key-file FILE` for a new key.

## 4. Start it

```sh
recly-events service install
```

- **Mac:** a launch agent that starts at login and restarts the server if it stops.
- **Linux:** a systemd user service. On a server, also run `loginctl enable-linger` once (or
  `sudo loginctl enable-linger $USER` if that is refused), so it keeps running after you log out and
  starts at boot; `service install` says so when it is needed.
- **Windows:** `service install` is not supported. Create a Task Scheduler task that runs
  `recly-events serve` at logon.

Or run `recly-events serve` in a terminal and stop it with Ctrl-C. About 30 seconds after the start,
`recly-events status` shows `Tunnel: … ready`.

If the Recly Mac or Windows app also has its agent switch on, run only one of the two: the app leaves
a recly-events you started alone and says **Already running outside Recly**.

## 5. Add the app in ChatGPT and tell your agent

Keep the server running, then do [step 3](agent.md#3-add-the-app-in-chatgpt) and
[step 4](agent.md#4-tell-your-agent-once) of the agent guide: add the `Recly events` app in ChatGPT
through your tunnel, and send your dot or Work chat the subscription message once.
`recly-events status` shows the subscription once the agent has made it.

## 6. Test it

```sh
recly-events test
```

This announces the newest Recly transcript in your Drive again, and your agent should start a run
within a minute. If it already handled that recording, it may only say so; that still shows the
whole path works. The real test is your next recording.

## Everyday use

```sh
recly-events status          # server, tunnel, Google, last Drive poll, subscriptions, deliveries
recly-events test            # announce the newest transcript again
recly-events version
```

Its sign-in, tunnel key and state live in one directory only your user account can read:
`~/Library/Application Support/recly-events` on a Mac, `~/.config/recly-events` on Linux,
`%AppData%\recly-events` on Windows. The server log is `logs/serve.log` there on a Mac, and
`journalctl --user -u recly-events` on Linux.

## If something is off

Start with `recly-events status`, then the log.

| What you see | What to do |
|---|---|
| `serve`: "already running" | Another `recly-events serve` uses the same directory, for example the service. Stop one of them. |
| ChatGPT lists no tunnel | The tunnel is not linked to your ChatGPT workspace, is less than 30 seconds old, or belongs to another organization. Edit it in OpenAI Platform. |
| ChatGPT cannot create the app | The server is not running, or the tunnel is not ready yet: `status` must show `Tunnel: … ready`. |
| `drive.poll.failed` with `invalid_grant` in the log | Google ended the sign-in. Disconnecting Google Drive in any Recly app does this, because recly-events uses the same Recly sign-in. Run `recly-events init --google` again. |
| `status`: "the subscription ended" | Ask your agent to subscribe again. Events from the meantime wait for it. |
| No event for a recording | Was it stored in Google Drive, made after recly-events first started, and transcribed with speech in it? `status` shows when Drive was last checked. |

Do not remove Recly at [myaccount.google.com/permissions](https://myaccount.google.com/permissions)
to stop recly-events: that disconnects every Recly app on every device as well.

## Remove it

1. Stop it: `recly-events service uninstall`, or stop `serve`, or delete the Windows task.
2. Delete its directory (see [Everyday use](#everyday-use)); its Google sign-in and tunnel key go
   with it.
3. Delete the program. On a Mac, where the package installed it:

   ```sh
   sudo rm /usr/local/bin/recly-events
   sudo rm -r /usr/local/share/doc/recly-events
   sudo pkgutil --forget dev.recly.events
   ```

4. Delete the tunnel and its key in OpenAI Platform, and the app in ChatGPT.

The full reference, with `config.json`, building it yourself and using a Google client of your own,
is [events/README.md](https://github.com/rokrokss/recly/blob/main/events/README.md).
