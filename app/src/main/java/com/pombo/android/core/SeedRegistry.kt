package com.pombo.android.core

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-disk registry of the files this device can serve, so seeding survives a
 * process restart (web parity: media.js persists seed files across sessions).
 * The piece bytes already survive — PieceStore keeps them in durable sparse
 * files — but which fileId belongs to which channel needs its own record.
 *
 * Passwords are deliberately NOT persisted. The channel's password is supplied
 * by the caller at restore time, from the channel store that already holds it —
 * writing it here would be a second, unencrypted copy.
 *
 * Writes are atomic (temp file + rename): a crash mid-write must leave the old
 * registry, not half a JSON document.
 */
class SeedRegistry(private val dir: () -> File) {

    data class Entry(
        val fileId: String,
        val messageStreamId: String,
        val ephemeralStreamId: String,
        val isDm: Boolean,
        val pieceCount: Int,
        val fileSize: Long,
        val fileName: String,
        val completedAt: Long
    )

    companion object {
        private const val FILE_NAME = "seeds.json"

        /** Same policy as the web's seedFilesExpireDays. */
        const val EXPIRE_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val lock = Any()
    private var cache: MutableMap<String, Entry>? = null

    private fun file() = File(dir(), FILE_NAME)

    private fun entries(): MutableMap<String, Entry> {
        cache?.let { return it }
        val loaded = LinkedHashMap<String, Entry>()
        try {
            val raw = file().takeIf { it.isFile }?.readText()
            if (raw != null) {
                val arr = JSONObject(raw).optJSONArray("seeds") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val fileId = o.optString("fileId")
                    val messageStreamId = o.optString("messageStreamId")
                    val ephemeralStreamId = o.optString("ephemeralStreamId")
                    val fileSize = o.optLong("fileSize", 0L)
                    val pieceCount = o.optInt("pieceCount", 0)
                    if (fileId.isEmpty() || messageStreamId.isEmpty() || ephemeralStreamId.isEmpty()) continue
                    if (fileSize <= 0 || pieceCount <= 0) continue
                    loaded[fileId] = Entry(
                        fileId = fileId,
                        messageStreamId = messageStreamId,
                        ephemeralStreamId = ephemeralStreamId,
                        isDm = o.optBoolean("isDm", false),
                        pieceCount = pieceCount,
                        fileSize = fileSize,
                        fileName = o.optString("fileName"),
                        completedAt = o.optLong("completedAt", 0L)
                    )
                }
            }
        } catch (e: Exception) {
            // A corrupt registry costs re-seeding, not data: start empty.
        }
        cache = loaded
        return loaded
    }

    fun record(entry: Entry) = synchronized(lock) {
        entries()[entry.fileId] = entry
        persist()
    }

    fun remove(fileId: String) = synchronized(lock) {
        if (entries().remove(fileId) != null) persist()
    }

    /** Entries for one stream — the set a channel rehydrates on open. */
    fun entriesFor(messageStreamId: String): List<Entry> = synchronized(lock) {
        entries().values.filter { it.messageStreamId == messageStreamId }
    }

    fun all(): List<Entry> = synchronized(lock) { entries().values.toList() }

    /**
     * Drops entries past [EXPIRE_MS] and returns them, so the caller can delete
     * the backing piece files — the registry does not own PieceStore's naming.
     */
    fun sweepExpired(now: Long = System.currentTimeMillis()): List<Entry> = synchronized(lock) {
        val expired = entries().values.filter { now - it.completedAt > EXPIRE_MS }
        if (expired.isNotEmpty()) {
            expired.forEach { entries().remove(it.fileId) }
            persist()
        }
        expired
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in entries().values) {
            arr.put(
                JSONObject()
                    .put("fileId", e.fileId)
                    .put("messageStreamId", e.messageStreamId)
                    .put("ephemeralStreamId", e.ephemeralStreamId)
                    .put("isDm", e.isDm)
                    .put("pieceCount", e.pieceCount)
                    .put("fileSize", e.fileSize)
                    .put("fileName", e.fileName)
                    .put("completedAt", e.completedAt)
            )
        }
        try {
            val target = file()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(JSONObject().put("seeds", arr).toString())
            if (!tmp.renameTo(target)) {
                // Windows-style rename-over-existing failure: replace explicitly.
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("rename failed")
            }
        } catch (e: Exception) {
            // Failing to persist must not fail the transfer that triggered it.
        }
    }
}
