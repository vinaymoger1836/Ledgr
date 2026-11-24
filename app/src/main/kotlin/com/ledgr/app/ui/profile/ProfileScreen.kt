package com.ledgr.app.ui.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.ui.icons.CategoryIcons
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.review.ReviewCountViewModel

/** The Profile tab (docs/08 §2.4): accounts as cards, then the places that hold the rest of the profile. */
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenIdentities: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenMerchants: () -> Unit,
    onOpenSenders: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenScanDebug: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reviewCount by hiltViewModel<ReviewCountViewModel>().count.collectAsStateWithLifecycle()
    val reducedMotion = rememberReducedMotion()

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .semantics { heading() },
            )
        }

        item(key = "accounts-title") { SectionTitle(stringResource(R.string.profile_accounts)) }

        if (state.accounts.isEmpty() && state.loaded) {
            item(key = "accounts-empty") {
                Text(
                    text = stringResource(R.string.profile_accounts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.accounts, key = { "account-${it.id}" }) { account ->
            AccountCard(
                account = account,
                reducedMotion = reducedMotion,
                onClick = { viewModel.onIntent(ProfileIntent.OpenEdit(account.id)) },
                modifier = Modifier.animateItem(
                    fadeInSpec = if (reducedMotion) null else tween(220),
                    placementSpec = if (reducedMotion) null else spring(),
                    fadeOutSpec = if (reducedMotion) null else tween(120),
                ),
            )
        }

        item(key = "accounts-add") {
            FilledTonalButton(
                onClick = { viewModel.onIntent(ProfileIntent.OpenAdd) },
                enabled = state.banks.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.profile_add_account), modifier = Modifier.padding(start = 8.dp))
            }
        }

        item(key = "more-title") {
            SectionTitle(stringResource(R.string.profile_more), modifier = Modifier.padding(top = 12.dp))
        }
        item(key = "row-identities") {
            NavRow(
                icon = Icons.Outlined.Person,
                title = stringResource(R.string.profile_identities),
                summary = pluralStringResource(R.plurals.profile_identities_summary, state.identityCount, state.identityCount),
                onClick = onOpenIdentities,
            )
        }
        item(key = "row-categories") {
            NavRow(
                icon = CategoryIcons.vector("shopping"),
                title = stringResource(R.string.profile_categories),
                summary = pluralStringResource(R.plurals.profile_categories_summary, state.visibleCategoryCount, state.visibleCategoryCount),
                onClick = onOpenCategories,
            )
        }
        item(key = "row-merchants") {
            NavRow(
                icon = Icons.Outlined.ShoppingCart,
                title = stringResource(R.string.profile_merchants),
                summary = pluralStringResource(R.plurals.profile_merchants_summary, state.merchantCount, state.merchantCount),
                onClick = onOpenMerchants,
            )
        }
        item(key = "row-senders") {
            NavRow(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.profile_senders),
                summary = stringResource(R.string.profile_senders_summary),
                onClick = onOpenSenders,
            )
        }
        item(key = "row-review") {
            NavRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.profile_review),
                summary = if (reviewCount > 0) {
                    pluralStringResource(R.plurals.review_banner, reviewCount, reviewCount)
                } else {
                    stringResource(R.string.profile_review_none)
                },
                onClick = onOpenReview,
            )
        }
        // P2 only: removed or hidden in P4.
        item(key = "row-scan-debug") {
            NavRow(
                icon = Icons.Outlined.Build,
                title = stringResource(R.string.profile_scan_debug),
                summary = stringResource(R.string.profile_scan_debug_summary),
                onClick = onOpenScanDebug,
            )
        }
        item(key = "row-settings") {
            NavRow(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.profile_open_settings),
                summary = stringResource(R.string.profile_open_settings_hint),
                onClick = onOpenSettings,
            )
        }
    }

    state.editor?.let { editor ->
        AccountSheet(
            editor = editor,
            banks = state.banks,
            onIntent = viewModel::onIntent,
            onFindSenders = {
                viewModel.onIntent(ProfileIntent.CloseEditor)
                onOpenSenders()
            },
        )
    }
}

/**
 * A bank-card style account tile: bank colour, nickname, bank + type, and `••1234`. Text colour is chosen for
 * >= 4.5:1 on the bank colour; the type and last four are words/digits, never colour alone.
 */
@Composable
internal fun AccountCard(
    account: AccountCardUi,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = Color(account.bankColor)
    val onBackground = remember(account.bankColor) { onColorFor(background) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // docs/08 §4: scale 0.97 on press, instant when animations are off.
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.97f else 1f,
        animationSpec = tween(100),
        label = "card-press",
    )

    val typeLabel = accountTypeLabel(account.type)
    val spokenDigits = account.last4.toList().joinToString(" ")
    val headers = pluralStringResource(R.plurals.account_card_headers, account.headerCount, account.headerCount)
    val inactive = stringResource(R.string.account_card_inactive)
    val description = buildString {
        append(account.nickname).append(", ").append(account.bankName).append(' ').append(typeLabel)
        append(", ").append(stringResource(R.string.account_card_ending, spokenDigits))
        append(", ").append(headers)
        if (!account.active) append(", ").append(inactive)
    }
    val editLabel = stringResource(R.string.account_card_edit)

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.extraLarge,
        color = background,
        contentColor = onBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = editLabel) {
                    onClick()
                    true
                }
            },
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 112.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.nickname,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${account.bankName} · $typeLabel",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!account.active) {
                    Text(
                        text = inactive,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(top = 2.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = headers,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "••${account.last4}",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
