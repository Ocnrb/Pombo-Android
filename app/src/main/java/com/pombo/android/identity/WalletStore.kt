package com.pombo.android.identity

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Secure identity storage (Android equivalent of the web secureStorage):
 * private keys live in EncryptedSharedPreferences (AES-256-GCM with a master
 * key in the Android Keystore). Supports multiple accounts + a current one,
 * mirroring the web's multi-wallet switching.
 *
 * Threat model — DOCUMENTED, ACCEPTED trade-off (audit M-I4): the web seals
 * the key under a user password (Keystore V3, scrypt N=131072), so an
 * offline attacker must brute-force it. Here the master key is device-bound
 * only: hardware-backed and non-exportable, but any code running as this app
 * on the unlocked device can decrypt. This is deliberate — the app has no
 * password by design (see DeviceAuth), and the interactive boundary is the
 * device credential instead. `setUserAuthenticationRequired(true)` on the
 * MasterKey was evaluated and rejected: every decrypt would demand a
 * biometric prompt, which breaks the headless readers — the FCM service
 * (notification titles/mutes read these stores with no UI to prompt from)
 * and the bridge boot at process start. Compensating controls: DeviceAuth
 * gates reveal/delete/switch, FLAG_SECURE covers every screen that renders
 * the key, and the bridge console redacts it.
 */
class WalletStore(context: Context) {

    /** One stored account. */
    data class Account(val address: String, val privateKey: String)

    private val prefs: SharedPreferences by lazy {
        // preserveOnReset: if the Keystore was invalidated this file holds the
        // only (sealed, unrecoverable) copy of the private keys — keep the
        // bytes in filesDir rather than destroying them, and let the UI warn.
        val p = com.pombo.android.core.SecurePrefs.create(
            context, "pombo_secure", preserveOnReset = true
        )
        migrateLegacy(p)
        p
    }

    /** Migrates the old single-wallet keys into the accounts list. */
    private fun migrateLegacy(p: SharedPreferences) {
        val legacyPk = p.getString(KEY_PK, null)
        val legacyAddr = p.getString(KEY_ADDRESS, null)
        if (!legacyPk.isNullOrEmpty() && !legacyAddr.isNullOrEmpty() && p.getString(KEY_ACCOUNTS, null) == null) {
            val arr = JSONArray().put(JSONObject().put("a", legacyAddr).put("k", legacyPk))
            p.edit().putString(KEY_ACCOUNTS, arr.toString()).putString(KEY_CURRENT, legacyAddr)
                .remove(KEY_PK).remove(KEY_ADDRESS).apply()
        }
    }

    private fun readAccounts(): MutableList<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val a = o.optString("a"); val k = o.optString("k")
                if (a.isNotEmpty() && k.isNotEmpty()) Account(a, k) else null
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun writeAccounts(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("a", it.address).put("k", it.privateKey)) }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    fun accounts(): List<Account> = readAccounts()

    val address: String? get() = prefs.getString(KEY_CURRENT, null)

    val privateKey: String? get() {
        val cur = address ?: return null
        return readAccounts().find { it.address.equals(cur, ignoreCase = true) }?.privateKey
    }

    val hasWallet: Boolean get() = !privateKey.isNullOrEmpty()

    /** Adds (or replaces) an account and makes it current. */
    fun save(privateKey: String, address: String) {
        val list = readAccounts().filterNot { it.address.equals(address, ignoreCase = true) }.toMutableList()
        list.add(Account(address, privateKey))
        writeAccounts(list)
        prefs.edit().putString(KEY_CURRENT, address).apply()
    }

    /** Switches the current account (must already be stored). */
    fun switchTo(address: String): Boolean {
        if (readAccounts().none { it.address.equals(address, ignoreCase = true) }) return false
        prefs.edit().putString(KEY_CURRENT, address).apply()
        return true
    }

    /** Removes the current account; switches to another if present, else clears. */
    fun clear() {
        val cur = address
        val remaining = readAccounts().filterNot { it.address.equals(cur, ignoreCase = true) }
        writeAccounts(remaining)
        prefs.edit().apply {
            if (remaining.isNotEmpty()) putString(KEY_CURRENT, remaining.first().address)
            else remove(KEY_CURRENT)
        }.apply()
    }

    // Username is scoped per address.
    var username: String?
        get() = prefs.getString(usernameKey(), null)
        set(value) { prefs.edit().putString(usernameKey(), value?.take(18)).apply() }

    private fun usernameKey(): String = KEY_USERNAME + "_" + (address?.lowercase() ?: "none")

    /** Peeks another stored account's username without switching to it (Profile's account list). */
    fun usernameFor(otherAddress: String): String? = prefs.getString(KEY_USERNAME + "_" + otherAddress.lowercase(), null)

    private companion object {
        const val KEY_PK = "pk"                 // legacy
        const val KEY_ADDRESS = "address"       // legacy
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_CURRENT = "current"
        const val KEY_USERNAME = "username"
    }
}
