package io.github.bi0shacker001.aria2engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The owner's rule (docs/design.md, "Identity"): HTTP(S) and FTP report the reduced WebView agent, BitTorrent aria2's own. */
class UserAgentTest {
    private val webView =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
    private val reduced = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    @Test
    fun `the WebView agent is reduced to Chrome's form`() {
        assertEquals(reduced, UserAgent.sanitize(webView))
        assertFalse(UserAgent.sanitize(webView)!!.contains("; wv"))
        assertFalse("no device model or build", UserAgent.sanitize(webView)!!.contains("Pixel"))
    }

    @Test
    fun `an agent with no Chrome version falls back to the same form`() {
        assertNull(UserAgent.sanitize("Dalvik/2.1.0 (Linux; U; Android 14)"))
        assertEquals("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${UserAgent.FALLBACK_MAJOR}.0.0.0 Mobile Safari/537.36", UserAgent.forDownloads(null))
    }

    @Test
    fun `HTTP downloads get the browser agent and torrents do not`() {
        val http = DownloadRequest(DownloadKind.URI, uris = listOf("https://example.com/game.zip"))
        assertEquals(reduced, UserAgent.optionsFor(http.effectiveKind, http.options, reduced)["user-agent"])

        val magnet = DownloadRequest(DownloadKind.URI, uris = listOf("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertNull("a magnet keeps aria2's own identity", UserAgent.optionsFor(magnet.effectiveKind, magnet.options, reduced)["user-agent"])

        val torrent = DownloadRequest(DownloadKind.TORRENT, metadata = ByteArray(1))
        assertNull(UserAgent.optionsFor(torrent.effectiveKind, torrent.options, reduced)["user-agent"])

        val metalink = DownloadRequest(DownloadKind.METALINK, metadata = ByteArray(1))
        assertNull(UserAgent.optionsFor(metalink.effectiveKind, metalink.options, reduced)["user-agent"])
    }

    @Test
    fun `a request's own agent wins`() {
        val session = DownloadRequest(DownloadKind.URI, uris = listOf("https://example.com/f"), options = mapOf("user-agent" to "Site/1"))
        assertEquals("Site/1", UserAgent.optionsFor(session.effectiveKind, session.options, reduced)["user-agent"])
    }
}
