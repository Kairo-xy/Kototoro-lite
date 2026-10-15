package org.skepsun.kototoro.cloudstream.runtime

import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CloudstreamPluginArchiveTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `failed activation restores the previous plugin bytes`() {
        val target = File(directory, "plugin.cs3").apply { writeText("old version") }
        val download = File(directory, "download").apply { writeText("new version") }
        assertThrows(LinkageError::class.java) {
            replaceCloudstreamPluginArchive(download, target) {
                assertEquals("new version", target.readText())
                throw LinkageError("Unsupported runtime API")
            }
        }
        assertEquals("old version", target.readText())
        assertEquals(setOf("plugin.cs3", "download"), directory.list()!!.toSet())
    }

    @Test
    fun `failed first install leaves no installed file`() {
        val target = File(directory, "plugin.cs3")
        val download = File(directory, "download").apply { writeText("new") }
        assertThrows(IllegalStateException::class.java) {
            replaceCloudstreamPluginArchive(download, target) { error("load failed") }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `runtime removing a rejected archive still restores the previous file`() {
        val target = File(directory, "plugin.cs3").apply { writeText("old") }
        val download = File(directory, "download").apply { writeText("new") }
        assertThrows(IllegalStateException::class.java) {
            replaceCloudstreamPluginArchive(download, target) {
                target.delete()
                error("rejected")
            }
        }
        assertEquals("old", target.readText())
    }

    @Test
    fun `failed copy preserves the previous plugin`() {
        val target = File(directory, "plugin.cs3").apply { writeText("old") }
        assertThrows(IOException::class.java) {
            replaceCloudstreamPluginArchive(File(directory, "missing"), target) {}
        }
        assertEquals("old", target.readText())
    }

    @Test
    fun `successful activation commits the new plugin and cleans staging files`() {
        val target = File(directory, "plugin.cs3").apply { writeText("old") }
        val download = File(directory, "download").apply { writeText("new") }
        replaceCloudstreamPluginArchive(download, target) { assertEquals("new", target.readText()) }
        assertEquals("new", target.readText())
        assertEquals(setOf("plugin.cs3", "download"), directory.list()!!.toSet())
    }
}
