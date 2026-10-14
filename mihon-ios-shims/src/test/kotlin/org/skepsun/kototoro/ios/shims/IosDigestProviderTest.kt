package org.skepsun.kototoro.ios.shims

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random

/** CommonCrypto digests through the real JNI bridge on macOS, checked byte for byte against the JDK's SUN provider. */
class IosDigestProviderTest {
    private val algorithms = listOf("MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512")

    @BeforeEach fun nativeLibrary() {
        assumeTrue(System.getProperty("kototoro.native.library") != null)
        Class.forName(IosNative::class.java.name) // loads the library, whose JNI_OnLoad installs the provider
        IosDigestProvider.install()
    }

    private fun native(algorithm: String) = MessageDigest.getInstance(algorithm, IosDigestProvider.NAME)
    private fun reference(algorithm: String) = MessageDigest.getInstance(algorithm, "SUN")

    @Test fun `the provider comes first for every algorithm and alias`() {
        for (name in algorithms + listOf("SHA", "SHA256", "SHA512", "2.16.840.1.101.3.4.2.1")) {
            assertEquals(IosDigestProvider.NAME, MessageDigest.getInstance(name).provider.name, name)
        }
    }

    @Test fun `digests match the JDK across lengths and update shapes`() {
        val random = Random(11)
        for (algorithm in algorithms) {
            for (size in listOf(0, 1, 31, 32, 55, 56, 63, 64, 65, 127, 128, 129, 1000, 65_537, 1_048_576 + 3)) {
                val data = random.nextBytes(size)
                val expected = reference(algorithm).digest(data)
                assertArrayEquals(expected, native(algorithm).digest(data), "$algorithm one-shot $size")
                // Mixed single bytes, small and large chunks, and a ByteBuffer.
                val mixed = native(algorithm)
                var position = 0
                while (position < size) {
                    when (random.nextInt(4)) {
                        0 -> mixed.update(data[position++])
                        1 -> { val n = minOf(random.nextInt(1, 40), size - position); mixed.update(data, position, n); position += n }
                        2 -> { val n = minOf(random.nextInt(1, 70_000), size - position); mixed.update(data, position, n); position += n }
                        else -> { val n = minOf(random.nextInt(1, 5_000), size - position); mixed.update(ByteBuffer.wrap(data, position, n)); position += n }
                    }
                }
                assertArrayEquals(expected, mixed.digest(), "$algorithm mixed $size")
            }
        }
    }

    @Test fun `a digest is reusable after digest and reset`() {
        for (algorithm in algorithms) {
            val digest = native(algorithm)
            digest.update("discarded".toByteArray())
            digest.reset()
            assertArrayEquals(reference(algorithm).digest("a".toByteArray()), digest.digest("a".toByteArray()))
            assertArrayEquals(reference(algorithm).digest("b".toByteArray()), digest.digest("b".toByteArray()))
            assertEquals(reference(algorithm).digestLength, digest.digestLength)
        }
    }

    @Test fun `clones continue independently from the same state`() {
        for (algorithm in algorithms) {
            val digest = native(algorithm)
            digest.update("prefix-".toByteArray())
            digest.update('x'.code.toByte()) // still batched in Java when cloned
            val copy = digest.clone() as MessageDigest
            digest.update("left".toByteArray())
            copy.update("right".toByteArray())
            assertArrayEquals(reference(algorithm).digest("prefix-xleft".toByteArray()), digest.digest())
            assertArrayEquals(reference(algorithm).digest("prefix-xright".toByteArray()), copy.digest())
        }
    }

    @Test fun `digest into a caller buffer`() {
        val out = ByteArray(40)
        val digest = native("SHA-256")
        digest.update("中文".toByteArray())
        assertEquals(32, digest.digest(out, 4, 32))
        assertArrayEquals(reference("SHA-256").digest("中文".toByteArray()), out.copyOfRange(4, 36))
    }

    @Test fun `contexts dropped without digest are released`() {
        repeat(20_000) { native("SHA-512").update(ByteArray(10)) }
        System.gc()
        assertArrayEquals(reference("MD5").digest(ByteArray(3)), native("MD5").digest(ByteArray(3)))
    }
}
