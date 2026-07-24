package com.pombo.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.pombo.android.ui.screens.PomboApp
import com.pombo.android.ui.theme.PomboTheme

/**
 * FragmentActivity (extends ComponentActivity), required by BiometricPrompt
 * to host its dialog.
 */
class MainActivity : FragmentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** Android 13+ requires an explicit grant before anything can be posted. */
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Granting the OS permission doubles as the push-enable consent: flips
        // the global toggle and registers with the relay. Denial means no
        // notifications.
        if (granted) viewModel.enablePushAfterPermissionGrant()
    }

    override fun onResume() {
        super.onResume()
        // Foreground flag + throttled background sync (state from other
        // devices is stalest exactly when the user comes back).
        viewModel.onAppForeground()
    }

    override fun onPause() {
        super.onPause()
        // Suppresses notifications for the conversation the user is looking
        // at, and flushes any dirty sync state the 15s debounce still holds.
        viewModel.onAppBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Host for the fingerprint prompt that gates every on-chain action.
        com.pombo.android.ui.ChainGuard.attach(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            PomboTheme {
                PomboApp(viewModel)
            }
        }
        handleInviteIntent(intent)
        handleTransferIntent(intent)
    }

    override fun onDestroy() {
        com.pombo.android.ui.ChainGuard.detach(this)
        super.onDestroy()
    }

    /** launchMode=singleTask, so a link tapped while we are alive lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInviteIntent(intent)
        handleTransferIntent(intent)
    }

    /** Transfer-notification tap → open the transfer's channel (or Chats if several). */
    private fun handleTransferIntent(intent: Intent?) {
        if (intent?.action == MediaTransferService.ACTION_OPEN_TRANSFERS) {
            viewModel.openTransferFromNotification()
        }
    }

    /**
     * Pulls the invite token out of a link. The web's canonical form keeps it
     * in the fragment (`…/#/invite/<token>`), which never reaches intent
     * filtering, so the fragment is parsed here rather than declared in the
     * manifest. `pombo://invite/<token>` carries it in the path instead.
     */
    private fun handleInviteIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val fromFragment = uri.fragment?.substringAfter("/invite/", "")?.ifEmpty { null }
        val fromPath = if (uri.scheme == "pombo") {
            uri.path?.trimStart('/')?.ifEmpty { null }
        } else {
            uri.path?.substringAfter("/invite/", "")?.ifEmpty { null }
        }
        val token = fromFragment ?: fromPath ?: return
        viewModel.openInviteToken(token)
    }
}
