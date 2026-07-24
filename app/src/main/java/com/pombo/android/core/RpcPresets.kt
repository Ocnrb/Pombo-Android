package com.pombo.android.core

/**
 * Polygon RPC endpoint presets — same keys, labels and URLs as the web's
 * RPC_PRESETS (config.js), driving the Settings → API picker.
 */
object RpcPresets {

    data class Preset(val key: String, val label: String, val urls: List<String>)

    val ALL = listOf(
        Preset("drpc", "dRPC (Recommended)", listOf("https://polygon.drpc.org")),
        Preset(
            "auto", "Auto (try all endpoints)", listOf(
                "https://polygon.drpc.org",
                "https://polygon-bor-rpc.publicnode.com",
                "https://rpc.ankr.com/polygon",
                "https://polygon.meowrpc.com",
                "https://polygon.gateway.tenderly.co",
                "https://polygon.llamarpc.com",
                "https://1rpc.io/matic"
            )
        ),
        Preset("publicnode", "PublicNode", listOf("https://polygon-bor-rpc.publicnode.com")),
        Preset("ankr", "Ankr", listOf("https://rpc.ankr.com/polygon")),
        Preset("meowrpc", "Meow RPC", listOf("https://polygon.meowrpc.com")),
        Preset("tenderly", "Tenderly", listOf("https://polygon.gateway.tenderly.co")),
        Preset("llamarpc", "Llama RPC", listOf("https://polygon.llamarpc.com")),
        Preset("1rpc", "1RPC (Privacy)", listOf("https://1rpc.io/matic")),
        Preset("custom", "Custom URL...", emptyList())
    )

    fun byKey(key: String): Preset = ALL.firstOrNull { it.key == key } ?: ALL.first()

    /** The endpoints the current selection stands for (custom = just the URL). */
    fun urlsFor(preset: String, customUrl: String?): List<String> =
        if (preset == "custom" && !customUrl.isNullOrBlank()) listOf(customUrl.trim())
        else byKey(preset).urls

    /**
     * Endpoints known to answer CORS from the WebView's https://pombo.local
     * origin. The rest work on the web (real origin) but not in the bridge.
     */
    private val WEBVIEW_SAFE = listOf(
        "https://polygon.drpc.org",
        "https://polygon-bor-rpc.publicnode.com",
        "https://rpc.ankr.com/polygon"
    )

    /**
     * What the bridge's Streamr client gets: the chosen endpoints first, then
     * the CORS-safe trio — so a pick that fails CORS inside the WebView still
     * falls through to a working endpoint instead of killing the connection.
     */
    fun bridgeUrls(preset: String, customUrl: String?): List<String> =
        (urlsFor(preset, customUrl) + WEBVIEW_SAFE).distinct()
}
