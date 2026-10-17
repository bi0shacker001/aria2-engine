package io.github.bi0shacker001.aria2engine

import org.json.JSONObject

/** What a download is: one file from URLs (HTTP, HTTPS, FTP, mirrors of one file), a torrent or magnet, or a Metalink. */
enum class DownloadKind { URI, TORRENT, METALINK }

/**
 * One download to add. [uris] for [DownloadKind.URI] (and a magnet link is a URI); [metadata] (a `.torrent` or
 * Metalink file's bytes) for the others, which the host stores under the engine's folder unread and the engine
 * parses. [options] are aria2's per-download options (`dir` relative to the host's folder is set by the host).
 */
data class DownloadRequest(
    val kind: DownloadKind,
    val uris: List<String> = emptyList(),
    val metadata: ByteArray? = null,
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(kind == DownloadKind.URI && uris.isNotEmpty() || kind != DownloadKind.URI && metadata != null) { "a download needs its URIs or its metadata" }
    }

    val isMagnet: Boolean get() = kind == DownloadKind.URI && uris.any { it.startsWith("magnet:") }

    /** BitTorrent, by file or magnet: kept on aria2's own identity (UserAgent.optionsFor). */
    val effectiveKind: DownloadKind get() = if (isMagnet) DownloadKind.TORRENT else kind
}

/** libaria2's DownloadEvent numbers (aria2.h). */
enum class EngineEvent(val code: Int) {
    START(1), PAUSE(2), STOP(3), COMPLETE(4), ERROR(5), BT_COMPLETE(6);

    companion object {
        fun of(code: Int): EngineEvent? = entries.firstOrNull { it.code == code }
    }
}

/** One file of a download, its path as the host sees it. */
data class DownloadFile(val path: String, val length: Long, val completedLength: Long, val selected: Boolean)

/** One download's state ([DownloadEngine.status]); paths are the host's real ones. */
data class DownloadStatus(
    val gid: String,
    val state: String,
    val totalLength: Long,
    val completedLength: Long,
    val uploadLength: Long,
    val downloadSpeed: Int,
    val uploadSpeed: Int,
    val errorCode: Int,
    val followedBy: List<String>,
    val files: List<DownloadFile>,
    val torrentName: String,
) {
    companion object {
        /** Parses the engine's typed status; [realPath] turns the engine's virtual paths into the host's. */
        fun parse(gid: String, json: String, realPath: (String) -> String): DownloadStatus {
            val o = JSONObject(json)
            val followed = o.optJSONArray("followedBy")
            val filesJson = o.optJSONArray("files")
            return DownloadStatus(
                gid = gid,
                state = o.optString("status"),
                totalLength = o.optLong("totalLength"),
                completedLength = o.optLong("completedLength"),
                uploadLength = o.optLong("uploadLength"),
                downloadSpeed = o.optInt("downloadSpeed"),
                uploadSpeed = o.optInt("uploadSpeed"),
                errorCode = o.optInt("errorCode"),
                followedBy = List(followed?.length() ?: 0) { followed!!.getString(it) },
                files = List(filesJson?.length() ?: 0) {
                    val f = filesJson!!.getJSONObject(it)
                    DownloadFile(realPath(f.optString("path")), f.optLong("length"), f.optLong("completedLength"), f.optBoolean("selected"))
                },
                torrentName = o.optString("torrentName"),
            )
        }
    }
}
