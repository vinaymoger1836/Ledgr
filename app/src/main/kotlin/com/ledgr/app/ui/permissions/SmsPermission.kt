package com.ledgr.app.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ledgr.app.ui.profile.SmsAccess

/**
 * Where READ_SMS stands, and how to ask (docs/03 §1, docs/08 §6). One place so Home and onboarding behave the same:
 * READ_SMS and RECEIVE_SMS are asked together, then POST_NOTIFICATIONS (it only decides whether the scan progress
 * notification is visible; the scan runs either way).
 */
@Stable
class SmsPermissionState internal constructor(
    val access: SmsAccess,
    private val runAction: () -> Unit,
    private val requestAction: () -> Unit,
    private val appInfoAction: () -> Unit,
) {
    /** What a "Scan" button does: asks first when SMS access is missing, otherwise just runs the action. */
    fun runWhenReady() = runAction()

    /** Only asks for the permission, without running anything afterwards beyond the usual follow-up. */
    fun request() = requestAction()

    /** Opens the system App info page (docs/03 §1: sideloaded apps may need "Allow restricted settings"). */
    fun openAppInfo() = appInfoAction()
}

/**
 * [onReady] runs once SMS access is in place (right after the person allows it, or at once if it already was). It is
 * what "Scan" starts.
 */
@Composable
fun rememberSmsPermission(onReady: () -> Unit): SmsPermissionState {
    val context = LocalContext.current
    val ready by rememberUpdatedState(onReady)
    var granted by remember { mutableStateOf(hasReadSms(context)) }
    // The system refused, or never showed its dialog: show the "restricted settings" help.
    var refused by rememberSaveable { mutableStateOf(false) }

    // Re-check on every return: the person may have just allowed SMS in App info.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasReadSms(context) }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> ready() }
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val read = result[Manifest.permission.READ_SMS] == true
        granted = read || hasReadSms(context)
        refused = !granted
        if (granted) {
            if (needsNotificationPermission(context)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else ready()
        }
    }
    val askSms = { sms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }

    val access = when {
        granted -> SmsAccess.Granted
        refused -> SmsAccess.Blocked
        else -> SmsAccess.NeedsPermission
    }
    return SmsPermissionState(
        access = access,
        runAction = {
            when {
                !granted -> askSms()
                needsNotificationPermission(context) -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> ready()
            }
        },
        requestAction = { if (!granted) askSms() },
        appInfoAction = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

private fun hasReadSms(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
