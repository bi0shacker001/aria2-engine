package io.github.bi0shacker001.aria2engine

import android.os.ParcelFileDescriptor

/** The host's sandbox broker for the engine process (native/broker/broker_jni.c), in the host's main process. */
internal object EngineBroker {
    init {
        System.loadLibrary("aria2broker")
    }

    /**
     * Starts a broker that maps [EngineConfig.VIRTUAL_ROOT] to [realRoot] and, with [network], makes the engine's
     * sockets; with [systemReads] (an engine under the host's UID) it also answers the platform's read-only files.
     * Returns the engine's end of its socket.
     */
    fun serve(realRoot: String, network: Boolean, systemReads: Boolean, codeReads: List<String> = emptyList()): ParcelFileDescriptor {
        val fd = nativeServe(EngineConfig.VIRTUAL_ROOT, realRoot, network, systemReads, codeReads.toTypedArray())
        check(fd >= 0) { "the engine's broker could not start ($fd)" }
        return ParcelFileDescriptor.adoptFd(fd)
    }

    @JvmStatic private external fun nativeServe(virtualRoot: String, realRoot: String, network: Boolean, systemReads: Boolean, codeReads: Array<String>): Int
}
