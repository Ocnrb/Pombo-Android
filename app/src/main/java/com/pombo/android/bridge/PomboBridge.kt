package com.pombo.android.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Headless WebView running the Pombo web vendor bundle (Streamr SDK + ethers)
 * as transport + identity primitives. Same pattern as PomboTV's StreamrBridge:
 * Kotlin→JS via evaluateJavascript (base64), JS→Kotlin via
 * @JavascriptInterface. Only this class knows about the WebView.
 *
 * Request/response calls use bridgeCall(id, method, argsB64) on the JS side and
 * Native.result(id, ok, payloadB64) back — exposed here as a suspending `call()`.
 */
class PomboBridge(
    context: Context,
    private val listener: Listener,
    /** Identity private key — injected via token replace; changes
     *  applied with reconnect() (reborn JS world). Empty = no identity
     *  (only ethers wallet create/import operations work). */
    @Volatile var privateKey: String,
    /** Seeds [rpcUrls] before the first page load, so a saved RPC
     *  preference applies from boot rather than the first reconnect. */
    initialRpcUrls: List<String> = emptyList()
) {

    interface Listener {
        fun onBridgeStatus(status: String)
        fun onBridgeConnected(address: String)
        /** Arrives on a background thread (WebView binder). */
        fun onBridgeMessage(streamId: String, partition: Int, contentJson: String, metaJson: String)
        /**
         * Binary payload (file pieces on -2/P2). Like [onBridgeMessage] this
         * arrives on the WebView binder thread — deliberately not posted to
         * main, because piece handling means hashing and disk writes that have
         * no business on the UI thread.
         *
         * Default no-op so a listener that never deals with media does not have
         * to implement it.
         */
        fun onBridgeBinary(streamId: String, partition: Int, data: ByteArray, metaJson: String) {}
        fun onBridgeError(message: String)
    }

    class BridgeException(message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val webView: WebView
    private val bridgeHtml: String
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1)
    private val inFlightBytes = AtomicLong(0)

    /**
     * Bytes handed to [publishBinary] that the network has not drained yet.
     * The backpressure signal for the transfer window: growing means the
     * sender is outrunning the link, not that the receiver is slow.
     */
    fun inFlight(): Long = maxOf(0L, inFlightBytes.get())

    /**
     * Polygon RPC endpoints for the Streamr client (Settings → API picker).
     * Empty keeps the page's CORS-safe defaults. Declared BEFORE the init
     * block because the first page load reads it; changes take effect on the
     * next [reconnect].
     */
    @Volatile var rpcUrls: List<String> = initialRpcUrls

    /**
     * Readiness gates. These are StateFlows rather than CompletableDeferreds
     * because a reconnect resets them: a waiter suspended on a replaced
     * Deferred would never wake, which silently stalled everything queued
     * during startup.
     */
    private val pageReadyFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val clientReadyFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Suspends until the Streamr client is usable. Anything that resends or
     * publishes must await this — calling earlier fails with "client not
     * connected", and a swallowed failure means the data never loads.
     */
    suspend fun awaitConnected(timeoutMs: Long = 60_000) {
        withTimeout(timeoutMs) { clientReadyFlow.first { it } }
    }

    @Volatile var connected = false
        private set

    init {
        @SuppressLint("SetJavaScriptEnabled")
        webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // https:// origin (loadDataWithBaseURL) but SDK connections may be ws://.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            addJavascriptInterface(JsApi(), "Native")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // bridgeCall only exists after the page script has run; calls
                    // issued before this point were silently lost.
                    pageReadyFlow.value = true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.lastPathSegment == "pombo-vendor.bundle.js") {
                        return try {
                            WebResourceResponse(
                                "application/javascript", "utf-8",
                                appContext.assets.open("pombo-vendor.bundle.js")
                            )
                        } catch (e: Exception) { null }
                    }
                    return null
                }
            }
            // JS console -> logcat; without this, errors inside the WebView are
            // invisible. Two guards (M-I3): release forwards WARNING+ only
            // (web logger.js is ERROR-only in production — a full DEBUG gate
            // would blind the release builds we actually test with), and the
            // injected private key is redacted from every line in every build,
            // because a JS error on this page can echo __BRIDGE_PK__ material.
            val debuggable = (appContext.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    val level = msg.messageLevel()
                    if (!debuggable &&
                        level != ConsoleMessage.MessageLevel.WARNING &&
                        level != ConsoleMessage.MessageLevel.ERROR
                    ) return true
                    var text = msg.message()
                    if (privateKey.isNotEmpty()) {
                        text = text
                            .replace(privateKey, "«pk»", ignoreCase = true)
                            .replace(privateKey.removePrefix("0x"), "«pk»", ignoreCase = true)
                    }
                    Log.d("PomboBridgeJS", "[$level] $text (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
            }
        }
        webView.resumeTimers()
        bridgeHtml = appContext.assets.open("pombo_bridge.html").readBytes().toString(Charsets.UTF_8)
        webView.loadDataWithBaseURL(BASE_URL, pageHtml(), "text/html", "utf-8", null)
    }

    private fun pageHtml() = bridgeHtml
        .replace("__BRIDGE_PK__", privateKey)
        .replace("__BRIDGE_RPCS__", org.json.JSONArray(rpcUrls).toString())

    /** Reborn JS world (new Streamr client) — used when switching identity
     *  or when the network changes. Fails all pending calls. */
    fun reconnect() {
        connected = false
        // inFlightBytes is NOT reset here: failAllPending completes every
        // in-flight publishBinary exceptionally, and each unwinds through its
        // own finally to give its bytes back. Zeroing as well would subtract
        // them twice and leave the counter permanently negative.
        failAllPending("bridge reloaded")
        // New page load: gate calls again until it is ready.
        pageReadyFlow.value = false
        clientReadyFlow.value = false
        main.post {
            webView.loadDataWithBaseURL(BASE_URL, pageHtml(), "text/html", "utf-8", null)
        }
    }

    private fun failAllPending(reason: String) {
        val entries = pending.entries.toList()
        pending.clear()
        entries.forEach { it.value.completeExceptionally(BridgeException(reason)) }
    }

    /**
     * Generic RPC call to the bridge. Throws BridgeException with the JS message
     * on error; 45s timeout (storage-node resends can be slow).
     */
    suspend fun call(method: String, args: JSONObject = JSONObject(), timeoutMs: Long = 45_000): JSONObject {
        // Last line of defence for spending: a method that signs a Polygon
        // transaction may only run inside an approval the user gave with their
        // fingerprint. Callers normally open that window in the ViewModel
        // (ChainGuard.approve + holding); anything that reaches here without
        // one has to ask for itself, and cannot be authorised at all from a
        // headless context (FCM, sync) where there is no activity to prompt on.
        if (method in com.pombo.android.ui.ChainGuard.CHAIN_METHODS &&
            !com.pombo.android.ui.ChainGuard.isApproved()
        ) {
            val ok = com.pombo.android.ui.ChainGuard.approve(
                "Blockchain transaction",
                "Pombo is about to submit a \"$method\" transaction."
            )
            if (!ok) throw BridgeException("Transaction not confirmed")
        }

        // Wait for the page instead of firing into a half-loaded WebView, where
        // the call would vanish and only surface as a timeout much later.
        withTimeout(timeoutMs) { pageReadyFlow.first { it } }

        val id = "r" + nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val b64 = Base64.encodeToString(args.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        js("bridgeCall('$id','$method','$b64')")
        try {
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Publishes a binary payload (a file piece on -2/P2). Suspends until the
     * network has taken it, so callers can bound how much they put in flight.
     *
     * Kept off [call] on purpose: that path base64-encodes the whole args JSON,
     * which would encode an already-base64 payload a second time and turn a
     * 240 KB piece into ~427 KB per evaluateJavascript. Here the payload gets
     * one base64 layer and only the tiny {streamId,partition} envelope pays for
     * two — which is also what keeps the stream id out of the interpolated JS.
     *
     * No ChainGuard check: this publishes to a stream, it never signs a
     * transaction, so there is nothing to spend.
     *
     * @param password for a password-protected channel. Encryption happens on
     *   the JS side (PBKDF2 in BoringSSL rather than Bouncy Castle — the same
     *   reason pwDecryptBatch exists), so what crosses here is plaintext.
     */
    suspend fun publishBinary(
        streamId: String,
        partition: Int,
        data: ByteArray,
        password: String? = null,
        /**
         * DM piece: key of a bridge-held sealed-sender binary sealer (one per
         * transfer, created via dmBinarySealerCreate). The bridge seals the
         * 0x02 envelope AND publishes under the sealer's throwaway identity.
         */
        dmSealerKey: String? = null,
        /**
         * Channel piece: the bridge rewrites the 0x01 frame as 0x03 with the
         * channel identity's inline proof and publishes under that identity.
         */
        channelEphemeral: Boolean = false,
        timeoutMs: Long = 45_000
    ) {
        withTimeout(timeoutMs) { pageReadyFlow.first { it } }

        val id = "b" + nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val args = JSONObject().put("streamId", streamId).put("partition", partition)
        if (password != null) args.put("password", password)
        if (dmSealerKey != null) args.put("dmSealerKey", dmSealerKey)
        if (channelEphemeral) args.put("channelEphemeral", true)
        val argsB64 = Base64.encodeToString(args.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(data, Base64.NO_WRAP)
        inFlightBytes.addAndGet(data.size.toLong())
        js("bridgePublishBinary('$id','$argsB64','$dataB64')")
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
            inFlightBytes.addAndGet(-data.size.toLong())
        }
    }

    /**
     * Publishes a storage-file chunk on a message-stream (-1) chunk partition
     * and returns the timestamp the network assigned it — upload verify matches
     * stored rows to published chunks by timestamp. Suspends until the network
     * has taken it, so the engine can cap how much it puts in flight.
     *
     * The chunk is already sealed (or plaintext) — storage crypto is native
     * (one PBKDF2 per FILE via [com.pombo.android.core.PomboCrypto], then
     * per-chunk AES-GCM), unlike the mesh per-piece path that seals in the
     * bridge. So nothing here touches password/ECDH; the bytes cross as-is.
     *
     * Kept off [call] for the same reason as [publishBinary]: that path would
     * base64 the whole args JSON, double-encoding the payload.
     *
     * @return the publish timestamp in epoch milliseconds
     */
    suspend fun publishStorageChunk(
        streamId: String,
        partition: Int,
        data: ByteArray,
        /** DM chunk: sealed in the bridge with the pair's ECDH key ([iv][ct]). */
        dmPeerPublicKey: String? = null,
        /**
         * DM chunk: key of a bridge-held per-transfer throwaway identity
         * (dmChunkIdentityCreate). The chunk bytes stay on the pair key; only
         * the PUBLISHER is disposable, and upload verify matches its address.
         */
        chunkIdentityKey: String? = null,
        timeoutMs: Long = 45_000
    ): Long {
        withTimeout(timeoutMs) { pageReadyFlow.first { it } }

        val id = "sc" + nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val args = JSONObject().put("streamId", streamId).put("partition", partition)
        if (dmPeerPublicKey != null) args.put("dmPeerPublicKey", dmPeerPublicKey)
        if (chunkIdentityKey != null) args.put("chunkIdentityKey", chunkIdentityKey)
        val argsB64 = Base64.encodeToString(args.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(data, Base64.NO_WRAP)
        inFlightBytes.addAndGet(data.size.toLong())
        js("bridgePublishStorageChunk('$id','$argsB64','$dataB64')")
        try {
            val res = withTimeout(timeoutMs) { deferred.await() }
            // Fall back to local time only if the bridge could not read one; the
            // window match tolerates a small skew via its padding.
            val ts = res.optLong("timestamp", 0L)
            return if (ts > 0L) ts else System.currentTimeMillis()
        } finally {
            pending.remove(id)
            inFlightBytes.addAndGet(-data.size.toLong())
        }
    }

    private fun js(script: String) {
        main.post { webView.evaluateJavascript(script, null) }
    }

    private inner class JsApi {
        @JavascriptInterface
        fun status(s: String) { main.post { listener.onBridgeStatus(s) } }

        @JavascriptInterface
        fun connected(addr: String) {
            connected = true
            clientReadyFlow.value = true
            main.post { listener.onBridgeConnected(addr) }
        }

        @JavascriptInterface
        fun message(streamId: String, partition: Int, contentB64: String, metaB64: String) {
            val content = try { String(Base64.decode(contentB64, Base64.NO_WRAP), Charsets.UTF_8) } catch (e: Exception) { return }
            val meta = try { String(Base64.decode(metaB64, Base64.NO_WRAP), Charsets.UTF_8) } catch (e: Exception) { "{}" }
            listener.onBridgeMessage(streamId, partition, content, meta)
        }

        @JavascriptInterface
        fun binary(streamId: String, partition: Int, dataB64: String, metaB64: String) {
            val data = try { Base64.decode(dataB64, Base64.NO_WRAP) } catch (e: Exception) { return }
            val meta = try { String(Base64.decode(metaB64, Base64.NO_WRAP), Charsets.UTF_8) } catch (e: Exception) { "{}" }
            listener.onBridgeBinary(streamId, partition, data, meta)
        }

        @JavascriptInterface
        fun error(msg: String) { main.post { listener.onBridgeError(msg) } }

        @JavascriptInterface
        fun result(id: String, ok: Boolean, payloadB64: String) {
            val deferred = pending.remove(id) ?: return
            val json = try {
                JSONObject(String(Base64.decode(payloadB64, Base64.NO_WRAP), Charsets.UTF_8))
            } catch (e: Exception) {
                deferred.completeExceptionally(BridgeException("invalid bridge payload"))
                return
            }
            if (ok) deferred.complete(json)
            else deferred.completeExceptionally(BridgeException(json.optString("error", "bridge error")))
        }
    }

    fun destroy() {
        failAllPending("bridge destroyed")
        main.post { webView.destroy() }
    }

    companion object {
        private const val BASE_URL = "https://pombo.local/"
    }
}
