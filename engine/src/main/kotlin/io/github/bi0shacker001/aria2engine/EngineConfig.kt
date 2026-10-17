package io.github.bi0shacker001.aria2engine

/**
 * What the engine lets libaria2 do (docs/design.md, "Options"). Pure, so it is unit-tested on the JVM.
 *
 * aria2 has options that run a program (`on-download-complete` and the other hooks) or name a file anywhere (a
 * session, a cookie jar, a log, certificates). The engine refuses all of them from any caller, the host included:
 * nothing it downloads is ever run, and every file it touches is under [VIRTUAL_ROOT], which the host's broker maps
 * to the host's chosen folder. A download's `dir` must stay under [VIRTUAL_ROOT].
 */
object EngineConfig {
    /** The folder libaria2 sees; the host's broker maps it to a real folder of the host's choosing. */
    const val VIRTUAL_ROOT = "/aria2/downloads"

    /** The lockdown's claim: every path under it goes to the broker. */
    const val CLAIM = "/aria2"

    /** aria2's state files (DHT routing tables, the CA bundle), kept beside the downloads. */
    const val STATE = "$VIRTUAL_ROOT/.state"

    /**
     * The platform's CA certificates as one PEM file, written by the host at start ([CaBundle]). Android names its
     * certificate files by OpenSSL's old subject hash, which OpenSSL 3 does not look up, so a directory will not do.
     */
    const val CA_BUNDLE = "$STATE/ca-bundle.pem"

    /** Options no caller may set: they run programs or name files outside [VIRTUAL_ROOT]. */
    private val REFUSED = setOf(
        "on-download-start", "on-download-pause", "on-download-stop", "on-download-complete", "on-download-error",
        "on-bt-download-complete", "conf-path", "input-file", "save-session", "save-session-interval", "log",
        "load-cookies", "save-cookies", "netrc-path", "private-key", "certificate", "ca-certificate",
        "rpc-certificate", "rpc-private-key", "rpc-secure", "dht-file-path", "dht-file-path6", "server-stat-of",
        "server-stat-if", "daemon", "stop-with-process", "rpc-listen-all", "rpc-allow-origin-all",
        "check-certificate", "deferred-input", "event-poll",
    )

    /** What every session starts with; a host option of the same name replaces it (unless it is [REFUSED]). */
    private val DEFAULTS = linkedMapOf(
        "dir" to VIRTUAL_ROOT,
        "continue" to "true",
        "allow-overwrite" to "false",
        "auto-file-renaming" to "false",
        "file-allocation" to "none",
        "max-concurrent-downloads" to "3",
        "max-connection-per-server" to "4",
        "split" to "4",
        "min-split-size" to "4M",
        "max-tries" to "5",
        "retry-wait" to "5",
        "timeout" to "30",
        "connect-timeout" to "20",
        // A torrent seeds only when the host asks for it (the owner's rule on uploads is the host's to apply).
        "seed-time" to "0",
        "bt-save-metadata" to "false",
        "bt-max-open-files" to "64",
        "enable-dht" to "true",
        "enable-dht6" to "false",
        "follow-torrent" to "true",
        "follow-metalink" to "true",
        "enable-rpc" to "false",
    )

    /** Set last, whatever was asked. */
    private val FORCED = mapOf(
        "check-certificate" to "true",
        "ca-certificate" to CA_BUNDLE,
        "rpc-listen-all" to "false",
        "rpc-allow-origin-all" to "false",
        "dht-file-path" to "$STATE/dht.dat",
        "dht-file-path6" to "$STATE/dht6.dat",
        "quiet" to "true",
    )

    /** The session's global options: [DEFAULTS], then the host's (less [REFUSED]), then [FORCED]. */
    fun sessionOptions(host: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(DEFAULTS)
        out.putAll(perDownload(host))
        // aria2's RPC is for the host alone: it is off unless the host gives it a secret.
        if (out["enable-rpc"] == "true" && out["rpc-secret"].isNullOrBlank()) out["enable-rpc"] = "false"
        out.putAll(FORCED)
        return out
    }

    /** A change to the session's global options at run time: the same filter, never the forced ones. */
    fun globalChange(host: Map<String, String>): Map<String, String> = perDownload(host).filterKeys { it !in FORCED && it != "enable-rpc" && it != "rpc-secret" && it != "rpc-listen-port" }

    /**
     * One download's options: [REFUSED] ones dropped, and a `dir` outside [VIRTUAL_ROOT] (or with `..` in it)
     * replaced by [VIRTUAL_ROOT].
     */
    fun perDownload(host: Map<String, String>): Map<String, String> = buildMap {
        for ((key, value) in host) {
            if (key in REFUSED || key.startsWith("on-")) continue
            put(key, if (key == "dir" && !insideRoot(value)) VIRTUAL_ROOT else value)
        }
    }

    fun insideRoot(path: String): Boolean =
        (path == VIRTUAL_ROOT || path.startsWith("$VIRTUAL_ROOT/")) && path.split('/').none { it == ".." }
}
