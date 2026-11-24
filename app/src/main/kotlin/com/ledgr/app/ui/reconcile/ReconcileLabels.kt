package com.ledgr.app.ui.reconcile

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ledgr.app.R
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.statement.AmountStyle
import com.ledgr.app.domain.statement.StatementException
import com.ledgr.app.domain.statement.StatementField
import com.ledgr.app.domain.statement.StatementWarning
import com.ledgr.app.domain.statement.WarningCodes
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
internal val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** The message for a failed import (a [StatementException] code, or a repository code like `draft_lost`). */
@StringRes
internal fun errorText(code: String): Int = when (code) {
    StatementException.TOO_LARGE -> R.string.recon_err_too_large
    StatementException.UNSUPPORTED -> R.string.recon_err_unsupported
    StatementException.BAD_ZIP, StatementException.CORRUPT -> R.string.recon_err_corrupt
    StatementException.ZIP_BOMB -> R.string.recon_err_zip_bomb
    StatementException.NO_TEXT -> R.string.recon_err_no_text
    StatementException.NO_HEADER -> R.string.recon_err_no_header
    StatementException.TIMEOUT -> R.string.recon_err_timeout
    StatementException.NO_ROWS -> R.string.recon_err_no_rows
    "no_account" -> R.string.recon_err_no_account
    "draft_lost" -> R.string.recon_err_draft_lost
    else -> R.string.recon_err_generic
}

@StringRes
internal fun fieldLabel(field: StatementField): Int = when (field) {
    StatementField.DATE -> R.string.recon_field_date
    StatementField.VALUE_DATE -> R.string.recon_field_value_date
    StatementField.DESCRIPTION -> R.string.recon_field_description
    StatementField.REF -> R.string.recon_field_ref
    StatementField.DEBIT -> R.string.recon_field_debit
    StatementField.CREDIT -> R.string.recon_field_credit
    StatementField.AMOUNT -> R.string.recon_field_amount
    StatementField.DR_CR -> R.string.recon_field_dr_cr
    StatementField.BALANCE -> R.string.recon_field_balance
}

@StringRes
internal fun amountStyleLabel(style: AmountStyle): Int = when (style) {
    AmountStyle.DEBIT_CREDIT_COLUMNS -> R.string.recon_style_columns
    AmountStyle.SIGNED -> R.string.recon_style_signed
    AmountStyle.AMOUNT_PLUS_DRCR -> R.string.recon_style_drcr
}

/** The fields a mapping needs for [style], required ones first. */
internal fun fieldsFor(style: AmountStyle): List<StatementField> = buildList {
    add(StatementField.DATE)
    add(StatementField.DESCRIPTION)
    when (style) {
        AmountStyle.DEBIT_CREDIT_COLUMNS -> {
            add(StatementField.DEBIT)
            add(StatementField.CREDIT)
        }
        AmountStyle.SIGNED -> add(StatementField.AMOUNT)
        AmountStyle.AMOUNT_PLUS_DRCR -> {
            add(StatementField.AMOUNT)
            add(StatementField.DR_CR)
        }
    }
    add(StatementField.BALANCE)
    add(StatementField.REF)
    add(StatementField.VALUE_DATE)
}

/** The sentence for one warning. Balance problems show money; everything else names the row and, where useful, what was read. */
@Composable
internal fun warningMessage(w: StatementWarning): String {
    val row = w.row ?: 0
    return when (w.code) {
        WarningCodes.BALANCE_BREAK -> {
            val parts = w.detail.split(':')
            val expected = parts.getOrNull(0)?.toLongOrNull()?.let(Money::format).orEmpty()
            val found = parts.getOrNull(1)?.toLongOrNull()?.let(Money::format).orEmpty()
            stringResource(R.string.recon_warn_balance_break, row, expected, found)
        }
        WarningCodes.SUM_MISMATCH ->
            stringResource(R.string.recon_warn_sum_mismatch, w.detail.toLongOrNull()?.let { Money.format(kotlin.math.abs(it)) }.orEmpty())
        WarningCodes.NO_BALANCE -> stringResource(R.string.recon_warn_no_balance)
        WarningCodes.NO_AMOUNT -> stringResource(R.string.recon_warn_no_amount, row)
        WarningCodes.BOTH_AMOUNTS -> stringResource(R.string.recon_warn_both_amounts, row)
        WarningCodes.BAD_DATE -> stringResource(R.string.recon_warn_bad_date, row, w.detail)
        WarningCodes.BAD_AMOUNT -> stringResource(R.string.recon_warn_bad_amount, row, w.detail)
        else -> stringResource(R.string.recon_warn_other, row, w.detail)
    }
}
