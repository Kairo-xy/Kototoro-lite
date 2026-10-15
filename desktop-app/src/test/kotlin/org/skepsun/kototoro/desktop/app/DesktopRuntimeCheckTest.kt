package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DesktopRuntimeCheckTest {
    @Test
    fun `runtime check refuses implicit user data directories before opening a platform`() {
        // runDesktop, not main: main leaves through exitProcess on failure, which would end the test JVM.
        assertThrows(IllegalArgumentException::class.java) { runDesktop(arrayOf("--check-runtime", "unused-report.properties")) }
    }
}
