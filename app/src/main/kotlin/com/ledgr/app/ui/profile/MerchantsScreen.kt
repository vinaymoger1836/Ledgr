package com.ledgr.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.data.db.CounterpartyEntity
import com.ledgr.app.ui.motion.rememberReducedMotion

/** Merchants (docs/08 §2.4): the shipped starter list plus your own, each with aliases, an avatar and a default category. */
@Composable
fun MerchantsScreen(onBack: () -> Unit, viewModel: MerchantsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.merchants_failed)
    LaunchedEffect(state.failed) {
        if (state.failed) {
            snackbar.showSnackbar(failedText)
            viewModel.onIntent(MerchantsIntent.FailureShown)
        }
    }
    MerchantsContent(state = state, snackbar = snackbar, onIntent = viewModel::onIntent, onBack = onBack)
}

@Composable
private fun MerchantsContent(
    state: MerchantsUiState,
    snackbar: SnackbarHostState,
    onIntent: (MerchantsIntent) -> Unit,
    onBack: () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    SubScreenScaffold(
        title = stringResource(R.string.merchants_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { onIntent(MerchantsIntent.OpenAdd) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.merchants_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.merchants_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(state.merchants, key = { "merchant-${it.id}" }) { merchant ->
                MerchantRow(
                    merchant = merchant,
                    onClick = { onIntent(MerchantsIntent.OpenEdit(merchant.id)) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = if (reducedMotion) null else tween(200),
                        placementSpec = if (reducedMotion) null else spring(),
                        fadeOutSpec = if (reducedMotion) null else tween(120),
                    ),
                )
            }
        }
    }

    state.editor?.let { editor -> MerchantEditorSheet(editor, state.categories, onIntent) }
}

@Composable
private fun MerchantRow(merchant: MerchantRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val editLabel = stringResource(R.string.merchants_edit, merchant.name)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = editLabel, onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MerchantAvatar(
                name = merchant.name,
                nameColorIndex = merchant.nameColor,
                categoryIconKey = merchant.categoryIcon.takeIf { merchant.icon == CounterpartyEntity.ICON_CATEGORY },
                categoryColorIndex = merchant.categoryColor.takeIf { merchant.icon == CounterpartyEntity.ICON_CATEGORY },
                image = merchant.image,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(merchant.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                val parts = buildList {
                    add(merchant.categoryName ?: stringResource(R.string.merchants_no_category))
                    if (merchant.aliasCount > 0) {
                        add(pluralStringResource(R.plurals.merchants_aliases_count, merchant.aliasCount, merchant.aliasCount))
                    }
                }
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MerchantEditorSheet(
    editor: MerchantEditorUi,
    categories: List<CategoryOption>,
    onIntent: (MerchantsIntent) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onIntent(MerchantsIntent.PickedImage(uri))
    }

    ModalBottomSheet(
        onDismissRequest = { onIntent(MerchantsIntent.CloseEditor) },
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
                stringResource(if (editor.isNew) R.string.merchants_sheet_add else R.string.merchants_sheet_edit),
            )

            OutlinedTextField(
                value = editor.nameDraft,
                onValueChange = { onIntent(MerchantsIntent.SetName(it)) },
                label = { Text(stringResource(R.string.merchants_name)) },
                singleLine = true,
                isError = editor.nameError != null,
                supportingText = editor.nameError?.let {
                    {
                        Text(
                            stringResource(
                                if (it == MerchantNameError.Blank) R.string.merchants_error_blank else R.string.merchants_error_duplicate,
                            ),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            CategoryDropdown(
                categories = categories,
                selectedId = editor.categoryId,
                onSelect = { onIntent(MerchantsIntent.SetCategory(it)) },
            )

            if (!editor.isNew) {
                val merchant = requireNotNull(editor.merchant)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.merchants_avatar),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val categoryOption = categories.firstOrNull { it.id == merchant.categoryId }
                        val showCategory = editor.avatarMode == AvatarMode.CategoryIcon
                        MerchantAvatar(
                            name = merchant.name,
                            nameColorIndex = merchant.nameColor,
                            categoryIconKey = categoryOption?.icon.takeIf { showCategory },
                            categoryColorIndex = categoryOption?.color.takeIf { showCategory },
                            image = merchant.image,
                            size = 56.dp,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AvatarChip(R.string.merchants_avatar_letter, editor.avatarMode == AvatarMode.Letter) {
                                onIntent(MerchantsIntent.SetAvatarMode(AvatarMode.Letter))
                            }
                            AvatarChip(
                                R.string.merchants_avatar_category,
                                editor.avatarMode == AvatarMode.CategoryIcon,
                                enabled = merchant.categoryId != null,
                            ) { onIntent(MerchantsIntent.SetAvatarMode(AvatarMode.CategoryIcon)) }
                            AvatarChip(R.string.merchants_avatar_picture, editor.avatarMode == AvatarMode.Picture) {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }
                    }
                    if (editor.imageError) ErrorText(stringResource(R.string.merchants_image_error))
                }

                AliasSection(editor = editor, onIntent = onIntent)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!editor.isNew && editor.merchant?.isShipped == false) {
                    TextButton(onClick = { onIntent(MerchantsIntent.RequestDelete) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.merchants_delete), color = MaterialTheme.colorScheme.error)
                    }
                    Box(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { onIntent(MerchantsIntent.CloseEditor) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = { onIntent(MerchantsIntent.Save) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (editor.isNew) R.string.action_add else R.string.action_done))
                }
            }
        }
    }

    if (editor.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.merchants_delete_title),
            message = stringResource(R.string.merchants_delete_message, editor.nameDraft),
            confirmLabel = stringResource(R.string.merchants_delete),
            onConfirm = { onIntent(MerchantsIntent.ConfirmDelete) },
            onDismiss = { onIntent(MerchantsIntent.CancelDelete) },
        )
    }
}

@Composable
private fun AvatarChip(label: Int, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(stringResource(label)) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

@Composable
private fun CategoryDropdown(categories: List<CategoryOption>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.merchants_category),
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
                if (selected != null) CategoryBadge(iconKey = selected.icon, colorIndex = selected.color, size = 28.dp)
                Text(
                    text = selected?.name ?: stringResource(R.string.merchants_no_category),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (selected != null) 12.dp else 0.dp),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.merchants_no_category)) },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
                categories.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        leadingIcon = { CategoryBadge(iconKey = option.icon, colorIndex = option.color, size = 28.dp) },
                        trailingIcon = if (option.id == selectedId) {
                            { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.picker_current)) }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            onSelect(option.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AliasSection(editor: MerchantEditorUi, onIntent: (MerchantsIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.merchants_aliases),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.merchants_aliases_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        editor.aliases.forEach { alias ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(alias, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onIntent(MerchantsIntent.RemoveAlias(alias)) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.merchants_alias_remove, alias))
                }
            }
        }
        val errorText = when (val e = editor.aliasError) {
            null -> null
            AliasError.Invalid -> stringResource(R.string.merchants_alias_invalid)
            is AliasError.AlreadyMapped ->
                if (e.sameMerchant) stringResource(R.string.merchants_alias_same) else stringResource(R.string.merchants_alias_taken, e.owner)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = editor.aliasInput,
                onValueChange = { onIntent(MerchantsIntent.SetAliasInput(it)) },
                label = { Text(stringResource(R.string.merchants_alias_label)) },
                singleLine = true,
                isError = errorText != null,
                supportingText = errorText?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onIntent(MerchantsIntent.AddAlias) }),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { onIntent(MerchantsIntent.AddAlias) },
                enabled = editor.aliasInput.isNotBlank(),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_add))
            }
        }
    }
}
