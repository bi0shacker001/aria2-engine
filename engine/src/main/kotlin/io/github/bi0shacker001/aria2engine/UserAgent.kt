package io.github.bi0shacker001.aria2engine

/**
 * The user agent the engine's HTTP(S) and FTP downloads report (docs/design.md, "Identity"). The owner: "What if we
 * just report a semi-sanitized android webview version?" So it is the device's WebView user agent reduced to the form
 * Chrome itself now sends: no `; wv`, the platform as `Linux; Android 10; K`, and only Chrome's major version.
 * BitTorrent never uses it: trackers and peers see aria2's own identity.
 */
object UserAgent {
    private val CHROME = Regex("""Chrome/(\d+)[.\d]*""")

    /** The reduced form of [webView] (WebSettings.getDefaultUserAgent's answer), or null when it names no Chrome. */
    fun sanitize(webView: String): String? {
        val major = CHROME.find(webView)?.groupValues?.get(1) ?: return null
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
    }

    /** The one the host should use: the sanitized WebView agent, or the same form for a known recent Chrome. */
    fun forDownloads(webView: String?): String = webView?.let(::sanitize) ?: sanitize("Chrome/$FALLBACK_MAJOR")!!

    /** Used only when the WebView reports no Chrome version at all. */
    const val FALLBACK_MAJOR = 140

    /**
     * The options for one download: an HTTP(S) or FTP download gets [agent] unless the caller gave its own
     * `user-agent` (a site session's); a torrent, a magnet or a Metalink keeps aria2's own.
     */
    fun optionsFor(kind: DownloadKind, options: Map<String, String>, agent: String): Map<String, String> =
        if (kind == DownloadKind.URI && "user-agent" !in options) options + ("user-agent" to agent) else options
}
