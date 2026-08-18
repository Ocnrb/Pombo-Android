package com.pombo.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gas estimation and balance queries for Polygon — mirror of the web
 * src/js/ui/GasEstimator.js.
 *
 * Deliberately does NOT go through the WebView bridge: the web does the same,
 * hitting the public RPCs with plain JSON-RPC rather than the Streamr SDK, so
 * an estimate never waits on the bridge being connected.
 */
object GasEstimator {

    /** CONFIG.network.rpcEndpoints — tried in order, sticky on the last success. */
    private val RPC_URLS = listOf(
        "https://polygon.drpc.org",
        "https://polygon-bor-rpc.publicnode.com",
        "https://rpc.ankr.com/polygon",
        "https://polygon.meowrpc.com",
        "https://polygon.gateway.tenderly.co",
        "https://polygon.llamarpc.com",
        "https://1rpc.io/matic"
    )

    @Volatile private var currentRpcIndex = 0

    /** CONFIG.network.rpcTimeoutMs */
    private const val RPC_TIMEOUT_MS = 5_000

    /** CONFIG.network.fallbackGasPriceGwei — used when every RPC fails. */
    private const val FALLBACK_GAS_PRICE_GWEI = 120L

    /** Approximate gas units for Streamr operations (based on real tx data). */
    private const val GAS_CREATE_STREAM = 420_000L
    private const val GAS_SET_PUBLIC_PERMISSIONS = 80_000L
    private const val GAS_SET_PERMISSIONS_BATCH = 210_000L
    private const val GAS_ADD_STORAGE_NODE = 165_000L
    private const val GAS_SET_STORAGE_DAY_COUNT = 50_000L

    private const val CACHE_DURATION_MS = 60_000L
    @Volatile private var cachedGasPrice: BigInteger? = null
    @Volatile private var cacheTime = 0L

    /**
     * Estimated cost per channel type, in wei, plus the gas price they came
     * from (the web shows it as the tooltip on the cost).
     */
    data class Costs(
        val public: BigInteger,
        val password: BigInteger,
        val native: BigInteger,
        val dmInbox: BigInteger,
        val gasPrice: BigInteger
    ) {
        /** Cost for a channel `type` as used by NewChannel/Channel. */
        fun forType(type: String): BigInteger = when (type) {
            "native", "gated" -> native
            "dmInbox" -> dmInbox
            else -> public
        }

        fun formattedFor(type: String): String = formatPOL(forType(type))
        val formattedGasPrice: String get() = formatGwei(gasPrice)
    }

    private suspend fun rpcCall(method: String, params: JSONArray = JSONArray()): JSONObject? =
        withContext(Dispatchers.IO) {
            val startIndex = currentRpcIndex
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params)
                .put("id", 1)
                .toString()
                .toByteArray()

            for (i in RPC_URLS.indices) {
                val index = (startIndex + i) % RPC_URLS.size
                try {
                    val conn = (URL(RPC_URLS[index]).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = RPC_TIMEOUT_MS
                        readTimeout = RPC_TIMEOUT_MS
                        setRequestProperty("Content-Type", "application/json")
                    }
                    conn.outputStream.use { it.write(body) }
                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        continue
                    }
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = JSONObject(text)
                    if (json.has("error")) continue
                    currentRpcIndex = index
                    return@withContext json
                } catch (e: Exception) {
                    continue
                }
            }
            null
        }

    private fun hexToWei(hex: String?): BigInteger? = try {
        hex?.removePrefix("0x")?.takeIf { it.isNotEmpty() }?.let { BigInteger(it, 16) }
    } catch (e: Exception) {
        null
    }

    suspend fun getGasPrice(): BigInteger {
        val now = System.currentTimeMillis()
        cachedGasPrice?.let { if (now - cacheTime < CACHE_DURATION_MS) return it }

        val wei = hexToWei(rpcCall("eth_gasPrice")?.optString("result"))
            // Fallback gas price if all RPCs fail — not cached, so the next
            // call retries the network instead of sticking to the guess.
            ?: return BigInteger.valueOf(FALLBACK_GAS_PRICE_GWEI * 1_000_000_000L)

        cachedGasPrice = wei
        cacheTime = now
        return wei
    }

    suspend fun estimateCosts(): Costs {
        val gasPrice = getGasPrice()
        fun cost(units: Long) = gasPrice.multiply(BigInteger.valueOf(units))

        // Channels create 3 streams (-1 message + -2 ephemeral + -3 admin) +
        // permissions on all three. Storage is enabled on -1 and -3 → 2×
        // addStorageNode + 2× setStorageDayCount.
        val publicCost = cost(
            3 * GAS_CREATE_STREAM +
                3 * GAS_SET_PUBLIC_PERMISSIONS +
                2 * GAS_ADD_STORAGE_NODE +
                2 * GAS_SET_STORAGE_DAY_COUNT
        )
        // Native adds the keys stream (-4, with storage) — N-A epoch keys.
        val nativeCost = cost(
            4 * GAS_CREATE_STREAM +
                4 * GAS_SET_PERMISSIONS_BATCH +
                3 * GAS_ADD_STORAGE_NODE +
                3 * GAS_SET_STORAGE_DAY_COUNT
        )
        // DM inbox: 2 streams (-1 + -2) + 2 public permissions + storage on -1.
        val dmInboxCost = cost(
            2 * GAS_CREATE_STREAM +
                2 * GAS_SET_PUBLIC_PERMISSIONS +
                GAS_ADD_STORAGE_NODE +
                GAS_SET_STORAGE_DAY_COUNT
        )

        return Costs(
            public = publicCost,
            password = publicCost,
            native = nativeCost,
            dmInbox = dmInboxCost,
            gasPrice = gasPrice
        )
    }

    /** null when every RPC failed — callers must not treat that as "broke". */
    suspend fun getBalance(address: String): BigInteger? =
        hexToWei(rpcCall("eth_getBalance", JSONArray().put(address).put("latest"))?.optString("result"))

    /** Streamr DATA token on Polygon. */
    private const val DATA_TOKEN_POLYGON = "0x3a9A81d576d83FF21f26f325066054540720fC34"

    /** ERC-20 balanceOf(address) via eth_call. null when every RPC failed. */
    suspend fun getDataBalance(address: String): BigInteger? {
        val padded = address.removePrefix("0x").lowercase().padStart(64, '0')
        val call = JSONObject()
            .put("to", DATA_TOKEN_POLYGON)
            .put("data", "0x70a08231$padded")
        return ethCall(call)
    }

    /**
     * eth_call across the RPC list. NOT the shared [rpcCall]: an endpoint can
     * answer 200 with no error but an empty `"0x"` result (method disabled or
     * state pruned), which the generic path accepts as success — that is what
     * silently blanked the DATA balance once the sticky index rotated onto
     * such a node. Here only a real 32-byte word counts; anything else moves
     * on to the next endpoint.
     */
    private suspend fun ethCall(call: JSONObject): BigInteger? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "eth_call")
            .put("params", JSONArray().put(call).put("latest"))
            .put("id", 1)
            .toString()
            .toByteArray()
        for (i in RPC_URLS.indices) {
            val index = (currentRpcIndex + i) % RPC_URLS.size
            val url = RPC_URLS[index]
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = RPC_TIMEOUT_MS
                    readTimeout = RPC_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body) }
                if (conn.responseCode != 200) {
                    android.util.Log.d(TAG, "ethCall $url -> HTTP ${conn.responseCode}")
                    conn.disconnect()
                    continue
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(text)
                if (json.has("error")) {
                    android.util.Log.d(TAG, "ethCall $url -> error: ${json.optJSONObject("error")?.optString("message")}")
                    continue
                }
                val result = json.optString("result")
                if (result.startsWith("0x") && result.length > 2) {
                    android.util.Log.d(TAG, "ethCall $url -> ok (${result.length} chars)")
                    return@withContext hexToWei(result)
                }
                android.util.Log.d(TAG, "ethCall $url -> empty result '$result'")
            } catch (e: Exception) {
                android.util.Log.d(TAG, "ethCall $url -> ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        android.util.Log.w(TAG, "ethCall: every endpoint failed")
        null
    }

    private const val TAG = "GasEstimator"

    /**
     * Web testRpcConnection: eth_blockNumber against each URL (max 3), 5s
     * timeout each. Returns how many answered with a result.
     */
    suspend fun testEndpoints(urls: List<String>): Int = withContext(Dispatchers.IO) {
        var working = 0
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "eth_blockNumber")
            .put("params", JSONArray())
            .put("id", 1)
            .toString()
            .toByteArray()
        for (url in urls.take(3)) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = RPC_TIMEOUT_MS
                    readTimeout = RPC_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body) }
                val ok = conn.responseCode == 200 &&
                    JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optString("result").isNotEmpty()
                conn.disconnect()
                if (ok) working++
            } catch (e: Exception) {
            }
        }
        working
    }

    fun formatBalanceDATA(wei: BigInteger?): String {
        if (wei == null) return "Error"
        val v = wei.toDouble() / 1e18
        return when {
            v == 0.0 -> "0 DATA"
            v < 0.0001 -> "< 0.0001 DATA"
            v < 1 -> fmt("%.4f DATA", v)
            v < 100 -> fmt("%.3f DATA", v)
            else -> fmt("%.2f DATA", v)
        }
    }

    private fun toPol(wei: BigInteger): Double = wei.toDouble() / 1e18

    // Locale.US throughout: JS toFixed always emits a dot, and a pt-PT device
    // would otherwise show "0,0001 POL".
    private fun fmt(pattern: String, value: Double) =
        String.format(java.util.Locale.US, pattern, value)

    fun formatPOL(wei: BigInteger): String {
        val pol = toPol(wei)
        return when {
            pol < 0.0001 -> "< 0.0001 POL"
            pol < 0.01 -> fmt("~%.4f POL", pol)
            else -> fmt("~%.3f POL", pol)
        }
    }

    fun formatGwei(wei: BigInteger): String = fmt("%.1f gwei", wei.toDouble() / 1e9)

    fun formatBalancePOL(wei: BigInteger?): String {
        if (wei == null) return "Error"
        val pol = toPol(wei)
        return when {
            pol == 0.0 -> "0 POL"
            pol < 0.0001 -> "< 0.0001 POL"
            pol < 1 -> fmt("%.4f POL", pol)
            pol < 100 -> fmt("%.3f POL", pol)
            else -> fmt("%.2f POL", pol)
        }
    }
}
