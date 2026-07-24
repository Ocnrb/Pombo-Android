package com.pombo.android.core

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Storage Endpoint Resolution (on-chain) — native mirror of web
 * `src/js/storageEndpoints.js`.
 *
 * Resolves the HTTP endpoints of the storage nodes assigned to a stream from
 * the on-chain registries (via the bridge's `resolveStorageEndpoints`, which
 * calls `getStorageNodes` + `getStorageNodeMetadata`). Only web-safe URLs
 * (https, hostname, non-localhost, non-IP-literal) survive filtering — the
 * same filter the web applies, kept here (not in the bridge) so it is
 * unit-testable on the JVM.
 *
 * Health tracking is session-scoped:
 *  - consecutive-failure ejection from the rotation ([noteFailure]/[noteSuccess]),
 *  - whether a node URL supports the Pombo `format=metadata` fast path
 *    ([supportsMetaFormat]/[setMetaFormatSupport]); vanilla nodes answer HTTP 400.
 *
 * The direct HTTP reads themselves run natively (OkHttp) — there is no reason to
 * shuttle 240 KB chunks through the WebView for plain HTTPS GETs.
 *
 * @param fetcher raw node fetcher (bridge-backed in production, a fake in tests);
 *   returns every node's URLs UNFILTERED — [resolve] applies the web-safe filter.
 * @param clock injectable time source, so the TTL is testable without sleeping.
 */
class StorageEndpoints(
    private val fetcher: suspend (streamId: String) -> List<Node>,
    private val ttlMs: Long = StorageMediaConfig.ENDPOINT_CACHE_TTL_MS,
    private val failureLimit: Int = StorageMediaConfig.NODE_FAILURE_LIMIT,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** A storage node and its (web-safe once resolved) HTTP base URLs. */
    data class Node(val nodeAddress: String, val urls: List<String>)

    private class Cached(val at: Long, val nodes: List<Node>)

    private val lock = Mutex()
    private val cache = HashMap<String, Cached>()
    private val inFlight = HashMap<String, CompletableDeferred<List<Node>>>()

    // url -> consecutive failure count / format=metadata support. Touched by
    // noteFailure/noteSuccess/setMetaFormatSupport without the mutex, so kept thread-safe.
    private val failures = ConcurrentHashMap<String, Int>()
    private val metaFormat = ConcurrentHashMap<String, Boolean>()

    /**
     * Resolve the storage nodes (and their web-safe HTTP URLs) for a stream.
     * Cached per stream with a TTL; concurrent callers share one resolution.
     * May return an empty list (no storage, or no node published a web-safe URL).
     */
    suspend fun resolve(streamId: String, force: Boolean = false): List<Node> {
        val deferred: CompletableDeferred<List<Node>>
        var owner = false
        lock.withLock {
            val cached = cache[streamId]
            if (!force && cached != null && clock() - cached.at < ttlMs) return cached.nodes
            val existing = inFlight[streamId]
            if (existing != null) {
                // Dedupe concurrent resolutions even when force is set, exactly
                // like the web (force only bypasses the cache, not the in-flight).
                deferred = existing
            } else {
                deferred = CompletableDeferred()
                inFlight[streamId] = deferred
                owner = true
            }
        }
        if (!owner) return deferred.await()

        try {
            val nodes = fetcher(streamId)
                .map { n ->
                    Node(
                        n.nodeAddress.lowercase(),
                        n.urls.filter { isWebSafeStorageNodeUrl(it) }.map { normalizeUrl(it) }
                    )
                }
                .filter { it.urls.isNotEmpty() }
            lock.withLock {
                cache[streamId] = Cached(clock(), nodes)
                inFlight.remove(streamId)
            }
            deferred.complete(nodes)
            return nodes
        } catch (e: Throwable) {
            lock.withLock { inFlight.remove(streamId) }
            deferred.completeExceptionally(e)
            throw e
        }
    }

    /**
     * Flat rotation list of healthy base URLs for a stream. EVERY healthy URL is
     * a rotation slot — the Pombo cluster registers one node address with two
     * URLs, and taking only the first per node pinned all direct reads to a
     * single server (halved throughput on the web). Slots interleave by URL index
     * so multi-URL nodes and multi-node sets both spread fairly. Ejected URLs
     * ([noteFailure] reached the limit) are skipped. May be empty.
     */
    suspend fun rotation(streamId: String): List<String> {
        val nodes = resolve(streamId)
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            var any = false
            for (node in nodes) {
                val u = node.urls.getOrNull(i) ?: continue
                any = true
                if ((failures[u] ?: 0) < failureLimit) out.add(u)
            }
            if (!any) break
            i++
        }
        return out
    }

    /** Record a failed read against a node URL (ejects after [failureLimit] in a row). */
    fun noteFailure(url: String) {
        val u = normalizeUrl(url)
        failures[u] = (failures[u] ?: 0) + 1
    }

    /** Record a successful read (resets the consecutive-failure count). */
    fun noteSuccess(url: String) {
        failures.remove(normalizeUrl(url))
    }

    /** `format=metadata` support for a node URL. Null = not probed yet. */
    fun supportsMetaFormat(url: String): Boolean? = metaFormat[normalizeUrl(url)]

    fun setMetaFormatSupport(url: String, supported: Boolean) {
        metaFormat[normalizeUrl(url)] = supported
    }

    /** Drop the cached node set for a stream (e.g. after add/remove storage node). */
    fun invalidate(streamId: String) {
        // Not on the hot path; a short critical section is fine.
        cache.remove(streamId)
    }

    /** Full reset (logout / client re-init). */
    fun clear() {
        cache.clear()
        inFlight.clear()
        failures.clear()
        metaFormat.clear()
    }

    companion object {
        /** Trim whitespace and any trailing slash(es) — the cache/health key form. */
        fun normalizeUrl(url: String): String = url.trim().trimEnd('/')

        private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

        /** Mirror of web `isIpLiteralHost`: dotted-quad OR any colon (IPv6). */
        private fun isIpLiteralHost(hostname: String): Boolean {
            if (hostname.isEmpty()) return false
            if (IPV4.matches(hostname)) return true
            return hostname.contains(':')
        }

        /**
         * Mirror of web `isWebSafeStorageNodeUrl`: https scheme, a real hostname
         * (not empty/localhost), and not an IP literal. A malformed URL is unsafe.
         */
        fun isWebSafeStorageNodeUrl(value: String): Boolean {
            return try {
                val uri = URI(value.trim())
                if (uri.scheme?.lowercase() != "https") return false
                // getHost() keeps IPv6 brackets ("[::1]"); the ':' check catches it.
                val host = uri.host?.lowercase() ?: return false
                if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return false
                if (isIpLiteralHost(host)) return false
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
