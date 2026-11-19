package local.filetransfer

/** Endpoint-independent identity verified by the authenticated QR and the TLS pin. */
internal data class RetryPeer(val serverId: String, val pin: String)
internal fun Pairing.retryPeer() = RetryPeer(serverId, pin)
internal fun Descriptor.retryPeer() = RetryPeer(serverId, pin)

/** In-memory work. Only a dispatched completion can make publication uncertain. */
internal data class UploadRetry<T>(
    val peer: RetryPeer,
    val remaining: List<T>,
    val uncertain: T? = null,
    val folders: List<String> = emptyList(),
) {
    fun confirming(source: T) = copy(uncertain = source)
    fun confirmed(source: T) = copy(remaining = remaining - source, uncertain = uncertain?.takeUnless { it == source })
    fun nextSelection(includeUncertain: Boolean): List<T> =
        if (includeUncertain || uncertain == null) remaining else remaining - uncertain
}

internal class RetryPeerMismatch : IllegalArgumentException()

internal fun transferRate(bytes: Long, started: Long, now: Long): Long? {
    val elapsed = now - started
    return if (elapsed >= 100_000_000L) (bytes.toDouble() * 1_000_000_000 / elapsed).toLong() else null
}
