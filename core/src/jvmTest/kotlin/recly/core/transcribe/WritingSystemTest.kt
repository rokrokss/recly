package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import recly.core.model.Track

/** docs/08 "Result files" · "Exports": how the writing system joins segments in a line and sizes a subtitle cue. */
class WritingSystemTest {
    private fun transcript(language: String, vararg segments: TranscriptSegment) = Transcript(
        recordingId = "01J9ABCDEF0123456789ABCDEF",
        track = Track.MONO,
        language = language,
        provider = TranscriptProvider("local"),
        createdAt = "2026-11-05T03:10:00.000Z",
        durationSec = 100.0,
        speakers = listOf(TranscriptSpeaker("S1")),
        segments = segments.toList(),
    )

    @Test
    fun `segments join without a space only between Han, Kana and full-width punctuation`() {
        listOf(
            "です。" to "次に" to "",
            "今日は" to "会議です" to "",
            "我们开始吧" to "谢谢" to "",
            "「はい」" to "それで" to "",
            "𠀋" to "中" to "",
            "ｱｲｳ" to "東京" to "",
            "시작합니다." to "네" to " ",
            "시작합니다" to "네" to " ",
            "東京" to "서울" to " ",
            "Hello." to "World" to " ",
            "สวัสดีครับ" to "เริ่มกันเลย" to " ",
            "我们用" to "API" to " ",
            "API" to "接口" to " ",
            "2026" to "年" to " ",
            "！" to "OK" to " ",
            "" to "中" to " ",
        ).forEach { (pair, expected) ->
            assertEquals(expected, WritingSystem.separator(pair.first, pair.second), "${pair.first}|${pair.second}")
        }
    }

    @Test
    fun `the text file joins a line's segments the way its language is written`() {
        fun line(language: String, first: String, second: String) =
            TranscriptNormalizer.text(transcript(language, TranscriptSegment(1.0, 2.0, "S1", first), TranscriptSegment(2.5, 4.0, "S1", " $second ")))

        assertEquals("[00:00:01] S1: 本日はありがとうございます。それでは始めます。\n", line("ja", "本日はありがとうございます。", "それでは始めます。"))
        assertEquals("[00:00:01] S1: 我们开始吧。第一个议题是预算。\n", line("zh-CN", "我们开始吧。", "第一个议题是预算。"))
        assertEquals("[00:00:01] S1: 我們開始吧。第一個議題是預算。\n", line("zh-TW", "我們開始吧。", "第一個議題是預算。"))
        assertEquals("[00:00:01] S1: 시작하겠습니다. 네, 보시죠.\n", line("ko", "시작하겠습니다.", "네, 보시죠."))
        assertEquals("[00:00:01] S1: Let's begin. First item.\n", line("en", "Let's begin.", "First item."))
        assertEquals("[00:00:01] S1: สวัสดีครับ เริ่มกันเลย\n", line("th", "สวัสดีครับ", "เริ่มกันเลย"))
        assertEquals("[00:00:01] S1: 我们用 API 接口\n", line("zh-CN", "我们用", "API 接口"))
        assertEquals("[00:00:01] S1: 会議は Zoom で\n", line("ja", "会議は", "Zoom で"))
    }

    @Test
    fun `a cue is measured in half-width units and limited by its writing system`() {
        assertEquals(8, WritingSystem.cueWidth("회의 API"))
        assertEquals(3, WritingSystem.cueWidth("ｱｲｳ"))
        assertEquals(8, WritingSystem.cueWidth("（はい）"))
        assertEquals(11, WritingSystem.cueWidth("Hello world"))
        assertEquals(2, WritingSystem.cueWidth("𠀋"))

        assertEquals(64, WritingSystem.cueLimit("회의에서 API 키를 바꾸기로 했습니다"))
        assertEquals(64, WritingSystem.cueLimit("我们明天见"))
        assertEquals(64, WritingSystem.cueLimit("我們明天見"))
        assertEquals(52, WritingSystem.cueLimit("東京で会いましょう"))
        assertEquals(84, WritingSystem.cueLimit("We met at 東京 station yesterday"))
        assertEquals(84, WritingSystem.cueLimit("สวัสดีครับ"))
        assertEquals(84, WritingSystem.cueLimit("Hello"))
    }

    /** The first cue's text of one 6-second segment with word timings, cut by length alone. */
    private fun firstCue(language: String, words: List<String>, separator: String): String {
        val step = 6.0 / words.size
        val timed = words.mapIndexed { i, word -> TranscriptWord(i * step, (i + 1) * step, word) }
        val segment = TranscriptSegment(0.0, 6.0, "S1", words.joinToString(separator), timed)
        return TranscriptNormalizer.vtt(transcript(language, segment)).split("\n")[3].removePrefix("S1: ")
    }

    @Test
    fun `Korean and Chinese cues hold two lines of 16 characters, Japanese of 13, Latin 84 characters`() {
        // 28 syllables and 6 half-width spaces: 31 characters.
        assertEquals(List(7) { "가나다라" }.joinToString(" "), firstCue("ko", List(10) { "가나다라" }, " "))
        assertEquals("我们".repeat(16), firstCue("zh-CN", List(20) { "我们" }, ""))
        assertEquals("ですね".repeat(8), firstCue("ja", List(10) { "ですね" }, ""))
        assertEquals(List(17) { "abcd" }.joinToString(" "), firstCue("en", List(20) { "abcd" }, " "))
    }
}
