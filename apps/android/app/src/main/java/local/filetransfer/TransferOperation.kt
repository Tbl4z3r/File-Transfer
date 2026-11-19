package local.filetransfer

/** Main-thread operation ownership: delayed callbacks from a previous transfer cannot affect its successor. */
internal class TransferOperation {
    private data class Ticket(val id: Long, val ready: () -> Unit, val cancel: (Boolean) -> Unit, val failed: (Exception) -> Unit, var started: Boolean = false)
    private var nextId = 0L
    private var ticket: Ticket? = null
    val currentId get() = ticket?.id
    val running get() = ticket != null
    val started get() = ticket?.started == true

    fun begin(ready: () -> Unit, cancel: (Boolean) -> Unit, failed: (Exception) -> Unit): Long {
        check(ticket == null)
        val id = ++nextId
        ticket = Ticket(id, ready, cancel, failed)
        return id
    }
    fun ready(id: Long?) {
        ticket?.takeIf { it.id == id && !it.started }?.let {
            it.started = true
            it.ready()
        }
    }
    fun cancel(id: Long?, timeout: Boolean) { ticket?.takeIf { it.id == id }?.cancel?.invoke(timeout) }
    fun fail(id: Long?, error: Exception) {
        val current = ticket?.takeIf { it.id == id } ?: return
        ticket = null
        current.failed(error)
    }
    fun finish(id: Long): Boolean {
        if (ticket?.id != id) return false
        ticket = null
        return true
    }
}
