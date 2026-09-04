# Contributing conventions

- **Language of the code base is English**: identifiers, code comments and doc comments, commit messages, log event names and log messages, test names, README/READMEs inside module directories. (Decision 2026-08-29.) The repository-front documents — the root `README.md` and module READMEs — are English too (decision 2026-09-01).
- **User-facing product text is localized** through each platform's resources (see `docs/recly.md` §7): English is the base language, Korean is a translation. Never hardcode UI text in code.
- **The design document `docs/recly.md`** is written in Korean for the product owner. It is the single normative description of the system; `spec/*.json` is the machine-readable contract.
- **Its section numbers are a contract.** Code comments cite rules as `docs/NN "subsection"`, which resolves to §NN of `docs/recly.md` (the numbers are kept from the previous `docs/NN-*.md` files). Do not renumber sections or rename a cited subsection heading without updating the citations.
- Log event names (`rec.*`, `shell.*`, `detect.*`, …) are stable identifiers — do not translate or rename them. The same holds for `CoreMessage` codes, which are written into `step_run.last_error` and rendered by the shells.

## License of contributions

Recly is licensed under **AGPL-3.0-or-later** ([LICENSE](LICENSE)) together with the additional
permissions in [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md) — the section 7 app-store permission
and the section 7(e) trademark declination.

There is **no CLA**. By submitting a contribution you agree that it is licensed under those same
terms — AGPL-3.0-or-later *with* those additional permissions (inbound = outbound) — and you
certify that you have the right to license it that way.

## Where things go

- **Bugs** → [GitHub Issues](https://github.com/rokrokss/recly/issues/new/choose), using the bug form.
- **Questions and ideas** → [GitHub Discussions](https://github.com/rokrokss/recly/discussions).
- **Security** → [SECURITY.md](SECURITY.md). Do not open a public issue for a vulnerability.
- **Pull request titles** are English and in the imperative: "Fix the watch handoff retry budget",
  not "Fixed…" or "Fixes…".

Build and test instructions are in [docs/development.md](docs/development.md); the same commands,
condensed for agents, are in [AGENTS.md](AGENTS.md).
