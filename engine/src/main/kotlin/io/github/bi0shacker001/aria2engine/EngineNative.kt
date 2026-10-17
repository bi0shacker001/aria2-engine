package io.github.bi0shacker001.aria2engine

/**
 * libaria2 through JNI (native/engine/engine_jni.cc), in the engine process only. Every call but [lockdown] must come
 * from the engine thread ([EngineThread]): libaria2 is not thread-safe.
 */
internal object EngineNative {
    init {
        System.loadLibrary("aria2engine")
    }

    /** Receives libaria2's download events, raised on the engine thread inside [runOnce]. */
    @Volatile
    var listener: ((event: Int, gid: String) -> Unit)? = null

    @JvmStatic
    fun onEvent(event: Int, gid: String) {
        listener?.invoke(event, gid)
    }

    @JvmStatic external fun lockdown(brokerFd: Int, claims: Array<String>, brokeredNet: Boolean): String
    @JvmStatic external fun sessionNew(keys: Array<String>, values: Array<String>): Long
    @JvmStatic external fun runOnce(session: Long): Int
    @JvmStatic external fun addUri(session: Long, uris: Array<String>, keys: Array<String>, values: Array<String>): String
    @JvmStatic external fun addTorrent(session: Long, path: String, keys: Array<String>, values: Array<String>): String
    @JvmStatic external fun addMetalink(session: Long, path: String, keys: Array<String>, values: Array<String>): String
    @JvmStatic external fun pause(session: Long, gid: String, force: Boolean): Int
    @JvmStatic external fun unpause(session: Long, gid: String): Int
    @JvmStatic external fun remove(session: Long, gid: String, force: Boolean): Int
    @JvmStatic external fun changeOption(session: Long, gid: String, keys: Array<String>, values: Array<String>): Int
    @JvmStatic external fun changeGlobalOption(session: Long, keys: Array<String>, values: Array<String>): Int
    @JvmStatic external fun activeDownloads(session: Long): String
    @JvmStatic external fun status(session: Long, gid: String): String?
    @JvmStatic external fun shutdown(session: Long, force: Boolean): Int
    @JvmStatic external fun sessionFinal(session: Long): Int
}
