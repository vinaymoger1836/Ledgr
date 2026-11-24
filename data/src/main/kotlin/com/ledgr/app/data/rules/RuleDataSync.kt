package com.ledgr.app.data.rules

import com.ledgr.app.data.db.CategoryEntity
import com.ledgr.app.data.db.CounterpartyEntity
import com.ledgr.app.data.db.LedgrDatabase
import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.profile.NameColor
import com.ledgr.app.domain.rules.RulesBundle

/**
 * Puts the shipped rule data into the database (docs/05 §2, docs/02 §4).
 *
 * Inserts only what is **missing**, keyed by `category.key` / `counterparty.merchant_key`, and never touches an
 * existing row: renames, colours, hidden flags, merges and merchant edits are the user's and always win
 * (CLAUDE.md rule 7). It is idempotent, so it simply runs on every start; a new version of a rules file that
 * adds a category or merchant shows up on the next launch.
 *
 * Matching data (VPA prefixes, keywords) is not copied: the merchant resolver reads it from the YAML.
 */
internal object RuleDataSync {

    fun apply(db: LedgrDatabase, rules: RulesBundle) {
        db.runInTransaction {
            syncCategories(db, rules)
            syncMerchants(db, rules)
        }
    }

    private fun syncCategories(db: LedgrDatabase, rules: RulesBundle) {
        val dao = db.categoryDao()
        val existing = dao.allKeysBlocking().toHashSet()
        var order = dao.maxSortOrderBlocking() ?: 0
        for (rule in rules.categories.categories) {
            if (rule.key in existing) continue
            order += 1
            dao.insertBlocking(
                CategoryEntity(
                    key = rule.key,
                    name = rule.name,
                    icon = rule.icon,
                    color = rule.color,
                    kind = rule.kind,
                    isDefault = true,
                    hidden = false,
                    sortOrder = order,
                ),
            )
        }
    }

    private fun syncMerchants(db: LedgrDatabase, rules: RulesBundle) {
        val categories = db.categoryDao()
        val dao = db.counterpartyDao()
        val existing = dao.merchantKeysBlocking().toHashSet()
        for (m in rules.merchants.merchants) {
            if (m.id in existing) continue
            val categoryId = categories.idForKeyBlocking(m.category)
            dao.insertBlocking(
                CounterpartyEntity(
                    displayName = m.name,
                    type = CounterpartyType.MERCHANT,
                    merchantKey = m.id,
                    defaultCategoryId = categoryId,
                    icon = if (categoryId != null) CounterpartyEntity.ICON_CATEGORY else CounterpartyEntity.ICON_LETTER,
                    color = NameColor.indexFor(m.name),
                ),
            )
        }
    }
}
