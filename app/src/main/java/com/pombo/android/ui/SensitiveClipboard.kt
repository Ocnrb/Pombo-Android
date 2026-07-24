package com.pombo.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Clipboard copy for secrets (the private key):
 *  - API 33+: flagged EXTRA_IS_SENSITIVE, so the system preview hides the
 *    content and clipboard-sync features skip it;
 *  - auto-clears after [CLEAR_AFTER_MS], but ONLY if the clipboard still
 *    holds this exact text — clobbering something the user copied later
 *    would be worse than leaving the key.
 */
object SensitiveClipboard {

    private const val CLEAR_AFTER_MS = 60_000L

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == text) {
                    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                    else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, CLEAR_AFTER_MS)
    }
}
