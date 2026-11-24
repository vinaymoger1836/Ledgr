package com.ledgr.app.golden

import com.ledgr.app.domain.ledger.LedgerView
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Runs scenario fixtures (G-DUP, G-XFER, G-REFUND): each scenario is a profile plus a few synthetic SMS, fed in order
 * through the real pipeline and ledger engine in a fresh in-memory ledger. The output pins every transaction's kind,
 * status and links, the proposals raised, and the totals through `LedgerRules` for each view.
 */
internal object LedgerScenarios {

    @Serializable
    private data class Scenario(val id: String, val title: String, val messages: List<FxMessage>)

    @Serializable
    private data class Fixture(val profile: FxProfile, val scenarios: List<Scenario>)

    @Serializable
    private data class MessageRow(val id: String, val verdict: String, val txn: String?)

    @Serializable
    private data class TxnRow(
        val label: String,
        val account: Long,
        val kind: String,
        val direction: String,
        val amountPaise: Long,
        val status: String,
        val category: String?,
        val counterparty: String?,
        val transferPair: String?,
        val duplicateOf: String?,
        val refundOf: String?,
        val check: String?,
    )

    @Serializable
    private data class ProposalRow(val type: String, val a: String?, val b: String?)

    @Serializable
    private data class Result(
        val id: String,
        val title: String,
        val messages: List<MessageRow>,
        val txns: List<TxnRow>,
        val proposals: List<ProposalRow>,
        val statusCounts: Map<String, Int>,
        val totals: Totals,
        /** Net spend per category, combined view with the toggle off. */
        val categorySpend: Map<String, Long>,
    )

    fun run(context: GoldenContext, fixturePath: String): String {
        val fixture = GoldenJson.json.decodeFromString(
            Fixture.serializer(),
            File(context.fixturesDir, fixturePath).readText(Charsets.UTF_8),
        )
        val rules = GoldenRules.load(context)
        val results = fixture.scenarios.map { s ->
            val sim = LedgerSim(rules, fixture.profile)
            val messageRows = s.messages.map { m ->
                val (verdict, txnId) = sim.receive(m)
                MessageRow(m.id, verdict, sim.label(txnId))
            }
            Result(
                id = s.id,
                title = s.title,
                messages = messageRows,
                txns = sim.rows.values.map { r ->
                    val t = r.txn
                    TxnRow(
                        label = r.label,
                        account = t.accountId,
                        kind = t.kind.name,
                        direction = t.direction.name,
                        amountPaise = t.amount,
                        status = t.status.name,
                        category = t.categoryKey,
                        counterparty = t.counterpartyKey,
                        transferPair = sim.label(t.transferPairId),
                        duplicateOf = sim.label(t.duplicateOf),
                        refundOf = sim.label(t.refundOf),
                        check = r.checkReason,
                    )
                },
                proposals = sim.proposals.map { ProposalRow(it.type, sim.label(it.a), sim.label(it.b)) },
                statusCounts = sim.statusCounts(),
                totals = sim.totals(),
                categorySpend = sim.categoryTotals(LedgerView.Combined(includeOwnTransfers = false)),
            )
        }
        return GoldenJson.finish(GoldenJson.json.encodeToString(ListSerializer(Result.serializer()), results))
    }
}

/** G-DUP (docs/04 §2, docs/12 §4): same ref twice (1 merge), two ₹120 coffees (0), a bank plus card alert pair (1). */
object GDupCase : GoldenCase {
    override val id = "G-DUP"
    override val expectedFile = "g-dup.json"
    override fun run(context: GoldenContext): String = LedgerScenarios.run(context, "dup/scenarios.json")
}

/** G-XFER (docs/04 §3, ADR-007, docs/12 §4): own transfers and card bill payments, with totals per view. */
object GXferCase : GoldenCase {
    override val id = "G-XFER"
    override val expectedFile = "g-xfer.json"
    override fun run(context: GoldenContext): String = LedgerScenarios.run(context, "xfer/scenarios.json")
}

/** G-REFUND (docs/04 §4–§5, docs/12 §4): refunds and reversals, in either arrival order. */
object GRefundCase : GoldenCase {
    override val id = "G-REFUND"
    override val expectedFile = "g-refund.json"
    override fun run(context: GoldenContext): String = LedgerScenarios.run(context, "refund/scenarios.json")
}
