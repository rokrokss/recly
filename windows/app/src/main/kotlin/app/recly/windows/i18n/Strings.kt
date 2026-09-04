package app.recly.windows.i18n

import java.util.Locale
import java.util.Properties

/**
 * Every sentence this app says to a person (docs/07 rule 4). The enum *is* the key list, so a
 * string that is not in the table does not compile into existence: [key] is the name lowercased
 * with dots for underscores, which is what `resources/i18n/strings_*.properties` holds.
 *
 * **Why `.properties` and not Compose Multiplatform resources** (docs/07, Windows row): a tray app
 * says most of what it says from outside a composition — `TrayIcon.displayMessage`, the loopback
 * sign-in page Ktor serves, `Recents.stateLabel`, the helper's self-test report — and the generated
 * CMP accessors are `@Composable` or `suspend`. A plain table is readable from any thread at any
 * time, which is the only shape that fits, and it costs no plugin and no generated code.
 */
enum class Str {
    STATUS_OPENING,
    STATUS_CORE_ERROR,
    STATUS_WAITING,
    STATUS_RECORDING,
    STATUS_SAVING,
    STATUS_DEFERRED,
    STATUS_NAMING,
    STATUS_HELPER_MISSING,
    STATUS_HELPER_DIED,
    STATUS_MIC_DENIED,
    STATUS_SIGN_IN_NEEDED,
    STATUS_SIGNING_IN,
    STATUS_NO_CLIENT,
    STATUS_SIGN_IN_FAILED,
    STATUS_WORKFLOW_GONE,
    STATUS_CANNOT_START,

    TRAY_TOOLTIP,
    TRAY_SIGN_IN,
    TRAY_STOP,
    TRAY_START_DETECTED,
    TRAY_START_AGAIN,
    TRAY_START,
    TRAY_OPEN,
    TRAY_EDIT_WORKFLOWS,
    TRAY_SETTINGS,
    TRAY_QUIT,
    /** docs/12: a quit under a running capture finalizes it first, and the label says so. */
    TRAY_QUIT_SAVING,

    // The tray popup (docs/09 화면 원칙 6): three state nodes, a ledger, a footer.
    NODE_DEVICE,
    NODE_WORKFLOW,
    NODE_STATE,
    /** ADR-016: nothing is chosen on this PC yet, so a start would run nothing. */
    WORKFLOW_CHOOSE,
    LABEL_NONE,
    LEDGER_TIME,
    LEDGER_TITLE,
    LEDGER_LENGTH,
    LEDGER_STATUS,
    LEDGER_ANNOUNCE,
    LEDGER_EMPTY,
    RECENT_OPEN_DRIVE,
    RECENT_RETRY,
    /** The row's own action, which is not the window that names the transcript surface. */
    RECENT_DETAILS,

    WINDOW_WORKFLOWS,
    WINDOW_SETTINGS,
    RECORDING_TITLE,
    TITLE_HINT,
    // docs/03: the stop dialog also asks how many people were in the room, as the phones do.
    RECORDING_PARTICIPANTS,
    PARTICIPANTS_UNKNOWN,
    PARTICIPANTS_MANY,
    SAVE,
    SKIP,
    CANCEL,
    DELETE,
    ACTION_PROCESSING,
    ACTION_DONE,

    CONSENT_QUESTION,
    CONSENT_BODY,
    CONSENT_CONFIRM,
    CONSENT_SUPPRESS,
    CONSENT_LINK,

    SETTINGS_ACCOUNT,
    SETTINGS_SIGNED_IN,
    SETTINGS_SIGNED_OUT,
    SIGN_IN,
    SIGN_OUT,
    SETTINGS_NO_CLIENT,
    SETTINGS_STARTUP,
    SETTINGS_LAUNCH_AT_LOGIN,
    SETTINGS_LAUNCH_UNSUPPORTED,
    SETTINGS_CONSENT_REMINDER,
    SETTINGS_RECORDING,
    // docs/14 "캡처": the microphone alone or the whole meeting, in the Mac's own two labels.
    SETTINGS_CAPTURE_MODE,
    SETTINGS_CAPTURE_MODE_MICROPHONE,
    SETTINGS_CAPTURE_MODE_MEETING,
    /** docs/14 "권한": the Windows page that turns the microphone back on for desktop apps. */
    SETTINGS_OPEN_MICROPHONE,
    SETTINGS_HELPER_MISSING,
    SETTINGS_HELPER_VERSION,
    SETTINGS_HELPER_SILENT,
    SETTINGS_SELF_TEST,
    SETTINGS_DATA,
    SETTINGS_OPEN_FOLDER,

    // docs/05 "워크플로우 내보내기 · 가져오기": definitions are per-device, and a file moves them.
    SETTINGS_WORKFLOWS,
    SETTINGS_EXPORT_WORKFLOWS,
    SETTINGS_IMPORT_WORKFLOWS,
    SETTINGS_WORKFLOWS_KEYS_HINT,
    WORKFLOWS_IMPORT_TITLE,
    WORKFLOWS_IMPORT_BODY,
    WORKFLOWS_IMPORTED,
    WORKFLOWS_EXPORTED,
    WORKFLOWS_FILE_FAILED,

    SETTINGS_LANGUAGE,
    LANGUAGE_KO,
    LANGUAGE_EN,

    // docs/09 화면 원칙 4: the theme the user chooses, and the honest system block.
    SETTINGS_THEME,
    THEME_SYSTEM,
    THEME_LIGHT,
    THEME_DARK,
    SETTINGS_ABOUT,
    SETTINGS_ABOUT_APP,
    SETTINGS_ABOUT_DEVICE,
    SETTINGS_OPEN_SOURCE,
    SETTINGS_OPEN_SOURCE_VALUE,

    SELF_TEST_RUNNING,
    SELF_TEST_NO_ANSWER,
    SELF_TEST_EMPTY,
    SELF_TEST_FAILED,
    MIC_GUIDANCE,

    NOTIFY_MEETING_TITLE,
    NOTIFY_MEETING_BODY,
    NOTIFY_IDLE_TITLE,
    NOTIFY_IDLE_BODY,

    STATE_NO_WORKFLOW,
    STATE_UPLOADING,
    STATE_RETRY_WAIT,
    STATE_DONE,
    STATE_FAILED,
    STATE_TOO_SHORT,
    /** docs/10 "Drive 용량 초과": parked because Drive is full, and no retry gets past that. */
    STATE_NO_SPACE,
    /** docs/08 "폴링 · 상태": a provider is transcribing and the only news is how long it has been. */
    STATE_WAITING_TRANSCRIPTION,
    /**
     * A recording nobody named. Its own word, not the workflows' [UNNAMED]: docs/07 rule 11 keeps the
     * shells literally the same, and the Mac says `Untitled` for a recording (`Recents.titleLabel`)
     * and `Unnamed` for a workflow (`WorkflowWindow`).
     */
    UNTITLED,

    /** A workflow nobody named — see [UNTITLED] for why the two are not one key. */
    UNNAMED,
    EDITOR_NEW_WORKFLOW,
    /** ADR-016: the mark on the row this PC runs, the row's one control, and what deleting it costs. */
    WORKFLOW_IN_USE,
    WORKFLOW_USE,
    WORKFLOW_DELETE_IN_USE,
    /** What a workflow delete costs, asked before it happens. The title is [DELETE_TITLE]. */
    WORKFLOWS_DELETE_BODY,
    EDITOR_MISSING_KEY,
    EDITOR_STALE,
    EDITOR_SAVED_ELSEWHERE,
    EDITOR_DELETED_ELSEWHERE,
    EDITOR_REOPEN,

    // docs/09 화면 원칙 3: the node graph and the inspector under it.
    EDITOR_WORKFLOWS,
    EDITOR_PICK_WORKFLOW,
    EDITOR_NODE_TRIGGER,
    EDITOR_NODE_TRIGGER_TITLE,
    EDITOR_NODE_END,
    EDITOR_INSERT_STEP,
    EDITOR_STEP_KICKER,
    EDITOR_NO_URL,
    EDITOR_MOVE_EARLIER,
    EDITOR_MOVE_LATER,
    EDITOR_RETRY,

    // docs/08 "결과 파일" · "오류": the transcripts and summaries window.
    WINDOW_RECORDINGS,
    DETAIL_PICK,
    DETAIL_LOADING,
    DETAIL_EMPTY,
    /** docs/03: the name of a recording, changed from the one screen that shows the recording. */
    DETAIL_RENAME,
    REASON_CHECK_KEY,

    // docs/08 "결과 파일": the recording itself, under the transcript's header. One button and the
    // recording's own clock; docs/03 ADR-017 is what the other three lines are about.
    PLAYER_PLAY,
    PLAYER_PAUSE,
    PLAYER_NO_AUDIO,
    PLAYER_FETCHING,
    PLAYER_FETCH_FAILED,
    /** docs/03: a delete refused because the speaker would not let go of the part it was reading. */
    PLAYER_STOP_FAILED,
    /** docs/09 접근성: what the waveform row is, for a reader that cannot see the shape. */
    PLAYER_POSITION,

    SECRETS_TITLE,
    SECRET_ADD,
    SECRET_NAME_LABEL,
    SECRET_VALUE_LABEL,
    SECRET_GENERATE,
    SECRET_GENERATED_NOTE,
    SECRET_COPY_AGAIN,
    SECRET_NAME_EMPTY,
    SECRET_NAME_INVALID,
    SECRET_NAME_TAKEN,
    SECRET_VALUE_REQUIRED,

    FIELD_NAME,
    FIELD_MIN_DURATION,
    FIELD_FOLDER,
    FIELD_INCLUDE_META,
    FIELD_URL,
    FIELD_SECRET_NAME,
    // docs/09: the two answers are a choice of one, so they are chips and not a switch — the same
    // pair the other three inspectors show.
    FIELD_ON_ERROR,
    ON_ERROR_ABORT,
    ON_ERROR_CONTINUE,
    FIELD_RETRIES,
    FIELD_FIRST_DELAY,
    FIELD_MAX_DELAY,
    STEP_ADD_DRIVE,
    STEP_ADD_WEBHOOK,
    STEP_ADD_TRANSCRIBE,

    // docs/08: the transcribe inspector.
    FIELD_PROVIDER,
    FIELD_API_KEY,
    FIELD_INVOKE_URL,
    FIELD_INVOKE_URL_HINT_REQUIRED,
    FIELD_INVOKE_URL_HINT_OPTIONAL,
    FIELD_LANGUAGE,
    FIELD_DIARIZE,
    FIELD_SPEAKERS_MIN,
    FIELD_SPEAKERS_MAX,
    FIELD_SPEAKERS_HINT,
    SECRET_NEW,
    EDITOR_ORDER_TRANSCRIBE_NEEDS_UPLOAD,

    LABEL_DRIVE,
    LABEL_DRIVE_UPLOAD,
    LABEL_WEBHOOK,
    LABEL_TRANSCRIBE,

    AUTH_PAGE_OK,
    AUTH_PAGE_DECLINED,
    AUTH_PAGE_DONE,
    AUTH_NO_REFRESH_TOKEN,
    AUTH_STATE_MISMATCH,
    AUTH_NO_CODE,

    // docs/03 "앱에서 지우기": one recording, and Drive is a separate answer whose default is
    // "leave it" — the irreversible half is never the default one.
    DELETE_TITLE,
    DELETE_LOCAL_ONLY,
    DELETE_WITH_DRIVE,
    /**
     * A recording another device uploaded has nothing on this PC to keep, so there is no choice to
     * offer — the deletion is the Drive folder, and every device loses it.
     */
    DELETE_REMOTE_BODY,
    DELETE_UNUPLOADED,
    DELETE_BUSY,
    DELETE_DRIVE_FAILED,
    DELETE_DONE,

    // docs/03 "로그아웃 vs 연결 해제" · docs/06: signing out is this PC, disconnecting is the grant.
    SETTINGS_DISCONNECT,
    SETTINGS_DISCONNECT_HINT,
    DISCONNECT_TITLE,
    DISCONNECT_OTHER_DEVICES,
    DISCONNECT_UNUPLOADED,
    DISCONNECT_LOCAL,
    DISCONNECT_ALSO_DELETE,
    DISCONNECT_PERMISSIONS,
    DISCONNECT_REVOKE_FAILED,
    DISCONNECT_DONE,
    DISCONNECT_DELETED,
    DISCONNECT_BUSY,
    DISCONNECT_STOP_RECORDING,
    DISCONNECT_PENDING_SIGN_IN,
    DISCONNECT_IN_PROGRESS,
    DISCONNECT_CLEANUP_FAILED,
    DISCONNECT_STILL_LISTED,
    DISCONNECT_REMOVED,
    DISCONNECT_SAVE_FAILED,

    // docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the seven reasons a person has to do something
    // about, folded one balloon and one banner line per reason.
    JOBS_OPEN_STORAGE,
    ALERT_NEEDS_AUTH,
    ALERT_NEEDS_SPACE,
    ALERT_MISSING_SECRET,
    ALERT_INVALID_SECRET,
    ALERT_AUTH_REJECTED,
    ALERT_QUOTA,
    ALERT_WEBHOOK,
    ALERT_WAITING,

    // docs/15 §3: what leaves this PC when the step runs, and whose policy decides the rest.
    PROVIDER_DISCLOSURE_TRANSCRIBE,

    CORE_NEEDS_AUTH,
    CORE_DRIVE_REAUTH,
    CORE_DRIVE_CONSENT_REQUIRED,
    CORE_DRIVE_STORAGE_FULL,
    CORE_SIGN_IN_CANCELLED,
    CORE_MISSING_SECRET,
    CORE_INVALID_SECRET,
    CORE_WEBHOOK_HTTP,
    CORE_FOLDER_TEMPLATE,
    CORE_RETRY_BUDGET_SPENT,
    CORE_NO_RUNNER,
    CORE_STEP_MISSING,
    CORE_UNSUPPORTED_STEP,
    CORE_STEP_FAILED,

    // docs/08 "오류": the transcribe table, in the words of someone who has to decide what to do
    // next. The provider's own line rides along as the code's detail.
    CORE_AUTH_REJECTED,
    CORE_QUOTA,
    CORE_PROVIDER_ERROR,
    CORE_UNSUPPORTED_AUDIO,
    CORE_NO_INPUT_TRACK,
    CORE_RESULT_TIMEOUT,

    CORE_STALE,
    CORE_UNSUPPORTED_SCHEMA,
    ;

    val key: String = name.lowercase(Locale.ROOT).replace('_', '.')
}

/**
 * One language's table. Immutable, so a screen that has one is looking at one language for as long
 * as it holds it — which is what makes a language change a new [Strings] rather than a mutation
 * anything could half-observe.
 */
class Strings internal constructor(
    /** `en` or `ko` — the language actually loaded, never `system`. */
    val language: String,
    private val values: Map<Str, String>,
) {
    private val locale: Locale = Locale.forLanguageTag(language)

    /** [args] are `String.format` positionals, so `%1$d` gets an `Int` and `%1$s` anything. */
    operator fun get(key: Str, vararg args: Any?): String {
        val pattern = values[key] ?: key.key
        return if (args.isEmpty()) pattern else String.format(locale, pattern, *args)
    }
}

/** Where the two tables are read from, once each per process. */
object StringTable {

    const val BASE: String = "en"

    const val KOREAN: String = "ko"

    private val tables = mutableMapOf<String, Strings>()

    /** Anything that is not [KOREAN] is the base language (docs/07 rule 1). */
    @Synchronized
    fun of(language: String): Strings {
        val tag = if (language == KOREAN) KOREAN else BASE
        return tables.getOrPut(tag) { Strings(tag, load(tag)) }
    }

    /**
     * Read as UTF-8 rather than left to `Properties.load(InputStream)`, which is ISO-8859-1 — the
     * Korean table is UTF-8 on disk and would otherwise come back as mojibake.
     */
    private fun load(tag: String): Map<Str, String> {
        val path = "/i18n/strings_$tag.properties"
        val properties = Properties()
        val stream = checkNotNull(StringTable::class.java.getResourceAsStream(path)) { "missing $path" }
        stream.reader(Charsets.UTF_8).use { properties.load(it) }
        return Str.entries.mapNotNull { key -> properties.getProperty(key.key)?.let { key to it } }.toMap()
    }
}
