package io.github.bi0shacker001.aria2engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole path on a device: the isolated engine process, the sandbox lockdown, the host's broker (files and
 * sockets) and libaria2. CI serves a 5 MiB file from the runner, which the emulator reaches at 10.0.2.2:8000.
 */
@RunWith(AndroidJUnit4::class)
class EngineServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "engine-test")
    private lateinit var engine: DownloadEngine

    @Before
    fun startEngine() = runBlocking {
        root.deleteRecursively()
        engine = DownloadEngine(context, root).start()
    }

    @After
    fun stopEngine() {
        engine.stop()
        root.deleteRecursively()
    }

    private fun completes(request: DownloadRequest, timeoutMs: Long = 120_000): DownloadStatus = runBlocking {
        val gid = engine.add(request)
        withTimeout(timeoutMs) {
            engine.events.first { (event, id) -> id == gid && (event == EngineEvent.COMPLETE || event == EngineEvent.ERROR) }
        }
        engine.status(gid) ?: error("the engine forgot $gid")
    }

    @Test
    fun anHttpDownloadLandsInTheHostsFolder() {
        val status = completes(DownloadRequest(DownloadKind.URI, uris = listOf("http://10.0.2.2:8000/blob.bin"), options = mapOf("dir" to "${EngineConfig.VIRTUAL_ROOT}/job1")))
        assertEquals("complete", status.state)
        val file = File(status.files.single().path)
        assertEquals(File(root, "job1/blob.bin").path, file.path)
        assertEquals(5_242_880L, file.length())
    }

    @Test
    fun httpsUsesThePlatformsCertificates() {
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
                uris = listOf("http://10.0.2.2:8000/blob.bin"),
                options = mapOf("dir" to "${EngineConfig.VIRTUAL_ROOT}/job3", "on-download-complete" to "/system/bin/touch"),
            ),
        )
        assertEquals("complete", status.state)
        assertFalse(marker.exists())
    }

    @Test
    fun aFolderOutsideTheEnginesIsNotReachable() {
        // Asked to write outside its folder, the download lands in the engine's own folder instead.
        val status = completes(DownloadRequest(DownloadKind.URI, uris = listOf("http://10.0.2.2:8000/blob.bin"), options = mapOf("dir" to context.filesDir.path)))
        assertTrue(status.files.single().path.startsWith(root.path))
    }
}
