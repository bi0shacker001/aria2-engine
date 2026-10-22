package io.github.bi0shacker001.aria2engine

import android.os.ParcelFileDescriptor

/** The host's sandbox broker for the engine process (native/broker/broker_jni.c), in the host's main process. */
internal object EngineBroker {
    init {
        System.loadLibrary("aria2broker")
    }

    /**
     * Starts a broker that maps [EngineConfig.VIRTUAL_ROOT] to [realRoot] and, with [network], makes the engine's
     * sockets, to the internet only unless [localNetwork] (net_scope.c); with [systemReads] (an engine under the host's
     * UID) it also answers the platform's read-only files. Returns the engine's end of its socket.
     */
    fun serve(
        realRoot: String,
        network: Boolean,
        systemReads: Boolean,
        codeReads: List<String> = emptyList(),
        localNetwork: Boolean = false,
    ): ParcelFileDescriptor {
        val fd = nativeServe(EngineConfig.VIRTUAL_ROOT, realRoot, network, localNetwork, systemReads, codeReads.toTypedArray())
        check(fd >= 0) { "the engine's broker could not start ($fd)" }
        return ParcelFileDescriptor.adoptFd(fd)
    }

    /**
     * A pass for [address] at [port] on the local network: the host walked an HTTP download there itself, so a connect to
     * exactly it is let through while every other local address stays refused (net_scope.c). Counted: each [allowLocal]
     * needs its [revokeLocal].
     */
    fun allowLocal(address: java.net.InetAddress, port: Int) {
        val rv = nativeAllowLocal(words(address, port))
        check(rv == 0) { "the engine's broker could not let ${address.hostAddress}:$port through ($rv)" }
    }

    fun revokeLocal(address: java.net.InetAddress, port: Int) {
        nativeRevokeLocal(words(address, port))
    }

    /** An address as the broker words it: "a.b.c.d:port" or "[v6]:port", no zone. */
    internal fun words(address: java.net.InetAddress, port: Int): String {
        val host = address.hostAddress.orEmpty().substringBefore('%')
        return if (address is java.net.Inet6Address) "[$host]:$port" else "$host:$port"
    }

    @JvmStatic private external fun nativeAllowLocal(words: String): Int

    @JvmStatic private external fun nativeRevokeLocal(words: String): Int

    @JvmStatic private external fun nativeServe(
        virtualRoot: String,
        realRoot: String,
        network: Boolean,
        localNetwork: Boolean,
        systemReads: Boolean,
        codeReads: Array<String>,
    ): Int
}
