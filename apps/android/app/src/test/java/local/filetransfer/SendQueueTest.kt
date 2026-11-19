package local.filetransfer

import org.junit.Assert.*
import org.junit.Test

class SendQueueTest {
    @Test fun batchBytesDoNotCountConfirmedCurrentFileTwice() {
        assertEquals(130L, SendQueueProgress(2, 3, 1, true, 100, 600).bytesWith(30))
        assertEquals(300L, SendQueueProgress(2, 3, 2, false, 300, 600).bytesWith(200))
        assertEquals(600L, SendQueueProgress(3, 3, 3, false, 600, 600).bytesWith(300))
        assertEquals(100L, SendQueueProgress(1, 1, 0, true, 0, 100).bytesWith(900))
    }

    @Test fun secondFileStartsOnlyAfterFirstCompleted() {
        val events = mutableListOf<String>()
        val states = mutableListOf<SendQueueProgress>()
        SendQueue(listOf("primero", "segundo")).run({ false }, { states += it }) { file ->
            events += "start:$file"
            if (file == "segundo") assertEquals("completed:primero", events[events.lastIndex - 1])
            events += "completed:$file"
            true
        }
        assertEquals(listOf("start:primero", "completed:primero", "start:segundo", "completed:segundo"), events)
        assertEquals(SendQueueProgress(1, 2, 0, true), states.first())
        assertEquals(1, states.first().pending)
        assertEquals(SendQueueProgress(2, 2, 2, false), states.last())
    }

    @Test fun unconfirmedOrRejectedItemStopsWithoutReplaying() {
        val sent = mutableListOf<Int>()
        val states = mutableListOf<SendQueueProgress>()
        SendQueue(listOf(1, 2, 3)).run({ false }, { states += it }) {
            sent += it
            it == 1
        }
        assertEquals(listOf(1, 2), sent)
        assertEquals(SendQueueProgress(2, 3, 1, false), states.last())
        assertEquals(1, states.last().pending)
    }

    @Test fun failureDoesNotStartSecondFile() {
        val sent = mutableListOf<Int>()
        var final: SendQueueProgress? = null
        try {
            SendQueue(listOf(1, 2)).run({ false }, { final = it }) {
                sent += it
                throw java.io.IOException("corte de red")
            }
            fail("Expected source or network failure")
        } catch (_: java.io.IOException) { }
        assertEquals(listOf(1), sent)
        assertEquals(SendQueueProgress(1, 2, 0, false), final)
    }

    @Test fun cancellationBetweenFilesPreservesFirstAndDoesNotOpenSecond() {
        var cancelled = false
        val sent = mutableListOf<Int>()
        var final: SendQueueProgress? = null
        SendQueue(listOf(1, 2)).run({ cancelled }, { final = it }) {
            sent += it
            cancelled = true
            true
        }
        assertEquals(listOf(1), sent)
        assertEquals(SendQueueProgress(1, 2, 1, false), final)
    }

    @Test fun cancellationBeforeFirstAndAfterPresentationDoesNotSend() {
        for (cancelAtPresentation in listOf(false, true)) {
            var cancelled = !cancelAtPresentation
            var sent = false
            SendQueue(listOf(1, 2)).run({ cancelled }, { if (cancelAtPresentation) cancelled = true }) {
                sent = true
                true
            }
            assertFalse(sent)
        }
    }
}
