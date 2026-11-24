package com.ledgr.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written migrations. The CREATE statements are copied verbatim from the exported schema of the version
 * they create (`data/schemas/.../<version>.json`), so Room's schema validation after the migration passes.
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


    /** P2: raw SMS store and scan runs (ADR-012). New tables only; nothing existing changes. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `raw_sms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`provider_id` INTEGER NOT NULL, `address` TEXT NOT NULL, `sender_header` TEXT, " +
                    "`sender_suffix` TEXT NOT NULL, `body` TEXT, `body_hash` TEXT NOT NULL, " +
                    "`received_at` INTEGER NOT NULL, `sub_id` INTEGER, `outcome` TEXT NOT NULL, " +
                    "`reason` TEXT NOT NULL, `template_id` TEXT, `rules_version` TEXT NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_sms_provider_id_body_hash` ON `raw_sms` (`provider_id`, `body_hash`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_sms_received_at` ON `raw_sms` (`received_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_sms_outcome_reason` ON `raw_sms` (`outcome`, `reason`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scan_run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`month` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`started_at` INTEGER NOT NULL, `finished_at` INTEGER, `read_count` INTEGER NOT NULL DEFAULT 0, " +
                    "`skipped_count` INTEGER NOT NULL DEFAULT 0, `error_count` INTEGER NOT NULL DEFAULT 0, " +
                    "`counts_json` TEXT NOT NULL DEFAULT '{}', `rules_version` TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_run_started_at` ON `scan_run` (`started_at`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
