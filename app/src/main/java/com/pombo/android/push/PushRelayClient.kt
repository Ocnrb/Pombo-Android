package com.pombo.android.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.pombo.android.bridge.PomboBridge
import com.pombo.android.core.PushRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Registers this device with the push relay — port of relayManager.js.
 *
 * Two levels, and the second depends on the first, exactly as on web:
 *   global   — obtain a delivery token once (web: pushManager.subscribe with
 *              VAPID; here: the FCM registration token)
 *   channel  — publish {type:'registration', tag, subscription} per channel the
 *              user wants notifications for
 *
 * The relay keys its table on (tag, token) and fans a wake signal out to every
 * row, so a browser subscription and this token can sit side by side under the
 * same tag without either client knowing about the other.
 */
class PushRelayClient(
    private val context: Context,
    private val bridge: PomboBridge,
    private val registry: PushRegistry
) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pombo_push", Context.MODE_PRIVATE)

    /** Web CONFIG.push.pushStreamId. */
    private val pushStreamId = "0xae340e799e8151f6a4999d245e466197aa217667/push"

    /** Whether the user turned notifications on at all (the global toggle). */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    private var cachedToken: String?
        get() = prefs.getString("fcm_token", null)
        set(v) = prefs.edit().putString("fcm_token", v).apply()

    /**
     * The delivery token. Shaped as `{"fcmToken": "..."}` because the relay
     * stores the subscription opaquely and branches on this field; a browser
     * sends `{endpoint, keys}` instead.
     */
    private suspend fun token(): String {
        cachedToken?.let { if (!prefs.getBoolean("needs_reregister", false)) return it }
        val fresh = suspendCancellableCoroutine<String> { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        cachedToken = fresh
        prefs.edit().putBoolean("needs_reregister", false).apply()
        return fresh
    }

    /**
     * Global enable. Fetching the token is what can fail (no Play Services, no
     * network), so it happens here rather than silently at first channel.
     */
    suspend fun enable(): Boolean {
        token()  // throws if unavailable
        enabled = true
        // Re-register everything already opted in, so a token rotation heals.
        registry.all().forEach { runCatching { publishRegistration(it.tag) } }
        return true
    }

    fun disable() {
        enabled = false
        registry.all().forEach { registry.remove(it.streamId) }
        // The relay has no delete endpoint, but the token can be revoked at
        // the source (web cancels the browser push subscription the same way):
        // its rows now hold a dead token, and the relay purges them when a
        // send fails. Re-enabling fetches a fresh token via needs_reregister.
        prefs.edit().remove("fcm_token").putBoolean("needs_reregister", true).apply()
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    /**
     * Per-channel opt-in. `native` selects the tag prefix: ONLY native
     * channels use 'native:' — public, password and DM inboxes all use
     * 'channel:' (web ChannelSettingsUI: isNative = type === 'native').
     */
    suspend fun subscribeChannel(streamId: String, type: String, name: String): Boolean {
        if (!enabled) return false
        val native = type == "native" || type == "gated"
        val tag = bridge.call(
            "pushTag",
            JSONObject().put("streamId", streamId).put("native", native)
        ).getString("tag")

        publishRegistration(tag)
        registry.add(
            PushRegistry.Entry(
                streamId = streamId,
                tag = tag,
                type = type,
                name = name,
                // Start at now: history that predates the opt-in must not
                // trigger a burst of notifications on the first push.
                lastTimestamp = System.currentTimeMillis()
            )
        )
        return true
    }

    /**
     * DM notifications: registers MY OWN inbox on the relay — one row covers
     * every conversation, because every incoming DM lands in the inbox and
     * the sender wakes its 'channel:' tag (web dmManager.subscribeInboxPush →
     * relayManager.subscribeToChannel(inbox)).
     */
    suspend fun subscribeDmInbox(inboxStreamId: String): Boolean {
        if (!enabled) return false
        val tag = bridge.call(
            "pushTag",
            JSONObject().put("streamId", inboxStreamId).put("native", false)
        ).getString("tag")
        publishRegistration(tag)
        registry.add(
            PushRegistry.Entry(
                streamId = inboxStreamId,
                tag = tag,
                type = "dm-inbox",
                name = "Direct Messages",
                lastTimestamp = System.currentTimeMillis()
            )
        )
        return true
    }

    fun unsubscribeDmInbox(inboxStreamId: String) = registry.remove(inboxStreamId)

    fun unsubscribeChannel(streamId: String) = registry.remove(streamId)

    fun isSubscribed(streamId: String) = registry.isSubscribed(streamId)

    /** Session cache — the relay key only changes with a relay migration. */
    @Volatile private var relayPk: String? = null

    private suspend fun publishRegistration(tag: String) {
        val payload = JSONObject()
            .put("type", "registration")
            .put("tag", tag)
            // The relay accepts a string or an object; the web sends a string,
            // so we match it.
            .put("subscription", JSONObject().put("fcmToken", token()).toString())
            .put("timestamp", System.currentTimeMillis())

        // Sealed to the relay's static key (§9.1 #3): the FCM token never
        // crosses the observable stream in the clear, and the sealing
        // ephemeral doubles as the transport publisher — the wire still
        // names no account. FAIL-CLOSED: no plaintext fallback, or a hostile
        // Graph endpoint could strip the key and downgrade every
        // registration; refreshRegistrationsIfDue retries on the next
        // connect. The pin against RELAY_ADDRESS runs in the bridge.
        val pk = relayPk
            ?: com.pombo.android.core.GraphApi.pushRelayKey(pushStreamId)?.also { relayPk = it }
            ?: throw IllegalStateException("Push stream metadata carries no relay key")
        bridge.call("pushSealAndPublish", JSONObject()
            .put("streamId", pushStreamId)
            .put("content", payload)
            .put("pk", pk)
            .put("relayAddress", RELAY_ADDRESS), 30_000)
    }

    /**
     * Re-publishes every relay row when it matters — called on each bridge
     * connect (web: relayManager's 6h re-registration timer).
     *
     * Two triggers, either is enough:
     *  - `needs_reregister` (FCM rotated the token): the rows on the relay
     *    hold a DEAD token, and nothing else ever republished them — push
     *    stayed broken until the user toggled it off and on;
     *  - 6h since the last refresh (web reRegistrationIntervalMs): keeps rows
     *    alive on the relay without a background job — a phone that opens the
     *    app at all refreshes at the web's cadence.
     * On failure the rotation flag is restored so the next connect retries.
     */
    suspend fun refreshRegistrationsIfDue() {
        if (!enabled) return
        val rotated = prefs.getBoolean("needs_reregister", false)
        val last = prefs.getLong("last_reregister_ts", 0L)
        if (!rotated && System.currentTimeMillis() - last < REREGISTER_INTERVAL_MS) return
        try {
            token()  // rotation → fetches the fresh token (and clears the flag)
            val entries = registry.all()
            entries.forEach { publishRegistration(it.tag) }
            prefs.edit().putLong("last_reregister_ts", System.currentTimeMillis()).apply()
            android.util.Log.d(
                "PomboPush",
                "registrations refreshed: ${entries.size} row(s), rotated=$rotated"
            )
        } catch (e: Exception) {
            if (rotated) prefs.edit().putBoolean("needs_reregister", true).apply()
            android.util.Log.w("PomboPush", "registration refresh failed: ${e.message}")
        }
    }

    private companion object {
        /** Web config.js push.reRegistrationIntervalMs. */
        const val REREGISTER_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** Web CONFIG.push.relays[0].address — the pin target for the relay
         *  key read from the push stream's metadata (§9.1 #2 mirror). */
        const val RELAY_ADDRESS = "0x905309e8b4d22a02b08459f42a203c7265abd3ad"
    }
}
