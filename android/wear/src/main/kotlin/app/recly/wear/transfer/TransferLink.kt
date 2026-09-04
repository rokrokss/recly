package app.recly.wear.transfer

import app.recly.datalayer.AckMessage
import okio.Path

/**
 * The Data Layer, as [TransferSender] sees it — `CapabilityClient`, `ChannelClient` and
 * `MessageClient` behind three methods.
 *
 * It is an interface for one reason: everything that can go wrong in a transfer (a phone that
 * appears halfway through, an ack that never comes, a `missing` list for parts the watch has
 * already deleted) is a decision the sender makes, and none of it is testable on a device without
 * a paired phone. The real one is [WearableTransferLink].
 */
interface TransferLink {

    /**
     * A phone that declares `rec_phone` and is reachable now, or null. Opening also starts
     * listening for acks — before the first byte goes out, so an ack cannot arrive before there is
     * anywhere to put it.
     */
    suspend fun open(): TransferChannel?
}

interface TransferChannel {

    /** Opens a channel at [path], sends [file] whole and waits for it to land. Throws if it does not. */
    suspend fun send(path: String, file: Path)

    /** The next ack the phone sends, whatever it is about. Suspends until one arrives. */
    suspend fun nextAck(): AckMessage

    suspend fun close()
}
