package local.filetransfer

import org.junit.Assert.*
import org.junit.Test

class SessionPollHealthTest {
    @Test fun transientHeartbeatFailuresDoNotAbortAnActiveBatch() {
        val health = SessionPollHealth()
        repeat(20) { assertFalse(health.failure(false, true, 1, 1)) }
        repeat(3) { assertFalse(health.failure(false, false, 1, 1)) }
        assertTrue(health.failure(false, false, 1, 1))
    }
    @Test fun aRequestStartedBeforeAnOperationCannotSpendTheNewIdleFailureBudget() {
        val health = SessionPollHealth()
        repeat(3) { assertFalse(health.failure(false, false, 0, 0)) }
        assertFalse(health.failure(false, false, 0, 1))
        repeat(3) { assertFalse(health.failure(false, false, 1, 1)) }
        health.success()
        assertFalse(health.failure(false, false, 1, 1))
    }
    @Test fun revokedOrClosedSessionsRemainAuthoritativeDuringTransfer() {
        assertTrue(SessionPollHealth().failure(true, true, 0, 1))
    }
}
