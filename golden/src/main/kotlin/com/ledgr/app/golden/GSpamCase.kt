package com.ledgr.app.golden

import com.ledgr.app.domain.rules.RulesBundle
import com.ledgr.app.domain.sms.Outcome
import com.ledgr.app.domain.sms.SmsInput
import com.ledgr.app.domain.sms.SmsPipeline
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * G-SPAM (docs/04 §1, docs/12 §4): synthetic SMS through the real gate and classifier with the shipped rule
 * files. Each message must land on the expected outcome and reason code, and none may become a transaction.
 */
object GSpamCase : GoldenCase {
    override val id = "G-SPAM"
    override val expectedFile = "g-spam.json"

    @Serializable
    private data class Message(
        val id: String,
        val address: String,
        val body: String,
        /** ISO-8601 with offset, e.g. `2025-11-12T10:00:00+05:30`. */
        val date: String,
    )

    @Serializable
    private data class Fixture(
        /** Headers the owner confirmed in Profile (docs/04 §1 L2/L3). */
        val confirmedHeaders: List<String> = emptyList(),
        val messages: List<Message>,
    )

    @Serializable
    private data class Row(
        val id: String,
        val header: String?,
        val suffix: String,
        val outcome: String,
        val reason: String,
        /** L4 class, or null when the message was stopped before the classifier. */
        val smsClass: String?,
    )

    override fun run(context: GoldenContext): String {
        val fixture = GoldenJson.json.decodeFromString(
            Fixture.serializer(),
            File(context.fixturesDir, "spam/messages.json").readText(Charsets.UTF_8),
        )
        val rulesDir = context.requireRulesDir()
        fun text(name: String) = File(rulesDir, name).readText(Charsets.UTF_8)
        val rules = RulesBundle.parse(text("banks.yaml"), text("spam.yaml"), text("merchants.yaml"), text("categories.yaml"))
        val pipeline = SmsPipeline(rules, fixture.confirmedHeaders.toSet())

        val rows = fixture.messages.map { m ->
            val verdict = pipeline.process(
                SmsInput(m.address, m.body, OffsetDateTime.parse(m.date).toInstant().toEpochMilli()),
            )
            check(verdict.outcome != Outcome.TXN) { "${m.id}: spam gate produced a transaction" }
            Row(
                id = m.id,
                header = verdict.sender.header,
                suffix = verdict.sender.suffix.name,
                outcome = verdict.outcome.name,
                reason = verdict.reason,
                smsClass = verdict.smsClass?.name,
            )
        }
        return GoldenJson.finish(GoldenJson.json.encodeToString(ListSerializer(Row.serializer()), rows))
    }
}
