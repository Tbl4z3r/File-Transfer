package local.filetransfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

class ReceiveStorageException(val code: String, message: String, cause: Throwable? = null) : IOException(message, cause)
data class ReceivedDocument(val uri: Uri, val name: String, val location: ReceiveLocation)
data class ReceiveLocation(val tree: Uri?, val label: String)
data class ReceiveFolderEntry(val uri: Uri, val name: String, val isDirectory: Boolean)

/** App-private staging keeps unverified bytes out of the selected provider. */
class ReceiveStorage(private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("receive_destination", Context.MODE_PRIVATE)
    fun selectedTree(): Uri? = prefs.getString("tree", null)?.let(Uri::parse)
    // This app persists grants only for receive trees. Keep the current setting and any
    // locations still referenced by this activity's history, including across rotation.
    fun pruneHistoryGrants(historyTrees: Set<Uri>) {
        val selected = selectedTree()
        resolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) && it.uri != selected }.forEach { permission ->
            var flags = 0
            if (permission.isReadPermission && permission.uri !in historyTrees) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) runCatching { resolver.releasePersistableUriPermission(permission.uri, flags) }
        }
    }

    private fun setDestination(uri: Uri?, label: String?, historyTrees: Set<Uri>) {
        check(prefs.edit().putString("tree", uri?.toString()).putString("label", label).commit())
        pruneHistoryGrants(historyTrees)
    }

    fun usePrivateInbox(historyTrees: Set<Uri>) = setDestination(null, null, historyTrees)

    fun selectTree(uri: Uri, flags: Int, historyTrees: Set<Uri>): String {
        val granted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 || granted and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            throw ReceiveStorageException("destination_permission", "No se concedió acceso de lectura y escritura. Elige otra carpeta.")
        }
        resolver.takePersistableUriPermission(uri, granted)
        try {
            val label = validateTree(uri)
            setDestination(uri, label, historyTrees)
            return label
        } catch (error: Exception) {
            pruneHistoryGrants(historyTrees)
            throw error
        }
    }

    fun destinationLabel(): String = if (selectedTree() != null) prefs.getString("label", "Carpeta seleccionada")!! else "Bandeja privada de la aplicación"
    fun validateDestination() { selectedTree()?.let { validateTree(it) } }
    fun directory(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun privateInbox() = File(context.filesDir, "inbox")

    private fun privateUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.inbox", file)

    fun listFolder(location: ReceiveLocation, folder: Uri?): List<ReceiveFolderEntry> {
        val tree = location.tree
        val entries = if (tree == null) {
            privateInbox().listFiles().orEmpty().filter(File::isFile)
                .map { ReceiveFolderEntry(privateUri(it), it.name, false) }
        } else {
            val parent = folder ?: directory(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                buildList<ReceiveFolderEntry> {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        add(ReceiveFolderEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            cursor.getString(1) ?: "Archivo", cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                    }
                }
            } ?: throw IOException("No se pudo leer la carpeta")
        }
        return entries.sortedWith(compareBy<ReceiveFolderEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun validateTree(tree: Uri): String {
        try {
            if (!resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
                throw ReceiveStorageException("destination_permission", "Se perdió el permiso de la carpeta. Vuelve a elegirla.")
            }
            resolver.query(directory(tree), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR && cursor.getLong(2) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L) {
                    return cursor.getString(0) ?: "Carpeta seleccionada"
                }
            }
            throw ReceiveStorageException("destination_unavailable", "La carpeta no permite crear archivos. Elige otra carpeta.")
        } catch (error: SecurityException) {
            throw ReceiveStorageException("destination_permission", "Se perdió el permiso de la carpeta. Vuelve a elegirla.", error)
        } catch (error: java.io.FileNotFoundException) {
            throw ReceiveStorageException("destination_unavailable", "La carpeta ya no está disponible. Vuelve a elegirla.", error)
        }
    }

    fun publish(source: File, requestedName: String, size: Long, hash: String, cancelled: () -> Boolean): ReceivedDocument {
        require(Validation.name(requestedName))
        val tree = selectedTree()
        if (tree == null) {
            val inbox = privateInbox().apply { check(isDirectory || mkdirs()) }
            val target = PrivateInboxPublisher.publish(source, inbox, requestedName, size, hash, cancelled)
            return ReceivedDocument(privateUri(target), target.name, ReceiveLocation(null, "Bandeja privada de la aplicación"))
        }
        val folderLabel = validateTree(tree)
        val names = HashSet<String>()
        val existingIds = HashSet<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: throw ReceiveStorageException("destination_unavailable", "No se pudo leer la carpeta elegida. Vuelve a elegirla.")
        cursor.use {
            while (it.moveToNext()) {
                if (cancelled()) throw java.io.InterruptedIOException("cancelled")
                existingIds.add(it.getString(0))
                names.add(it.getString(1).lowercase(java.util.Locale.ROOT))
            }
        }
        val dot = requestedName.lastIndexOf('.').takeIf { it > 0 } ?: requestedName.length
        var candidate = requestedName
        var index = 0
        while (candidate.lowercase(java.util.Locale.ROOT) in names) {
            if (++index > 10000) throw ReceiveStorageException("destination_collision", "Hay demasiados archivos con ese nombre. Elige otra carpeta.")
            candidate = requestedName.substring(0, dot) + " ($index)" + requestedName.substring(dot)
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(requestedName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
        if (cancelled()) throw java.io.InterruptedIOException("cancelled")
        val created = DocumentsContract.createDocument(resolver, directory(tree), mime, candidate)
            ?: throw ReceiveStorageException("destination_create", "No se pudo crear el archivo en la carpeta elegida.")
        // Some providers rename on collision. Never reopen a document found by name, and refuse a returned existing ID.
        if (DocumentsContract.getDocumentId(created) in existingIds) {
            throw ReceiveStorageException("destination_collision", "El proveedor devolvió un archivo existente. No se modificó; elige otra carpeta.")
        }
        try {
            val output = resolver.openOutputStream(created, "w")
                ?: throw ReceiveStorageException("destination_write", "No se pudo abrir el archivo nuevo para guardar.")
            output.use { VerifiedCopy.copy(source, it, size, hash, cancelled) }
            val actualName = resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: candidate
            return ReceivedDocument(created, actualName, ReceiveLocation(tree, folderLabel))
        } catch (error: Exception) {
            // Delete only the document created by this operation. Providers may refuse cleanup; expose that case.
            val cleaned = runCatching { DocumentsContract.deleteDocument(resolver, created) }.getOrDefault(false)
            if (!cleaned) throw ReceiveStorageException("destination_cleanup", "No se pudo terminar el guardado ni retirar el archivo incompleto. Revísalo en la carpeta elegida.", error)
            throw error
        }
    }
}
