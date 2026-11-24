package com.ledgr.app.domain.statement

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The JSON the statement tables store (docs/05): saved maps, source cells, warnings and reason codes. */
object StatementJson {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class WarningDto(val code: String, val row: Int? = null, val detail: String = "")

    /** `column_map.mapping`: the map without its PDF dividers, which have their own column. */
    fun encodeMapping(map: ColumnMap): String = json.encodeToString(ColumnMap.serializer(), map.copy(pdfColumns = null))

    fun encodeDividers(dividers: List<Int>?): String? = dividers?.let { json.encodeToString(ListSerializer(Int.serializer()), it) }

    /** @throws kotlinx.serialization.SerializationException when the stored text is not a map (a corrupt row is ignored by the caller). */
    fun decodeMap(mapping: String, pdfColumns: String?): ColumnMap =
        json.decodeFromString(ColumnMap.serializer(), mapping)
            .copy(pdfColumns = pdfColumns?.let { json.decodeFromString(ListSerializer(Int.serializer()), it) })

    fun encodeStrings(values: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), values)

    fun decodeStrings(text: String): List<String> =
        try {
            json.decodeFromString(ListSerializer(String.serializer()), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            emptyList()
        }

    fun encodeWarnings(warnings: List<StatementWarning>): String =
        json.encodeToString(ListSerializer(WarningDto.serializer()), warnings.map { WarningDto(it.code, it.row, it.detail) })

    fun decodeWarnings(text: String): List<StatementWarning> =
        try {
            json.decodeFromString(ListSerializer(WarningDto.serializer()), text).map { StatementWarning(it.code, it.row, it.detail) }
        } catch (e: kotlinx.serialization.SerializationException) {
            emptyList()
        }
}
