package app.recly.datalayer

/**
 * The phone app's language, for the watch: the watch app shows itself in the language the phone app is in, as
 * the Apple Watch app follows the iPhone app's (docs/07). Without one, the watch follows its own system language.
 *
 * A `DataClient` data item at [PATH], not a message: a data item is kept and synced when the two devices next
 * reach each other, so the watch hears of a change made while it was away, and of the language at all when it
 * is installed after the phone app. The payload is the language tag as UTF-8 — `ko`, `zh-Hans`, `pt-BR` — and
 * nothing else; hand-written, as the acks are.
 *
 * It arrives from another device, so [decode] is a whitelist: a payload that is not tag-shaped is no language
 * at all, and the watch keeps the one it has.
 */
object WearLanguage {

    /** Beside the transfer's `/rec/…` paths — and a data item, so the channel grammar never sees it. */
    const val PATH: String = "/rec/language"

    fun encode(tag: String): ByteArray = tag.encodeToByteArray()

    /** The tag, or null for a payload that is not one. Empty is a tag too: "the system's language". */
    fun decode(payload: ByteArray): String? {
        if (payload.size > MAX_BYTES) return null
        val tag = payload.decodeToString()
        return tag.takeIf { it.isEmpty() || TAG.matches(it) }
    }

    /** BCP 47's longest useful tags are well under this; anything longer is not one of the app's languages. */
    private const val MAX_BYTES = 35

    /** A language subtag and the script or region subtags after it — letters and digits between hyphens. */
    private val TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")
}
