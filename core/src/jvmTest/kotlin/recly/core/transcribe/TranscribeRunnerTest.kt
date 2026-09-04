@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import recly.core.job.StepFailure
import recly.core.job.StepOutcome
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language
import recly.core.model.Speakers
import recly.core.model.Track

class TranscribeRunnerTest {
    private fun jsonName(h: TranscribeHarness) = TranscribeRunner.jsonFileName(h.base)

    private fun textName(h: TranscribeHarness) = TranscribeRunner.textFileName(h.base)

    @Test
    fun `the first pass submits the joined audio and waits without finishing`() = runBlocking {
        val h = TranscribeHarness(partCount = 2)

        val outcome = h.run(h.transcribeStep())

        val waiting = assertIs<StepOutcome.Waiting>(outcome)
        assertEquals(30, waiting.retryAfterSec)
        assertEquals("t-0001", waiting.state["ref"]!!.jsonPrimitive.content)
        assertEquals("mono", waiting.state["track"]!!.jsonPrimitive.content)
        assertEquals(listOf("aai-key"), h.stt.requests.map { it.headers["authorization"] }.distinct())
        // Two parts: the shell's muxer joined them, and the temp file did not survive the step.
        assertEquals(1, h.audio.calls.size)
        assertEquals(listOf(h.meta.parts[0].file, h.meta.parts[1].file), h.audio.calls.single().map { it.name })
        assertEquals("audio-1-MONOaudio-2-MONO", h.stt.uploaded().text)
        assertFalse(h.fs.list(h.dir).any { it.name.endsWith(".concat.m4a") }, "the temp file is deleted")
    }

    @Test
    fun `a single-part recording is uploaded as it is, without a remux`() = runBlocking {
        val h = TranscribeHarness(partCount = 1)

        h.run(h.transcribeStep())

        assertEquals(emptyList(), h.audio.calls, "one part is already the file the provider needs")
        assertEquals("audio-1-MONO", h.stt.uploaded().text)
    }

    @Test
    fun `the submit body carries the pinned model, the language and the diarization hints`() = runBlocking {
        val h = TranscribeHarness(participants = 4)

        h.run(h.transcribeStep(speakers = Speakers(min = 2, max = 6)))

        val body = h.stt.submitted().json()
        assertEquals(listOf("universal-2"), body["speech_models"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(h.stt.uploadUrl, body["audio_url"]!!.jsonPrimitive.content)
        assertEquals("ko", body["language_code"]!!.jsonPrimitive.content)
        assertTrue(body["speaker_labels"]!!.jsonPrimitive.boolean)
        // The recording knew there were four people; the workflow only guessed 2..6.
        assertEquals(4, body["speakers_expected"]!!.jsonPrimitive.int)
    }

    @Test
    fun `without participants an exact speaker count is sent and a range is not`() = runBlocking {
        val exact = TranscribeHarness()
        exact.run(exact.transcribeStep(speakers = Speakers(min = 3, max = 3)))
        assertEquals(3, exact.stt.submitted().json()["speakers_expected"]!!.jsonPrimitive.int)

        val range = TranscribeHarness()
        range.run(range.transcribeStep(speakers = Speakers(min = 2, max = 6)))
        assertFalse(range.stt.submitted().json().containsKey("speakers_expected"))
    }

    @Test
    fun `language auto asks the provider to detect instead of naming one`() = runBlocking {
        val h = TranscribeHarness()

        h.run(h.transcribeStep().copy(language = Language.AUTO))

        val body = h.stt.submitted().json()
        assertTrue(body["language_detection"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("language_code"))
    }

    @Test
    fun `a later pass polls the same job and writes both files locally and to Drive`() = runBlocking {
        val h = TranscribeHarness()
        val step = h.transcribeStep()

        val output = h.runToDone(step)

        // One submit, then polls — never a second upload.
        assertEquals(1, h.stt.requests.count { it.path == "/v2/upload" })
        assertEquals(listOf("/v2/transcript/t-0001", "/v2/transcript/t-0001"), h.stt.polls().map { it.path })

        val transcript = Json.parseToJsonElement(h.localContent(jsonName(h))).jsonObject
        assertEquals(1, transcript["schema"]!!.jsonPrimitive.int)
        assertEquals("mono", transcript["track"]!!.jsonPrimitive.content)
        assertEquals("t-0001", transcript["provider"]!!.jsonObject["jobRef"]!!.jsonPrimitive.content)
        assertEquals(listOf("S1", "S2"), transcript["speakers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(h.localContent(jsonName(h)), h.driveContent(jsonName(h)))
        assertEquals(h.localContent(textName(h)), h.driveContent(textName(h)))
        assertEquals(
            "[00:00:00] S1: 안녕하세요\n[00:00:03] S2: 반갑습니다\n[00:15:10] S1: 두 번째 파트\n",
            h.localContent(textName(h)),
        )

        val summary = output.json["transcript"]!!.jsonObject
        assertEquals(h.drive.idOf(jsonName(h)), summary["jsonFileId"]!!.jsonPrimitive.content)
        assertEquals(h.drive.idOf(textName(h)), summary["txtFileId"]!!.jsonPrimitive.content)
        assertEquals("ko", summary["language"]!!.jsonPrimitive.content)
        assertEquals(2, summary["speakerCount"]!!.jsonPrimitive.int)
        assertEquals("assemblyai", summary["provider"]!!.jsonPrimitive.content)
        assertEquals("universal-2", summary["model"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("transcript", "transcript"),
            output.json["files"]!!.jsonArray.map { it.jsonObject["track"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `re-running skips an identical Drive file and overwrites a changed one`() = runBlocking {
        val h = TranscribeHarness()
        h.runToDone(h.transcribeStep())
        val firstJsonId = h.drive.idOf(jsonName(h))
        val uploadsAfterFirst = h.drive.uploadOrder().size

        // Same result, same bytes: nothing is written a second time.
        h.state = null
        h.stt.pendingPolls = 0
        h.runToDone(h.transcribeStep())
        assertEquals(uploadsAfterFirst, h.drive.uploadOrder().size, "an identical file is skipped")
        assertEquals(firstJsonId, h.drive.idOf(jsonName(h)), "and keeps its id")

        // A different result overwrites the file in place, so the newest run is canonical.
        h.state = null
        h.stt.completed = """{"id":"t-0001","status":"completed","language_code":"ko","audio_duration":10.0,
            "utterances":[{"start":0,"end":1000,"speaker":"A","text":"다시 돌렸습니다"}]}"""
        h.runToDone(h.transcribeStep())
        assertEquals(firstJsonId, h.drive.idOf(jsonName(h)), "overwritten, not duplicated")
        assertContains(h.driveContent(jsonName(h))!!, "다시 돌렸습니다")
        assertEquals(h.localContent(jsonName(h)), h.driveContent(jsonName(h)))
    }

    @Test
    fun `mix is used when there is no mono track, and neither is NO_INPUT_TRACK`() = runBlocking {
        val mix = TranscribeHarness(tracks = listOf(Track.MIX))
        mix.run(mix.transcribeStep())
        assertEquals("mix", (mix.state!!["track"])!!.jsonPrimitive.content)

        val neither = TranscribeHarness(tracks = listOf(Track.MIC, Track.SYS))
        val failure = assertFailsWith<StepFailure> { neither.run(neither.transcribeStep()) }
        assertContains(failure.reason, "NO_INPUT_TRACK")
        assertFalse(failure.retryable)
    }

    @Test
    fun `a provider this build cannot run fails without spending the audio`() = runBlocking {
        val h = TranscribeHarness()

        val failure = assertFailsWith<StepFailure> { h.run(h.transcribeStep(provider = "rtzr")) }

        assertFalse(failure.retryable)
        assertEquals(emptyList(), h.stt.requests)
    }

    @Test
    fun `a secret this device does not have is terminal`() = runBlocking {
        val h = TranscribeHarness()
        h.secrets.entries.clear()

        val failure = assertFailsWith<StepFailure> { h.run(h.transcribeStep()) }

        // docs/07 §5: the argument is the `secretRef` itself, or the code does not parse as a key.
        assertEquals(CoreMessageRef(CoreMessage.MISSING_SECRET, STT_KEY), CoreMessageRef.parse(failure.reason))
        assertFalse(failure.retryable)
    }

    @Test
    fun `a submission that never finishes gives up after the provider's timeout and submits again`() = runBlocking {
        val h = TranscribeHarness()
        h.stt.pendingPolls = 100
        val step = h.transcribeStep()
        h.run(step)

        // `assemblyai` declares nothing, so it gets the default two hours.
        h.clock.advance(2.hours)
        h.run(step)
        h.clock.advance(1.hours)

        val failure = assertFailsWith<StepFailure> { h.run(step) }
        assertContains(failure.reason, "RESULT_TIMEOUT")
        assertTrue(failure.retryable, "a retry submits the audio again")

        // And it really does: the dead ref is gone from the state, so the retry re-submits rather
        // than polling a job that will never answer.
        assertEquals(null, h.state!!["ref"])
        h.stt.transcriptId = "t-0002"
        val retry = assertIs<StepOutcome.Waiting>(h.run(step))
        assertEquals("t-0002", retry.state["ref"]!!.jsonPrimitive.content)
        assertEquals(2, h.submits())
    }

    /**
     * `rev` documents up to six hours of turnaround, so the default two would drop a live job and
     * pay for the same audio a second time (docs/08 "폴링 · 상태").
     */
    @Test
    fun `a provider that declares a longer timeout is still waited on past the default two hours`() = runBlocking {
        val provider = PatientProvider()
        val h = TranscribeHarness(providers = { provider })
        val step = h.transcribeStep(provider = PatientProvider.NAME)
        h.run(step)

        h.clock.advance(3.hours)

        val waiting = assertIs<StepOutcome.Waiting>(h.run(step))
        assertEquals("p-0001", waiting.state["ref"]!!.jsonPrimitive.content, "the submission is still alive")
    }

    @Test
    fun `a file over the provider's byte ceiling fails before anything is uploaded`() = runBlocking {
        val h = TranscribeHarness(partCount = 1, partBytes = 30 * 1024 * 1024, extraSttHost = OPENAI_HOST)

        val failure = assertFailsWith<StepFailure> { h.run(h.transcribeStep(provider = "openai")) }

        assertContains(failure.reason, "UNSUPPORTED_AUDIO")
        assertContains(failure.reason, "30 MB exceeds openai's 25 MB")
        assertFalse(failure.retryable)
        assertEquals(emptyList(), h.other.requests, "the bytes never left the device")
    }

    @Test
    fun `audio longer than the provider takes fails before anything is uploaded`() = runBlocking {
        // Nine 900-second parts: 2h 15m of audio for a sync call that stops at two hours.
        val h = TranscribeHarness(partCount = 9, extraSttHost = CLOVA_HOST)
        val step = h.transcribeStep(provider = ClovaProvider.NAME, invokeUrl = "$CLOVA_HOST/external/v1/1/a")

        val failure = assertFailsWith<StepFailure> { h.run(step) }

        assertContains(failure.reason, "UNSUPPORTED_AUDIO")
        assertContains(failure.reason, "2h 15m exceeds clova's 2h")
        assertFalse(failure.retryable)
        assertEquals(emptyList(), h.other.requests, "the bytes never left the device")
        assertFalse(h.fs.list(h.dir).any { it.name.endsWith(".concat.m4a") }, "the joined file is still deleted")
    }

    @Test
    fun `audio exactly at the ceiling is submitted`() = runBlocking {
        // Eight parts: 7200 seconds, which is clova's two hours to the second.
        val h = TranscribeHarness(partCount = 8, extraSttHost = CLOVA_HOST)
        h.other.reply(CLOVA_COMPLETED)
        val step = h.transcribeStep(provider = ClovaProvider.NAME, invokeUrl = "$CLOVA_HOST/external/v1/1/a")

        assertIs<StepOutcome.Done>(h.run(step))

        assertEquals(1, h.other.requests.size, "the audio went out")
    }

    @Test
    fun `the docs 08 error table maps every provider answer`() = runBlocking {
        suspend fun failure(status: Int, headers: Map<String, String> = emptyMap(), body: String = ""): StepFailure {
            val h = TranscribeHarness()
            h.stt.failNext(status, body = body, headers = headers) { it.path == "/v2/upload" }
            return assertFailsWith { h.run(h.transcribeStep()) }
        }

        assertContains(failure(401).reason, "AUTH_REJECTED")
        assertFalse(failure(401).retryable)
        assertContains(failure(403).reason, "AUTH_REJECTED")
        assertContains(failure(402).reason, "QUOTA")
        assertTrue(failure(402).retryable)
        val quota = failure(429, headers = mapOf("Retry-After" to "120"))
        assertContains(quota.reason, "QUOTA")
        assertEquals(120L, quota.retryAfterSec)
        assertContains(failure(503).reason, "PROVIDER_ERROR")
        assertTrue(failure(503).retryable)
        val rejected = failure(400, body = "unsupported media")
        assertContains(rejected.reason, "UNSUPPORTED_AUDIO")
        assertFalse(rejected.retryable)
    }

    @Test
    fun `a provider error status keeps the message, stays retryable and drops the dead ref`() = runBlocking {
        val h = TranscribeHarness()
        h.stt.pendingPolls = 0
        h.stt.completed = """{"id":"t-0001","status":"error","error":"transcoding failed"}"""
        val step = h.transcribeStep()
        h.run(step)

        val failure = assertFailsWith<StepFailure> { h.run(step) }

        assertContains(failure.reason, "PROVIDER_ERROR")
        assertContains(failure.reason, "transcoding failed")
        assertTrue(failure.retryable)

        assertEquals(null, h.state!!["ref"], "a failed job is not worth polling again")
        h.stt.transcriptId = "t-0002"
        h.stt.completed = FakeStt.TWO_SPEAKERS
        val retry = assertIs<StepOutcome.Waiting>(h.run(step))
        assertEquals("t-0002", retry.state["ref"]!!.jsonPrimitive.content)
        assertEquals(2, h.submits(), "the audio is submitted again, not polled")
    }

    /** The other half of the rule: a transport hiccup leaves the submission alone. */
    @Test
    fun `a transient failure while polling keeps the ref and polls it again`() = runBlocking {
        val h = TranscribeHarness()
        val step = h.transcribeStep()
        h.run(step)
        h.stt.failNext(503) { it.method == "GET" }

        val failure = assertFailsWith<StepFailure> { h.run(step) }

        assertContains(failure.reason, "PROVIDER_ERROR")
        assertTrue(failure.retryable)
        assertEquals("t-0001", h.state!!["ref"]!!.jsonPrimitive.content, "the submission is still alive")

        h.stt.pendingPolls = 0
        h.runToDone(step)

        assertEquals(1, h.submits(), "no second upload, no second submit")
        assertEquals(1, h.stt.requests.count { it.path == "/v2/upload" })
        assertEquals(
            "t-0001",
            Json.parseToJsonElement(h.localContent(jsonName(h)))
                .jsonObject["provider"]!!.jsonObject["jobRef"]!!.jsonPrimitive.content,
            "the result came from the submission that was already in flight",
        )
    }

    @Test
    fun `a dropped connection is a retryable provider error`() = runBlocking {
        val h = TranscribeHarness()
        h.stt.networkFailure = true

        val failure = assertFailsWith<StepFailure> { h.run(h.transcribeStep()) }

        assertTrue(failure.retryable)
        assertContains(failure.reason, "PROVIDER_ERROR")
    }

    /**
     * docs/08 `clova`: the submission *is* the result, so the step ends on the pass that made it —
     * no `Waiting`, no ref, and nothing to poll.
     */
    @Test
    fun `a synchronous provider finishes on the pass that submitted`() = runBlocking {
        val h = TranscribeHarness(extraSttHost = CLOVA_HOST)
        h.other.reply(CLOVA_COMPLETED)
        val step = h.transcribeStep(provider = ClovaProvider.NAME, invokeUrl = "$CLOVA_HOST/external/v1/1/a")

        val outcome = h.run(step)

        val done = assertIs<StepOutcome.Done>(outcome)
        assertEquals(1, h.other.requests.size, "one upload and no poll")
        assertEquals("$CLOVA_HOST/external/v1/1/a/recognizer/upload", h.other.request(0).url)
        assertEquals("clova", done.output.json["transcript"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
        val transcript = Json.parseToJsonElement(h.driveContent(jsonName(h))!!).jsonObject
        assertEquals(2, transcript["segments"]!!.jsonArray.size)
        // There was never a ref, so the published `jobRef` has nothing to say.
        assertFalse("jobRef" in transcript["provider"]!!.jsonObject, "a sync provider has no job to point at")
        assertEquals(null, h.state!!["ref"], "nothing is left to poll")
    }

    /**
     * A provider whose response carries no timings — OpenAI's plain `json` — has nothing but this
     * to put on the segment it produces (docs/08).
     */
    @Test
    fun `the provider is told how long the audio it was handed is`() = runBlocking {
        val provider = CapturingProvider()
        // Two 900-second parts: the joined file lasts 1800 s, not the recording's 2700 s of clock.
        val h = TranscribeHarness(partCount = 2, providers = { provider })

        h.runToDone(h.transcribeStep(provider = CapturingProvider.NAME))

        assertEquals(1800.0, provider.audioDurationSec)
    }

    private companion object {
        const val CLOVA_HOST = "https://clovaspeech-gw.ncloud.com"
        const val OPENAI_HOST = "https://api.openai.com"
        const val CLOVA_COMPLETED = """
            {"result":"COMPLETED","message":"Succeeded",
             "segments":[
               {"start":0,"end":3200,"text":"안녕하세요","speaker":{"label":"A"}},
               {"start":905000,"end":906800,"text":"두 번째 파트","speaker":{"label":"B"}}
             ]}
        """
    }
}

/** Answers with a transcript at once and keeps the context it was called with. */
private class CapturingProvider : SttProvider {
    override val name: String = NAME

    var audioDurationSec: Double? = null

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        audioDurationSec = ctx.audioDurationSec
        return Submitted.Finished(
            SttResult(
                segments = listOf(SttSegment(0.0, 1.0, null, "안녕하세요")),
                language = "ko",
                durationSec = 1.0,
                model = null,
            ),
        )
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult = error("never polled")

    companion object {
        const val NAME = "capture"
    }
}

/** An asynchronous provider that never finishes and asks to be waited on for a long day. */
private class PatientProvider : SttProvider {
    override val name: String = NAME
    override val resultTimeout: Duration = 8.hours

    override suspend fun submit(ctx: SttContext, file: Path): Submitted = Submitted.Polling("p-0001")

    override suspend fun poll(ctx: SttContext, ref: String): PollResult = PollResult.Pending

    companion object {
        const val NAME = "patient"
    }
}
