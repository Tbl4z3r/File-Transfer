package local.filetransfer

/** Heartbeats still renew the peer lease during transfers, but transient polling errors do not cancel content. */
internal class SessionPollHealth {
    private var losses = 0
    fun success() { losses = 0 }
    fun failure(authoritative: Boolean, busy: Boolean, startedEpoch: Long, currentEpoch: Long): Boolean {
        if (authoritative) return true
        if (busy || startedEpoch != currentEpoch) losses = 0 else losses++
        return losses >= 4
    }
}
