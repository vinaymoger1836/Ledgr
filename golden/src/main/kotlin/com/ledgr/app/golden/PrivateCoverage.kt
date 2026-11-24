package com.ledgr.app.golden

import com.ledgr.app.domain.rules.RulesBundle
import com.ledgr.app.domain.sms.Outcome
import com.ledgr.app.domain.sms.SmsClass
import com.ledgr.app.domain.sms.SmsInput
import com.ledgr.app.domain.sms.SmsPipeline
import com.ledgr.app.domain.template.TemplateEngine
import com.ledgr.app.domain.template.TemplateStageImpl
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * docs/12 §4(c): runs the owner's own SMS (the JSON files in `golden/private/sms`, git-ignored) through the real pipeline and
 * reports coverage. It never asserts, and it never prints message text, amounts or digits: only file names, headers,
 * classes and reason codes. The same report is written to `golden/private/coverage-report.txt`.
 *
 * "Bank transaction SMS" means messages that passed the gate as a transaction candidate (amount plus a debit or credit
 * verb). Coverage is the share of those that a template turned into a transaction. Accounts are not known here, so the
 * account match (L6) is permissive.
 */
internal object PrivateCoverage {

    fun report(privateDir: File, rules: RulesBundle) {
        val smsDir = File(privateDir, "sms")
        val files = smsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) {
            println("[info] golden/private/sms has no *.json files: private coverage not measured")
            return
        }
        val pipeline = SmsPipeline(
            rules, emptySet(),
            TemplateStageImpl(TemplateEngine(rules.templates), accounts = emptyList(), permissiveAccounts = true),
        )

        val byOutcome = sortedMapOf<String, Int>()
        val byReason = sortedMapOf<String, Int>()
        val byTemplate = sortedMapOf<String, Int>()
        val misses = ArrayList<String>()
        val unreadable = ArrayList<String>()
        var candidates = 0
        var parsed = 0

        for (f in files) {
            val sms = try {
                read(f)
            } catch (_: Exception) {
                unreadable += f.name
                continue
            }
            val v = pipeline.process(sms)
            byOutcome.merge(v.outcome.name, 1, Int::plus)
            byReason.merge(v.code, 1, Int::plus)
            val bankTxn = v.smsClass == SmsClass.TXN_CANDIDATE &&
                (v.outcome == Outcome.TXN || v.outcome == Outcome.REVIEW)
            if (bankTxn) {
                candidates++
                if (v.outcome == Outcome.TXN) {
                    parsed++
                    v.templateId?.let { byTemplate.merge(it, 1, Int::plus) }
                } else {
                    misses += "${f.name}  header=${v.sender.header ?: "-"}  ${v.code}"
                }
            }
        }

        val percent = if (candidates == 0) "n/a" else "%.1f%%".format(parsed * 100.0 / candidates)
        val lines = buildList {
            add("PRIVATE COVERAGE: ${files.size} message file(s), ${unreadable.size} unreadable")
            add("  bank transaction SMS (passed the gate as a transaction): $candidates")
            add("  parsed by a template: $parsed  ->  coverage $percent (target >= 90%)")
            add("  by outcome: " + byOutcome.entries.joinToString { "${it.key}=${it.value}" })
            add("  by reason code:")
            byReason.forEach { (k, n) -> add("    $k: $n") }
            add("  by template:")
            byTemplate.forEach { (k, n) -> add("    $k: $n") }
            add("  misses (${misses.size}): file, header, reason; no message text is printed")
            misses.take(MAX_MISSES).forEach { add("    $it") }
            if (misses.size > MAX_MISSES) add("    ... ${misses.size - MAX_MISSES} more in coverage-report.txt")
            unreadable.forEach { add("  unreadable: $it") }
        }
        lines.take(REPORT_ECHO).forEach(::println)
        File(privateDir, "coverage-report.txt").writeText(
            (lines + misses.drop(MAX_MISSES).map { "    $it" }).joinToString("\n") + "\n",
            Charsets.UTF_8,
        )
    }

    /** `{ "address": "...", "body": "...", "date": "<ISO-8601 with offset | local date-time (IST) | epoch ms>" }`. */
    private fun read(f: File): SmsInput {
        val o: JsonObject = Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull ?: error("missing $key")
        val date = o["date"] as? JsonPrimitive ?: error("missing date")
        val at = date.longOrNull ?: runCatching { OffsetDateTime.parse(date.content).toInstant().toEpochMilli() }.getOrElse {
            LocalDateTime.parse(date.content).atZone(com.ledgr.app.domain.time.Ist.ZONE).toInstant().toEpochMilli()
        }
        return SmsInput(str("address"), str("body"), at)
    }

    private const val MAX_MISSES = 60
    private const val REPORT_ECHO = 40
}
