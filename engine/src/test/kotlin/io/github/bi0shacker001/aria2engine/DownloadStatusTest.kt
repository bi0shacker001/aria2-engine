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

    @Test
    fun `a download reports where it is fetched from, for the host to vouch for its own`() {
        val json = """{"status":"paused","totalLength":0,"completedLength":0,"uploadLength":0,"downloadSpeed":0,"uploadSpeed":0,
            "connections":0,"errorCode":0,"followedBy":[],"following":"","dir":"/aria2/downloads/j2",
            "files":[{"index":1,"path":"/aria2/downloads/j2/a.bin","length":5,"completedLength":0,"selected":true,
            "uris":["http://192.168.1.20/seed/a.bin","https://mirror.example.org/a.bin"]}],
            "announceList":["http://tracker.lan:6969/announce","udp://tracker.example.org:1337"],"torrentName":"A"}"""
        val status = DownloadStatus.parse("00000000000000a2", json) { it }
        assertEquals(listOf("http://192.168.1.20/seed/a.bin", "https://mirror.example.org/a.bin"), status.files.single().uris)
        assertEquals(listOf("http://tracker.lan:6969/announce", "udp://tracker.example.org:1337"), status.announceList)
    }

    @Test
    fun `an older answer without them still reads`() {
        val json = """{"status":"active","files":[{"index":1,"path":"/aria2/downloads/x","length":1,"completedLength":0,"selected":true}]}"""
        val status = DownloadStatus.parse("00000000000000a3", json) { it }
        assertEquals(emptyList<String>(), status.files.single().uris)
        assertEquals(emptyList<String>(), status.announceList)
    }
}
