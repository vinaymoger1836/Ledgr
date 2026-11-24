package com.ledgr.app.ui.profile

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.ui.motion.rememberReducedMotion

/** Categories (docs/08 §2.4): rename, reorder, colour/icon, hide and merge. Edits here always win over `categories.yaml`. */
@Composable
fun CategoriesScreen(onBack: () -> Unit, viewModel: CategoriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val message = state.message
    val text = when (message) {
        is CategoryMessage.Merged -> stringResource(R.string.categories_merged, message.source, message.target)
        is CategoryMessage.Protected -> stringResource(R.string.categories_protected, message.name)
        CategoryMessage.Failed -> stringResource(R.string.categories_failed)
        null -> null
    }
    LaunchedEffect(message) {
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.onIntent(CategoriesIntent.MessageShown)
        }
    }

    CategoriesContent(state = state, snackbar = snackbar, onIntent = viewModel::onIntent, onBack = onBack)
}

@Composable
private fun CategoriesContent(
    state: CategoriesUiState,
    snackbar: SnackbarHostState,
    onIntent: (CategoriesIntent) -> Unit,
    onBack: () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    SubScreenScaffold(
        title = stringResource(R.string.categories_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            TextButton(
                onClick = { onIntent(CategoriesIntent.ToggleReorder) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.reorderMode) R.string.categories_reorder_done else R.string.categories_reorder))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.reorderMode) {
                item(key = "reorder-hint") {
                    Text(
                        text = stringResource(R.string.categories_reorder_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            categoryGroup("expense", R.string.categories_group_expense, state.expense, state.reorderMode, reducedMotion, onIntent)
            categoryGroup("income", R.string.categories_group_income, state.income, state.reorderMode, reducedMotion, onIntent)
            categoryGroup("transfer", R.string.categories_group_transfer, state.transfer, state.reorderMode, reducedMotion, onIntent)

            if (state.hidden.isNotEmpty()) {
                item(key = "hidden-toggle") {
                    TextButton(
                        onClick = { onIntent(CategoriesIntent.ToggleShowHidden) },
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (state.showHidden) R.string.categories_hide_hidden else R.string.categories_show_hidden,
                                state.hidden.size,
                            ),
                        )
                    }
                }
                if (state.showHidden) {
                    items(state.hidden, key = { "hidden-${it.id}" }) { row ->
                        CategoryRow(row, reorderMode = false, onIntent = onIntent)
                    }
                }
            }
        }
    }

    state.editor?.let { editor -> CategoryEditorSheet(editor, onIntent) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.categoryGroup(
    id: String,
    @androidx.annotation.StringRes title: Int,
    rows: List<CategoryRowUi>,
    reorderMode: Boolean,
    reducedMotion: Boolean,
    onIntent: (CategoriesIntent) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "title-$id") {
        SectionTitle(stringResource(title), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    }
    items(rows, key = { "$id-${it.id}" }) { row ->
        CategoryRow(
            row = row,
            reorderMode = reorderMode,
            onIntent = onIntent,
            modifier = Modifier.animateItem(
                fadeInSpec = if (reducedMotion) null else tween(200),
                placementSpec = if (reducedMotion) null else spring(),
                fadeOutSpec = if (reducedMotion) null else tween(120),
            ),
        )
    }
}

@Composable
private fun CategoryRow(
    row: CategoryRowUi,
    reorderMode: Boolean,
    onIntent: (CategoriesIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editLabel = stringResource(R.string.categories_edit, row.name)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (reorderMode) {
                    Modifier
                } else {
                    Modifier.clickable(onClickLabel = editLabel) { onIntent(CategoriesIntent.OpenEditor(row.id)) }
                },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryBadge(iconKey = row.icon, colorIndex = row.color)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            val note = when {
                row.mergedInto != null -> stringResource(R.string.categories_merged_into, row.mergedInto)
                row.hidden -> stringResource(R.string.categories_hidden_label)
                else -> null
            }
            if (note != null) {
                Text(text = note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (reorderMode) {
            IconButton(onClick = { onIntent(CategoriesIntent.Move(row.id, -1)) }, enabled = row.canMoveUp) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.categories_move_up, row.name))
            }
            IconButton(onClick = { onIntent(CategoriesIntent.Move(row.id, +1)) }, enabled = row.canMoveDown) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.categories_move_down, row.name))
            }
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditorSheet(editor: CategoryEditorUi, onIntent: (CategoriesIntent) -> Unit) {
    val category = editor.category
    ModalBottomSheet(
        onDismissRequest = { onIntent(CategoriesIntent.CloseEditor) },
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
            SectionTitle(stringResource(R.string.categories_editor_title))

            OutlinedTextField(
                value = editor.nameDraft,
                onValueChange = { onIntent(CategoriesIntent.SetName(it)) },
                label = { Text(stringResource(R.string.categories_name)) },
                singleLine = true,
                isError = editor.nameError != null,
                supportingText = editor.nameError?.let {
                    {
                        Text(
                            stringResource(
                                if (it == CategoryNameError.Blank) R.string.categories_error_blank else R.string.categories_error_duplicate,
                            ),
                        )
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.categories_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ColorSwatchPicker(selected = category.color, onSelect = { onIntent(CategoriesIntent.PickColor(it)) })
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.categories_icon),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconPicker(selectedKey = category.icon, onSelect = { onIntent(CategoriesIntent.PickIcon(it)) })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onIntent(CategoriesIntent.ToggleHidden) },
                    enabled = !category.isProtected,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (category.hidden) R.string.categories_unhide else R.string.categories_hide))
                }
                OutlinedButton(
                    onClick = { onIntent(CategoriesIntent.StartMerge) },
                    enabled = !category.isProtected && !category.hidden,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.categories_merge))
                }
            }
            if (category.isProtected) {
                Text(
                    text = stringResource(R.string.categories_protected_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = { onIntent(CategoriesIntent.CloseEditor) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = { onIntent(CategoriesIntent.SaveAndClose) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }

    editor.merge?.let { merge -> MergeDialog(source = category, merge = merge, onIntent = onIntent) }
}

@Composable
private fun MergeDialog(source: CategoryRowUi, merge: MergeUi, onIntent: (CategoriesIntent) -> Unit) {
    val target = merge.targets.firstOrNull { it.id == merge.selectedId }
    AlertDialog(
        onDismissRequest = { onIntent(CategoriesIntent.CancelMerge) },
        title = { Text(stringResource(R.string.categories_merge_title, source.name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.categories_merge_pick), style = MaterialTheme.typography.bodyMedium)
                merge.targets.forEach { option ->
                    val chosen = option.id == merge.selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = chosen,
                                role = Role.RadioButton,
                                onClick = { onIntent(CategoriesIntent.PickMergeTarget(option.id)) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = chosen, onClick = null)
                        CategoryBadge(iconKey = option.icon, colorIndex = option.color, size = 32.dp)
                        Text(option.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (target != null) {
                    Text(
                        text = stringResource(R.string.categories_merge_consequence, source.name, target.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onIntent(CategoriesIntent.ConfirmMerge) },
                enabled = target != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.categories_merge))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(CategoriesIntent.CancelMerge) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
