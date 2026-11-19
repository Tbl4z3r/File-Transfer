package local.filetransfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class OutgoingQueueStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun source(name: String = "foto.jpg", bytes: ByteArray = byteArrayOf(1, 2, 3)) = OutgoingSource(name) { ByteArrayInputStream(bytes) }

    @Test fun temporarySourceIsSnapshotAndSurvivesRestartWithoutSourcePermission() {
        val root = temporary.newFolder()
        var permission = true
        val store = OutgoingQueueStore(root)
        store.importFiles(listOf(OutgoingSource("foto.jpg") {
            check(permission)
            ByteArrayInputStream(byteArrayOf(4, 5, 6))
        }), true) { false }
        permission = false
        val restored = OutgoingQueueStore(root)
        assertTrue(restored.state.armed)
        assertEquals("foto.jpg", restored.state.files.single().name)
        assertArrayEquals(byteArrayOf(4, 5, 6), restored.file(restored.state.files.single()).readBytes())
        restored.remove(restored.state.files.single().id)
        assertTrue(OutgoingQueueStore(root).state.files.isEmpty())
        assertEquals(listOf("queue.json"), root.list()!!.toList())
    }

    @Test fun newShareDoesNotRearmHeldOrAttemptedFiles() {
        val store = OutgoingQueueStore(temporary.newFolder())
        store.importFiles(listOf(source("held.txt")), false) { false }
        store.importFiles(listOf(source("attempt.txt")), true) { false }
        val attempted = store.state.files.last()
        store.arm(false, setOf(attempted.id)) // committed before the network attempt
        store.importFiles(listOf(source("new.txt")), true) { false }
        assertEquals(listOf(false, false, true), store.state.files.map { it.armed })
        val restored = OutgoingQueueStore(temporary.root.listFiles()!!.single())
        assertEquals(listOf("new.txt"), restored.state.files.filter { it.armed }.map { it.name })
    }

    @Test fun automaticSelectionRespectsDestinationAndHeldFiles() {
        val first = RetryPeer("12345678-1234-4234-8234-123456789abc", "a".repeat(64))
        val second = RetryPeer("12345678-1234-4234-8234-123456789abd", "b".repeat(64))
        val state = OutgoingState(listOf(
            OutgoingFile("1", "held.txt", 0),
            OutgoingFile("2", "first.txt", 0, armed = true, peer = first),
            OutgoingFile("3", "unbound.txt", 0, armed = true),
            OutgoingFile("4", "uncertain.txt", 0, uncertain = true, armed = true),
        ))
        assertEquals(listOf("first.txt", "unbound.txt"), state.automaticFiles(first).map { it.name })
        assertEquals(listOf("unbound.txt"), state.automaticFiles(second).map { it.name })
    }

    @Test fun intendedAuthenticatedPeerSurvivesRestart() {
        val root = temporary.newFolder()
        val store = OutgoingQueueStore(root)
        val peer = RetryPeer("12345678-1234-4234-8234-123456789abc", "a".repeat(64))
        store.importFiles(listOf(source()), true, peer) { false }
        assertEquals(peer, OutgoingQueueStore(root).state.files.single().peer)
    }

    @Test fun dispatchedCompletionRestoresAsUncertainAndCannotArmSilently() {
        val root = temporary.newFolder()
        val store = OutgoingQueueStore(root)
        store.importFiles(listOf(source()), true) { false }
        val entry = store.state.files.single()
        store.arm(false)
        store.uncertain(entry.id)
        val restored = OutgoingQueueStore(root)
        restored.arm(true)
        assertTrue(restored.state.files.single().uncertain)
        assertFalse(restored.state.armed)
    }

    @Test fun importFailureOrCancellationRollsBackEntireSelectionAndRetainsPreviousQueue() {
        for (cancel in listOf(false, true)) {
            val root = temporary.newFolder()
            val store = OutgoingQueueStore(root)
            store.importFiles(listOf(source("previous.txt")), false) { false }
            val previous = store.state
            val failing = OutgoingSource("broken.txt") {
                object : InputStream() {
                    override fun read(): Int = throw IOException("lost URI grant")
                }
            }
            try {
                store.importFiles(listOf(source("new.txt"), failing), true) { cancel }
                fail("Expected failure")
            } catch (_: Exception) { }
            assertEquals(previous, store.state)
            assertEquals(previous, OutgoingQueueStore(root).state)
            assertEquals(2, root.list()!!.size)
        }
    }

    @Test fun crashDebrisAndMissingSnapshotsAreRemovedWithNotice() {
        val root = temporary.newFolder()
        val store = OutgoingQueueStore(root)
        store.importFiles(listOf(source()), false) { false }
        store.file(store.state.files.single()).delete()
        File(root, "uncommitted.part").writeText("partial")
        File(root, "uncommitted.bin").writeText("finished copy without committed index")
        val restored = OutgoingQueueStore(root)
        assertTrue(restored.recoveryNotice)
        assertTrue(restored.state.files.isEmpty())
        assertEquals(listOf("queue.json"), root.list()!!.toList())
    }

    @Test fun failedJournalWriteDoesNotCommitOrLoseExistingEntries() {
        val root = temporary.newFolder()
        val store = OutgoingQueueStore(root)
        store.importFiles(listOf(source()), false) { false }
        val previous = store.state
        File(root, "queue.tmp").mkdir()
        try {
            store.importFiles(listOf(source("new.txt")), true) { false }
            fail("Expected journal failure")
        } catch (_: IOException) { }
        assertEquals(previous, store.state)
        assertEquals(previous, OutgoingQueueStore(root).state)
        assertEquals(2, root.list()!!.size)
    }

    @Test fun copyUsesBoundedReadsAndHandlesEmptyFiles() {
        var remaining = 5L * 1024 * 1024
        var maximumRead = 0
        val stream = object : InputStream() {
            override fun read(): Int = error("must use a buffer")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                maximumRead = maxOf(maximumRead, length)
                if (remaining == 0L) return -1
                return minOf(remaining, length.toLong()).toInt().also { remaining -= it }
            }
        }
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
        assertEquals(5L * 1024 * 1024, copyOutgoing(stream, sink) { false })
        assertTrue(maximumRead <= 64 * 1024)
        assertEquals(0L, copyOutgoing(ByteArrayInputStream(byteArrayOf()), sink) { false })
    }

    @Test fun invalidNamesAndExcessiveSelectionDoNotOpenStreams() {
        val store = OutgoingQueueStore(temporary.newFolder())
        for (sources in listOf(listOf(OutgoingSource("../escape") { error("must not open") }), List(65) { source() })) {
            try { store.importFiles(sources, true) { false }; fail("Expected validation failure") }
            catch (_: SendSelectionException) { }
            assertTrue(store.state.files.isEmpty())
        }
    }
}
