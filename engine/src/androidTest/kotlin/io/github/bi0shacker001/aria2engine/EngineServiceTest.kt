package io.github.bi0shacker001.aria2engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The whole path on a device: the isolated engine process, the sandbox lockdown, the host's broker (files and
 * sockets) and libaria2. CI serves a 5 MiB file from the runner, which the emulator reaches at 10.0.2.2:8000.
 */
@RunWith(Parameterized::class)
class EngineServiceTest(private val androidIsolation: Boolean) {
    companion object {
        /** The sandboxed engine everywhere, and the isolated one where Android has it (API 30 and later). */
        @JvmStatic
        @Parameterized.Parameters(name = "androidIsolation={0}")
        fun modes(): List<Array<Any>> = listOfNotNull(arrayOf<Any>(false), if (android.os.Build.VERSION.SDK_INT >= 30) arrayOf<Any>(true) else null)
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // The runner's test server: CI and a local device both use adb reverse and -e serverBase http://127.0.0.1:8000;
    // 10.0.2.2 is the default for an emulator with a working network.
    private val serverBase = InstrumentationRegistry.getArguments().getString("serverBase") ?: "http://10.0.2.2:8000"
    private val root = File(context.filesDir, "engine-test")
    private lateinit var engine: DownloadEngine

    @Before
    fun startEngine() = runBlocking {
        root.deleteRecursively()
        // Info-level log: a session that fails says why in the failure's message (DownloadEngine.start).
        engine = DownloadEngine(context, root, androidIsolation = androidIsolation).start(mapOf("log-level" to "info"))
        assertEquals(androidIsolation, engine.isolated)
    }

    @After
    fun stopEngine() {
        // libaria2's log, so a failed run says why (EngineConfig.LOG, at info level from startEngine).
        File(root, ".state/engine.log").takeIf { it.isFile }?.readLines()?.takeLast(60)?.forEach { Log.i("aria2engine.test", it) }
        if (::engine.isInitialized) engine.stop()
        root.deleteRecursively()
    }

    private fun hasInternet(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun completes(request: DownloadRequest, timeoutMs: Long = 120_000): DownloadStatus = runBlocking {
        // One connection per download: the runner's python server cannot serve ranges, so a connection that adb reverse
        // drops (seen on API 28) could not be resumed by a second one.
        val gid = engine.add(request.copy(options = mapOf("split" to "1", "max-connection-per-server" to "1") + request.options))
        withTimeout(timeoutMs) {
            engine.events.first { (event, id) -> id == gid && (event == EngineEvent.COMPLETE || event == EngineEvent.ERROR) }
        }
        (engine.status(gid) ?: error("the engine forgot $gid")).also { Log.i("aria2engine.test", "status $it") }
    }

    @Test
    fun anHttpDownloadLandsInTheHostsFolder() {
        val status = completes(DownloadRequest(DownloadKind.URI, uris = listOf("$serverBase/blob.bin"), options = mapOf("dir" to "${EngineConfig.VIRTUAL_ROOT}/job1")))
        assertEquals("complete", status.state)
        val file = File(status.files.single().path)
        assertEquals(File(root, "job1/blob.bin").path, file.path)
        assertEquals(5_242_880L, file.length())
    }

    @Test
    fun httpsUsesThePlatformsCertificates() {
        // A CI emulator's own network can be dead (ConnectivityService "Machine is not on the network"); the other tests
        // reach the runner over adb reverse and do not need it.
        assumeTrue("the device has no internet", hasInternet())
        val status = completes(DownloadRequest(DownloadKind.URI, uris = listOf("https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0.tar.xz"), options = mapOf("dir" to "${EngineConfig.VIRTUAL_ROOT}/job2")))
        assertEquals("complete", status.state)
        assertEquals(1_705_256L, File(status.files.single().path).length())
    }

    @Test
    fun aHookOptionIsRefusedAndNothingRuns() {
        val marker = File(root, "ran")
        val status = completes(
            DownloadRequest(
                DownloadKind.URI,
                uris = listOf("$serverBase/blob.bin"),
                options = mapOf("dir" to "${EngineConfig.VIRTUAL_ROOT}/job3", "on-download-complete" to "/system/bin/touch"),
            ),
        )
        assertEquals("complete", status.state)
        assertFalse(marker.exists())
    }

    @Test
    fun aFolderOutsideTheEnginesIsNotReachable() {
        // Asked to write outside its folder, the download lands in the engine's own folder instead.
        val status = completes(DownloadRequest(DownloadKind.URI, uris = listOf("$serverBase/blob.bin"), options = mapOf("dir" to context.filesDir.path)))
        assertTrue(status.files.single().path.startsWith(root.path))
    }
}
