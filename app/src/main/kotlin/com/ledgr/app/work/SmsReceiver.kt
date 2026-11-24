package com.ledgr.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ledgr.app.data.sms.SmsAssembler
import com.ledgr.app.domain.sms.BodyHash

/**
 * `SMS_RECEIVED` (docs/02 §3.2). Assembles multi-part messages and queues one unit of work per message. It reads no
 * database and does no parsing, so it returns immediately; the sender and body are hashed and then dropped.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        for (message in SmsAssembler.assemble(intent)) {
            ScanScheduler.enqueueLive(
                context,
                BodyHash.sha256Hex(message.address),
                message.receivedAt,
                BodyHash.sha256Hex(message.body),
            )
        }
    }
}
