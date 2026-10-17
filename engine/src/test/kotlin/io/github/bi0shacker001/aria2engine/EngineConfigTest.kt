package io.github.bi0shacker001.aria2engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What no caller can make libaria2 do (docs/design.md, "Options"). */
class EngineConfigTest {
    @Test
    fun `no option that runs a program or names a file elsewhere gets through`() {
        val asked = mapOf(
            "on-download-complete" to "/system/bin/sh", "on-bt-download-complete" to "/x", "on-anything" to "/x",
            "save-session" to "/data/x", "log" to "/sdcard/x", "load-cookies" to "/data/x", "ca-certificate" to "/x",
            "check-certificate" to "false", "rpc-listen-all" to "true", "conf-path" to "/x", "input-file" to "/x",
            "max-download-limit" to "1M",
        )
        val download = EngineConfig.perDownload(asked)
        assertEquals(mapOf("max-download-limit" to "1M"), download)
        val session = EngineConfig.sessionOptions(asked)
        assertEquals("true", session["check-certificate"])
        assertEquals("false", session["rpc-listen-all"])
        assertFalse(session.keys.any { it.startsWith("on-") })
        assertFalse("save-session" in session)
    }

    @Test
    fun `a download's folder stays under the engine's`() {
        assertEquals(EngineConfig.VIRTUAL_ROOT, EngineConfig.perDownload(mapOf("dir" to "/data/data/host"))["dir"])
        assertEquals(EngineConfig.VIRTUAL_ROOT, EngineConfig.perDownload(mapOf("dir" to "${EngineConfig.VIRTUAL_ROOT}/../x"))["dir"])
        assertEquals("${EngineConfig.VIRTUAL_ROOT}/job7", EngineConfig.perDownload(mapOf("dir" to "${EngineConfig.VIRTUAL_ROOT}/job7"))["dir"])
        assertTrue(EngineConfig.insideRoot("${EngineConfig.VIRTUAL_ROOT}/a/b"))
        assertFalse(EngineConfig.insideRoot("${EngineConfig.VIRTUAL_ROOT}x/a"))
    }

    @Test
    fun `RPC is on only with a secret`() {
        assertEquals("false", EngineConfig.sessionOptions(mapOf("enable-rpc" to "true"))["enable-rpc"])
        assertEquals("true", EngineConfig.sessionOptions(mapOf("enable-rpc" to "true", "rpc-secret" to "s"))["enable-rpc"])
        assertFalse("a run-time change cannot turn RPC on or move it", "enable-rpc" in EngineConfig.globalChange(mapOf("enable-rpc" to "true", "rpc-listen-port" to "1")))
    }

    @Test
    fun `torrents seed only when asked`() {
        assertEquals("0", EngineConfig.sessionOptions(emptyMap())["seed-time"])
    }
}
