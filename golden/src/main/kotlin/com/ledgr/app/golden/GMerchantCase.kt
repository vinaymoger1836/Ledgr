package com.ledgr.app.golden

import com.ledgr.app.domain.ledger.Categorizer
import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.ledger.CounterpartyInput
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.MerchantResolver
import com.ledgr.app.domain.ledger.TxnKind
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * G-MERCHANT (docs/03 §6–7, docs/12 §4): the merchant resolver (alias, own identity, dictionary, aggregator unwrap,
 * person heuristic, unknown) and the categoriser, with the shipped `merchants.yaml` and `ledger.yaml`. Asserts the
 * counterparty, its type and the category for each input.
 */
object GMerchantCase : GoldenCase {
    override val id = "G-MERCHANT"
    override val expectedFile = "g-merchant.json"

    @Serializable
    private data class Input(
        val id: String,
        val vpa: String? = null,
        val merchant: String? = null,
        val payee: String? = null,
        val channel: Channel,
        val credit: Boolean = false,
        val body: String = "",
    )

    @Serializable
    private data class Fixture(val profile: FxProfile, val inputs: List<Input>)

    @Serializable
    private data class Row(
        val id: String,
        val type: String,
        val displayName: String,
        val identityKey: String?,
        val category: String,
        val needsCheck: Boolean,
    )

    override fun run(context: GoldenContext): String {
        val fixture = GoldenJson.json.decodeFromString(
            Fixture.serializer(),
            File(context.fixturesDir, "merchant/inputs.json").readText(Charsets.UTF_8),
        )
        val rules = GoldenRules.load(context)
        val merchantContext = fixture.profile.merchantContext(rules)
        val resolver = MerchantResolver(merchantContext)
        val categorizer = Categorizer(merchantContext)

        val rows = fixture.inputs.map { i ->
            val r = resolver.resolve(CounterpartyInput(i.vpa, i.merchant, i.payee), i.channel)
            val direction = if (i.credit) Direction.CREDIT else Direction.DEBIT
            val kind = if (i.credit) TxnKind.INCOME else TxnKind.EXPENSE
            Row(
                id = i.id,
                type = r.type.name,
                displayName = r.displayName,
                identityKey = r.identityKey,
                category = categorizer.categorize(r, kind, i.channel, direction, i.body),
                needsCheck = r.needsCheck,
            )
        }
        return GoldenJson.finish(GoldenJson.json.encodeToString(ListSerializer(Row.serializer()), rows))
    }
}
