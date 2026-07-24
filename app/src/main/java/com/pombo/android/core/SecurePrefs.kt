package com.pombo.android.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * EncryptedSharedPreferences with an invalidated-Keystore fallback.
 *
 * Every store used to build its prefs inline with no try/catch. If the
 * Keystore master key is invalidated — lock screen removed, backup restored
 * onto another device, vendor keystore corruption — `create()` throws, the
 * store never constructs, and the app is permanently unbootable: the one
 * failure mode encrypted-at-rest storage must never have.
 *
 * The data sealed under a dead key is cryptographically gone either way, so
 * recovery is: drop the unreadable file, recreate it empty, and let the
 * account state flow back in through device sync. Two escalation steps:
 *   1. delete just this prefs file and retry — covers a corrupt per-file
 *      keyset while the master key is still fine (other stores unaffected);
 *   2. delete the master key alias itself and retry — covers the invalidated
 *      Keystore. Other stores' files are already unreadable at that point;
 *      each self-heals through this same path when it is next constructed.
 *
 * Resets are recorded in PLAIN SharedPreferences (file names only, nothing
 * sensitive) so the UI can tell the user — losing the wallet file silently
 * would look like the app ate their account.
 */
object SecurePrefs {

    private const val TAG = "PomboSecurePrefs"
    private const val NOTICE_FILE = "pombo_reset_notices"
    private const val NOTICE_KEY = "reset_files"

    /**
     * @param preserveOnReset copy the undecryptable file into filesDir before
     *   deleting it. For the wallet: the bytes are unrecoverable without the
     *   master key, but destroying the only copy of a private key, even a
     *   sealed one, is not this code's call to make.
     */
    fun create(
        context: Context,
        fileName: String,
        preserveOnReset: Boolean = false
    ): SharedPreferences {
        val app = context.applicationContext
        try {
            return build(app, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "$fileName unreadable (${e.javaClass.simpleName}: ${e.message}) — resetting file")
            if (preserveOnReset) preserveFile(app, fileName)
            app.deleteSharedPreferences(fileName)
            recordReset(app, fileName)
        }
        try {
            return build(app, fileName)
        } catch (e: Exception) {
            // The file was fresh and it still failed: the master key itself is
            // dead. Drop the alias; a new one is minted on the next build.
            Log.e(TAG, "$fileName failed on a fresh file (${e.message}) — resetting master key alias")
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
            app.deleteSharedPreferences(fileName)
        }
        // Third build is not guarded: with a fresh file and a fresh master key
        // a failure means the Keystore is unusable, and crashing with the real
        // exception beats looping or losing writes to a silent stub.
        return build(app, fileName)
    }

    private fun build(app: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            app,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun preserveFile(app: Context, fileName: String) {
        runCatching {
            val src = File(app.dataDir, "shared_prefs/$fileName.xml")
            if (src.exists()) {
                src.copyTo(File(app.filesDir, "lost_${fileName}_${System.currentTimeMillis()}.xml"))
            }
        }
    }

    private fun recordReset(app: Context, fileName: String) {
        val plain = app.getSharedPreferences(NOTICE_FILE, Context.MODE_PRIVATE)
        val cur = plain.getStringSet(NOTICE_KEY, emptySet()) ?: emptySet()
        plain.edit().putStringSet(NOTICE_KEY, cur + fileName).apply()
    }

    /** Reset notices accumulated since the last call; clears them. */
    fun consumeResetNotices(context: Context): Set<String> {
        val plain = context.applicationContext
            .getSharedPreferences(NOTICE_FILE, Context.MODE_PRIVATE)
        val cur = plain.getStringSet(NOTICE_KEY, emptySet()) ?: emptySet()
        if (cur.isNotEmpty()) plain.edit().remove(NOTICE_KEY).apply()
        return cur
    }
}
