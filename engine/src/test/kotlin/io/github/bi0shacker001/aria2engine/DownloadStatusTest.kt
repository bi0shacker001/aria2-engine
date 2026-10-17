package io.github.bi0shacker001.aria2engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStatusTest {
    @Test
    fun `the engine's paths become the host's`() {
        val json = """{"status":"complete","totalLength":10,"completedLength":10,"uploadLength":0,"downloadSpeed":0,"uploadSpeed":0,
            "connections":0,"errorCode":0,"followedBy":["00000000000000aa"],"following":"","dir":"/aria2/downloads/j1",
            "files":[{"index":1,"path":"/aria2/downloads/j1/game.zip","length":10,"completedLength":10,"selected":true}],"torrentName":""}"""
        val status = DownloadStatus.parse("00000000000000a1", json) { it.replace("/aria2/downloads", "/host/root") }
        assertEquals("complete", status.state)
        assertEquals("/host/root/j1/game.zip", status.files.single().path)
        assertEquals(listOf("00000000000000aa"), status.followedBy)
    }
}
