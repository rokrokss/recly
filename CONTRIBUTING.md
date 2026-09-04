# Contributing conventions

- **Language of the code base is English**: identifiers, code comments and doc comments, commit messages, log event names and log messages, test names, README/READMEs inside module directories. (Decision 2026-08-29.) The repository-front documents — the root `README.md` and module READMEs — are English too (decision 2026-09-01).
- **User-facing product text is localized** through each platform's resources (see `docs/recly.md` §7): English is the base language, Korean is a translation. Never hardcode UI text in code.
- **The design document `docs/recly.md`** is written in Korean for the product owner. It is the single normative description of the system; `spec/*.json` is the machine-readable contract.
- **Its section numbers are a contract.** Code comments cite rules as `docs/NN "subsection"`, which resolves to §NN of `docs/recly.md` (the numbers are kept from the previous `docs/NN-*.md` files). Do not renumber sections or rename a cited subsection heading without updating the citations.
- Log event names (`rec.*`, `shell.*`, `detect.*`, …) are stable identifiers — do not translate or rename them. The same holds for `CoreMessage` codes, which are written into `step_run.last_error` and rendered by the shells.
