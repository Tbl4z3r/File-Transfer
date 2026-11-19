package local.filetransfer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class OutgoingFile(val id: String, val name: String, val size: Long, val uncertain: Boolean = false, val armed: Boolean = false, val peer: RetryPeer? = null)
internal data class OutgoingState(val files: List<OutgoingFile> = emptyList()) {
    val armed get() = files.any { it.armed }
    fun automaticFiles(peer: RetryPeer) = files.filter { it.armed && !it.uncertain && (it.peer == null || it.peer == peer) }
}

/** Private snapshots: only committed files appear in the index; a crash never resumes network work. */
internal class OutgoingQueueStore(private val directory: File) {
    private val index = File(directory, "queue.json")
    var recoveryNotice: Boolean = false
        private set
    @Volatile var state: OutgoingState
        private set

    init {
        directory.mkdirs()
        state = try {
            if (!index.exists()) OutgoingState() else {
                require(index.length() <= 64 * 1024)
                val json = JSONObject(index.readText())
                require(json.getInt("version") == 1)
                val files = json.getJSONArray("files")
                val entries = (0 until files.length()).map { n ->
                    val item = files.getJSONObject(n)
                    val entry = OutgoingFile(item.getString("id"), item.getString("name"), item.getLong("size"), item.optBoolean("uncertain"), item.optBoolean("armed") && !item.optBoolean("uncertain"),
                        item.optJSONObject("peer")?.let { peer -> RetryPeer(peer.getString("server_id"), peer.getString("pin")).also { require(Validation.id(it.serverId) && Validation.hash(it.pin)) } })
                    require(Validation.id(entry.id) && Validation.name(entry.name))
                    Validation.size(entry.size.toString())
                    entry
                }
                require(entries.size <= 64 && entries.map { it.id }.distinct().size == entries.size)
                val available = entries.filter { file(it).let { f -> f.isFile && f.length() == it.size } }
                recoveryNotice = available.size != entries.size
                OutgoingState(available)
            }
        } catch (_: Exception) {
            recoveryNotice = true
            OutgoingState()
        }
        val retained = state.files.map { "${it.id}.bin" }.toSet() + index.name
        val orphans = directory.listFiles()?.filter { it.name !in retained }.orEmpty()
        if (orphans.isNotEmpty()) recoveryNotice = true
        File(directory, "queue.tmp").delete()
        // Failure to write at startup is reported when the user changes/imports the queue.
        if (runCatching { save(state) }.isSuccess) orphans.forEach { it.delete() }
        else recoveryNotice = true
    }

    fun file(entry: OutgoingFile) = File(directory, "${entry.id}.bin")

    /** Whole selection commits together. Temporary grants are consumed now, with bounded memory. */
    @Synchronized fun importFiles(
        sources: List<OutgoingSource>, arm: Boolean, peer: RetryPeer? = null, cancelled: () -> Boolean,
    ): OutgoingState {
        if (sources.isEmpty()) return state
        if (state.files.size + sources.size > 64) throw SendSelectionException("La cola admite como máximo 64 archivos. Elimina o envía algunos primero.")
        val created = mutableListOf<File>()
        val imported = mutableListOf<OutgoingFile>()
        try {
            sources.forEach { source ->
                if (!Validation.name(source.name)) throw SendSelectionException("Este nombre de archivo no es válido para Windows. Cámbialo y vuelve a seleccionarlo.")
                val entry = OutgoingFile(UUID.randomUUID().toString(), source.name, 0)
                val temporary = File(directory, "${entry.id}.part")
                created += temporary
                val size = source.open().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val count = copyOutgoing(input, output, cancelled)
                        output.fd.sync()
                        count
                    }
                }
                val ready = entry.copy(size = size, armed = arm, peer = peer)
                val target = file(ready)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                created += target
                imported += ready
            }
            check(!cancelled()) { "Cancelada" }
            val next = OutgoingState(state.files + imported)
            save(next)
            return state
        } catch (error: Exception) {
            created.forEach { it.delete() }
            throw error
        }
    }

    @Synchronized fun arm(value: Boolean, ids: Set<String> = state.files.map { it.id }.toSet()) {
        save(state.copy(files = state.files.map { if (it.id in ids) it.copy(armed = value && !it.uncertain) else it }))
    }
    @Synchronized fun attempt(ids: Set<String>, peer: RetryPeer) {
        save(state.copy(files = state.files.map { if (it.id in ids) it.copy(armed = false, peer = peer) else it }))
    }
    @Synchronized fun uncertain(id: String) { save(state.copy(files = state.files.map { if (it.id == id) it.copy(uncertain = true, armed = false) else it })) }
    @Synchronized fun remove(id: String) {
        val entry = state.files.find { it.id == id } ?: return
        val remaining = state.files - entry
        save(OutgoingState(remaining))
        file(entry).delete() // A crash here leaves an orphan, cleaned on next initialization.
    }

    private fun save(next: OutgoingState) {
        val json = JSONObject().put("version", 1).put("files", JSONArray().apply {
            next.files.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("uncertain", it.uncertain).put("armed", it.armed).put("peer", it.peer?.let { peer -> JSONObject().put("server_id", peer.serverId).put("pin", peer.pin) })) }
        })
        val temporary = File(directory, "queue.tmp")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        state = next
    }
}

internal data class OutgoingSource(val name: String, val open: () -> InputStream)

internal fun copyOutgoing(input: InputStream, output: java.io.OutputStream, cancelled: () -> Boolean): Long {
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        check(!cancelled()) { "Cancelada" }
        val count = input.read(buffer)
        if (count < 0) return size
        if (size > Validation.MAX_SIZE - count) throw SendSelectionException("El archivo supera el tamaño permitido.")
        output.write(buffer, 0, count)
        size += count
    }
}
