package com.ledgr.app.golden

import com.ledgr.app.domain.ledger.AliasTarget
import com.ledgr.app.domain.ledger.EditableField
import com.ledgr.app.domain.ledger.IngestInput
import com.ledgr.app.domain.ledger.IngestPlan
import com.ledgr.app.domain.ledger.LedgerContext
import com.ledgr.app.domain.ledger.LedgerEngine
import com.ledgr.app.domain.ledger.LedgerRules
import com.ledgr.app.domain.ledger.LedgerTxn
import com.ledgr.app.domain.ledger.LedgerView
import com.ledgr.app.domain.ledger.MerchantContext
import com.ledgr.app.domain.ledger.NEW
import com.ledgr.app.domain.ledger.TxnPatch
import com.ledgr.app.domain.ledger.TxnStatus
import com.ledgr.app.domain.model.AccountType
import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.profile.AliasKey
import com.ledgr.app.domain.rules.RulesBundle
import com.ledgr.app.domain.sms.Outcome
import com.ledgr.app.domain.sms.SmsInput
import com.ledgr.app.domain.sms.SmsPipeline
import com.ledgr.app.domain.template.AccountRef
import com.ledgr.app.domain.template.TemplateEngine
import com.ledgr.app.domain.template.TemplateStageImpl
import com.ledgr.app.domain.time.Ist
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable

/** Loads the shipped rule files, plus the synthetic templates the golden cases use (never shipped in the app). */
internal object GoldenRules {
    /**
     * All rule files. Templates come from the shipped `rules/templates` (if any) and, unless
     * [includeSyntheticTemplates] is false, from `fixtures/parse/rules/templates`. Private-data coverage turns the
     * synthetic ones off so that only real, shipped templates count towards the number.
     */
    fun load(context: GoldenContext, includeSyntheticTemplates: Boolean = true): RulesBundle {
        val rulesDir = context.requireRulesDir()
        fun text(name: String) = File(rulesDir, name).readText(Charsets.UTF_8)
        val templates = LinkedHashMap<String, String>()
        val dirs = listOfNotNull(
            File(rulesDir, "templates"),
            File(context.fixturesDir, "parse/rules/templates").takeIf { includeSyntheticTemplates },
        )
        for (dir in dirs) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".yaml") }.orEmpty().sortedBy { it.name }.forEach {
                templates[it.path] = it.readText(Charsets.UTF_8)
            }
        }
        return RulesBundle.parse(
            text("banks.yaml"), text("spam.yaml"), text("merchants.yaml"), text("categories.yaml"),
            ledgerYaml = text("ledger.yaml"),
            templateYamls = templates,
        )
    }
}

// ───────────────────────────── the profile a case runs against ─────────────────────────────

@Serializable
internal data class FxAccount(val id: Long, val bank: String, val type: AccountType, val last4: String)

@Serializable
internal data class FxAlias(
    val raw: String,
    val name: String,
    val merchantKey: String? = null,
    val type: CounterpartyType = CounterpartyType.MERCHANT,
)

@Serializable
internal data class FxProfile(
    val accounts: List<FxAccount> = emptyList(),
    val ownUpiIds: List<String> = emptyList(),
    val ownNames: List<String> = emptyList(),
    val aliases: List<FxAlias> = emptyList(),
) {
    fun accountRefs(): List<AccountRef> = accounts.map { AccountRef(it.id, it.bank, it.type, it.last4) }

    fun merchantContext(rules: RulesBundle): MerchantContext = MerchantContext(
        aliases = aliases.withIndex().associate { (i, a) ->
            (AliasKey.normalize(a.raw) ?: a.raw) to AliasTarget(ALIAS_ID_BASE + i, a.merchantKey, a.name, a.type)
        },
        ownUpiIds = ownUpiIds.map { it.lowercase() }.toSet(),
        ownNames = ownNames.mapNotNull { AliasKey.normalize(it) }.toSet(),
        merchants = rules.merchants.merchants,
        aggregators = rules.merchants.aggregators,
        ledger = rules.ledger,
    )

    private companion object {
        const val ALIAS_ID_BASE = 1000L
    }
}

@Serializable
internal data class FxMessage(val id: String, val address: String, val body: String, val date: String) {
    fun toSms() = SmsInput(address, body, OffsetDateTime.parse(date).toInstant().toEpochMilli())
}

internal fun isoIst(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).atZone(Ist.ZONE).toOffsetDateTime().toString()

// ───────────────────────────── an in-memory ledger ─────────────────────────────

/** What the sim remembers about a transaction beyond the engine's [LedgerTxn]. */
internal class SimRow(val label: String, var txn: LedgerTxn, var checkReason: String?, val displayName: String)

/** One stored proposal. */
internal class SimProposal(val type: String, val a: Long, val b: Long)

/**
 * The ledger the device would build, held in memory: SMS go through the real pipeline (gate, templates) and the real
 * [LedgerEngine], and the returned plan is applied the way `LedgerSession` applies it to the database.
 */
internal class LedgerSim(private val rules: RulesBundle, private val profile: FxProfile) {
    private val accounts = profile.accountRefs()
    private val pipeline = SmsPipeline(rules, emptySet(), TemplateStageImpl(TemplateEngine(rules.templates), accounts))
    private val engine = LedgerEngine(LedgerContext(profile.merchantContext(rules), accounts, LedgerContext.duplicatePairs(rules)))
    private var nextId = 1L
    private var nextRaw = 1L

    val rows = LinkedHashMap<Long, SimRow>()
    val proposals = ArrayList<SimProposal>()

    /** Feeds one message in. Returns the verdict code and the transaction id it produced (if any). */
    fun receive(m: FxMessage): Pair<String, Long?> {
        val sms = m.toSms()
        val verdict = pipeline.process(sms)
        val parsed = verdict.parsed
        if (verdict.outcome != Outcome.TXN || parsed == null) return verdict.code to null
        val plan = engine.plan(IngestInput(parsed, m.body, nextRaw++, rules.version), rows.values.map { it.txn })
        return verdict.code to apply(m.id, plan)
    }

    private fun apply(label: String, plan: IngestPlan): Long {
        val d = plan.draft
        val id = nextId++
        rows[id] = SimRow(
            label = label,
            txn = LedgerTxn(
                id = id, accountId = d.accountId, kind = d.kind, direction = d.direction, amount = d.amount,
                occurredAt = d.occurredAt, channel = d.channel, counterpartyKey = d.resolution.identityKey,
                categoryKey = d.categoryKey, refNo = d.refNo, balanceAfter = d.balanceAfter, templateId = d.templateId,
                rawCounterparty = d.rawCounterparty, status = d.status, duplicateOf = d.duplicateOf,
                transferPairId = d.transferPairId, refundOf = d.refundOf,
            ),
            checkReason = d.checkReason,
            displayName = d.resolution.displayName,
        )
        fun sub(x: Long?): Long? = if (x == NEW) id else x
        for (p in plan.patches) {
            rows[p.id]?.let {
                it.txn = patched(it.txn, p, ::sub)
                // As `LedgerSession` does: a row whose kind or status was settled by a link has nothing left to check.
                if (p.kind != null || p.status != null) it.checkReason = null
            }
        }
        for (p in plan.proposals) proposals += SimProposal(p.type.name, sub(p.txnId)!!, sub(p.otherId)!!)
        return id
    }

    private fun patched(t: LedgerTxn, p: TxnPatch, sub: (Long?) -> Long?): LedgerTxn {
        var u = t
        p.kind?.let { if (EditableField.KIND !in t.userEdited) u = u.copy(kind = it) }
        p.categoryKey?.let { if (EditableField.CATEGORY !in t.userEdited) u = u.copy(categoryKey = it) }
        p.status?.let { u = u.copy(status = it) }
        p.duplicateOf?.let { u = u.copy(duplicateOf = sub(it)) }
        p.transferPairId?.let { u = u.copy(transferPairId = sub(it)) }
        p.refundOf?.let { u = u.copy(refundOf = sub(it)) }
        p.refNo?.let { u = u.copy(refNo = it) }
        p.balanceAfter?.let { u = u.copy(balanceAfter = it) }
        return u
    }

    fun label(id: Long?): String? = id?.let { rows[it]?.label }

    // ───────────── totals, always through LedgerRules ─────────────

    fun totals(): Totals {
        fun sum(view: LedgerView, accountId: Long? = null): Pair<Long, Long> {
            var spend = 0L
            var income = 0L
            for (r in rows.values) {
                val t = r.txn
                if (accountId != null && t.accountId != accountId) continue
                val c = LedgerRules.contribution(t.kind, t.status, t.refundOf != null, t.amount, view)
                spend += c.spend
                income += c.income
            }
            return spend to income
        }
        val off = sum(LedgerView.Combined(includeOwnTransfers = false))
        val on = sum(LedgerView.Combined(includeOwnTransfers = true))
        return Totals(
            combinedOff = Amounts(off.first, off.second),
            combinedOn = Amounts(on.first, on.second),
            perAccount = profile.accounts.map { a ->
                val s = sum(LedgerView.PerAccount, a.id)
                AccountAmounts(a.id, "${a.bank}-${a.last4}", s.first, s.second)
            },
        )
    }

    fun categoryTotals(view: LedgerView): Map<String, Long> {
        val out = sortedMapOf<String, Long>()
        for (r in rows.values) {
            val t = r.txn
            val c = LedgerRules.contribution(t.kind, t.status, t.refundOf != null, t.amount, view)
            if (c.spend != 0L) out[t.categoryKey ?: "?"] = (out[t.categoryKey ?: "?"] ?: 0L) + c.spend
        }
        return out
    }

    fun statusCounts(): Map<String, Int> = rows.values.groupingBy { it.txn.status.name }.eachCount().toSortedMap()

    fun activeCount(): Int = rows.values.count { it.txn.status == TxnStatus.ACTIVE }
}

@Serializable
internal data class Amounts(val spend: Long, val income: Long)

@Serializable
internal data class AccountAmounts(val accountId: Long, val account: String, val spend: Long, val income: Long)

@Serializable
internal data class Totals(
    /** Combined view, "Count transfers between my own accounts" off (the default). */
    val combinedOff: Amounts,
    /** Combined view, the toggle on. */
    val combinedOn: Amounts,
    val perAccount: List<AccountAmounts>,
)
