package app.recly.android.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/15 "Policy pages the user opens": the iPhone's `PrivacyLinks.recly`, page for page. */
class PrivacyPolicyLinkTest {

    @Test
    fun `Korean opens the Korean policy, every other language the English one`() {
        assertEquals("https://recly.dev/policy/privacy-policy.ko", privacyPolicyUrl(Locale.KOREA))
        assertEquals("https://recly.dev/policy/privacy-policy", privacyPolicyUrl(Locale.US))
        assertEquals("https://recly.dev/policy/privacy-policy", privacyPolicyUrl(Locale.JAPAN))
    }
}
