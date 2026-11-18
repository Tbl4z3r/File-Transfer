package local.filetransfer

/** One session, one create attempt; only an authenticated old-server 404 enables legacy mode. */
internal class UploadAuthorization(private val client: ProtocolClient, private val files: List<UploadFile>) {
    var batchId: String? = null
        private set

    fun request(cancelled: () -> Boolean, waiting: () -> Unit) {
        require(files.isNotEmpty() && files.size <= 64)
        if (files.size > 1) uploadManifest(files)
        check(!cancelled()) { "Cancelada" }
        if (files.size == 1) return
        var batch = try { client.createUploadBatch(files) }
        catch (error: ProtocolException) {
            if (error.status == 404 && error.code == "not_found") return
            throw error
        }
        batchId = batch.id
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120)
        while (batch.state == "awaiting_acceptance") {
            check(!cancelled()) { "Cancelada" }
            check(System.nanoTime() < deadline) { "El PC no aceptó el lote a tiempo." }
            waiting()
            Thread.sleep(700)
            check(!cancelled()) { "Cancelada" }
            batch = client.batchStatus(batch.id)
        }
        check(!cancelled()) { "Cancelada" }
        check(batch.state == "accepted") { "El PC terminó o rechazó el lote." }
    }

    fun start(index: Int, cancelled: () -> Boolean): Transfer {
        check(!cancelled()) { "Cancelada" }
        val file = files[index]
        val id = batchId
        val transfer = if (id == null) client.createUpload(file.name, file.size) else client.startBatchFile(id, index)
        require(transfer.direction == "upload" && transfer.name == file.name && transfer.size == file.size)
        require(transfer.state == if (id == null) "awaiting_acceptance" else "ready")
        return transfer
    }

    /** Pending integrity confirmation owns the current transfer until retry or explicit abandonment. */
    fun stop(confirmationPending: Boolean) {
        if (!confirmationPending) batchId?.let { runCatching { client.cancelBatch(it) } }
    }
}

internal fun requireReadyUpload(transfer: Transfer, file: UploadFile) {
    require(transfer.state == "ready" && transfer.direction == "upload" && transfer.name == file.name && transfer.size == file.size && transfer.bytes == 0L) {
        "El PC preparó un archivo distinto del seleccionado."
    }
}
