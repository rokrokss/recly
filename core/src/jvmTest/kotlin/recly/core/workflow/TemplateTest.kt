package recly.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.TimeZone
import recly.core.model.Source
import recly.core.testing.testMeta

class TemplateTest {
    private val meta = testMeta(
        source = Source.WATCH,
        startedAt = "2026-08-25T22:05:00.000Z",
        timezone = "Asia/Seoul",
        title = "주간 회의",
    )

    @Test
    fun rendersEveryDocumentedVariable() {
        val ctx = TemplateContext.of(meta, workflowName = "회의")
        assertEquals("2026", Template.render("{{yyyy}}", ctx))
        assertEquals("08", Template.render("{{MM}}", ctx))
        assertEquals("26", Template.render("{{dd}}", ctx))
        assertEquals("07", Template.render("{{HH}}", ctx))
        assertEquals("05", Template.render("{{mm}}", ctx))
        assertEquals("주간 회의", Template.render("{{title}}", ctx))
        assertEquals("watch", Template.render("{{source}}", ctx))
        assertEquals("01J9ABCDEF0123456789ABCDEF", Template.render("{{recordingId}}", ctx))
        assertEquals("회의", Template.render("{{workflowName}}", ctx))
        assertEquals("MacBook Pro", Template.render("{{device}}", ctx))
    }

    @Test
    fun rendersTheDefaultFolderTemplate() {
        val ctx = TemplateContext.of(meta, workflowName = "회의")
        assertEquals("recly/2026/2026-08", Template.render("recly/{{yyyy}}/{{yyyy}}-{{MM}}", ctx))
    }

    @Test
    fun clockFieldsFollowTheRecordingTimezone() {
        val seoul = TemplateContext.of(meta, workflowName = "회의")
        val utc = TemplateContext.of(meta, workflowName = "회의", zone = TimeZone.UTC)
        assertEquals("2026-08-26 07:05", Template.render("{{yyyy}}-{{MM}}-{{dd}} {{HH}}:{{mm}}", seoul))
        assertEquals("2026-08-25 22:05", Template.render("{{yyyy}}-{{MM}}-{{dd}} {{HH}}:{{mm}}", utc))
    }

    @Test
    fun titleFallsBackToTheWorkflowName() {
        val ctx = TemplateContext.of(testMeta(title = null), workflowName = "메모")
        assertEquals("메모", Template.render("{{title}}", ctx))
    }

    @Test
    fun replacesPathSeparatorsAndControlCharactersAndTrims() {
        val ctx = TemplateContext.of(
            testMeta(title = "  a/b\\c\td  "),
            workflowName = "회의",
        )
        assertEquals("a_b_c_d", Template.render("{{title}}", ctx))
    }

    @Test
    fun leavesTextAroundVariablesAlone() {
        val ctx = TemplateContext.of(meta, workflowName = "회의")
        assertEquals("a{b}c 2026", Template.render("a{b}c {{yyyy}}", ctx))
    }

    @Test
    fun rejectsUnknownVariables() {
        val ctx = TemplateContext.of(meta, workflowName = "회의")
        val error = assertFailsWith<IllegalArgumentException> { Template.render("recly/{{yyyyy}}", ctx) }
        assertEquals("unknown template variable '{{yyyyy}}'", error.message)
    }
}
