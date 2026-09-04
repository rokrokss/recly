package app.recly.windows.ui

import app.recly.windows.i18n.Str

/**
 * docs/12 M8 · ADR-011: a local capture shows the other participants nothing at all, so the
 * responsibility for telling them is the user's and the app's job is to remind them — once, before
 * the recording, and never again once they have said not to. There is no covert mode.
 *
 * **The words are the Mac's**, character for character, in both languages
 * (`RecMac/MenuModel.askAboutConsentIfNeeded` and `RecMac/Localizable.xcstrings`). A user with both
 * machines is being told about the same law by the same product, and a test holds the two texts
 * together (`ConsentTest`).
 */
object Consent {
    val QUESTION: Str = Str.CONSENT_QUESTION

    /** docs/research/02 §동의·법. Not legal advice and not a jurisdiction the app tries to guess. */
    val BODY: Str = Str.CONSENT_BODY

    val CONFIRM: Str = Str.CONSENT_CONFIRM
    val CANCEL: Str = Str.CANCEL
    val SUPPRESS: Str = Str.CONSENT_SUPPRESS

    val LINK_TEXT: Str = Str.CONSENT_LINK

    /** Wikipedia's summary of recording-consent law until Recly has a page of its own to point at. */
    const val LINK: String = "https://en.wikipedia.org/wiki/Telephone_call_recording_laws"
}
