package com.pombo.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * The YouTube player, in its OWN PROCESS (`:player`, see the manifest).
 *
 * Not an optimisation — a hard requirement, proven by isolation: the main
 * process hosts the bridge's headless WebView (never attached to a window),
 * and any OTHER WebView sharing that process plays video as sound over a
 * black picture — inline, in a Dialog window, on phone and emulator alike.
 * The same iframe in a bridge-free process renders normally. So the player
 * gets a bridge-free process; back closes it, Android reaps the process.
 */
class YtProbeActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Two processes may not share a WebView data directory (Android 9+).
        runCatching { WebView.setDataDirectorySuffix("player") }
        val videoId = intent.getStringExtra("videoId") ?: "dQw4w9WgXcQ"
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(android.graphics.Color.BLACK)
            webChromeClient = WebChromeClient()
            val html = """
                <html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
                iframe{width:100%;height:100%;border:0}</style>
                </head><body>
                <iframe src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0"
                    allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe>
                </body></html>
            """.trimIndent()
            loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
        }
        setContentView(wv)
    }
}
