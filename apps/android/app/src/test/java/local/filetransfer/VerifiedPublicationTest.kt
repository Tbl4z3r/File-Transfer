package local.filetransfer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

class VerifiedPublicationTest {
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test fun copyWritesExactBytesAndSupportsEmptyFiles() {
        val dir = Files.createTempDirectory("verified-copy").toFile()
        try {
            val source = File(dir, "source").apply { writeBytes(byteArrayOf(0, 1, 2, -1, 4)) }
            val bytes = source.readBytes()
            val out = ByteArrayOutputStream()
            VerifiedCopy.copy(source, out, bytes.size.toLong(), sha256(bytes))
            assertArrayEquals(bytes, out.toByteArray())

            val empty = File(dir, "empty").apply { writeBytes(byteArrayOf()) }
            val emptyOut = ByteArrayOutputStream()
            VerifiedCopy.copy(empty, emptyOut, 0, emptyHash)
            assertArrayEquals(byteArrayOf(), emptyOut.toByteArray())
        } finally { dir.deleteRecursively() }
    }

    @Test fun publisherPreservesCollisionAndUsesNumberedName() {
        val dir = Files.createTempDirectory("verified-publish").toFile()
        try {
            val inbox = File(dir, "inbox").apply { mkdir() }
            val original = File(inbox, "photo.bin").apply { writeText("keep") }
            val source = File(dir, "source").apply { writeBytes(byteArrayOf(8, 7, 6)) }
            val result = PrivateInboxPublisher.publish(source, inbox, "photo.bin", 3, sha256(source.readBytes()))
            assertEquals("photo (1).bin", result.name)
            assertEquals("keep", original.readText())
            assertArrayEquals(source.readBytes(), result.readBytes())
            assertTrue(source.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun symlinkCollisionIsNeverFollowedOrOverwritten() {
        val dir = Files.createTempDirectory("verified-link").toFile()
        try {
            val inbox = File(dir, "inbox").apply { mkdir() }
            val outside = File(dir, "outside").apply { writeText("untouched") }
            val link = File(inbox, "payload.txt")
            try { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            catch (_: UnsupportedOperationException) { return }
            catch (_: SecurityException) { return }

            val source = File(dir, "source").apply { writeText("safe") }
            val result = PrivateInboxPublisher.publish(source, inbox, "payload.txt", 4, sha256("safe".toByteArray()))
            assertEquals("payload (1).txt", result.name)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("untouched", outside.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun changedSizeOrHashAndCancellationRemoveOnlyNewDestination() {
        val dir = Files.createTempDirectory("verified-failure").toFile()
        try {
            val inbox = File(dir, "inbox").apply { mkdir() }
            val source = File(dir, "source").apply { writeText("actual") }
            val previous = File(inbox, "existing.txt").apply { writeText("keep") }
            assertFailureCleans(inbox, source, "wrong-size.txt", 5, sha256("actual".toByteArray()))
            assertFailureCleans(inbox, source, "wrong-hash.txt", 6, sha256("other!".toByteArray()))
            val cancelledTarget = File(inbox, "cancel.txt")
            assertTrue(runCatching {
                PrivateInboxPublisher.publish(source, inbox, cancelledTarget.name, 6, sha256("actual".toByteArray())) { true }
            }.exceptionOrNull() is java.io.InterruptedIOException)
            assertFalse(cancelledTarget.exists())
            assertEquals("keep", previous.readText())
            assertTrue(source.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun copyUsesBoundedWritesAndLongExpectedSizes() {
        val dir = Files.createTempDirectory("verified-bounded").toFile()
        try {
            val source = File(dir, "large").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
            var largestWrite = 0
            val sink = object : OutputStream() {
                override fun write(b: Int) { largestWrite = maxOf(largestWrite, 1) }
                override fun write(b: ByteArray, off: Int, len: Int) { largestWrite = maxOf(largestWrite, len) }
            }
            VerifiedCopy.copy(source, sink, source.length(), sha256(source.readBytes()))
            assertTrue(largestWrite <= 64 * 1024)
            // Long sizes are accepted as metadata without allocating a multi-gigabyte fixture.
            assertTrue(runCatching { VerifiedCopy.copy(source, ByteArrayOutputStream(), 5_000_000_000L, sha256(source.readBytes())) }.isFailure)
        } finally { dir.deleteRecursively() }
    }

    private fun assertFailureCleans(inbox: File, source: File, name: String, size: Long, hash: String) {
        val target = File(inbox, name)
        assertTrue(runCatching { PrivateInboxPublisher.publish(source, inbox, name, size, hash) }.isFailure)
        assertFalse("$name must be removed after failed publication", target.exists())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
}
