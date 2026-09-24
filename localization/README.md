# App localization

Recly ships the languages in `languages.json` on phones, watches and desktops. English is the source
language; English and Korean keep their existing native resource files. The other ten languages
are maintained in `translations/<tag>.json` and generated into each platform's normal resources.
App language and transcription language are separate settings. Transcription capabilities are
owned by the shared core and the runtime speech engine, not by this directory.

Run from the repository root:

```sh
python3 scripts/localize.py --inventory  # List normalized English source messages.
python3 scripts/localize.py              # Generate native resources after editing translations.
python3 scripts/localize.py --check      # Check coverage, placeholders and generated files.
```

Add an English and Korean resource first, then add its normalized English text to every translation
file. Formatting arguments use `{0}`, `{1}`, etc. in these dictionaries; the generator restores the
source's exact native specifiers (`%@`, `%1$s`, `%1$d`). Preserve `${applicationName}` in App Shortcuts.
Use actual JSON newline escapes for paragraphs. Android quoting and Java properties escaping are
handled by the generator.

`unchanged.json` explicitly lists brands, technical labels, language autonyms and format-only
messages. It is not a fallback for missing translations. Numeric plural messages use count-neutral
wording (for example, “Waiting recordings: {0}”) so Android's `other` quantity works for all counts.
Dates use native formatting patterns in the translation dictionaries.

Commit the dictionaries and generated resources together. Normal platform tests verify key coverage
and formatting across all shipped languages. Apple also tests resource bundle switching, and the
iPhone language UI test covers Japanese, Traditional Chinese, Arabic RTL and persistence.
