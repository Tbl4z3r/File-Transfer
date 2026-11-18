package local.filetransfer

/** Session-only queue: an unconfirmed item is never replayed or skipped. */
internal data class SendQueueProgress(
    val current: Int,
    val total: Int,
    val completed: Int,
    val running: Boolean,
) {
    val pending: Int get() = total - current
}

internal class SendQueue<T>(private val items: List<T>) {
    fun run(cancelled: () -> Boolean, changed: (SendQueueProgress) -> Unit, send: (T) -> Boolean) {
        var current = 0
        var completed = 0
        try {
            for (item in items) {
                if (cancelled()) break
                current++
                changed(SendQueueProgress(current, items.size, completed, true))
                if (cancelled() || !send(item)) break
                completed++
            }
        } finally {
            changed(SendQueueProgress(current, items.size, completed, false))
        }
    }
}

internal class SendSelectionException(message: String) : IllegalArgumentException(message)
