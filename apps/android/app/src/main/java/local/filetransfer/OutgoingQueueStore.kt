package local.filetransfer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class OutgoingFile(val id: String, val name: String, val size: Long, val uncertain: Boolean = false, val armed: Boolean = false, val peer: RetryPeer? = null, val relativePath: String? = null, val folderId: String? = null)
internal data class OutgoingFolder(val id: String, val directories: List<String>, val armed: Boolean, val peer: RetryPeer?, val uncertain: Boolean = false)
internal data class OutgoingState(val files: List<OutgoingFile> = emptyList(), val folders: List<OutgoingFolder> = emptyList()) {
    val armed get() = files.any { it.armed } || folders.any { it.armed }
    fun automaticFiles(peer: RetryPeer) = files.filter { file ->
        file.armed && !file.uncertain && (file.peer == null || file.peer == peer) &&
            (file.folderId == null || folders.any { it.id == file.folderId && !it.uncertain && it.armed && (it.peer == null || it.peer == peer) })
    }
    fun automaticFolders(peer: RetryPeer) = folders.filter { !it.uncertain && it.armed && (it.peer == null || it.peer == peer) }
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
                require(index.length() <= 128 * 1024)
                val json = JSONObject(index.readText())
                require(json.getInt("version") in 1..2)
                val files = json.getJSONArray("files")
                val entries = (0 until files.length()).map { n ->
                    val item = files.getJSONObject(n)
                    val entry = OutgoingFile(item.getString("id"), item.getString("name"), item.getLong("size"), item.optBoolean("uncertain"), item.optBoolean("armed") && !item.optBoolean("uncertain"),
                        readPeer(item), item.optString("relative_path").takeIf { it.isNotEmpty() }, item.optString("folder_id").takeIf { it.isNotEmpty() })
                    require(Validation.id(entry.id) && Validation.name(entry.name))
                    Validation.size(entry.size.toString())
                    entry
                }
                require(entries.size <= 64 && entries.map { it.id }.distinct().size == entries.size)
                val folderJson = json.optJSONArray("folders") ?: JSONArray()
                require(folderJson.length() <= 64)
                val folders = (0 until folderJson.length()).map { n ->
                    val item = folderJson.getJSONObject(n)
                    val dirs = item.getJSONArray("directories").let { a -> (0 until a.length()).map { a.getString(it) } }
                    OutgoingFolder(item.getString("id"), dirs, item.optBoolean("armed") && !item.optBoolean("uncertain"), readPeer(item), item.optBoolean("uncertain")).also { require(Validation.id(it.id)); validateFolderManifest(entries.filter { file -> file.folderId == it.id }.map { file -> UploadFile(file.name, file.size, file.relativePath) }, dirs) }
                }
                require(folders.sumOf { it.directories.size } <= 64 && folders.map { it.id }.distinct().size == folders.size)
                require(entries.all { it.folderId == null && it.relativePath == null || folders.any { folder -> folder.id == it.folderId } })
                val available = entries.filter { file(it).let { f -> f.isFile && f.length() == it.size } }
                recoveryNotice = available.size != entries.size
                OutgoingState(if (recoveryNotice) available.map { it.copy(armed = false) } else available, if (recoveryNotice) folders.map { it.copy(armed = false) } else folders)
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
        sources: List<OutgoingSource>, arm: Boolean, peer: RetryPeer? = null, directories: List<String> = emptyList(), cancelled: () -> Boolean,
    ): OutgoingState {
        if (sources.isEmpty() && directories.isEmpty()) return state
        if (state.files.size + sources.size > 64) throw SendSelectionException("La cola admite como máximo 64 archivos. Elimina o envía algunos primero.")
        if (directories.isNotEmpty()) validateFolderManifest(sources.map { UploadFile(it.name, 0, it.relativePath) }, directories)
        if (state.folders.sumOf { it.directories.size } + directories.size > 64) throw SendSelectionException("La cola admite como máximo 64 carpetas. Envía o elimina algunas primero.")
        val folder = directories.takeIf { it.isNotEmpty() }?.let { OutgoingFolder(UUID.randomUUID().toString(), it, arm, peer) }
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
                val ready = entry.copy(size = size, armed = arm, peer = peer, relativePath = source.relativePath, folderId = folder?.id)
                val target = file(ready)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                created += target
                imported += ready
            }
            check(!cancelled()) { "Cancelada" }
            if (directories.isNotEmpty()) try { uploadManifest(imported.map { UploadFile(it.name, it.size, it.relativePath) }, directories) }
            catch (_: IllegalArgumentException) { throw SendSelectionException("Los nombres de la carpeta superan el límite del lote. Selecciona una carpeta más pequeña o cambia los nombres.") }
            val next = OutgoingState(state.files + imported, state.folders + listOfNotNull(folder))
            save(next)
            return state
        } catch (error: Exception) {
            created.forEach { it.delete() }
            throw error
        }
    }

    @Synchronized fun arm(value: Boolean, ids: Set<String> = state.files.map { it.id }.toSet()) {
        save(state.copy(files = state.files.map { if (it.id in ids) it.copy(armed = value && !it.uncertain) else it },
            folders = state.folders.map { it.copy(armed = value && !it.uncertain && state.files.none { file -> file.folderId == it.id && file.uncertain }) }))
    }
    @Synchronized fun attempt(ids: Set<String>, peer: RetryPeer, folderIds: Set<String> = emptySet()) {
        save(state.copy(files = state.files.map { if (it.id in ids) it.copy(armed = false, peer = peer) else it }, folders = state.folders.map { if (it.id in folderIds) it.copy(armed = false, peer = peer, uncertain = true) else it }))
    }
    @Synchronized fun uncertain(id: String) { save(state.copy(files = state.files.map { if (it.id == id) it.copy(uncertain = true, armed = false) else it })) }
    @Synchronized fun remove(id: String) {
        val entry = state.files.find { it.id == id } ?: return
        val remaining = state.files - entry
        save(state.copy(files = remaining))
        file(entry).delete() // A crash here leaves an orphan, cleaned on next initialization.
    }

    @Synchronized fun removeFolder(id: String) {
        val removed = state.files.filter { it.folderId == id }
        save(state.copy(files = state.files - removed.toSet(), folders = state.folders.filterNot { it.id == id }))
        removed.forEach { file(it).delete() }
    }

    private fun readPeer(item: JSONObject): RetryPeer? = item.optJSONObject("peer")?.let { peer ->
        RetryPeer(peer.getString("server_id"), peer.getString("pin")).also { require(Validation.id(it.serverId) && Validation.hash(it.pin)) }
    }
    private fun peerJson(peer: RetryPeer?) = peer?.let { JSONObject().put("server_id", it.serverId).put("pin", it.pin) }

    private fun save(next: OutgoingState) {
        val json = JSONObject().put("version", 2).put("files", JSONArray().apply {
            next.files.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("uncertain", it.uncertain).put("armed", it.armed).put("peer", peerJson(it.peer)).put("relative_path", it.relativePath).put("folder_id", it.folderId)) }
        }).put("folders", JSONArray().apply {
            next.folders.forEach { put(JSONObject().put("id", it.id).put("directories", JSONArray(it.directories)).put("armed", it.armed).put("peer", peerJson(it.peer)).put("uncertain", it.uncertain)) }
        })
        val temporary = File(directory, "queue.tmp")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        state = next
    }
}

internal data class OutgoingSource(val name: String, val relativePath: String? = null, val open: () -> InputStream)

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

internal data class OutgoingDispatch(val fileIds: List<String>, val folderIds: List<String> = emptyList())

/** Each folder selection owns its manifest, so identical roots and the JSON budget cannot collide. */
internal fun outgoingDispatches(files: List<OutgoingFile>, folders: List<OutgoingFolder>): List<OutgoingDispatch> = buildList {
    folders.forEach { folder -> add(OutgoingDispatch(files.filter { it.folderId == folder.id }.map { it.id }, listOf(folder.id))) }
    var loose = emptyList<OutgoingFile>()
    files.filter { it.folderId == null }.forEach { file ->
        val candidate = loose + file
        if (candidate.size > 1 && runCatching { uploadManifest(candidate.map { UploadFile(it.name, it.size) }) }.isFailure) {
            add(OutgoingDispatch(loose.map { it.id })); loose = listOf(file)
        } else loose = candidate
    }
    if (loose.isNotEmpty()) add(OutgoingDispatch(loose.map { it.id }))
}
