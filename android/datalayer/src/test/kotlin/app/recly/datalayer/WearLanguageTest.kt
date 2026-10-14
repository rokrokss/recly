package app.recly.datalayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The phone app's language, as the watch reads it: a contract both builds link, and untrusted on arrival. */
class WearLanguageTest {

    @Test
    fun `the path sits beside the transfer's and is not a channel path`() {
        assertEquals("/rec/language", WearLanguage.PATH)
        assertNull(TransferPath.parse(WearLanguage.PATH), "the channel grammar must not mistake it for a transfer")
    }

    @Test
    fun `every app language round-trips`() {
        listOf("en", "ko", "ja", "zh-Hans", "zh-Hant", "pt-BR", "pt-PT", "fil", "ar", "th").forEach { tag ->
            assertEquals(tag, WearLanguage.decode(WearLanguage.encode(tag)))
        }
    }

    @Test
    fun `the payload is the tag as UTF-8 and nothing else`() {
        assertEquals("zh-Hans", WearLanguage.encode("zh-Hans").decodeToString())
    }

    @Test
    fun `empty is the system's language`() {
        assertEquals("", WearLanguage.decode(ByteArray(0)))
    }

    @Test
    fun `a payload that is not a tag is no language at all`() {
        listOf("../ko", "ko_KR", "k", "ko-", "{\"tag\":\"ko\"}", "ko,en", "x".repeat(40)).forEach { junk ->
            assertNull(WearLanguage.decode(junk.encodeToByteArray()), junk)
        }
    }
}
