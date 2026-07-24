package com.pombo.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Marks the screen secure while composed: no screenshots, no screen
 * recording, no key in the recents thumbnail. Native hardening the web
 * cannot have — compose it wherever the private key is on screen.
 *
 * Compose dialogs are their own window (a known trap in this project), so
 * both the enclosing dialog's window (when there is one) and the activity's
 * are flagged; either alone leaves a capture path open.
 */
@Composable
fun SecureFlag() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val activityWindow = generateSequence<Context>(context) {
            (it as? ContextWrapper)?.baseContext
        }.filterIsInstance<Activity>().firstOrNull()?.window
        val windows = listOfNotNull(dialogWindow, activityWindow).distinct()
        windows.forEach { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        onDispose {
            windows.forEach { it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}
