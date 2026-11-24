package com.ledgr.app.domain.rules

import com.charleskorn.kaml.Yaml
import com.ledgr.app.domain.model.CategoryKind
import kotlinx.serialization.Serializable

/** One default category from `assets/rules/categories.yaml` (docs/05 §2). */
@Serializable
data class CategoryRule(
    val key: String,
    val name: String,
    val kind: CategoryKind,
    /** Icon key resolved to a vector by the UI layer; a plain string here. */
    val icon: String,
    /** Index 0..9 into the fixed category palette; the theme picks the light/dark shade. */
    val color: Int,
)

/** The whole `categories.yaml` file. */
@Serializable
data class CategoryRulesFile(
    val version: String,
    val categories: List<CategoryRule>,
)

class RulesFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Parses and validates `categories.yaml`. Pure Kotlin so `:golden` can reuse it. */
object CategoryRulesParser {

    const val PALETTE_SIZE = 10

    fun parse(yamlText: String): CategoryRulesFile {
        val file = try {
            Yaml.default.decodeFromString(CategoryRulesFile.serializer(), yamlText)
        } catch (e: Exception) {
            throw RulesFormatException("categories.yaml is not valid: ${e.message}", e)
        }
        validate(file)
        return file
    }

    private fun validate(file: CategoryRulesFile) {
        if (file.version.isBlank()) throw RulesFormatException("categories.yaml: version is blank")
        if (file.categories.isEmpty()) throw RulesFormatException("categories.yaml: no categories")

        val seen = HashSet<String>()
        for (c in file.categories) {
            if (!KEY_PATTERN.matches(c.key)) {
                throw RulesFormatException("categories.yaml: bad key '${c.key}' (use lower_snake_case)")
            }
            if (!seen.add(c.key)) throw RulesFormatException("categories.yaml: duplicate key '${c.key}'")
            if (c.name.isBlank()) throw RulesFormatException("categories.yaml: '${c.key}' has no name")
            if (c.color !in 0 until PALETTE_SIZE) {
                throw RulesFormatException("categories.yaml: '${c.key}' colour ${c.color} outside 0..${PALETTE_SIZE - 1}")
            }
        }
    }

    private val KEY_PATTERN = Regex("""^[a-z][a-z0-9_]*$""")
}
