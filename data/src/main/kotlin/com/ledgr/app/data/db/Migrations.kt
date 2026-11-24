package com.ledgr.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written migrations. The CREATE statements are copied verbatim from the exported schema of the version
 * they create (`data/schemas/.../2.json`), so Room's schema validation after the migration passes.
 */
object Migrations {

    /** P1: own identities, counterparties + aliases, and category ordering / merge bookkeeping (ADR-011). */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // category: keep the order P0 showed (by id) as the starting sort order.
            db.execSQL("ALTER TABLE `category` ADD COLUMN `sort_order` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `category` ADD COLUMN `merged_into_id` INTEGER")
            db.execSQL("UPDATE `category` SET `sort_order` = `id`")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `own_identity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `value` TEXT NOT NULL COLLATE NOCASE)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_own_identity_kind_value` ON `own_identity` (`kind`, `value`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `counterparty` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`display_name` TEXT NOT NULL, `type` TEXT NOT NULL, `merchant_key` TEXT, " +
                    "`default_category_id` INTEGER, `icon` TEXT NOT NULL DEFAULT 'LETTER', `color` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_counterparty_merchant_key` ON `counterparty` (`merchant_key`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `counterparty_alias` (`raw_key` TEXT NOT NULL, " +
                    "`counterparty_id` INTEGER NOT NULL, `created_by` TEXT NOT NULL, PRIMARY KEY(`raw_key`), " +
                    "FOREIGN KEY(`counterparty_id`) REFERENCES `counterparty`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_counterparty_alias_counterparty_id` " +
                    "ON `counterparty_alias` (`counterparty_id`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
