package com.ledgr.app.data.rules

import android.content.Context
import com.ledgr.app.domain.rules.RulesBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the rule files in the app's `assets/rules` folder (the four P1 files, `ledger.yaml` and the `templates` folder) and parses
 * them with the pure `:domain` parsers (the same code `goldenCheck` runs). The result is cached for the life of the process.
 * Blocking: call from a background thread.
 *
 * A malformed shipped file throws `RulesFormatException`; the caller surfaces it, nothing is swallowed.
 */
@Singleton
class RulesLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var cached: RulesBundle? = null

    /** [load] on the IO dispatcher, for callers that are already in a coroutine. */
    suspend fun bundle(): RulesBundle = withContext(Dispatchers.IO) { load() }

    @Synchronized
    fun load(): RulesBundle {
        cached?.let { return it }
        val bundle = RulesBundle.parse(
            banksYaml = read("banks.yaml"),
            spamYaml = read("spam.yaml"),
            merchantsYaml = read("merchants.yaml"),
            categoriesYaml = read("categories.yaml"),
            ledgerYaml = read("ledger.yaml"),
            templateYamls = templateFiles(),
            statementProfilesYaml = read("statement_profiles.yaml"),
        )
        cached = bundle
        return bundle
    }

    /** `rules/templates/<bank>.yaml`, sorted by name. The folder may be missing or empty (no templates yet). */
    private fun templateFiles(): Map<String, String> {
        val names = context.assets.list("rules/templates").orEmpty().filter { it.endsWith(".yaml") }.sorted()
        return names.associate { "templates/$it" to read("templates/$it") }
    }

    private fun read(name: String): String =
        context.assets.open("rules/$name").use { it.readBytes().toString(Charsets.UTF_8) }
}
