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

    /** P3: the ledger (ADR-013). New tables only; nothing existing changes. */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // A person's decision about a message (Needs review); null for every message stored so far.
            db.execSQL("ALTER TABLE `raw_sms` ADD COLUMN `user_decision` TEXT")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `txn` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`account_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `direction` TEXT NOT NULL, " +
                    "`amount` INTEGER NOT NULL, `occurred_at` INTEGER NOT NULL, `local_date` TEXT NOT NULL, " +
                    "`channel` TEXT NOT NULL, `counterparty_id` INTEGER, `raw_counterparty` TEXT, " +
                    "`category_id` INTEGER, `ref_no` TEXT, `balance_after` INTEGER, `source` TEXT NOT NULL, " +
                    "`raw_sms_id` INTEGER, `statement_line_id` INTEGER, `confidence` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, `duplicate_of` INTEGER, `transfer_pair_id` INTEGER, `refund_of` INTEGER, " +
                    "`user_edited_fields` TEXT NOT NULL DEFAULT '[]', `note` TEXT, " +
                    "`recon_state` TEXT NOT NULL DEFAULT 'UNRECONCILED', `template_id` TEXT, " +
                    "`rules_version` TEXT NOT NULL, `check_reason` TEXT, " +
                    "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                    "FOREIGN KEY(`counterparty_id`) REFERENCES `counterparty`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , " +
                    "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_account_id_local_date` ON `txn` (`account_id`, `local_date`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_local_date` ON `txn` (`local_date`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_ref_no` ON `txn` (`ref_no`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_counterparty_id_local_date` ON `txn` (`counterparty_id`, `local_date`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_category_id_local_date` ON `txn` (`category_id`, `local_date`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_txn_raw_sms_id` ON `txn` (`raw_sms_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_occurred_at` ON `txn` (`occurred_at`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `balance_snapshot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`account_id` INTEGER NOT NULL, `at` INTEGER NOT NULL, `balance` INTEGER NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_balance_snapshot_account_id_at` ON `balance_snapshot` (`account_id`, `at`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `link_proposal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`type` TEXT NOT NULL, `txn_id` INTEGER NOT NULL, `other_txn_id` INTEGER NOT NULL, " +
                    "`state` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_link_proposal_type_txn_id_other_txn_id` " +
                    "ON `link_proposal` (`type`, `txn_id`, `other_txn_id`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_link_proposal_state` ON `link_proposal` (`state`)")
        }
    }

    /** v5 (P5, ADR-015): the person's answers to "is this recurring?". */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recurring_override` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`key` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_override_key` ON `recurring_override` (`key`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
