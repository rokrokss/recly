# Security Policy

## Supported versions

Only the latest [GitHub release](https://github.com/rokrokss/recly/releases) is supported. Fixes
land in the next release; older builds are not patched.

Recly has no server. Reports therefore concern the client apps (Galaxy Watch, Android phone,
Apple Watch, iPhone, macOS, Windows) and what runs on them: recording, storage and secret
handling, Google Drive and iCloud upload, local folders, and the transcription adapters. They also
concern recly-events, the optional program that tells a ChatGPT agent about new transcripts.

## Reporting a vulnerability

Report privately through GitHub:
<https://github.com/rokrokss/recly/security/advisories/new>. **Please do not open a public
issue.**

Include:

- the affected platform(s) and the Recly version (Settings → About, or the release tag),
- steps to reproduce, and what an attacker gains.

**Never attach recordings, transcripts, API keys, tunnel keys or OAuth tokens.** Describe the
problem instead; redact anything you must include.

## What to expect

You will get an acknowledgement within 7 days, and a fix is targeted for the next release. If
you would like credit, say so in the report and you will be named in the release notes.
