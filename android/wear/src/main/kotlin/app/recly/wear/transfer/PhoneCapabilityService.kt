package app.recly.wear.transfer

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.WearableListenerService

/**
 * docs/11 W4: "폰 꺼진 채 녹음 → 폰 켜면 자동 전송 완료". The phone declaring `rec_phone` becoming
 * reachable is the one event that turns a queue that could do nothing into one that can, and it is
 * the only trigger that does not need this app to have been running.
 *
 * Play Services starts this process to deliver the callback, so `RecWearApp.onCreate` has run and
 * the queue is loaded by the time the worker gets to it. All this does is ask for a pass — the
 * whole decision about what is worth sending belongs to [TransferSender].
 */
class PhoneCapabilityService : WearableListenerService() {

    override fun onCapabilityChanged(info: CapabilityInfo) {
        // Also fires when the phone goes away, and asking then is harmless: the pass finds no
        // reachable node, returns NO_PHONE and leaves everything where it is.
        TransferScheduler.runNow(applicationContext)
    }
}
