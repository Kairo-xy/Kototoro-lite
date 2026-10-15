package org.skepsun.kototoro.settings.sources.unified

import com.lagradost.cloudstream3.MainAPI
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.SortOrder

class SourceAvailabilityProbeTest {
    @Test
    fun `search only provider is unverified without creating or calling a repository`() = runTest {
        val result = probeSourceAvailability(source(hasMainPage = false), {
            error("A search-only source cannot be tested with a blank homepage query")
        })
        assertEquals(ContentSourceAvailability.UNKNOWN, result.availability)
        assertEquals(null, result.failure)
    }

    @Test
    fun `homepage provider with results is available`() = runTest {
        val result = probeSourceAvailability(source(true), { repository(listOf(mockk())) })
        assertEquals(ContentSourceAvailability.AVAILABLE, result.availability)
    }

    @Test
    fun `empty homepage is unavailable`() = runTest {
        val result = probeSourceAvailability(source(true), { repository(emptyList()) })
        assertEquals(ContentSourceAvailability.EMPTY, result.availability)
        assertEquals("empty", result.failure)
    }

    @Test
    fun `timeout is a failure`() = runTest {
        val repository = repository(emptyList())
        coEvery { repository.getList(any(), any(), any()) } coAnswers { delay(100); emptyList() }
        assertEquals("timeout", probeSourceAvailability(source(true), { repository }, timeoutMs = 10).failure)
    }

    @Test
    fun `external cancellation propagates`() {
        val repository = repository(emptyList())
        coEvery { repository.getList(any(), any(), any()) } throws CancellationException("cancelled")
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest { probeSourceAvailability(source(true), { repository }) }
        }
    }

    private fun source(hasMainPage: Boolean) = CloudstreamSource(
        object : MainAPI() {
            override val hasMainPage = hasMainPage
        },
        "test.cs3",
        "test",
    )

    private fun repository(items: List<Content>) = mockk<ContentRepository> {
        every { defaultSortOrder } returns SortOrder.RELEVANCE
        coEvery { getList(0, SortOrder.RELEVANCE, any()) } returns items
    }
}
