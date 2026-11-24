package com.ledgr.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.BuildConfig
import com.ledgr.app.R
import com.ledgr.app.data.db.DbStatus
import com.ledgr.app.data.settings.LockTimeout
import com.ledgr.app.data.settings.ThemeMode

@Composable
fun SettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state = state, onIntent = viewModel::onIntent, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.settings_title),
                    modifier = Modifier.semantics { heading() },
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .navigationBarsPadding() // the bottom bar is hidden on this screen
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ThemeSection(state.themeMode) { onIntent(SettingsIntent.SetTheme(it)) }
            LockTimeoutSection(state.lockTimeout) { onIntent(SettingsIntent.SetLockTimeout(it)) }
            OwnTransfersSection(state.countOwnTransfers) { onIntent(SettingsIntent.SetCountOwnTransfers(it)) }
            AboutSection(
                database = state.database,
                rulesVersion = state.rulesVersion,
                developerMode = state.developerMode,
                onDeveloperMode = { onIntent(SettingsIntent.SetDeveloperMode(it)) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ThemeSection(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.settings_theme))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun LockTimeoutSection(selected: LockTimeout, onSelect: (LockTimeout) -> Unit) {
    val options = LockTimeout.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.settings_lock_timeout))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, timeout ->
                val spoken = pluralStringResource(
                    R.plurals.settings_lock_timeout_option_description,
                    timeout.minutes,
                    timeout.minutes,
                )
                SegmentedButton(
                    selected = timeout == selected,
                    onClick = { onSelect(timeout) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = spoken },
                ) {
                    Text(stringResource(R.string.settings_lock_timeout_option, timeout.minutes))
                }
            }
        }
    }
}

/** docs/04 §6: off by default; turning it on counts both legs of a transfer between own accounts, by design. */
@Composable
private fun OwnTransfersSection(checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.settings_transfers))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_transfers_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(
            text = stringResource(R.string.settings_transfers_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Taps on the version line that turn developer options on, as on Android's own Settings. */
private const val DEVELOPER_TAPS = 7

/** From this many taps on, say how many are left, so a stray tap or two stays unremarked. */
private const val DEVELOPER_HINT_FROM = 3

@Composable
private fun AboutSection(
    database: DbStatus,
    rulesVersion: String?,
    developerMode: Boolean,
    onDeveloperMode: (Boolean) -> Unit,
) {
    var taps by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.settings_about))
        // Tapping the version seven times reveals developer options (docs/12 P4 item 6, ADR-014).
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) {
                    if (!developerMode) {
                        taps += 1
                        if (taps >= DEVELOPER_TAPS) {
                            taps = 0
                            onDeveloperMode(true)
                        }
                    }
                }
                .wrapContentHeight(Alignment.CenterVertically),
        )
        if (!developerMode && taps >= DEVELOPER_HINT_FROM) {
            Text(
                text = pluralStringResource(R.plurals.settings_developer_taps_left, DEVELOPER_TAPS - taps, DEVELOPER_TAPS - taps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (developerMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_developer_on),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = { onDeveloperMode(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_developer_turn_off))
                }
            }
        }
        if (rulesVersion != null) {
            Text(
                text = stringResource(R.string.settings_rules_version, rulesVersion),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val (text, isError) = when (database) {
            DbStatus.Opening -> stringResource(R.string.settings_db_opening) to false
            is DbStatus.Ready ->
                pluralStringResource(R.plurals.settings_db_ready, database.categoryCount, database.categoryCount) to false
            is DbStatus.Failed -> stringResource(R.string.settings_db_failed, database.reason) to true
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
