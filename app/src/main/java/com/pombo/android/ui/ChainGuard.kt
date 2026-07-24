package com.pombo.android.ui

import android.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Fingerprint confirmation in front of every transaction this app signs.
 *
 * On-chain actions here are not "sending crypto" — they are Streamr registry
 * writes (create/delete stream, permissions, metadata, storage) — but they all
 * spend POL from the user's wallet and several are irreversible, so each one
 * is worth an explicit, informed yes.
 *
 * Two layers, on purpose:
 *
 *  1. [approve] is called by the ViewModel per user action, so the prompt can
 *     say what the action is and what it costs.
 *  2. [holding] keeps that approval open for the duration of the action, and
 *     [isApproved] lets [com.pombo.android.bridge.PomboBridge] refuse any
 *     chain-writing bridge method that arrives outside one. That second layer
 *     is what makes the coverage exhaustive: a chain write added later, or one
 *     missed here, cannot go out silently — worst case it prompts on its own.
 *
 * A single user action costs several transactions (creating a channel is eight)
 * and each is retried, so the grant is scoped to the action rather than to one
 * transaction. Nothing is time-based: the window opens when the user confirms
 * and closes when the action returns.
 */
object ChainGuard {

    class DeniedException(message: String = "Not confirmed") : Exception(message)

    /** Set while MainActivity exists — the prompt needs a FragmentActivity. */
    @Volatile private var host: WeakReference<FragmentActivity>? = null

    /** >0 while an approved action is running. */
    private val depth = AtomicInteger(0)

    fun attach(activity: FragmentActivity) { host = WeakReference(activity) }

    fun detach(activity: FragmentActivity) {
        if (host?.get() === activity) host = null
    }

    fun isApproved(): Boolean = depth.get() > 0

    /**
     * Bridge methods that produce a Polygon transaction. Everything else the
     * bridge does (publish, resend, subscribe, ENS, crypto) is free and
     * off-chain.
     */
    val CHAIN_METHODS = setOf(
        "createStream",
        "getOrCreateStream",
        "setPermissions",
        "updateStreamMetadata",
        "deleteStream",
        "addToStorageNode",
        "removeFromStorageNode",
        "removeStorageNode",
        "setStorageDays",
        "createDMInbox",
        "repairDmInbox"
    )

    private const val WARNING =
        "This writes to the Polygon blockchain and spends POL from your wallet."

    /**
     * Prompts for fingerprint (or device PIN/pattern/password) with a warning
     * describing the action. Returns false on cancel, on error, and when there
     * is no activity to prompt on — the caller must treat anything but true as
     * a refusal.
     */
    suspend fun approve(action: String, detail: String): Boolean {
        val activity = host?.get() ?: return false
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (DeviceAuth.canAuthenticate(activity)) {
                    DeviceAuth.authenticate(
                        activity,
                        title = "Confirm on-chain action",
                        subtitle = action,
                        description = "$detail\n\n$WARNING"
                    ) { ok -> if (cont.isActive) cont.resume(ok) }
                } else {
                    // No fingerprint and no screen lock: nothing to verify
                    // identity against. Falls back to an explicit
                    // acknowledgement naming the missing protection.
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(action)
                        .setMessage(
                            "$detail\n\n$WARNING\n\n" +
                                "This device has no fingerprint or screen lock, so Pombo cannot " +
                                "verify it is you. Set up a screen lock to protect your wallet."
                        )
                        .setPositiveButton("Confirm") { _, _ -> if (cont.isActive) cont.resume(true) }
                        .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                        .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                        .show()
                }
            }
        }
    }

    /** Runs [block] with the approval open, so its transactions get through. */
    suspend fun <T> holding(block: suspend () -> T): T {
        depth.incrementAndGet()
        try {
            return block()
        } finally {
            depth.decrementAndGet()
        }
    }

    /**
     * Approve + hold in one call, for chain writes reached outside the
     * ViewModel. Throws [DeniedException] if the user refuses.
     */
    suspend fun <T> confirmed(action: String, detail: String, block: suspend () -> T): T {
        if (!approve(action, detail)) throw DeniedException()
        return holding(block)
    }
}
