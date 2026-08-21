package com.pombo.android.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
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
        /** Step announcements from multi-transaction bridge flows (gatePay). */
        fun onBridgeProgress(op: String, step: String) {}
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
            // Kept on: the SDK caches group keys for legacy history in
            // IndexedDB; without DOM storage every such row costs a key
            // request round-trip per boot.
            settings.domStorageEnabled = true
            // This page holds the identity key, so everything it does not
            // need is closed off. Cleartext ws:// peers were already dead
            // under usesCleartextTraffic="false", and the https:// web build
            // proves the wss/WebRTC-only topology works without them.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(JsApi(), "Native")
            webViewClient = object : WebViewClient() {
                // Loaded via loadDataWithBaseURL and replaced only through
                // reconnect() — the page itself never navigates, so any
                // navigation request is dropped.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

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

                /**
                 * Without overriding this, WebView's own crash handling aborts the
                 * WHOLE APP the moment the renderer dies (crashpad: "Render
                 * process crash wasn't handled by all associated webviews,
                 * triggering application crash") — returning true here is what
                 * stops that. reconnect() reloads the bridge fresh in the same
                 * WebView instance; every pending bridge.call() unwinds via
                 * failAllPending() exactly like an explicit reconnect() would, so
                 * callers see a rejected call instead of a hang.
                 */
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.w(
                        "PomboBridge",
                        "WebView render process gone (crashed=${detail.didCrash()}, " +
                            "priority=${detail.rendererPriorityAtExit()}) — reconnecting bridge"
                    )
                    reconnect()
                    return true
                }
            }
            // JS console -> logcat; without this, errors inside the WebView are
            // invisible. Release forwards WARNING+ only (web logger.js is
            // ERROR-only in production — a full DEBUG gate would blind the
            // release builds we actually test with). No key redaction: the
            // private key never enters this page (Phase C), so the console
            // cannot echo it.
            val debuggable = (appContext.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    val level = msg.messageLevel()
                    if (!debuggable &&
                        level != ConsoleMessage.MessageLevel.WARNING &&
                        level != ConsoleMessage.MessageLevel.ERROR
                    ) return true
                    Log.d("PomboBridgeJS", "[$level] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
            }
        }
        webView.resumeTimers()
        bridgeHtml = appContext.assets.open("pombo_bridge.html").readBytes().toString(Charsets.UTF_8)
        webView.loadDataWithBaseURL(BASE_URL, pageHtml(), "text/html", "utf-8", null)
    }

    // The key never enters the page (docs/private_key_in_webview.md, Phase C):
    // the page gets the account address + compressed public key and delegates
    // every signature to the Native oracle endpoints below.
    private fun pageHtml() = bridgeHtml
        .replace(
            "__BRIDGE_ADDR__",
            if (privateKey.isEmpty()) ""
            else com.pombo.android.core.EthereumSigner.checksumAddress(
                com.pombo.android.core.EthereumSigner.address(privateKey)
            )
        )
        .replace(
            "__BRIDGE_PUB__",
            if (privateKey.isEmpty()) ""
            else com.pombo.android.core.EthereumSigner.compressedPublicKey(privateKey)
        )
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
        // one has to ask for itself — and the window must stay OPEN for the
        // whole JS run, because the transaction signature now comes back
        // through the native oracle (signTransactionPayload), which checks
        // isApproved() again. Headless contexts (FCM, sync) still cannot
        // authorise at all: there is no activity to prompt on.
        if (method in com.pombo.android.ui.ChainGuard.CHAIN_METHODS &&
            !com.pombo.android.ui.ChainGuard.isApproved()
        ) {
            val ok = com.pombo.android.ui.ChainGuard.approve(
                "Blockchain transaction",
                "Pombo is about to submit a \"$method\" transaction."
            )
            if (!ok) throw BridgeException("Transaction not confirmed")
            return com.pombo.android.ui.ChainGuard.holding { dispatch(method, args, timeoutMs) }
        }
        return dispatch(method, args, timeoutMs)
    }

    private suspend fun dispatch(method: String, args: JSONObject, timeoutMs: Long): JSONObject {
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
         * Publish under this throwaway identity. The frame arrives finished —
         * 0x02 sealed natively (SealedSenderCrypto) or 0x03 with the native
         * channel proof — so the page only password-seals (PBKDF2 stays in
         * BoringSSL) and publishes; no key material is read bridge-side.
         */
        identityPk: String? = null,
        /**
         * Epoch piece on a GATED channel: publish as the gate clone
         * (ERC-1271) — the bytes arrive already epoch-sealed (0x04).
         */
        gateAddress: String? = null,
        /**
         * Epoch piece on a NATIVE channel: publish as the account with
         * encryptionType NONE (client.publish on a members-only stream would
         * re-wrap the sealed bytes in the SDK's group-key AES).
         */
        asAccount: Boolean = false,
        timeoutMs: Long = 45_000
    ) {
        withTimeout(timeoutMs) { pageReadyFlow.first { it } }

        val id = "b" + nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val args = JSONObject().put("streamId", streamId).put("partition", partition)
        if (password != null) args.put("password", password)
        if (identityPk != null) args.put("identityPk", identityPk)
        if (gateAddress != null) args.put("gateAddress", gateAddress)
        if (asAccount) args.put("asAccount", true)
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
     * per-chunk AES-GCM; DM chunks pair-sealed via SealedSenderCrypto). So
     * nothing here touches password/ECDH; the bytes cross as-is.
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
        /**
         * Publish under this throwaway identity — the per-transfer DM chunk
         * key or the channel pseudonym, both minted natively. DM chunk bytes
         * arrive already pair-sealed (SealedSenderCrypto.pairSeal); only the
         * publisher is decided here. Native/readOnly channels leave this null
         * and publish under the account.
         */
        identityPk: String? = null,
        /** Gated channel chunk: publish as the gate clone (ERC-1271). */
        gateAddress: String? = null,
        /**
         * Native epoch chunk: publish as the account with encryptionType NONE
         * (client.publish on a members-only stream would re-wrap the sealed
         * bytes in the SDK's group-key AES and break the HTTP hex reader).
         */
        asAccount: Boolean = false,
        timeoutMs: Long = 45_000
    ): Long {
        withTimeout(timeoutMs) { pageReadyFlow.first { it } }

        val id = "sc" + nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val args = JSONObject().put("streamId", streamId).put("partition", partition)
        if (identityPk != null) args.put("identityPk", identityPk)
        if (gateAddress != null) args.put("gateAddress", gateAddress)
        if (asAccount) args.put("asAccount", true)
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
        /**
         * Streamr/personal-message signature over the payload. The EIP-191
         * magic is applied HERE, so this endpoint can never yield a valid
         * transaction signature no matter what a compromised renderer feeds
         * it (the "no opaque digests" oracle rule). Synchronous on purpose:
         * ~1–2 ms of ECDSA on the JavaBridge thread beats a correlation
         * round-trip per publish. Empty string = no identity / bad input.
         */
        @JavascriptInterface
        fun signMessagePayload(payloadB64: String): String {
            val pk = privateKey
            if (pk.isEmpty()) return ""
            return try {
                com.pombo.android.core.EthereumSigner.toHex(
                    com.pombo.android.core.EthereumSigner.signMessage(
                        Base64.decode(payloadB64, Base64.NO_WRAP), pk
                    )
                )
            } catch (e: Exception) { "" }
        }

        /**
         * Polygon transaction signature. This is a JS→Kotlin entry the
         * [call] method gate never sees, so the spend gate repeats here:
         * the bytes must parse as a transaction envelope AND the call must
         * arrive inside a ChainGuard approval window. Empty string = refused.
         */
        @JavascriptInterface
        fun signTransactionPayload(unsignedHex: String): String {
            val pk = privateKey
            if (pk.isEmpty()) return ""
            return try {
                val bytes = com.pombo.android.core.SealedSenderCrypto.hexToBytes(unsignedHex)
                if (!com.pombo.android.core.Rlp.isTransactionEnvelope(bytes)) return ""
                if (!com.pombo.android.ui.ChainGuard.isApproved()) return ""
                com.pombo.android.core.EthereumSigner.toHex(
                    com.pombo.android.core.EthereumSigner.signDigest(
                        com.pombo.android.core.SealedSenderCrypto.keccak256(bytes), pk
                    )
                )
            } catch (e: Exception) { "" }
        }

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
        fun progress(op: String, step: String) { main.post { listener.onBridgeProgress(op, step) } }

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
