package com.ledgr.app.golden

import com.ledgr.app.domain.sender.SenderNormalizer
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** G-SENDER: addresses -> header / suffix / prefix / type (docs/03 §3, docs/12 §4). */
object GSenderCase : GoldenCase {
    override val id = "G-SENDER"
    override val expectedFile = "g-sender.json"

    @Serializable
    private data class Row(
        val input: String,
        val type: String,
        val prefix: String?,
        val header: String?,
        val suffix: String,
    )

    override fun run(context: GoldenContext): String {
        val addresses = GoldenJson.json.decodeFromString(
            ListSerializer(String.serializer()),
            File(context.fixturesDir, "sender/addresses.json").readText(Charsets.UTF_8),
        )
        val rows = addresses.map { address ->
            val s = SenderNormalizer.normalize(address)
            Row(
                input = address,
                type = s.type.name,
                prefix = s.prefix,
                header = s.header,
                suffix = s.suffix.name,
            )
        }
        return GoldenJson.finish(GoldenJson.json.encodeToString(ListSerializer(Row.serializer()), rows))
    }
}
