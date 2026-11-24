package com.ledgr.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.permissions.rememberSmsPermission
import com.ledgr.app.ui.profile.AccountSheet
import com.ledgr.app.ui.profile.ProfileIntent
import com.ledgr.app.ui.profile.ProfileViewModel
import com.ledgr.app.ui.profile.SmsAccess

private const val STEPS = 3
private const val SLIDE_MS = 250

/**
 * First run (docs/08 §6), three skippable steps: what Ledgr does and that nothing leaves the phone; add accounts; allow
 * SMS and scan this month. [onFinish] gets `true` when the person chose to scan.
 */
@Composable
fun OnboardingScreen(
    onFinish: (scan: Boolean) -> Unit,
    onFindSenders: () -> Unit,
    modifier: Modifier = Modifier,
    profile: ProfileViewModel = hiltViewModel(),
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val reduced = rememberReducedMotion()
    val profileState by profile.state.collectAsStateWithLifecycle()
    val permission = rememberSmsPermission(onReady = { onFinish(true) })

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StepDots(step)
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState > initialState
                    (fadeIn() + slideInHorizontally(tweenSpec()) { if (forward) it / 4 else -it / 4 }) togetherWith
                        (fadeOut() + slideOutHorizontally(tweenSpec()) { if (forward) -it / 4 else it / 4 })
                }
            },
            label = "onboarding-step",
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (page) {
                    0 -> WelcomeStep()
                    1 -> AccountsStep(
                        accounts = profileState.accounts.map { "${it.nickname} ••${it.last4}" },
                        canAdd = profileState.banks.isNotEmpty(),
                        onAdd = { profile.onIntent(ProfileIntent.OpenAdd) },
                    )
                    else -> ScanStep(blocked = permission.access == SmsAccess.Blocked, onOpenAppInfo = permission::openAppInfo)
                }
            }
        }
        Buttons(
            step = step,
            onNext = { step += 1 },
            onBack = { step -= 1 },
            onSkip = { onFinish(false) },
            onScan = permission::runWhenReady,
        )
    }

    profileState.editor?.let { editor ->
        AccountSheet(
            editor = editor,
            banks = profileState.banks,
            onIntent = profile::onIntent,
            onFindSenders = {
                profile.onIntent(ProfileIntent.CloseEditor)
                onFindSenders()
            },
        )
    }
}

private fun <T> tweenSpec() = androidx.compose.animation.core.tween<T>(SLIDE_MS)

@Composable
private fun StepDots(step: Int) {
    val label = stringResource(R.string.onboarding_step, step + 1, STEPS)
    Row(
        modifier = Modifier.semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(STEPS) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == step) 12.dp else 8.dp)
                    .background(
                        if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
    }
    Text(text = title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun WelcomeStep() {
    StepHeader(Icons.Outlined.Home, stringResource(R.string.onboarding_welcome_title))
    Body(stringResource(R.string.onboarding_welcome_what))
    Body(stringResource(R.string.onboarding_welcome_private), muted = true)
}

@Composable
private fun AccountsStep(accounts: List<String>, canAdd: Boolean, onAdd: () -> Unit) {
    StepHeader(Icons.Outlined.Search, stringResource(R.string.onboarding_accounts_title))
    Body(stringResource(R.string.onboarding_accounts_body))
    accounts.forEach { Body(it) }
    OutlinedButton(onClick = onAdd, enabled = canAdd, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.profile_add_account))
    }
}

@Composable
private fun ScanStep(blocked: Boolean, onOpenAppInfo: () -> Unit) {
    StepHeader(Icons.Outlined.Lock, stringResource(R.string.onboarding_scan_title))
    Body(stringResource(R.string.scan_explainer_what))
    Body(stringResource(R.string.scan_explainer_why))
    Body(stringResource(R.string.scan_explainer_private), muted = true)
    if (blocked) {
        Body(stringResource(R.string.home_perm_blocked_body))
        OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.senders_open_app_info))
        }
    }
}

@Composable
private fun Buttons(step: Int, onNext: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit, onScan: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (step < STEPS - 1) {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_next))
            }
        } else {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_scan_button))
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, enabled = step > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.nav_back))
            }
            TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
    }
}
