package com.ledgr.app.domain.ledger

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** `txn.user_edited_fields`: a JSON list of [EditableField] names, e.g. `["CATEGORY","NOTE"]`. */
object UserEdits {
    private val serializer = ListSerializer(String.serializer())

    fun encode(fields: Set<EditableField>): String =
        Json.encodeToString(serializer, fields.map { it.name }.sorted())

    /** A damaged or unknown value reads as "nothing edited" for that entry rather than crashing a scan. */
    fun decode(stored: String): Set<EditableField> =
        try {
            Json.decodeFromString(serializer, stored).mapNotNull { n -> EditableField.entries.firstOrNull { it.name == n } }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
}
