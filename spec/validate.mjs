// 스키마 ↔ 예제 검증 + 워크플로우 스키마 부정 케이스. 실행: cd spec && npm install && npm run validate
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const load = (p) => JSON.parse(readFileSync(join(root, p), "utf8"));
const pairs = [
  ["workflow.schema.json", "examples/workflows.json"],
  ["recording.meta.schema.json", "examples/recording.meta.json"],
  ["webhook.payload.schema.json", "examples/webhook.payload.json"],
  ["transcript.schema.json", "examples/transcript.json"],
];
let failed = 0;
for (const [s, d] of pairs) {
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  addFormats(ajv);
  const ok = ajv.validate(load(s), load(d));
  console.log(`${ok ? "OK  " : "FAIL"} ${s} <- ${d}`);
  if (!ok) { failed++; console.log(JSON.stringify(ajv.errors, null, 1)); }
}

const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);
const wf = ajv.compile(load("workflow.schema.json"));
const base = load("examples/workflows.json");
const mut = (f) => { const c = structuredClone(base); f(c); return c; };
const cases = [
  ["http url rejected", false, mut((c) => (c.workflows[0].steps[1].url = "http://example.com/x"))],
  ["localhost http allowed", true, mut((c) => (c.workflows[0].steps[1].url = "http://localhost:5678/webhook/rec"))],
  ["127.0.0.1 http allowed", true, mut((c) => (c.workflows[0].steps[1].url = "http://127.0.0.1:5678/rec"))],
  ["unknown step type", false, mut((c) => (c.workflows[0].steps[0].type = "translate"))],
  ["unknown transcribe provider", false, mut((c) => (c.workflows[2].steps[1].provider = "whisper"))],
  ["invokeUrl on assemblyai is not the schema's business", true, mut((c) => (c.workflows[2].steps[1].invokeUrl = "https://x.y/z"))],
  ["speakers.max over 10", false, mut((c) => (c.workflows[2].steps[1].speakers.max = 11))],
  ["transcribe without secretRef", false, mut((c) => delete c.workflows[2].steps[1].secretRef)],
  ["bad step id", false, mut((c) => (c.workflows[0].steps[0].id = "Up-1"))],
  ["11 steps", false, mut((c) => { for (let i = 0; i < 10; i++) c.workflows[0].steps.push({ id: "s" + i, type: "webhook", url: "https://x.y/" }); })],
  ["bad ulid", false, mut((c) => (c.workflows[0].id = "not-a-ulid"))],
  ["negative minDurationSec", false, mut((c) => (c.workflows[0].minDurationSec = -1))],
  ["extra field", false, mut((c) => (c.workflows[0].foo = 1))],
  ["schema 1 rejected", false, mut((c) => (c.schema = 1))],
  // 이 스키마는 schema 3만 기술한다. 1..2 문서를 읽어 3으로 올리는 마이그레이션은 코어 파서의 일이고
  // (docs §5 동결), 여기서는 옛 필드를 그대로 둔 문서가 v3이 아니라는 사실만 못 박는다.
  ["schema 2 rejected", false, mut((c) => (c.schema = 2))],
  ["legacy trigger rejected", false, mut((c) => (c.workflows[0].trigger = { sources: ["phone"] }))],
  ["legacy enabled/isDefault rejected", false, mut((c) => { c.workflows[0].enabled = true; c.workflows[0].isDefault = true; })],
];
for (const [name, expect, doc] of cases) {
  const ok = wf(doc);
  console.log(`${ok === expect ? "OK  " : "FAIL"} ${name} -> valid=${ok}`);
  if (ok !== expect) failed++;
}
process.exit(failed ? 1 : 0);
