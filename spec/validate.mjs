// Schemas ↔ examples, plus negative cases for the settings and transcript schemas. Run: cd spec && npm install && npm run validate
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const load = (p) => JSON.parse(readFileSync(join(root, p), "utf8"));
const pairs = [
  ["recording.meta.schema.json", "examples/recording.meta.json"],
  ["transcript.schema.json", "examples/transcript.json"],
  ["transcript.schema.json", "examples/transcript-local.json"],
  ["recording-settings.schema.json", "examples/recording-settings.json"],
  ["recording-settings.schema.json", "examples/recording-settings-external.json"],
];
let failed = 0;
for (const [s, d] of pairs) {
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  addFormats(ajv);
  const ok = ajv.validate(load(s), load(d));
  console.log(`${ok ? "OK  " : "FAIL"} ${s} <- ${d}`);
  if (!ok) { failed++; console.log(JSON.stringify(ajv.errors, null, 1)); }
}

const settingsAjv = new Ajv2020({ strict: true, allErrors: true });
addFormats(settingsAjv);
const settingsSchema = settingsAjv.compile(load("recording-settings.schema.json"));
const settingsBase = load("examples/recording-settings.json");
const settingsMut = (f) => { const c = structuredClone(settingsBase); f(c); return c; };
const settingsCases = [
  ["off mode", true, settingsMut(c => c.settings.transcription.mode = "off")],
  ["external requires configuration", false, settingsMut(c => c.settings.transcription.mode = "external")],
  ["unknown settings field", false, settingsMut(c => c.settings.telemetry = true)],
  ["unknown nested field", false, settingsMut(c => c.settings.transcription.speakers = { min: 1, max: 10, extra: true })],
  ["legacy speaker choices remain readable", true, settingsMut(c => { c.settings.transcription.diarize = false; c.settings.transcription.speakers = { min: 2, max: 4 }; })],
  ["webhook field rejected", false, settingsMut(c => c.settings.webhook = { url: "https://example.com/old-hook" })],
  ["raw API key excluded", false, settingsMut(c => c.settings.transcription.external = { provider: "assemblyai", secretRef: "key", apiKey: "not-a-key" })],
  ["unknown provider", false, settingsMut(c => c.settings.transcription.external = { provider: "whisper", secretRef: "key" })],
  ["bad secretRef", false, settingsMut(c => c.settings.transcription.external = { provider: "assemblyai", secretRef: "Bad-Key" })],
  ["http invokeUrl rejected", false, settingsMut(c => c.settings.transcription.external = { provider: "clova", secretRef: "key", invokeUrl: "http://example.com/x" })],
  ["speakers.max over 10", false, settingsMut(c => c.settings.transcription.speakers = { min: 1, max: 11 })],
  ["future settings version", false, settingsMut(c => c.schema = 2)],
  ["explicit null", false, settingsMut(c => c.settings.storage = null)],
  ["workflow folder dependency", false, settingsMut(c => c.settings.storage.folder = "recly/{{ workflowName }}")],
  ["title fallback dependency", false, settingsMut(c => c.settings.storage.folder = "recly/{{ title }}")],
  ["empty settings uses defaults", true, settingsMut(c => c.settings = {})],
];
for (const language of ["ko", "en", "ko-en", "auto", "ja", "zh-cn", "zh-tw", "es", "fr", "de", "pt", "ar", "hi", "ru", "it", "id", "tr", "vi", "th", "nl", "pl", "uk"]) {
  settingsCases.push([`language ${language}`, true, settingsMut(c => c.settings.transcription.language = language)]);
}
settingsCases.push(["unknown language rejected", false, settingsMut(c => c.settings.transcription.language = "xx")]);
for (const [name, expect, document] of settingsCases) {
  const ok = settingsSchema(document);
  console.log(`${ok === expect ? "OK  " : "FAIL"} settings: ${name} -> valid=${ok}`);
  if (ok !== expect) failed++;
}
const transcriptAjv = new Ajv2020({ allErrors: true, strict: true });
addFormats(transcriptAjv);
const transcriptSchema = transcriptAjv.compile(load("transcript.schema.json"));
const localTranscript = load("examples/transcript-local.json");
for (const [label, expected, change] of [
  ["v2 unknown speaker", true, c => c],
  ["v2 missing capability", false, c => { delete c.speakerIdentification; return c; }],
  ["v2 fabricated speaker", false, c => { c.segments[0].speaker = "S1"; return c; }],
  ["v1 requires speaker", false, c => { c.schema = 1; delete c.speakerIdentification; delete c.timing; return c; }],
]) {
  const valid = transcriptSchema(change(structuredClone(localTranscript)));
  if (valid !== expected) { console.error(`FAIL transcript: ${label}`); failed++; }
  else console.log(`OK   transcript: ${label} -> valid=${valid}`);
}
process.exit(failed ? 1 : 0);
