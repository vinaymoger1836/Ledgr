package com.ledgr.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ledgr.app.R
import com.ledgr.app.data.profile.IdentityAddResult
import com.ledgr.app.data.profile.IdentityRepository
import com.ledgr.app.domain.model.OwnIdentityKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IdentityItem(val id: Long, val value: String)

enum class IdentityFieldError { Invalid, Duplicate, Failed }

data class IdentitiesUiState(
    val upiIds: List<IdentityItem> = emptyList(),
    val names: List<IdentityItem> = emptyList(),
    val upiInput: String = "",
    val nameInput: String = "",
    val upiError: IdentityFieldError? = null,
    val nameError: IdentityFieldError? = null,
)

sealed interface IdentitiesIntent {
    data class SetInput(val kind: OwnIdentityKind, val text: String) : IdentitiesIntent
    data class Add(val kind: OwnIdentityKind) : IdentitiesIntent
    data class Remove(val id: Long) : IdentitiesIntent
}

private data class Inputs(
    val upiInput: String = "",
    val nameInput: String = "",
    val upiError: IdentityFieldError? = null,
    val nameError: IdentityFieldError? = null,
)

@HiltViewModel
class IdentitiesViewModel @Inject constructor(
    private val repository: IdentityRepository,
) : ViewModel() {

    private val inputs = MutableStateFlow(Inputs())

    val state: StateFlow<IdentitiesUiState> = combine(repository.identities, inputs) { rows, input ->
        IdentitiesUiState(
            upiIds = rows.filter { it.kind == OwnIdentityKind.UPI_ID }.map { IdentityItem(it.id, it.value) },
            names = rows.filter { it.kind == OwnIdentityKind.NAME_VARIANT }.map { IdentityItem(it.id, it.value) },
            upiInput = input.upiInput,
            nameInput = input.nameInput,
            upiError = input.upiError,
            nameError = input.nameError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IdentitiesUiState())

    fun onIntent(intent: IdentitiesIntent) {
        when (intent) {
            is IdentitiesIntent.SetInput -> inputs.update {
                when (intent.kind) {
                    OwnIdentityKind.UPI_ID -> it.copy(upiInput = intent.text.take(MAX_INPUT), upiError = null)
                    OwnIdentityKind.NAME_VARIANT -> it.copy(nameInput = intent.text.take(MAX_INPUT), nameError = null)
                }
            }
            is IdentitiesIntent.Add -> add(intent.kind)
            is IdentitiesIntent.Remove -> viewModelScope.launch { runCatching { repository.remove(intent.id) } }
        }
    }

    private fun add(kind: OwnIdentityKind) {
        val text = when (kind) {
            OwnIdentityKind.UPI_ID -> inputs.value.upiInput
            OwnIdentityKind.NAME_VARIANT -> inputs.value.nameInput
        }
        if (text.isBlank()) return
        viewModelScope.launch {
            val error = when (runCatching { repository.add(kind, text) }.getOrNull()) {
                IdentityAddResult.Added -> null
                IdentityAddResult.Invalid -> IdentityFieldError.Invalid
                IdentityAddResult.Duplicate -> IdentityFieldError.Duplicate
                null -> IdentityFieldError.Failed
            }
            inputs.update {
                when (kind) {
                    OwnIdentityKind.UPI_ID -> if (error == null) it.copy(upiInput = "", upiError = null) else it.copy(upiError = error)
                    OwnIdentityKind.NAME_VARIANT -> if (error == null) it.copy(nameInput = "", nameError = null) else it.copy(nameError = error)
                }
            }
        }
    }

    private companion object {
        const val MAX_INPUT = 80
    }
}

/** My UPI IDs & names (docs/08 §2.4): own VPAs and name spellings used to spot transfers between my own accounts. */
@Composable
fun IdentitiesScreen(onBack: () -> Unit, viewModel: IdentitiesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    IdentitiesContent(state = state, onIntent = viewModel::onIntent, onBack = onBack)
}

@Composable
private fun IdentitiesContent(state: IdentitiesUiState, onIntent: (IdentitiesIntent) -> Unit, onBack: () -> Unit) {
    SubScreenScaffold(title = stringResource(R.string.identities_title), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.identities_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { SectionTitle(stringResource(R.string.identities_upi), modifier = Modifier.padding(top = 12.dp)) }
            item {
                AddField(
                    value = state.upiInput,
                    label = stringResource(R.string.identities_upi_label),
                    hint = stringResource(R.string.identities_upi_hint),
                    error = state.upiError?.let { stringResource(identityErrorText(it, upi = true)) },
                    keyboardType = KeyboardType.Email,
                    onValueChange = { onIntent(IdentitiesIntent.SetInput(OwnIdentityKind.UPI_ID, it)) },
                    onAdd = { onIntent(IdentitiesIntent.Add(OwnIdentityKind.UPI_ID)) },
                )
            }
            if (state.upiIds.isEmpty()) {
                item { EmptyHint(stringResource(R.string.identities_upi_empty)) }
            }
            items(state.upiIds, key = { "upi-${it.id}" }) { item ->
                IdentityRow(item.value) { onIntent(IdentitiesIntent.Remove(item.id)) }
            }

            item { SectionTitle(stringResource(R.string.identities_names), modifier = Modifier.padding(top = 16.dp)) }
            item {
                AddField(
                    value = state.nameInput,
                    label = stringResource(R.string.identities_name_label),
                    hint = stringResource(R.string.identities_name_hint),
                    error = state.nameError?.let { stringResource(identityErrorText(it, upi = false)) },
                    keyboardType = KeyboardType.Text,
                    onValueChange = { onIntent(IdentitiesIntent.SetInput(OwnIdentityKind.NAME_VARIANT, it)) },
                    onAdd = { onIntent(IdentitiesIntent.Add(OwnIdentityKind.NAME_VARIANT)) },
                )
            }
            if (state.names.isEmpty()) {
                item { EmptyHint(stringResource(R.string.identities_names_empty)) }
            }
            items(state.names, key = { "name-${it.id}" }) { item ->
                IdentityRow(item.value) { onIntent(IdentitiesIntent.Remove(item.id)) }
            }
        }
    }
}

private fun identityErrorText(error: IdentityFieldError, upi: Boolean): Int = when (error) {
    IdentityFieldError.Invalid -> if (upi) R.string.identities_upi_invalid else R.string.identities_name_invalid
    IdentityFieldError.Duplicate -> R.string.identities_duplicate
    IdentityFieldError.Failed -> R.string.identities_save_failed
}

@Composable
private fun EmptyHint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddField(
    value: String,
    label: String,
    hint: String,
    error: String?,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(hint) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = onAdd,
            enabled = value.isNotBlank(),
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.action_add), modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun IdentityRow(value: String, onRemove: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.identities_remove, value),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
