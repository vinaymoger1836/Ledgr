package com.ledgr.app.data.sms

import android.content.Intent
import android.provider.Telephony

/** A message just received by the broadcast, with its parts joined. */
data class LiveSms(
    val address: String,
    val body: String,
    /** When this device received it (epoch ms). */
    val receivedAt: Long,
)

/**
 * Joins the parts of a multi-part SMS from one `SMS_RECEIVED` broadcast (docs/02 §3.2). Parts from the same
 * sender are concatenated in the order they arrive; different senders in one broadcast stay separate.
 */
object SmsAssembler {
    fun assemble(intent: Intent, now: Long = System.currentTimeMillis()): List<LiveSms> {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return emptyList()
        val bodies = LinkedHashMap<String, StringBuilder>()
        for (part in parts) {
            val address = part?.originatingAddress ?: continue
            bodies.getOrPut(address) { StringBuilder() }.append(part.messageBody.orEmpty())
        }
        return bodies.map { (address, body) -> LiveSms(address, body.toString(), now) }
    }
}
