package org.skepsun.kototoro.settings.sources.test

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceTestResultTest {

	private val pass = StepResult(StepStatus.PASS)
	private val skip = StepResult(StepStatus.SKIP, "desteklenmiyor")
	private val fail = StepResult(StepStatus.FAIL, "HTTP 403")

	@Test
	fun `a single failed step fails the whole source`() {
		val steps = mapOf(
			SourceTestStep.LIST to pass,
			SourceTestStep.COVERS to fail,
			SourceTestStep.DETAILS to pass,
		)
		overallStatusOf(steps) shouldBe StepStatus.FAIL
	}

	@Test
	fun `passed and skipped steps pass the source`() {
		val steps = mapOf(
			SourceTestStep.LIST to pass,
			SourceTestStep.RANDOM to skip,
			SourceTestStep.SEARCH to pass,
		)
		overallStatusOf(steps) shouldBe StepStatus.PASS
	}

	@Test
	fun `result computes overall from steps by default`() {
		val result = SourceTestResult(
			sourceKey = "src",
			title = "Source",
			steps = mapOf(SourceTestStep.LIST to pass, SourceTestStep.PAGES to fail),
		)
		result.overall shouldBe StepStatus.FAIL
	}

	@Test
	fun `empty step map passes`() {
		overallStatusOf(emptyMap()) shouldBe StepStatus.PASS
	}

	@Test
	fun `cloudflare block without failures gives cloudflare overall`() {
		val cloudflare = StepResult(StepStatus.CLOUDFLARE, "engel")
		val steps = mapOf(
			SourceTestStep.LIST to pass,
			SourceTestStep.CHAPTERS to cloudflare,
		)
		overallStatusOf(steps) shouldBe StepStatus.CLOUDFLARE
	}

	@Test
	fun `failure takes precedence over cloudflare block`() {
		val cloudflare = StepResult(StepStatus.CLOUDFLARE, "engel")
		val steps = mapOf(
			SourceTestStep.LIST to fail,
			SourceTestStep.CHAPTERS to cloudflare,
		)
		overallStatusOf(steps) shouldBe StepStatus.FAIL
	}

	@Test
	fun `video streams are accepted and html error pages are not`() {
		looksLikeVideoStream("video/mp4", ByteArray(0)) shouldBe true
		looksLikeVideoStream("application/vnd.apple.mpegurl", ByteArray(0)) shouldBe true
		looksLikeVideoStream("text/plain", "#EXTM3U\n#EXT-X-VERSION:3".toByteArray()) shouldBe true
		looksLikeVideoStream("", byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray()) shouldBe true
		looksLikeVideoStream("application/octet-stream", byteArrayOf(1, 2, 3)) shouldBe true
		looksLikeVideoStream("text/html; charset=utf-8", "<html>blocked</html>".toByteArray()) shouldBe false
	}
}
