package local.filetransfer

import org.junit.Assert.*
import org.junit.Test

class TransferOperationTest {
    @Test fun repeatedServiceStartRunsTheBatchOnlyOnce() {
        val operation = TransferOperation()
        var starts = 0
        val id = operation.begin({ starts++ }, {}, { throw it })
        operation.ready(id)
        operation.ready(id)
        assertEquals(1, starts)
        assertTrue(operation.running)
        assertTrue(operation.finish(id))
        assertFalse(operation.running)
    }

    @Test fun oldServiceAndNotificationCallbacksCannotCancelOrFinishTheNextTransfer() {
        val operation = TransferOperation()
        val old = operation.begin({}, {}, {})
        assertTrue(operation.finish(old))
        var starts = 0
        var cancellations = 0
        val next = operation.begin({ starts++ }, { cancellations++ }, { throw it })
        operation.ready(old)
        operation.cancel(old, false)
        operation.fail(old, IllegalStateException("stale service"))
        assertFalse(operation.finish(old))
        assertEquals(next, operation.currentId)
        operation.ready(next)
        assertEquals(1, starts)
        assertEquals(0, cancellations)
        operation.cancel(next, false)
        assertEquals(1, cancellations)
    }

    @Test fun startupFailureReleasesTheSlotBeforeReportingFailure() {
        val operation = TransferOperation()
        var reported: Exception? = null
        val error = IllegalStateException("foreground unavailable")
        val id = operation.begin({ fail("must not start") }, {}, {
            assertFalse(operation.running)
            reported = it
        })
        operation.fail(id, error)
        operation.ready(id)
        assertSame(error, reported)
        assertFalse(operation.running)
        operation.begin({}, {}, {})
    }

    @Test fun timeoutCancelsTheWholeOperationUntilWorkerCleanupFinishes() {
        val operation = TransferOperation()
        var timedOut = false
        val id = operation.begin({}, { timedOut = it }, { throw it })
        operation.ready(id)
        operation.cancel(id, true)
        assertTrue(timedOut)
        assertTrue(operation.running)
        assertTrue(operation.finish(id))
    }
}
