package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourcePlaybackTest {
    private val episode = SourceChapter(
        id = 1, title = "第1集", number = 1f, volume = 0, url = "/1", scanlator = null,
        uploadDate = 0, branch = "线路一", source = SourceRef("AGEDM", "zh", "VIDEO"),
    )

    @Test
    fun `parser branches are exposed as episode lines`() {
        assertEquals("线路一", episode.playbackLine)
    }

    @Test
    fun `aniyomi scanlator branches are exposed as episode lines`() {
        assertEquals("备用", episode.copy(source = SourceRef("ANIYOMI_1", "zh", "VIDEO"),
            branch = "备用", scanlator = "备用").playbackLine)
    }

    @Test
    fun `CMS line remains available independently of the branch`() {
        assertEquals("高清", episode.copy(sourceData = """{"line":"高清"}""").playbackLine)
    }

    @Test
    fun `opaque malformed and empty line metadata preserve the parser branch`() {
        for (data in listOf(null, "opaque", "[]", """{"line":1}""", """{"line":" "}""", "{}")) {
            assertEquals("线路一", episode.copy(sourceData = data).playbackLine, data)
        }
        assertNull(episode.copy(branch = " ").playbackLine)
        assertNull(episode.copy(branch = null).playbackLine)
    }
}
