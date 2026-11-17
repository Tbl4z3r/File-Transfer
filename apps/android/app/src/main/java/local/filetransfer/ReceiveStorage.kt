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
data class ReceivedDocument(val uri: Uri, val name: String)

/** App-private staging keeps unverified bytes out of the selected provider. */
class ReceiveStorage(private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("receive_destination", Context.MODE_PRIVATE)
    fun selectedTree(): Uri? = prefs.getString("tree", null)?.let(Uri::parse)
    fun usePrivateInbox() {
        val previous = selectedTree()
        check(prefs.edit().remove("tree").remove("label").commit())
        previous?.let { release(it) }
    }
    private fun release(uri: Uri) { runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }

    fun selectTree(uri: Uri, flags: Int): String {
        val granted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 || granted and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            throw ReceiveStorageException("destination_permission", "No se concedió acceso de lectura y escritura. Elige otra carpeta.")
        }
        val previous = selectedTree()
        resolver.takePersistableUriPermission(uri, granted)
        try {
            val label = validateTree(uri)
            check(prefs.edit().putString("tree", uri.toString()).putString("label", label).commit())
            if (previous != uri) previous?.let { release(it) }
            return label
        } catch (error: Exception) {
            if (previous != uri) release(uri)
            throw error
        }
    }

    fun destinationLabel(): String = if (selectedTree() != null) prefs.getString("label", "Carpeta seleccionada")!! else "Bandeja privada de la aplicación"
    fun validateDestination() { selectedTree()?.let { validateTree(it) } }
    private fun directory(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

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
            val inbox = File(context.filesDir, "inbox").apply { check(isDirectory || mkdirs()) }
            val target = PrivateInboxPublisher.publish(source, inbox, requestedName, size, hash, cancelled)
            return ReceivedDocument(FileProvider.getUriForFile(context, "${context.packageName}.inbox", target), target.name)
        }
        validateTree(tree)
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
            return ReceivedDocument(created, actualName)
        } catch (error: Exception) {
            // Delete only the document created by this operation. Providers may refuse cleanup; expose that case.
            val cleaned = runCatching { DocumentsContract.deleteDocument(resolver, created) }.getOrDefault(false)
            if (!cleaned) throw ReceiveStorageException("destination_cleanup", "No se pudo terminar el guardado ni retirar el archivo incompleto. Revísalo en la carpeta elegida.", error)
            throw error
        }
    }
}
