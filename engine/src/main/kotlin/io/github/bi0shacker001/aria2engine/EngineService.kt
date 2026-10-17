package io.github.bi0shacker001.aria2engine

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * The engine process (docs/design.md): an isolated service, a UID of its own, that confines itself to the host's
 * sandbox broker and then runs one libaria2 session. It holds nothing of the host's: every file it writes is one the
 * host's broker opened under [EngineConfig.VIRTUAL_ROOT], and every socket the broker made.
 *
 * Only the host app's main process drives it: a caller of another UID is refused, and the process that started the
 * session is the only one that may call it after.
 */
open class EngineService : Service() {
    /**
     * True for the isolated engine (Android 11 and later); false for [EngineOpenService], the same engine in a process
     * of the host's UID, still under the lockdown with every path brokered, for Android 8 to 10.
     */
    protected open val isolated: Boolean = true

    private var thread: EngineThread? = null
    private var events: IEngineEvents? = null

    @Volatile
    private var owner = 0

    private fun requireHost() {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val pinned = owner
        if (uid != applicationInfo.uid || (pinned != 0 && pid != pinned)) {
            Log.w(TAG, "refused a call from uid $uid pid $pid")
            throw SecurityException("only the host app drives the download engine")
        }
    }

    private fun engine(): EngineThread = thread ?: throw IllegalStateException("the engine is not started")

    /** A download's options through [EngineConfig.perDownload]. */
    private fun filtered(keys: Array<String>, values: Array<String>): Pair<Array<String>, Array<String>> {
        val options = EngineConfig.perDownload(keys.zip(values).toMap())
        return options.keys.toTypedArray() to options.values.toTypedArray()
    }

    /** A path the engine may hand libaria2: under its one folder, never a host path. */
    private fun requireOwnPath(path: String) {
        require(EngineConfig.insideRoot(path) && path != EngineConfig.VIRTUAL_ROOT) { "not a path of the engine's folder" }
    }

    /** Answers [report], then ends this process once the answer has gone back. */
    private fun endAfter(report: String): String {
        Thread {
            Thread.sleep(500)
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
        return report
    }

    private val binder = object : IEngine.Stub() {
        override fun start(broker: ParcelFileDescriptor, keys: Array<String>, values: Array<String>, events: IEngineEvents): String {
            requireHost()
            synchronized(this@EngineService) {
                if (thread != null) return "error: started"
                // Below Android 11 an isolated process cannot use a socket it was handed, so the brokered network does
                // not work there and the host binds EngineOpenService instead (docs/design.md, "Processes").
                if (isolated && Build.VERSION.SDK_INT < 30) return "error: the isolated engine needs Android 11 or later"
                val report = EngineNative.lockdown(broker.detachFd(), arrayOf(EngineConfig.CLAIM), isolated)
                // The lockdown holds for the life of the process, so a start that fails after it ends the process:
                // the host's next start binds a fresh one, never this one again.
                if (report != "on") return endAfter(report)
                val options = EngineConfig.sessionOptions(keys.zip(values).toMap())
                val session = EngineNative.sessionNew(options.keys.toTypedArray(), options.values.toTypedArray())
                if (session == 0L) return endAfter("error: libaria2 refused the session (its log: ${EngineConfig.LOG})")
                this@EngineService.events = events
                EngineNative.listener = { event, gid -> runCatching { this@EngineService.events?.onEvent(event, gid) } }
                owner = Binder.getCallingPid()
                thread = EngineThread(session).also { it.start() }
                return "on"
            }
        }

        override fun addUri(uris: Array<String>, keys: Array<String>, values: Array<String>): String {
            requireHost()
            val (k, v) = filtered(keys, values)
            return engine().call { EngineNative.addUri(it, uris, k, v) }
        }

        override fun addTorrent(path: String, keys: Array<String>, values: Array<String>): String {
            requireHost()
            requireOwnPath(path)
            val (k, v) = filtered(keys, values)
            return engine().call { EngineNative.addTorrent(it, path, k, v) }
        }

        override fun addMetalink(path: String, keys: Array<String>, values: Array<String>): String {
            requireHost()
            requireOwnPath(path)
            val (k, v) = filtered(keys, values)
            return engine().call { EngineNative.addMetalink(it, path, k, v) }
        }

        override fun pause(gid: String, force: Boolean): Int {
            requireHost()
            return engine().call { EngineNative.pause(it, gid, force) }
        }

        override fun unpause(gid: String): Int {
            requireHost()
            return engine().call { EngineNative.unpause(it, gid) }
        }

        override fun remove(gid: String, force: Boolean): Int {
            requireHost()
            return engine().call { EngineNative.remove(it, gid, force) }
        }

        override fun changeOption(gid: String, keys: Array<String>, values: Array<String>): Int {
            requireHost()
            val (k, v) = filtered(keys, values)
            return engine().call { EngineNative.changeOption(it, gid, k, v) }
        }

        override fun changeGlobalOption(keys: Array<String>, values: Array<String>): Int {
            requireHost()
            val options = EngineConfig.globalChange(keys.zip(values).toMap())
            return engine().call { EngineNative.changeGlobalOption(it, options.keys.toTypedArray(), options.values.toTypedArray()) }
        }

        override fun status(gid: String): String? {
            requireHost()
            return engine().call { EngineNative.status(it, gid) }
        }

        override fun active(): String {
            requireHost()
            return engine().call { EngineNative.activeDownloads(it) }
        }

        // The lockdown and libaria2 start once per process, so a stopped engine ends its process: the host's next
        // start binds a fresh one.
        override fun stop() {
            requireHost()
            val running = thread ?: return
            running.finish()
            Thread {
                running.join(10_000)
                android.os.Process.killProcess(android.os.Process.myPid())
            }.start()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        thread?.finish()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "aria2engine"
    }
}
