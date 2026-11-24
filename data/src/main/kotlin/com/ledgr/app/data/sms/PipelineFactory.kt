package com.ledgr.app.data.sms

import com.ledgr.app.data.db.LedgrDatabase
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.domain.profile.SenderHeaders
import com.ledgr.app.domain.rules.RulesBundle
import com.ledgr.app.domain.sms.SmsPipeline
import com.ledgr.app.domain.template.AccountRef
import com.ledgr.app.domain.template.TemplateEngine
import com.ledgr.app.domain.template.TemplateStageImpl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the pure [SmsPipeline] with the real template stage: the rules, the owner's confirmed sender headers and the
 * profile's accounts (layer L6). Shared by the scanner and by Needs review ("record it").
 */
@Singleton
class PipelineFactory @Inject constructor(private val rulesLoader: RulesLoader) {
    private var engine: Pair<RulesBundle, TemplateEngine>? = null

    class Built(val pipeline: SmsPipeline, val rules: RulesBundle)

    suspend fun create(db: LedgrDatabase): Built {
        val rules = rulesLoader.bundle()
        val accounts = db.accountDao().getAll()
        val confirmed = accounts.flatMap { SenderHeaders.decode(it.senderHeaders) }.toSet()
        val refs = accounts.filter { it.active }.map {
            AccountRef(it.id, it.bankId, it.type, it.last4, SenderHeaders.decode(it.senderHeaders).toSet())
        }
        val stage = TemplateStageImpl(templateEngine(rules), refs)
        return Built(SmsPipeline(rules, confirmed, stage), rules)
    }

    /** Compiling templates is the slow part; keep the engine for as long as the rules bundle is the same one. */
    @Synchronized
    private fun templateEngine(rules: RulesBundle): TemplateEngine {
        engine?.let { (bundle, e) -> if (bundle === rules) return e }
        return TemplateEngine(rules.templates).also { engine = rules to it }
    }
}
