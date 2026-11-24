package com.ledgr.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.domain.model.AccountType

@Composable
internal fun accountTypeLabel(type: AccountType): String = stringResource(
    when (type) {
        AccountType.SAVINGS -> R.string.account_type_savings
        AccountType.CURRENT -> R.string.account_type_current
        AccountType.CREDIT_CARD -> R.string.account_type_credit_card
        AccountType.WALLET -> R.string.account_type_wallet
    },
)

/**
 * Add / edit an account (docs/08 §2.4). The last-4 field only ever holds four digits: typing a fifth is ignored
 * and pasting a longer number is refused (see `Last4.accept`), so a full account or card number cannot be stored.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AccountSheet(
    editor: AccountEditorUi,
    banks: List<BankOption>,
    onIntent: (ProfileIntent) -> Unit,
    onFindSenders: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(ProfileIntent.CloseEditor) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle(
                stringResource(if (editor.isNew) R.string.account_sheet_title_add else R.string.account_sheet_title_edit),
            )

            BankPicker(selectedId = editor.bankId, banks = banks, onSelect = { onIntent(ProfileIntent.SetBank(it)) })

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.account_type),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountType.entries.forEach { type ->
                        FilterChip(
                            selected = editor.type == type,
                            onClick = { onIntent(ProfileIntent.SetType(type)) },
                            label = { Text(accountTypeLabel(type)) },
                            leadingIcon = if (editor.type == type) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = editor.nickname,
                onValueChange = { onIntent(ProfileIntent.SetNickname(it)) },
                label = { Text(stringResource(R.string.account_nickname)) },
                placeholder = { Text(stringResource(R.string.account_nickname_hint)) },
                singleLine = true,
                isError = editor.error == AccountFormError.Nickname,
                supportingText = if (editor.error == AccountFormError.Nickname) {
                    { Text(stringResource(R.string.account_error_nickname)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            val last4Error = editor.error == AccountFormError.Last4 || editor.error == AccountFormError.Duplicate
            OutlinedTextField(
                value = editor.last4,
                onValueChange = { onIntent(ProfileIntent.SetLast4(it)) },
                label = { Text(stringResource(R.string.account_last4)) },
                prefix = { Text("••") },
                singleLine = true,
                isError = last4Error,
                supportingText = {
                    Text(
                        when (editor.error) {
                            AccountFormError.Last4 -> stringResource(R.string.account_error_last4)
                            AccountFormError.Duplicate -> stringResource(R.string.account_error_duplicate)
                            else -> stringResource(R.string.account_last4_hint)
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            SenderHeaderSection(editor = editor, onIntent = onIntent, onFindSenders = onFindSenders)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = editor.active,
                        role = Role.Switch,
                        onValueChange = { onIntent(ProfileIntent.SetActive(it)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.account_active), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.account_active_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = editor.active, onCheckedChange = null)
            }

            if (editor.error == AccountFormError.SaveFailed) ErrorText(stringResource(R.string.account_error_save))
            if (editor.error == AccountFormError.HasTransactions) ErrorText(stringResource(R.string.account_error_has_transactions))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!editor.isNew) {
                    TextButton(
                        onClick = { onIntent(ProfileIntent.RequestDelete) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error)
                    }
                    Box(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { onIntent(ProfileIntent.CloseEditor) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { onIntent(ProfileIntent.Save) },
                    enabled = !editor.saving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (editor.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.account_delete_title),
            message = stringResource(R.string.account_delete_message, editor.nickname.ifBlank { "—" }),
            confirmLabel = stringResource(R.string.account_delete),
            onConfirm = { onIntent(ProfileIntent.ConfirmDelete) },
            onDismiss = { onIntent(ProfileIntent.CancelDelete) },
        )
    }
}

@Composable
private fun BankPicker(selectedId: String, banks: List<BankOption>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = banks.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.account_bank),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                ColorDot(selected?.color)
                Text(
                    text = selected?.name ?: selectedId,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                banks.forEach { bank ->
                    DropdownMenuItem(
                        text = { Text(bank.name) },
                        leadingIcon = { ColorDot(bank.color) },
                        trailingIcon = if (bank.id == selectedId) {
                            { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.picker_current)) }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            onSelect(bank.id)
                        },
                    )
                }
            }
        }
    }
}

/** A small filled circle in a bank's colour; decorative (the bank name is always next to it). */
@Composable
internal fun ColorDot(argb: Int?) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(if (argb != null) Color(argb) else MaterialTheme.colorScheme.outline, CircleShape),
    )
}

@Composable
private fun SenderHeaderSection(editor: AccountEditorUi, onIntent: (ProfileIntent) -> Unit, onFindSenders: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.account_headers),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.account_headers_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (editor.headers.isEmpty()) {
            Text(stringResource(R.string.account_headers_none), style = MaterialTheme.typography.bodyMedium)
        }
        editor.headers.forEach { choice ->
            val note = stringResource(
                when (choice.note) {
                    HeaderNote.Confirmed -> R.string.header_note_confirmed
                    HeaderNote.Unverified -> R.string.header_note_unverified
                    HeaderNote.Listed -> R.string.header_note_listed
                    HeaderNote.Manual -> R.string.header_note_manual
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = choice.selected,
                        role = Role.Checkbox,
                        onValueChange = { onIntent(ProfileIntent.ToggleHeader(choice.header)) },
                    )
                    .semantics { stateDescription = note },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = choice.selected, onCheckedChange = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(choice.header, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = editor.manualHeader,
                onValueChange = { onIntent(ProfileIntent.SetManualHeader(it)) },
                label = { Text(stringResource(R.string.account_header_add_label)) },
                singleLine = true,
                isError = editor.manualHeaderInvalid,
                supportingText = if (editor.manualHeaderInvalid) {
                    { Text(stringResource(R.string.account_header_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { onIntent(ProfileIntent.AddManualHeader) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_add), modifier = Modifier.padding(start = 4.dp))
            }
        }
        TextButton(onClick = onFindSenders, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.account_find_senders))
        }
    }
}
