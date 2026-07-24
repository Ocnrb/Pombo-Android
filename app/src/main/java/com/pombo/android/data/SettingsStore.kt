package com.pombo.android.data

import android.content.Context

/**
 * User-configurable settings that mirror the web's API panel
 * (index.html #settings-panel-api). Kept encrypted at rest because the
 * Graph API key is a billable credential.
 */
class SettingsStore(context: Context) {

    private val prefs = com.pombo.android.core.SecurePrefs.create(context, "pombo_settings")

    /** Whose settings these are — sync state is per account. */
    @Volatile var scopeAddress: String? = null

    /**
     * Null means "use the shared default", exactly like the web. Scoped per
     * account — a billable credential set by account A must not ride along
     * into account B's session. The first scoped read migrates the old
     * device-global value into the current account and deletes it, so the
     * account logged in during the upgrade inherits it and the others start
     * clean.
     */
    var graphApiKey: String?
        get() {
            prefs.getString(scoped(KEY_GRAPH_API), null)?.trim()?.ifEmpty { null }?.let { return it }
            val legacy = prefs.getString(KEY_GRAPH_API, null)?.trim()?.ifEmpty { null } ?: return null
            if (!scopeAddress.isNullOrEmpty()) {
                prefs.edit().putString(scoped(KEY_GRAPH_API), legacy).remove(KEY_GRAPH_API).apply()
            }
            return legacy
        }
        set(value) = prefs.edit().putString(scoped(KEY_GRAPH_API), value?.trim()?.ifEmpty { null }).apply()

    private fun scoped(name: String) =
        if (scopeAddress.isNullOrEmpty()) name else "${name}_${scopeAddress!!.lowercase()}"

    /**
     * The last merged sync payload, kept verbatim. Pushing a payload rebuilt
     * only from local state would drop the slices this client does not model
     * (sentMessages, dmLeftAt) and erase them on every device.
     */
    var syncBase: String?
        get() = prefs.getString(scoped(KEY_SYNC_BASE), null)
        set(value) = prefs.edit().putString(scoped(KEY_SYNC_BASE), value).apply()

    /**
     * Peers whose messages are permanently ignored (web `blockedPeers`).
     *
     * A synced slice: blocking on the web must take effect here and vice
     * versa, so this is read back out of the merged sync payload rather than
     * being purely local. Addresses are stored lowercased, as the web does.
     */
    var blockedPeers: Set<String>
        get() = try {
            val arr = org.json.JSONArray(prefs.getString(scoped(KEY_BLOCKED), null) ?: "[]")
            (0 until arr.length()).mapNotNull { arr.optString(it).lowercase().ifEmpty { null } }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        set(value) = prefs.edit()
            .putString(scoped(KEY_BLOCKED), org.json.JSONArray(value.map { it.lowercase() }).toString())
            .apply()

    /**
     * Whether YouTube links in messages render an inline player (web
     * secureStorage.getYouTubeEmbedsEnabled, default true). Local-only —
     * on the web this lives outside the sync slices too.
     */
    var youtubeEmbeds: Boolean
        get() = prefs.getBoolean(scoped(KEY_YT_EMBEDS), true)
        set(value) = prefs.edit().putBoolean(scoped(KEY_YT_EMBEDS), value).apply()

    /**
     * Whether NSFW/Adult channels appear in Explore (web
     * secureStorage.getNsfwEnabled, default false). Local-only, like the
     * YouTube toggle.
     */
    var nsfwEnabled: Boolean
        get() = prefs.getBoolean(scoped(KEY_NSFW), false)
        set(value) = prefs.edit().putBoolean(scoped(KEY_NSFW), value).apply()

    /**
     * The "Direct Messages" push sub-toggle (web pombo_dm_push_<addr>,
     * default OFF): whether MY inbox is registered on the relay.
     */
    var dmPushEnabled: Boolean
        get() = prefs.getBoolean(scoped(KEY_DM_PUSH), false)
        set(value) = prefs.edit().putBoolean(scoped(KEY_DM_PUSH), value).apply()

    /**
     * The "Channel Invites" settings toggle (web invitesMuted_<addr>, default
     * unmuted): whether the inbox notification partition (P3) is subscribed.
     */
    var inviteNotificationsEnabled: Boolean
        get() = prefs.getBoolean(scoped(KEY_INVITE_NOTIFS), true)
        set(value) = prefs.edit().putBoolean(scoped(KEY_INVITE_NOTIFS), value).apply()

    /**
     * DM peers muted in Channel Details (lowercase addresses). The relay row
     * is per-inbox, not per-peer, so muting is enforced locally at display
     * time — both in the FCM path and the in-app one.
     */
    var mutedDmPeers: Set<String>
        get() = try {
            val arr = org.json.JSONArray(prefs.getString(scoped(KEY_MUTED_DM), null) ?: "[]")
            (0 until arr.length()).mapNotNull { arr.optString(it).lowercase().ifEmpty { null } }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        set(value) = prefs.edit()
            .putString(scoped(KEY_MUTED_DM), org.json.JSONArray(value.map { it.lowercase() }).toString())
            .apply()

    /**
     * How eagerly cross-device sync runs (Settings → Account). Per account,
     * because "how my data moves between my devices" is a property of the
     * account, not of this handset.
     *
     * Stored as the enum name so a value written by a build that knows a mode
     * this one does not simply falls back to AUTOMATIC instead of crashing.
     */
    var syncMode: SyncMode
        get() = SyncMode.entries.firstOrNull { it.name == prefs.getString(scoped(KEY_SYNC_MODE), null) }
            ?: SyncMode.AUTOMATIC
        set(value) = prefs.edit().putString(scoped(KEY_SYNC_MODE), value.name).apply()

    /**
     * Polygon RPC preference (web rpcPreference in localStorage): preset key
     * from RpcPresets plus the custom URL when preset == "custom". Device-wide,
     * not per-account — the web stores it the same way.
     */
    var rpcPreset: String
        get() = prefs.getString(KEY_RPC_PRESET, "drpc") ?: "drpc"
        set(value) = prefs.edit().putString(KEY_RPC_PRESET, value).apply()

    var rpcCustomUrl: String?
        get() = prefs.getString(KEY_RPC_CUSTOM, null)?.trim()?.ifEmpty { null }
        set(value) = prefs.edit().putString(KEY_RPC_CUSTOM, value?.trim()?.ifEmpty { null }).apply()

    /** Per-slice mutation timestamps that drive the latest-wins merge. */
    fun sliceTsJson(): org.json.JSONObject = try {
        org.json.JSONObject(prefs.getString(scoped(KEY_SLICE_TS), null) ?: "{}")
    } catch (e: Exception) {
        org.json.JSONObject()
    }

    fun setSliceTs(key: String, ts: Long) {
        val o = sliceTsJson().put(key, ts)
        prefs.edit().putString(scoped(KEY_SLICE_TS), o.toString()).apply()
    }

    fun saveSliceTs(o: org.json.JSONObject) {
        prefs.edit().putString(scoped(KEY_SLICE_TS), o.toString()).apply()
    }

    private companion object {
        const val KEY_GRAPH_API = "graph_api_key"
        const val KEY_SYNC_BASE = "sync_base"
        const val KEY_SLICE_TS = "slice_ts"
        const val KEY_BLOCKED = "blocked_peers"
        const val KEY_YT_EMBEDS = "youtube_embeds"
        const val KEY_NSFW = "nsfw_enabled"
        const val KEY_DM_PUSH = "dm_push_enabled"
        const val KEY_INVITE_NOTIFS = "invite_notifications_enabled"
        const val KEY_MUTED_DM = "muted_dm_peers"
        const val KEY_RPC_PRESET = "rpc_preset"
        const val KEY_RPC_CUSTOM = "rpc_custom_url"
        const val KEY_SYNC_MODE = "sync_mode"
    }
}

/**
 * When cross-device sync is allowed to run by itself.
 *
 * The manual "Sync devices" action is never gated by this — every mode leaves
 * it working. What changes is which of the three unprompted triggers fire:
 * the forced run on bridge connect, the throttled foreground tick, and the
 * debounced push after a local change.
 */
enum class SyncMode(val label: String) {
    /** Start-up, foreground return, and after a local change. */
    AUTOMATIC("Automatic"),

    /**
     * Skips only the connect-time run — the slowest one, since it competes with
     * the first paint. The foreground tick catches up within minutes.
     */
    NOT_ON_START("Skip on start"),

    /** No unprompted sync at all. Local changes wait for the next manual run. */
    MANUAL_ONLY("Manual only")
}
