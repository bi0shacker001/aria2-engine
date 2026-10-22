package io.github.bi0shacker001.aria2engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout

/**
 * The download engine, as the host's main process sees it (docs/design.md). [start] starts the isolated engine
 * process and its broker; then [add], [pause], [resume], [remove], the limits and [status] drive libaria2 there, and
 * [events] reports its download events. Every file the engine writes is under [root]; a path the engine reports is
 * turned into the host's own with [realPath]. These calls block on a binder call: make them off the main thread.
 *
 * aria2's JSON-RPC is on for the host as well when [rpc] is set: [rpcPort] and [rpcSecret] (made for this run, never
 * shown to anyone else) reach it on loopback. Plugins are given neither; the host brokers their downloads.
 */
class DownloadEngine(
    private val context: Context,
    val root: File,
    private val rpc: Boolean = true,
    val rpcPort: Int = 6800,
    /**
     * Run the engine as an Android isolated process (API 30 and later). Off by default: the owner's order is the
     * sandboxed engine first, "once we've tested it with just our sandbox, we should build the android isolation
     * path", so the isolated one is turned on per host once it has passed its own rig check.
     */
    androidIsolation: Boolean = false,
    /**
     * Let the engine connect to the device itself and the network it is on (loopback, private, link-local, carrier NAT).
     * Off by default: a host's downloads come from the internet, and a torrent's peers, a web seed or a Metalink's
     * mirror is an address no host checked (docs/design.md, "Network"). A test against a server on the runner turns it on.
     */
    private val localNetwork: Boolean = false,
) {
    private var engine: IEngine? = null
    private var connection: ServiceConnection? = null
    private val _events = MutableSharedFlow<Pair<EngineEvent, String>>(extraBufferCapacity = 256)

    /** (event, gid) as libaria2 raises them. */
    val events: SharedFlow<Pair<EngineEvent, String>> = _events.asSharedFlow()

    /** The secret aria2's JSON-RPC asks for (`token:<secret>`), new for each run. */
    val rpcSecret: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private val app = context.applicationContext

    /**
     * Whether the engine runs isolated, under a UID of its own: asked for, on Android 11 and later. Otherwise it runs
     * in a process of the host's UID, under the sandbox lockdown with every path brokered (docs/design.md, "Processes").
     */
    val isolated: Boolean = androidIsolation && Build.VERSION.SDK_INT >= 30

    /** Starts the engine process and its session with [options] (aria2's global options). */
    suspend fun start(options: Map<String, String> = emptyMap()): DownloadEngine {
        check(engine == null) { "the engine is started" }
        root.mkdirs()
        File(root, ".state").mkdirs()
        File(root, ".meta").mkdirs()
        // The platform's CAs for the engine's OpenSSL, refreshed at every start (a system update may change them).
        check(CaBundle.write(File(root, ".state/ca-bundle.pem")) > 0) { "the platform's CA store is empty" }
        gone = CountDownLatch(1)
        val connected = CompletableDeferred<IEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null) connected.complete(IEngine.Stub.asInterface(binder)) else connected.completeExceptionally(IllegalStateException("the engine gave no binder"))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                engine = null
                gone.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("the engine gave no binder"))
            }
        }
        val bound = if (isolated) {
            // A new instance each start: a stopped engine's process ends, and must not be the one bound again.
            app.bindIsolatedService(Intent(app, EngineService::class.java), Context.BIND_AUTO_CREATE, "aria2_${System.nanoTime()}", app.mainExecutor, conn)
        } else {
            app.bindService(Intent(app, EngineOpenService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
        check(bound) { "the engine service could not be bound" }
        connection = conn
        try {
            startSession(withTimeout(30_000) { connected.await() }, options)
        } catch (e: Throwable) {
            // An engine process that answered but could not start ends itself (EngineService); wait for that.
            processEnding = connected.isCompleted && !connected.isCancelled
            stop()
            throw e
        }
        return this
    }

    private fun startSession(remote: IEngine, options: Map<String, String>) {
        val global = LinkedHashMap(options)
        if (rpc) {
            global["enable-rpc"] = "true"
            global["rpc-listen-port"] = rpcPort.toString()
            global["rpc-secret"] = rpcSecret
        }
        // An engine under the host's UID names every path through the broker: the platform's files and the host's own
        // code (its APKs and native libraries) read-only, never the host's data.
        val code = if (isolated) emptyList() else listOfNotNull(app.applicationInfo.sourceDir, app.applicationInfo.nativeLibraryDir) +
            app.applicationInfo.splitSourceDirs.orEmpty()
        val broker = EngineBroker.serve(root.path, network = true, systemReads = !isolated, codeReads = code, localNetwork = localNetwork)
        val report = try {
            remote.start(broker, global.keys.toTypedArray(), global.values.toTypedArray(), object : IEngineEvents.Stub() {
                override fun onEvent(event: Int, gid: String) {
                    EngineEvent.of(event)?.let { _events.tryEmit(it to gid) }
                }
            })
        } finally {
            broker.close()
        }
        check(report == "on") { "the engine did not start: $report${logTail()}" }
        engine = remote
    }

    /** The end of libaria2's log, for a start that failed. */
    private fun logTail(): String {
        val log = File(realPath(EngineConfig.LOG))
        val lines = runCatching { log.readLines().takeLast(20) }.getOrNull().orEmpty()
        return if (lines.isEmpty()) "" else "\n" + lines.joinToString("\n")
    }

    private fun remote(): IEngine = engine ?: throw IllegalStateException("the engine is not running")

    private fun answer(value: String): String {
        if (value.startsWith("error")) throw EngineException(value)
        return value
    }

    /**
     * Adds [request]; returns its gid. An HTTP(S) or FTP download reports the sanitized WebView user agent unless its
     * options carry their own; a torrent, a magnet or a Metalink keeps aria2's own identity (UserAgent).
     */
    fun add(request: DownloadRequest): String {
        val options = UserAgent.optionsFor(request.effectiveKind, request.options, userAgent())
        // The engine cannot make its download's folder: aria2 makes a missing one parent by parent from the top, and the
        // broker answers only the engine's own folder, not "/aria2" above it (stat /aria2, then mkdir /aria2: refused,
        // and the download dies with errorCode=1 before it connects). The host makes it, so aria2 finds it present.
        EngineConfig.perDownload(options)["dir"]?.takeIf { it != EngineConfig.VIRTUAL_ROOT }?.let { File(realPath(it)).mkdirs() }
        val keys = options.keys.toTypedArray()
        val values = options.values.toTypedArray()
        return when (request.kind) {
            DownloadKind.URI -> answer(remote().addUri(request.uris.toTypedArray(), keys, values))
            DownloadKind.TORRENT -> answer(remote().addTorrent(storeMetadata(request.metadata!!, "torrent"), keys, values))
            DownloadKind.METALINK -> answer(remote().addMetalink(storeMetadata(request.metadata!!, "meta4"), keys, values))
        }
    }

    /** Writes metadata bytes under the engine's folder, unread: the engine parses them, never the host. */
    private fun storeMetadata(bytes: ByteArray, extension: String): String {
        val name = "${UUID.randomUUID()}.$extension"
        File(File(root, ".meta"), name).writeBytes(bytes)
        return "${EngineConfig.VIRTUAL_ROOT}/.meta/$name"
    }

    fun pause(gid: String, force: Boolean = false): Int = remote().pause(gid, force)
    fun resume(gid: String): Int = remote().unpause(gid)
    fun remove(gid: String, force: Boolean = false): Int = remote().remove(gid, force)
    fun changeOption(gid: String, options: Map<String, String>): Int = remote().changeOption(gid, options.keys.toTypedArray(), options.values.toTypedArray())
    fun changeGlobalOption(options: Map<String, String>): Int = remote().changeGlobalOption(options.keys.toTypedArray(), options.values.toTypedArray())

    /** Bytes per second across every download, 0 for no limit. */
    fun setGlobalLimits(downloadBytesPerSecond: Long = 0, uploadBytesPerSecond: Long = 0, concurrent: Int? = null): Int =
        changeGlobalOption(buildMap {
            put("max-overall-download-limit", downloadBytesPerSecond.toString())
            put("max-overall-upload-limit", uploadBytesPerSecond.toString())
            concurrent?.let { put("max-concurrent-downloads", it.toString()) }
        })

    fun setLimits(gid: String, downloadBytesPerSecond: Long = 0, uploadBytesPerSecond: Long = 0): Int =
        changeOption(gid, mapOf("max-download-limit" to downloadBytesPerSecond.toString(), "max-upload-limit" to uploadBytesPerSecond.toString()))

    /**
     * Lets the engine connect to [address] at [port] on the local network, for an HTTP download the host walked there
     * itself (a NAS, a tailnet machine): with [localNetwork] off, that is the only local address it may reach, so a
     * torrent's peers still never do. Each call needs its [revokeLocal] when the download ends. Not needed, and harmless,
     * for a public address.
     */
    fun allowLocal(address: java.net.InetAddress, port: Int) = EngineBroker.allowLocal(address, port)

    fun revokeLocal(address: java.net.InetAddress, port: Int) = EngineBroker.revokeLocal(address, port)

    fun status(gid: String): DownloadStatus? = remote().status(gid)?.let { DownloadStatus.parse(gid, it, ::realPath) }

    /** Every active, waiting or paused download. */
    fun active(): List<String> = remote().active().split(',').filter { it.isNotEmpty() }

    /** A path the engine reported, as the host's own; a path outside the engine's folder comes back unchanged. */
    fun realPath(path: String): String =
        if (path == EngineConfig.VIRTUAL_ROOT || path.startsWith(EngineConfig.VIRTUAL_ROOT + "/")) root.path + path.removePrefix(EngineConfig.VIRTUAL_ROOT) else path

    /** The engine's name for [file] under [root], for a download's `dir`. */
    fun enginePath(file: File): String {
        val relative = file.canonicalFile.relativeTo(root.canonicalFile).path
        require(!relative.startsWith("..")) { "not under the engine's folder" }
        return if (relative.isEmpty()) EngineConfig.VIRTUAL_ROOT else "${EngineConfig.VIRTUAL_ROOT}/$relative"
    }

    /**
     * Stops the engine and waits (at most 15 s, so not from the main thread) for its process to end. The lockdown and
     * libaria2 start once per process, so a start that bound the old process again would find them used: the second
     * sbx_lockdown answers -114, which failed every start after the first in EngineOpenService.
     */
    fun stop() {
        val running = engine
        runCatching { running?.stop() }
        if (running != null || processEnding) gone.await(15, TimeUnit.SECONDS)
        processEnding = false
        engine = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
    }

    @Volatile
    private var gone = CountDownLatch(1)

    @Volatile
    private var processEnding = false

    private var agent: Pair<Long, String>? = null

    /** The sanitized WebView user agent, recomputed when WebView updates. */
    @Synchronized
    fun userAgent(): String {
        val version = WebView.getCurrentWebViewPackage()?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: -1L
        agent?.let { (v, ua) -> if (v == version) return ua }
        val fresh = UserAgent.forDownloads(runCatching { WebSettings.getDefaultUserAgent(app) }.getOrNull())
        agent = version to fresh
        return fresh
    }
}

/** libaria2 refused a call; [message] carries its "error:<code>". */
class EngineException(message: String) : RuntimeException(message)
