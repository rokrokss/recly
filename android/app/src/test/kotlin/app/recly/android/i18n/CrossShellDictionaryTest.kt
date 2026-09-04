package app.recly.android.i18n

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element

/**
 * The cross-shell dictionary (리드 정본, 2026-08-31): the lines a user meets on more than one of the
 * four shells are the *same* line there, in both languages. `ConsentTextTest` holds the consent
 * reminder that way; this holds everything else the parity audit found drifting — a status word, a
 * field label, the sentence under a generated secret.
 *
 * Every shell's own resource file is read rather than a copy of it, so a rewording on any one of
 * them fails here, which is the only place a user could otherwise be the one to notice.
 *
 * The format arguments are not compared as written: Android and Windows spell them `%1$s`, a String
 * Catalog spells the same argument `%@`, and which of the two a file uses is not a wording decision
 * (see [normalized]).
 */
class CrossShellDictionaryTest {

    @Test
    fun `every shell that says one of these says it in the same words, in both languages`() {
        val failures = mutableListOf<String>()
        DICTIONARY.forEach { line ->
            line.readings().forEach { (shell, reading) ->
                if (reading == null) {
                    failures += "${line.what}: $shell has no such key"
                    return@forEach
                }
                // A shell the dictionary is still waiting on says nothing here yet: the line names
                // its key so the wording locks the moment it catches up (see [Line.pending]).
                if (shell in line.pending) return@forEach
                if (normalized(reading.en) != normalized(line.en)) {
                    failures += "${line.what}: $shell says en \"${reading.en}\", not \"${line.en}\""
                }
                if (normalized(reading.ko) != normalized(line.ko)) {
                    failures += "${line.what}: $shell says ko \"${reading.ko}\", not \"${line.ko}\""
                }
            }
        }
        assertEquals(emptyList(), failures, "the shells no longer say the same thing")
    }

    /** Two shells at least, or the entry is not holding anything together. */
    @Test
    fun `every line of the dictionary is one more than one shell says`() {
        DICTIONARY.forEach { line ->
            assertEquals(
                true,
                line.readings().count { it.value != null } >= 2,
                "${line.what} is only on one shell — it belongs in that shell's own test",
            )
        }
    }

    // --- the shells' own files -----------------------------------------------------------------

    private data class Reading(val en: String, val ko: String)

    /**
     * One line of the dictionary: what it is, what it says, and the key each shell holds it under.
     * A null key is a shell that does not have this line at all — the desktop's consent row, say,
     * which the phones word differently because a phone cannot detect a meeting (docs/12 M8).
     */
    private data class Line(
        val what: String,
        val en: String,
        val ko: String,
        val android: String? = null,
        val windows: String? = null,
        val reckit: String? = null,
        val mac: String? = null,
        val phone: String? = null,
        /**
         * Shells that carry this line under the key named above but have not been reworded yet —
         * the parity audit's own findings, which land shell by shell. Their reading is not compared
         * until then; taking a name out of here is what locks that shell, and a name left behind
         * once the shell is fixed holds nothing together.
         */
        val pending: Set<String> = emptySet(),
    ) {
        /** What each shell that carries this line actually says, keyed by the shell's name. */
        fun readings(): Map<String, Reading?> = buildMap {
            android?.let { put("android", androidStrings[it]) }
            windows?.let { put("windows", windowsStrings[it]) }
            reckit?.let { put("RecKit", RECKIT[it]) }
            mac?.let { put("RecMac", RECMAC[it]) }
            phone?.let { put("RecPhone", RECPHONE[it]) }
        }
    }

    private companion object {
        /** Unit tests run with `android/app` as the working directory. */
        val REPO_ROOT: File = File("../..").canonicalFile

        /**
         * A String Catalog's `%@` and a resource file's `%1$s` are the same argument written the way
         * each platform writes it, and neither is a word. Positional `%1$d` goes the same way.
         */
        fun normalized(value: String): String =
            value.replace(Regex("%(\\d+\\$)?[@sd]"), "%")

        // --- Android -----------------------------------------------------------------------------

        val androidStrings: Map<String, Reading> = run {
            val en = androidLocale("values")
            val ko = androidLocale("values-ko")
            en.keys.intersect(ko.keys).associateWith { Reading(en.getValue(it), ko.getValue(it)) }
        }

        fun androidLocale(locale: String): Map<String, String> {
            val file = File(REPO_ROOT, "android/app/src/main/res/$locale/strings.xml")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as Element
                // `aapt` undoes Android's own escaping at build time; this has to undo it itself
                // before the text can be compared with anybody else's (as `ConsentTextTest` does).
                element.getAttribute("name") to
                    element.textContent.replace("\\n", "\n").replace("\\'", "'")
            }
        }

        // --- Windows -----------------------------------------------------------------------------

        val windowsStrings: Map<String, Reading> = run {
            val en = windowsLocale("en")
            val ko = windowsLocale("ko")
            en.stringPropertyNames().intersect(ko.stringPropertyNames()).associateWith {
                Reading(en.getProperty(it), ko.getProperty(it))
            }
        }

        fun windowsLocale(language: String): Properties = Properties().apply {
            File(REPO_ROOT, "windows/app/src/main/resources/i18n/strings_$language.properties")
                .inputStream()
                .use { load(it.reader(Charsets.UTF_8)) }
            // `Properties` reads `\n` as a real newline already; nothing else to undo.
        }

        // --- Apple -------------------------------------------------------------------------------

        val RECKIT = catalog("apple/RecKit/Sources/RecKit/Resources/Localizable.xcstrings")
        val RECMAC = catalog("apple/RecMac/RecMac/Localizable.xcstrings")
        val RECPHONE = catalog("apple/RecPhone/RecPhone/Localizable.xcstrings")

        /** A `.xcstrings` file is JSON: key → localizations → language → stringUnit → value. */
        fun catalog(path: String): Map<String, Reading> {
            val root = Json.parseToJsonElement(File(REPO_ROOT, path).readText()).jsonObject
            return root.getValue("strings").jsonObject.mapNotNull { (key, entry) ->
                val localizations = entry.jsonObject["localizations"]?.jsonObject ?: return@mapNotNull null
                val en = localizations.value("en") ?: return@mapNotNull null
                val ko = localizations.value("ko") ?: return@mapNotNull null
                key to Reading(en, ko)
            }.toMap()
        }

        fun kotlinx.serialization.json.JsonObject.value(language: String): String? =
            this[language]?.jsonObject?.get("stringUnit")?.jsonObject?.get("value")?.jsonPrimitive?.content

        // --- the dictionary ------------------------------------------------------------------------

        val DICTIONARY = listOf(
            // docs/07 §5: what the core says when a job needs the user back at the sign-in, and what
            // the list calls a job that is parked on it.
            Line(
                what = "the NEEDS_AUTH sentence",
                en = "Sign in again to carry on",
                ko = "계속하려면 다시 로그인하세요",
                android = "core_needs_auth",
                windows = "core.needs.auth",
                reckit = "Sign in again to carry on",
            ),
            Line(
                what = "the NEEDS_AUTH status",
                en = "Sign-in needed",
                ko = "로그인 필요",
                android = "job_state_needs_auth",
                windows = "status.sign.in.needed",
                reckit = "Sign-in needed",
            ),
            // docs/03 "다른 기기의 녹음" · docs/09 화면 원칙 2: the three "somewhere else" statuses.
            Line(
                what = "the RECEIVING status",
                en = "Receiving from the watch",
                ko = "워치에서 받는 중",
                android = "job_state_receiving",
                windows = "state.receiving",
                reckit = "Receiving from the watch",
            ),
            Line(
                what = "the remote UPLOADING status",
                en = "Uploading on another device",
                ko = "다른 기기에서 업로드 중",
                android = "job_state_remote_uploading",
                windows = "state.remote.uploading",
                reckit = "Uploading on another device",
            ),
            Line(
                what = "the remote TRANSCRIBING status",
                en = "Transcribing on another device",
                ko = "다른 기기에서 전사 중",
                android = "job_state_remote_transcribing",
                windows = "state.remote.transcribing",
                reckit = "Transcribing on another device",
            ),
            // docs/09 화면 원칙 2: the words on an expanded ledger row's own buttons.
            Line(
                what = "Retry",
                en = "Retry",
                ko = "다시 시도",
                android = "action_retry",
                windows = "recent.retry",
                mac = "Retry",
                phone = "Retry",
            ),
            Line(
                what = "Open in Drive",
                en = "Open in Drive",
                ko = "Drive 열기",
                android = "jobs_open_drive",
                windows = "recent.open.drive",
                mac = "Open in Drive",
                phone = "Open in Drive",
            ),
            // docs/05 "워크플로우 내보내기 · 가져오기": definitions are per-device, and the file is
            // the only way one device's workflows reach another — so the two controls that move
            // them are the same words wherever a user meets them. RecKit's catalog answers for both
            // Apple shells: the section is drawn once and both settings screens show that one.
            Line(
                what = "the export control",
                en = "Export workflows",
                ko = "워크플로우 내보내기",
                android = "settings_export_workflows",
                windows = "settings.export.workflows",
                reckit = "Export workflows",
            ),
            Line(
                what = "the import control",
                en = "Import workflows",
                ko = "워크플로우 가져오기",
                android = "settings_import_workflows",
                windows = "settings.import.workflows",
                reckit = "Import workflows",
            ),
            // docs/05: an import replaces the whole document — there is no merge — and the number
            // the confirmation names is the one the file actually holds.
            Line(
                what = "the import replace warning",
                en = "The %1\$d workflow(s) in the file replace every workflow on this device.",
                ko = "파일에 있는 워크플로우 %1\$d개가 이 기기의 워크플로우를 모두 대체합니다.",
                android = "workflows_import_body",
                windows = "workflows.import.body",
                reckit = "The %@ workflow(s) in the file replace every workflow on this device.",
            ),
            // docs/05 "시크릿": the export carries the names, never the values — said on every shell
            // that offers the file, because a user who read it on one and not the other loses a key.
            Line(
                what = "the keys-are-per-device hint",
                en = "Provider keys are not in this file — enter them on each device.",
                ko = "전사 키는 이 파일에 들어가지 않습니다 — 기기마다 입력합니다.",
                android = "settings_workflows_keys_hint",
                windows = "settings.workflows.keys.hint",
                reckit = "Provider keys are not in this file — enter them on each device.",
            ),
            // docs/08 "폴링 · 상태": the warning under a synchronous provider in the transcribe
            // step. Only the two phones say it — a desktop's background is not the one that cuts a
            // long request off — so Windows has no key for it and is left out of this line.
            Line(
                what = "the synchronous-provider hint",
                en = "This provider answers on one long request. On a phone, an asynchronous " +
                    "provider is more reliable in the background.",
                ko = "이 제공자는 요청 하나로 결과를 기다립니다. 폰에서는 백그라운드에서 비동기 제공자가 더 안정적입니다.",
                android = "editor_provider_synchronous_hint",
                reckit = "This provider answers on one long request. On a phone, an asynchronous " +
                    "provider is more reliable in the background.",
            ),
            // docs/03: the same two questions, in the same order, at the end of every recording.
            Line(
                what = "the title prompt",
                en = "Recording title",
                ko = "녹음 제목",
                android = "recording_title_prompt",
                windows = "recording.title",
                mac = "Recording title",
                phone = "Recording title",
            ),
            Line(
                what = "the title prompt's hint",
                en = "Leave it empty to keep the timestamp name",
                ko = "비워두면 시각으로 지은 이름을 그대로 씁니다",
                android = "recording_title_hint",
                windows = "title.hint",
                mac = "Leave it empty to keep the timestamp name",
                phone = "Leave it empty to keep the timestamp name",
            ),
            Line(
                what = "the participant question",
                en = "People in the room",
                ko = "참석 인원",
                android = "recording_participants",
                windows = "recording.participants",
                mac = "People in the room",
                phone = "People in the room",
            ),
            Line(
                what = "the unknown participant count",
                en = "Unknown",
                ko = "모름",
                android = "recording_participants_unknown",
                windows = "participants.unknown",
                mac = "Unknown",
                phone = "Unknown",
            ),
            Line(
                what = "six participants or more",
                en = "6+",
                ko = "6명 이상",
                android = "recording_participants_many",
                windows = "participants.many",
                mac = "6+",
                phone = "6+",
            ),
            // docs/05 "시크릿": the value is shown once, and the sentence has to say so on every shell
            // that shows it — a user who read the softer wording on one device loses the key.
            Line(
                what = "the secrets form",
                en = "Add a secret",
                ko = "시크릿 추가",
                android = "secrets_add",
                windows = "secret.add",
                mac = "Add a secret",
                phone = "Add a secret",
            ),
            Line(
                what = "the generated secret",
                en = "Copy the generated value now. It cannot be read again once it is saved " +
                    "(it is on the clipboard).",
                ko = "생성한 값을 지금 복사해 두세요. 저장 후에는 다시 볼 수 없습니다 (클립보드에 복사됨).",
                android = "secrets_generated",
                windows = "secret.generated.note",
                reckit = "Copy the generated value now. It cannot be read again once it is saved " +
                    "(it is on the clipboard).",
            ),
            // docs/09 화면 원칙 2: the three states the audit found each shell wording its own way.
            Line(
                what = "the RETRY status",
                en = "Retry pending",
                ko = "재시도 대기",
                android = "job_state_waiting",
                windows = "state.retry.wait",
                reckit = "Retry pending",
            ),
            Line(
                what = "the SKIPPED status",
                en = "Too short",
                ko = "너무 짧음",
                android = "job_state_skipped_short",
                windows = "state.too.short",
                reckit = "Too short",
            ),
            Line(
                what = "the NO_JOB status",
                en = "No workflow",
                ko = "워크플로우 없음",
                android = "job_state_no_workflow",
                windows = "state.no.workflow",
                reckit = "No workflow",
            ),
            // docs/09 화면 원칙 2: a ledger with nothing in it yet. Not a sentence — it is the
            // empty state of a table, and only Android had been ending it with a full stop.
            Line(
                what = "the empty ledger",
                en = "No recordings yet",
                ko = "아직 녹음이 없습니다",
                android = "jobs_empty",
                windows = "ledger.empty",
                mac = "No recordings yet",
                phone = "No recordings yet",
            ),
            // docs/12 M8: a desktop can tell a meeting from anything else and asks before each one,
            // so the two desktops share a row the two phones word differently (settings_consent_*).
            Line(
                what = "the desktop consent setting",
                en = "Consent check before recording",
                ko = "녹음 전 동의 확인",
                windows = "settings.consent.reminder",
                mac = "Consent check before recording",
            ),
            // docs/09 화면 원칙 3: the inspector's own field labels.
            Line(
                what = "the folder field",
                en = "Folder template",
                ko = "폴더 템플릿",
                android = "editor_folder",
                windows = "field.folder",
                reckit = "Folder template",
            ),
            Line(
                what = "the retries field",
                en = "Retries",
                ko = "재시도",
                android = "editor_max_attempts",
                windows = "field.retries",
                reckit = "Retries",
            ),
            Line(
                what = "the first-delay field",
                en = "First delay (s)",
                ko = "첫 지연(초)",
                android = "editor_initial_delay",
                windows = "field.first.delay",
                reckit = "First delay (s)",
            ),
            Line(
                what = "the max-delay field",
                en = "Max delay (s)",
                ko = "최대 지연(초)",
                android = "editor_max_delay",
                windows = "field.max.delay",
                reckit = "Max delay (s)",
            ),
            Line(
                what = "the minimum-length field",
                en = "Minimum length (s)",
                ko = "최소 길이(초)",
                android = "editor_min_duration",
                windows = "field.min.duration",
                reckit = "Minimum length (s)",
            ),
            Line(
                what = "the secret-name field",
                en = "Secret name",
                ko = "시크릿 이름",
                android = "editor_secret",
                windows = "field.secret.name",
                reckit = "Secret name",
            ),
            // docs/08: `invokeUrl` means one of two things, and which one is the provider's answer
            // (`WorkflowParser.invokeUrlUse`) — so the line under the field is one of two, and both
            // are the same line on the three shells that draw the editor.
            Line(
                what = "the invoke-URL hint for a provider that requires one",
                en = "The provider's endpoint URL. Required for this provider.",
                ko = "제공자의 엔드포인트 URL입니다. 이 제공자에는 필수입니다.",
                android = "editor_invoke_url_hint_required",
                windows = "field.invoke.url.hint.required",
                reckit = "The provider's endpoint URL. Required for this provider.",
            ),
            Line(
                what = "the invoke-URL hint for a provider that only accepts one",
                en = "Leave empty for the provider's default endpoint. " +
                    "Set it for a self-hosted or regional endpoint.",
                ko = "비우면 제공자 기본 엔드포인트를 씁니다. 자체 호스팅이나 리전 엔드포인트일 때만 입력하세요.",
                android = "editor_invoke_url_hint_optional",
                windows = "field.invoke.url.hint.optional",
                reckit = "Leave empty for the provider's default endpoint. " +
                    "Set it for a self-hosted or regional endpoint.",
            ),
            // The two answers to "what happens after a failure", as chips on all four.
            Line(
                what = "the on-error label",
                en = "On failure",
                ko = "오류 시",
                android = "editor_on_error",
                windows = "field.on.error",
                reckit = "On failure",
            ),
            Line(
                what = "the on-error abort chip",
                en = "Stop",
                ko = "중단",
                android = "editor_on_error_abort",
                windows = "on.error.abort",
                reckit = "onError.abort",
            ),
            Line(
                what = "the on-error continue chip",
                en = "Continue",
                ko = "계속",
                android = "editor_on_error_continue",
                windows = "on.error.continue",
                reckit = "onError.continue",
            ),
            // docs/09 화면 원칙 3: the step node's kicker is the position and the type, on both the
            // shells whose kicker is a format string (Apple interpolates it in the view).
            Line(
                what = "the step kicker",
                en = "%1\$d · %2\$s",
                ko = "%1\$d · %2\$s",
                android = "editor_step_kicker",
                windows = "editor.step.kicker",
            ),
            // ADR-016 (리드 정본, 2026-09-02): there is one selection per device and no "default"
            // vocabulary anywhere near it. The mark on the workflow every recording on this device
            // runs, said on every shell that lists workflows.
            Line(
                what = "the in-use badge",
                en = "In use",
                ko = "사용 중",
                android = "workflow_in_use",
                windows = "workflow.in.use",
                mac = "In use",
                phone = "In use",
            ),
            // The list's one control, and the other half of that badge: exactly one of the two is on
            // a row. It writes nothing to the document — the pointer it moves is this device's own.
            Line(
                what = "the select-this-workflow control",
                en = "Use",
                ko = "사용",
                android = "workflow_use",
                windows = "workflow.use",
                mac = "Use",
                phone = "Use",
            ),
            // Nothing is selected on this device — no pointer, or one pointing at a workflow that is
            // gone — said where the user would meet it: the picker and the record screen's node.
            Line(
                what = "the choose-a-workflow nudge",
                en = "Choose a workflow",
                ko = "워크플로우를 선택하세요",
                android = "recording_workflow_choose",
                windows = "workflow.choose",
                mac = "Choose a workflow",
                phone = "Choose a workflow",
            ),
            // docs/09 "접근성": what a click on the workflow row does, said out loud rather than
            // drawn — Android's `onClickLabel`, the Mac row's accessibility hint. The row is the
            // control on both, so it is the same promise and has to be the same words.
            Line(
                what = "the open-the-workflow row label",
                en = "Edit the workflow",
                ko = "워크플로우 편집",
                android = "workflow_open",
                mac = "Edit the workflow",
            ),
            // ADR-016: deleting the workflow this device runs is allowed, and the dialog says which
            // one it is before it asks — said in the confirm dialog every shell now asks the delete
            // through, and nowhere else. On Apple the dialog is RecKit's, shared by the phone and
            // the Mac, so the wording is too.
            Line(
                what = "the deleting-the-workflow-in-use warning",
                en = "This is the workflow in use on this device.",
                ko = "이 기기에서 사용 중인 워크플로우입니다.",
                android = "workflow_delete_in_use",
                windows = "workflow.delete.in.use",
                reckit = "This is the workflow in use on this device.",
            ),
            // What that same dialog says a delete costs before the warning: the definition is this
            // device's own (docs/05), so nothing syncs it back.
            Line(
                what = "the workflow delete body",
                en = "This workflow disappears from this device. Jobs that already exist still run.",
                ko = "이 워크플로우는 이 기기에서 사라집니다. 이미 만들어진 Job은 그대로 실행됩니다.",
                android = "workflows_delete_body",
                windows = "workflows.delete.body",
                reckit = "This workflow disappears from this device. Jobs that already exist still run.",
            ),
            // docs/08 "결과 파일": the window a desktop opens and the screen a phone pushes. What is
            // behind a row is the whole of the recording — its audio as well as its transcript — so
            // the surface is named after the row action that opens it, below. Android has only that
            // action, because the screen it pushes is titled with the recording instead.
            Line(
                what = "the transcripts window title",
                en = "Details",
                ko = "상세",
                windows = "window.recordings",
                // The Mac's own key: the title is resolved by the scene, outside the RecKit subtree
                // whose strings the row action comes from.
                mac = "Details",
            ),
            // The row action that opens it: what is behind the row is the whole of the recording,
            // not only its transcript, so the action says so wherever a row offers it.
            Line(
                what = "the row's detail action",
                en = "Details",
                ko = "상세",
                android = "detail_open",
                windows = "recent.details",
                reckit = "Details",
            ),
            // docs/03 "제목": the recording is renamed from the page that is about it, on every
            // shell that opens one — the title reaches Drive and so every other device reads it,
            // which makes the words the user renames by the same words everywhere.
            Line(
                what = "the detail screen's rename action",
                en = "Rename",
                ko = "이름 바꾸기",
                android = "detail_rename",
                windows = "detail.rename",
                reckit = "Rename",
            ),
            // docs/08 "결과 파일" · docs/03 ADR-017: the player bar behind that row. One button, one
            // clock, and the three sentences that stand in for them — the parts are the same parts
            // wherever the recording is opened, so what the bar says about them is the same too.
            Line(
                what = "the player's Play",
                en = "Play",
                ko = "재생",
                android = "player_play",
                windows = "player.play",
                reckit = "Play",
            ),
            Line(
                what = "the player's Pause",
                en = "Pause",
                ko = "일시정지",
                android = "player_pause",
                windows = "player.pause",
                reckit = "Pause",
            ),
            Line(
                what = "nothing left here to play",
                en = "No audio on this device",
                ko = "이 기기에 오디오가 없습니다",
                android = "player_no_audio",
                windows = "player.no.audio",
                reckit = "No audio on this device",
            ),
            Line(
                what = "the parts on their way back from Drive",
                en = "Fetching from Drive…",
                ko = "Drive에서 받는 중…",
                android = "player_fetching",
                windows = "player.fetching",
                reckit = "Fetching from Drive…",
            ),
            Line(
                what = "the trip to Drive that did not bring them back",
                en = "Could not fetch from Drive",
                ko = "Drive에서 받지 못했습니다",
                android = "player_fetch_failed",
                windows = "player.fetch.failed",
                reckit = "Could not fetch from Drive",
            ),
            // docs/07 rule 1·2: the same setting, said the same way, on the three shells that have
            // one. The two language names are pinned in *both* columns because they are endonyms —
            // a name that translated would be unreadable to the one person who comes to this
            // screen to get out of the language they cannot read.
            Line(
                what = "the language setting",
                en = "Language",
                ko = "언어",
                android = "settings_language",
                windows = "settings.language",
                reckit = "Language",
            ),
            Line(
                what = "Korean, under its own name",
                en = "한국어",
                ko = "한국어",
                android = "settings_language_ko",
                windows = "language.ko",
                reckit = "language.ko",
            ),
            Line(
                what = "English, under its own name",
                en = "English",
                ko = "English",
                android = "settings_language_en",
                windows = "language.en",
                reckit = "language.en",
            ),
            // docs/09 화면 원칙 4: the screen the settings are on is called the same thing on the
            // shell that gives it a tab and the ones that give it a window.
            Line(
                what = "the settings surface",
                en = "Settings",
                ko = "설정",
                android = "tab_settings",
                windows = "window.settings",
                mac = "Settings",
                phone = "Settings",
                pending = setOf("windows"),
            ),
            // docs/12: the two switches only a desktop has — a phone neither starts with the
            // session nor can tell a meeting from anything else.
            Line(
                what = "the launch-at-login setting",
                en = "Launch at login",
                ko = "로그인 시 자동 실행",
                windows = "settings.launch.at.login",
                mac = "Launch at login",
                pending = setOf("windows"),
            ),
            // docs/05 "시크릿": a step naming a key this device has no value for, on every shell
            // that lists workflows — a key is per device (docs/05), so the row has to say which
            // device it is talking about, and every shell says "this device" for itself.
            Line(
                what = "the missing-key mark on a workflow row",
                en = "No key on this device: %1\$s",
                ko = "이 기기에 키 없음: %1\$s",
                android = "workflow_missing_secrets",
                windows = "editor.missing.key",
                mac = "No key on this device: %@",
                phone = "No key on this device: %@",
            ),
            // docs/09 화면 원칙 4 · docs/03: the account section, and the two things it says when
            // there is nobody signed in. Which account it is about is the whole of the section, so
            // the heading names Google rather than leaving "Account" to mean anything at all.
            Line(
                what = "the account section",
                en = "Google account",
                ko = "Google 계정",
                android = "settings_account",
                mac = "Google account",
                phone = "Google account",
            ),
            Line(
                what = "the signed-out row",
                en = "Signed out",
                ko = "로그아웃됨",
                android = "signed_out",
                mac = "Signed out",
                phone = "Signed out",
            ),
            Line(
                what = "the sign-in button",
                en = "Sign in with Google",
                ko = "Google 로그인",
                android = "sign_in",
                mac = "Sign in with Google",
                phone = "Sign in with Google",
            ),
            // docs/09 화면 원칙 4: the about block's own line, which is a legal notice and so is the
            // same notice everywhere.
            Line(
                what = "the open-source notices label",
                en = "Open-source notices",
                ko = "오픈소스 고지",
                android = "settings_open_source",
                windows = "settings.open.source",
                mac = "Open-source notices",
                phone = "Open-source notices",
            ),
            // docs/03 "다른 기기의 녹음": deleting one of those rows is deleting the Drive folder —
            // there is no local half to keep, and the deletion reaches every device that pulled it.
            // The shell that shows the softer wording would be the one that loses a recording.
            Line(
                what = "the delete body for another device's recording",
                en = "Recorded on another device. Deleting removes it from Drive and from every device.",
                ko = "다른 기기에서 녹음한 것입니다. 지우면 Drive와 모든 기기에서 사라집니다.",
                android = "delete_remote_body",
                windows = "delete.remote.body",
                reckit = "Recorded on another device. Deleting removes it from Drive and from every device.",
            ),
            // docs/03 "앱에서 지우기": Drive refused the folder and the local deletion is not undone
            // by it. RecKit's sentence says "this device" because it is shared by the Mac and the
            // phone (docs/07 rule 10); the PC's says PC, and is left out of this line for it.
            Line(
                what = "the Drive half of a delete that Drive refused",
                en = "Deleted here, but Drive refused: %1\$s",
                ko = "이 기기에서는 지웠지만 Drive에서 지우지 못했습니다: %1\$s",
                android = "delete_drive_failed",
                reckit = "Deleted here, but Drive refused: %@",
            ),
            // docs/05 "시크릿": the secret picker's own way out — a key that is not defined yet is
            // defined from where it was needed, and the ellipsis is what says the form is coming.
            Line(
                what = "the new-secret choice in the picker",
                en = "New…",
                ko = "새로 만들기",
                android = "editor_secret_new",
                windows = "secret.new",
                reckit = "New…",
            ),
            // docs/13 deliverable 1: the microphone is what this app is for, so a refusal is said
            // the same way wherever the user meets it — the note the recorder puts up, and the line
            // over the way back into the system settings.
            Line(
                what = "the microphone refusal",
                en = "The microphone permission is needed",
                ko = "마이크 권한 필요",
                android = "recording_mic_denied",
                mac = "The microphone permission is needed",
                phone = "The microphone permission is needed",
            ),
            // The Mac says this in an alert, whose `messageText` carries no full stop; the two
            // phones say it as a line on the recording screen, and it is a sentence there.
            Line(
                what = "the microphone line on the recording screen",
                en = "The microphone permission is required.",
                ko = "마이크 권한이 필요합니다.",
                android = "recording_mic_required",
                phone = "The microphone permission is required.",
            ),
            Line(
                what = "the way into the system's own settings",
                en = "Open System Settings",
                ko = "시스템 설정 열기",
                android = "action_open_settings",
                mac = "Open System Settings",
                phone = "Open System Settings",
            ),
            // docs/09 "접근성": what a screen reader is told a ledger row is. Everything the row
            // draws, as one sentence — and the date and the length inside it are locale-formatted,
            // which is exactly what the row's own fixed-width columns are not.
            Line(
                what = "the ledger row read out loud",
                en = "%1\$s, recorded %2\$s, length %3\$s, %4\$s",
                ko = "%1\$s, %2\$s 녹음, 길이 %3\$s, %4\$s",
                android = "jobs_row_description",
                reckit = "%1\$@, recorded %2\$@, length %3\$@, %4\$@",
            ),
            // docs/09 "접근성": the system decides dark mode, and this is the user's override of it —
            // a per-device choice like the language, said the same way on the shells that offer one.
            Line(
                what = "the theme section",
                en = "Theme",
                ko = "테마",
                android = "settings_theme",
                windows = "settings.theme",
            ),
            Line(
                what = "the theme's system default",
                en = "System default",
                ko = "시스템 기본",
                android = "theme_system",
                windows = "theme.system",
            ),
            Line(
                what = "the light theme",
                en = "Light",
                ko = "밝게",
                android = "theme_light",
                windows = "theme.light",
            ),
            Line(
                what = "the dark theme",
                en = "Dark",
                ko = "어둡게",
                android = "theme_dark",
                windows = "theme.dark",
            ),
            // docs/03 "로그아웃 vs 연결 해제": what disconnecting is, under the row that does it.
            // The apostrophe is the typographic one every shell but Windows already writes.
            Line(
                what = "the disconnect hint",
                en = "Take this app’s access to your Google account away.",
                ko = "이 앱의 Google 계정 접근 권한을 회수합니다.",
                android = "settings_disconnect_hint",
                windows = "settings.disconnect.hint",
                mac = "Take this app’s access to your Google account away.",
                phone = "Take this app’s access to your Google account away.",
                pending = setOf("windows"),
            ),
        )
    }
}
