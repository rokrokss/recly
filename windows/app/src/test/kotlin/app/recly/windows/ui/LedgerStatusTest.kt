@file:OptIn(ExperimentalTime::class)

package app.recly.windows.ui

import app.recly.windows.job
import app.recly.windows.plain
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.component.statusColumn
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.jobs.Recents
import app.recly.windows.ui.component.BadgeTone
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import okio.Path.Companion.toPath
import recly.core.job.JobStatus
import recly.core.message.CoreMessage
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.recording.RecordingRecord

/**
 * docs/09 screen principle 2: every row state has a code and a tone, the code is the word the core and the
 * logs already use, and no two states look the same to someone who cannot tell the tones apart.
 */
class LedgerStatusTest {

    /** The mapping is only worth anything if it covers everything `Recents` can actually report. */
    @Test
    fun `every state Recents can report has a badge`() {
        statesRecentsCanReport().forEach { (name, state) ->
            assertTrue(
                (state as UiMessage.Res).key in LedgerStates,
                "$name has no badge, so its row would say UNKNOWN",
            )
        }
    }

    @Test
    fun `every badge is a code, and two states that share one wear it the same way`() {
        LedgerStates.values.forEach { badge ->
            assertTrue(badge.code.isNotBlank(), "a state has no code")
            assertEquals(badge.code.uppercase(Locale.ROOT), badge.code, "${badge.code} is not a code")
        }
        // docs/03 (2026-09-04): an upload on another device is `UPLOADING` and a provider transcribing
        // for it is `TRANSCRIBING` — the same news as this PC's own, so deliberately the same code,
        // with the row's sentence saying where. What two states may never do is share a code in
        // different tones: that is a pair told apart only by colour.
        LedgerStates.values.groupBy { it.code }.forEach { (code, badges) ->
            assertEquals(
                1,
                badges.map { it.tone }.toSet().size,
                "$code is worn in two tones, so those states are told apart only by colour",
            )
        }
    }

    @Test
    fun `the tone says what kind of news it is`() {
        assertEquals(BadgeTone.SUCCESS, Str.STATE_DONE.message().ledgerStatus().tone)
        assertEquals(BadgeTone.DANGER, Str.STATE_FAILED.message().ledgerStatus().tone)
        assertEquals(BadgeTone.DANGER, Str.STATUS_RECORDING.message().ledgerStatus().tone)
        assertEquals(BadgeTone.ACCENT, Str.STATE_UPLOADING.message().ledgerStatus().tone)
        // docs/03: work in flight elsewhere is still work in flight — the accent, like this PC's own.
        assertEquals(BadgeTone.ACCENT, Str.STATE_RECEIVING.message().ledgerStatus().tone)
        assertEquals(BadgeTone.ACCENT, Str.STATE_REMOTE_UPLOADING.message().ledgerStatus().tone)
        assertEquals(BadgeTone.ACCENT, Str.STATE_REMOTE_TRANSCRIBING.message().ledgerStatus().tone)
        // Something the user has to act on, but nothing is lost yet.
        assertEquals(BadgeTone.NEUTRAL, Str.STATUS_SIGN_IN_NEEDED.message().ledgerStatus().tone)
        assertEquals(BadgeTone.WARNING, Str.STATE_RETRY_WAIT.message().ledgerStatus().tone)
        // Waiting for the speech model is a wait the user ends, worn like consent — never FAILED.
        val model = Str.STATE_NEEDS_MODEL.message().ledgerStatus()
        assertEquals("NEEDS_MODEL", model.code)
        assertEquals(BadgeTone.WARNING, model.tone)
        assertEquals(Str.STATE_CONSENT_REQUIRED.message().ledgerStatus().tone, model.tone)
        // Nothing is wrong and nothing is happening.
        assertEquals(BadgeTone.NEUTRAL, Str.STATUS_WAITING.message().ledgerStatus().tone)
        assertEquals(BadgeTone.NEUTRAL, Str.STATE_TOO_SHORT.message().ledgerStatus().tone)
    }

    /**
     * docs/09 screen principle 2: the status column is measured against every label a badge can wear, so
     * `NEEDS_MODEL` — cut to "NEEDS_MODE" at the old fixed width — and the rest always fit whole.
     */
    @Test
    fun `the status column is measured against every badge the ledger can wear`() {
        for (language in listOf(StringTable.BASE, StringTable.KOREAN)) {
            val strings = StringTable.of(language)
            val labels = ledgerBadgeLabels(strings)
            assertTrue(strings[Str.BADGE_NEEDS_MODEL] in labels)
            assertTrue(strings[Str.DRIVE_PENDING] in labels, "NEEDS_AUTH is drawn in words")
            assertTrue(strings[Str.BADGE_UNKNOWN] in labels)
            LedgerStates.forEach { (key, badge) ->
                assertTrue(key.message().ledgerStatus(strings).label in labels, "${badge.code} is not measured")
            }
        }
        // The widest label, plus the badge's padding and border either side.
        assertEquals(100.dp, statusColumn(widest = 86.dp, line = 1.dp))
    }

    /**
     * The UX decisions of 2026-10-08: a badge says a word in the app's language, never the code — the
     * code is the state's internal name. Every state has one, and the waits keep their tones.
     */
    @Test
    fun `a badge says its word, not its code`() {
        val en = StringTable.of(StringTable.BASE)
        val ko = StringTable.of(StringTable.KOREAN)
        LedgerStates.forEach { (key, badge) ->
            assertTrue(badge.word != null, "${badge.code} has no word")
            assertTrue(key.message().ledgerStatus(en).label != badge.code || badge.code == "UNKNOWN", "${badge.code} is drawn as its code")
        }
        val words = mapOf(
            Str.STATE_DONE to ("Done" to "완료"),
            Str.STATE_FAILED to ("Failed" to "실패"),
            Str.STATE_RETRY_WAIT to ("Retrying" to "재시도 대기"),
            Str.STATUS_WAITING to ("Waiting" to "대기"),
            Str.STATE_UPLOADING to ("Uploading" to "업로드 중"),
            Str.STATE_REMOTE_UPLOADING to ("Uploading" to "업로드 중"),
            Str.STATE_RECEIVING to ("Receiving" to "받는 중"),
            Str.STATE_REMOTE_TRANSCRIBING to ("Transcribing" to "전사 중"),
            Str.PROCESSING_LOCAL_RUNNING to ("Transcribing" to "전사 중"),
            Str.STATE_IMPORTING to ("Importing" to "가져오는 중"),
            Str.STATUS_RECORDING to ("Recording" to "녹음 중"),
            Str.STATUS_SIGN_IN_NEEDED to ("Waiting for Drive" to "Drive 연결 대기"),
            Str.STATE_CONSENT_REQUIRED to ("Needs permission" to "허용 필요"),
            Str.STATE_NEEDS_MODEL to ("Waiting for model" to "모델 대기"),
            Str.STATE_NO_SPACE to ("Storage full" to "저장 공간 부족"),
            Str.STATE_TOO_SHORT to ("Too short" to "너무 짧음"),
            Str.STATE_WAITING_FOLDER to ("Waiting for folder" to "폴더 대기"),
        )
        words.forEach { (key, word) ->
            assertEquals(word.first, key.message().ledgerStatus(en).label, key.name)
            assertEquals(word.second, key.message().ledgerStatus(ko).label.plain(), key.name)
        }
        assertEquals("Unknown", UiMessage.Text("Weekly meeting").ledgerStatus(en).label)
        assertEquals("알 수 없음", UiMessage.Text("Weekly meeting").ledgerStatus(ko).label.plain())
        // The banner over the ledger says the same words for the same waits.
        assertEquals("Waiting for model", AlertReason.LOCAL_MODEL_REQUIRED.badge().worded(en).label)
        assertEquals("Storage full", AlertReason.NEEDS_SPACE.badge().worded(en).label)
        // A job the queue gave up on says `Failed`, whatever the reason the banner names; the tone stays the banner's.
        listOf(
            AlertReason.MISSING_SECRET, AlertReason.AUTH_REJECTED, AlertReason.QUOTA,
            AlertReason.LOCAL_TRANSCRIPTION_UNAVAILABLE, AlertReason.LOCAL_DIARIZATION_UNAVAILABLE,
        ).forEach { reason ->
            assertEquals("Failed", reason.badge().worded(en).label, reason.name)
            assertEquals("실패", reason.badge().worded(ko).label.plain(), reason.name)
            assertEquals(BadgeTone.WARNING, reason.badge().tone, reason.name)
        }
        // The NEEDS_AUTH sentence is the badge's words too.
        assertEquals("Waiting for Drive", en[Str.STATUS_SIGN_IN_NEEDED])
        assertEquals("Drive 연결 대기", ko[Str.STATUS_SIGN_IN_NEEDED].plain())
        // And the banner's count of them.
        assertEquals("Waiting for Drive: 2", en[Str.ALERT_UPLOADS_WAITING, 2])
        assertEquals("Drive 연결 대기 2건", ko[Str.ALERT_UPLOADS_WAITING, 2].plain())
    }

    /** docs/09: red is failure. A job that is waiting says why in its badge's warning tone. */
    @Test
    fun `only a failure's reason is red`() {
        assertEquals(BadgeTone.DANGER, reasonTone(JobStatus.FAILED))
        listOf(JobStatus.NEEDS_MODEL, JobStatus.NEEDS_CONSENT, JobStatus.NEEDS_AUTH, JobStatus.NEEDS_SPACE, JobStatus.WAITING)
            .forEach { assertEquals(BadgeTone.WARNING, reasonTone(it), "$it") }
    }

    /** A title the user typed is not a state, and it must not be drawn as a blank cell. */
    @Test
    fun `anything that is not a known state is still a code`() {
        assertEquals("UNKNOWN", UiMessage.Text("Weekly meeting").ledgerStatus().code)
        assertEquals("UNKNOWN", Str.UNTITLED.message().ledgerStatus().code)
    }

    /** docs/09: the time column is monospace data, so it is a fixed pattern, not a locale's date. */
    @Test
    fun `the time column is a fixed pattern and the spoken form is the locale's`() {
        val at = "2026-08-27T10:00:00.000Z"

        assertEquals(5, LedgerFormat.date(at).length, "the date column is not MM-dd")
        assertEquals(5, LedgerFormat.time(at).length, "the time column is not HH:mm")
        assertTrue(LedgerFormat.spoken(at, Locale.ENGLISH).contains("2026"))
        assertTrue(LedgerFormat.spoken(at, Locale.KOREAN).contains("2026"))
    }

    /**
     * docs/09 screen principle 2: `HH:mm`, twenty-four hour — a twelve-hour clock is five wide too, and without
     * the day period it writes 15:05 as `03:05`, the same text as 03:05.
     */
    @Test
    fun `the time column is twenty-four hour`() {
        val at = LocalDateTime.of(2026, 2, 9, 15, 5).atZone(ZoneId.systemDefault()).toInstant().toString()

        assertEquals("02-09", LedgerFormat.date(at))
        assertEquals("15:05", LedgerFormat.time(at))
    }

    /** A timestamp the parser cannot read is data too — it is shown, not hidden. */
    @Test
    fun `an unparseable timestamp is shown as it stands`() {
        assertEquals("not a date", LedgerFormat.date("not a date"))
    }

    @Test
    fun `the timer counts hours, minutes and seconds`() {
        assertEquals("00:00:00", LedgerFormat.elapsed(0))
        assertEquals("00:00:59", LedgerFormat.elapsed(59_999))
        assertEquals("01:02:03", LedgerFormat.elapsed(3_723_000))
        // A clock that went backwards is not a negative recording.
        assertEquals("00:00:00", LedgerFormat.elapsed(-5_000))
    }

    /**
     * The UX decisions of 2026-10-08: a drawn time is `MM:SS` under an hour and `HH:MM:SS` from one. A live
     * timer follows itself; inside one recording, the recording's length picks, so every time there has one
     * width; a recording of unknown length uses the time itself.
     */
    @Test
    fun `a drawn time is minutes under the hour and hours from it`() {
        assertEquals("00:12", LedgerFormat.clock(12_000))
        assertEquals("59:59", LedgerFormat.clock(3_599_999))
        assertEquals("01:00:00", LedgerFormat.clock(3_600_000))
        assertEquals("00:00", LedgerFormat.clock(-5_000))
        // Inside a recording an hour long, the first seconds already carry the hours.
        assertEquals("00:00:12", LedgerFormat.clock(12.0, totalSec = 3_700.0))
        assertEquals("02:03", LedgerFormat.clock(123.0, totalSec = 600.0))
        assertEquals("02:03", LedgerFormat.clock(123.0, totalSec = null))
        assertEquals("01:02:03", LedgerFormat.clock(3_723.0, totalSec = null))
    }

    /**
     * docs/09 screen principle 2: the length column, in the shape the phone and the Mac write it — and the
     * placeholder all three use for a recording that has no length yet, which is a cell that says
     * "not in yet" rather than one that lost its value.
     */
    @Test
    fun `the length column is minutes, hours past the hour, and a placeholder until finalized`() {
        assertEquals("00:00", LedgerFormat.length(0.0))
        assertEquals("42:10", LedgerFormat.length(2_530.0))
        assertEquals("01:02:03", LedgerFormat.length(3_723.4))
        assertEquals(LedgerFormat.LONGEST_LENGTH.length, LedgerFormat.length(3_723.4).length)
        assertEquals(LedgerFormat.NO_LENGTH, LedgerFormat.length(null))
        assertEquals(LedgerFormat.NO_LENGTH, LedgerFormat.length(-1.0))
    }

    /** Every answer `Recents.stateLabel` has, driven through it rather than restated here. */
    private fun statesRecentsCanReport(): List<Pair<String, UiMessage>> =
        listOf("RECORDING" to Recents.stateLabel(record(RecordingStatus.RECORDING), null)) +
            listOf("no job" to Recents.stateLabel(record(), null)) +
            // docs/03 "Recordings from other devices": the three another device's work puts on this list.
            listOf(
                "receiving" to Recents.stateLabel(
                    record(RecordingStatus.RECORDING, source = Source.WATCH),
                    null,
                ),
                "remote uploading" to Recents.stateLabel(
                    record(RecordingStatus.RECORDING, remote = true),
                    null,
                ),
                "remote transcribing" to Recents.stateLabel(
                    record(remote = true, remotePending = setOf("transcribe")),
                    null,
                ),
                "remote done" to Recents.stateLabel(record(remote = true), null),
                // docs/03 "Storage location": the upload waiting for the local folder.
                "folder wait" to Recents.stateLabel(
                    record(),
                    job("j", JobStatus.WAITING),
                    CoreMessage.FOLDER_UNAVAILABLE.code(),
                ),
            ) +
            JobStatus.entries.map { status ->
                status.name to Recents.stateLabel(record(), job("j", status))
            }

    private fun record(
        status: RecordingStatus = RecordingStatus.FINALIZED,
        source: Source = Source.DESKTOP,
        remote: Boolean = false,
        remotePending: Set<String> = emptySet(),
    ) = RecordingRecord(
        id = "rec-1",
        meta = RecordingMeta(
            schema = 1,
            recordingId = "rec-1",
            source = source,
            platform = Platform.WINDOWS,
            deviceId = "device",
            deviceName = "PC",
            title = "Weekly meeting",
            startedAt = "2026-08-27T10:00:00.000Z",
            timezone = "Asia/Seoul",
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
            tracks = listOf(Track.MIC, Track.SYS, Track.MIX),
            parts = emptyList(),
            status = status,
        ),
        dir = "/tmp/rec-1".toPath(),
        remote = remote,
        remotePending = remotePending,
    )
}
