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
data class ReceiveLocation(val tree: Uri?, val label: String, val directory: Uri? = null, val privatePath: String? = null)
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
            val parent = if (folder == null) privateDirectory(location.privatePath) else privateFileFromUri(folder)
            parent.listFiles().orEmpty().filterNot { java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .map { ReceiveFolderEntry(privateUri(it), it.name, it.isDirectory) }
        } else {
            val parent = folder ?: location.directory ?: directory(tree)
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

    private fun checkedCreated(tree: Uri, created: Uri): Uri {
        require(created.scheme == "content" && created.authority == tree.authority)
        require(!DocumentsContract.isTreeUri(created) || DocumentsContract.getTreeDocumentId(created) == DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created))
    }

    private fun privateDirectory(path: String?): File {
        val root = privateInbox()
        if (path == null) return root
        require(path.split('/').all(Validation::name))
        var target = root
        path.split('/').forEach { part ->
            target = File(target, part)
            require(!java.nio.file.Files.isSymbolicLink(target.toPath()) && target.isDirectory)
        }
        require(target.canonicalPath.startsWith(root.canonicalPath + File.separator))
        return target
    }

    private fun privateFileFromUri(uri: Uri): File {
        require(uri.authority == "${context.packageName}.inbox" && uri.pathSegments.firstOrNull() == "inbox")
        return privateDirectory(uri.pathSegments.drop(1).joinToString("/").takeIf { it.isNotEmpty() })
    }

    /** Create each root exclusively. Only IDs/paths created for this manifest are reused below it. */
    fun prepareFolders(paths: List<String>, cancelled: () -> Boolean): Map<String, ReceiveLocation> {
        if (paths.isEmpty()) return emptyMap()
        validateFolderManifest(emptyList(), paths)
        validateDestination()
        val tree = selectedTree()
        if (tree == null) return PrivateFolderPlan.prepare(privateInbox(), paths, cancelled).mapValues { (_, target) ->
            val relative = target.relativeTo(privateInbox()).invariantSeparatorsPath
            ReceiveLocation(null, "Bandeja privada / $relative", privatePath = relative)
        }
        val locations = LinkedHashMap<String, ReceiveLocation>()
        val ownedDocuments = mutableListOf<Uri>()
        val knownIds = HashSet<String>()
        try {
            for (path in paths.sortedBy { it.count { c -> c == '/' } }) {
                check(!cancelled()) { "Cancelada" }
                val parentPath = path.substringBeforeLast('/', "")
                val parentLocation = if (parentPath.isEmpty()) null else checkNotNull(locations[parentPath])
                val requestedName = path.substringAfterLast('/')
                val parent = parentLocation?.directory ?: directory(tree)
                knownIds += DocumentsContract.getDocumentId(parent)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
                val names = HashSet<String>()
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        check(!cancelled()) { "Cancelada" }
                        knownIds += cursor.getString(0)
                        names += cursor.getString(1).lowercase(java.util.Locale.ROOT)
                    }
                } ?: throw IOException("No se pudo leer el destino")
                var n = 0
                var candidate = requestedName
                while (candidate.lowercase(java.util.Locale.ROOT) in names) {
                    if (++n > 10_000) throw IOException("No hay un nombre de carpeta disponible")
                    candidate = numberedFolderName(requestedName, n)
                }
                val created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, candidate)
                    ?.let { checkedCreated(tree, it) } ?: throw IOException("No se pudo crear la carpeta")
                val id = DocumentsContract.getDocumentId(created)
                if (!knownIds.add(id)) throw ReceiveStorageException("destination_collision", "El proveedor devolvió una carpeta existente. Elige otro destino.")
                ownedDocuments += created
                val actualName = resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
                    check(it.moveToFirst() && it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR)
                    it.getString(0)
                } ?: throw IOException("No se pudo comprobar la carpeta nueva")
                locations[path] = ReceiveLocation(tree, (parentLocation?.label ?: destinationLabel()) + " / " + actualName, created)
            }
            return locations
        } catch (error: Exception) {
            var cleaned = true
            ownedDocuments.asReversed().forEach { if (!runCatching { DocumentsContract.deleteDocument(resolver, it) }.getOrDefault(false)) cleaned = false }
            if (!cleaned) throw ReceiveStorageException("destination_cleanup", "No se pudieron preparar todas las carpetas ni retirar algunas vacías. Revisa el destino.", error)
            throw error
        }
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

    fun publish(source: File, requestedName: String, size: Long, hash: String, location: ReceiveLocation? = null, cancelled: () -> Boolean): ReceivedDocument {
        require(Validation.name(requestedName))
        val tree = if (location == null) selectedTree() else location.tree
        if (tree == null) {
            val inbox = if (location == null) privateInbox().apply { check(isDirectory || mkdirs()) } else privateDirectory(location.privatePath)
            val target = PrivateInboxPublisher.publish(source, inbox, requestedName, size, hash, cancelled)
            return ReceivedDocument(privateUri(target), target.name, (location ?: ReceiveLocation(null, "Bandeja privada de la aplicación")))
        }
        val folderLabel = location?.label ?: validateTree(tree)
        validateTree(tree)
        val parent = location?.directory ?: directory(tree)
        val names = HashSet<String>()
        val existingIds = hashSetOf(DocumentsContract.getDocumentId(parent))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
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
        val created = DocumentsContract.createDocument(resolver, parent, mime, candidate)
            ?.let { checkedCreated(tree, it) } ?: throw ReceiveStorageException("destination_create", "No se pudo crear el archivo en la carpeta elegida.")
        // Some providers rename on collision. Never reopen a document found by name, and refuse a returned existing ID.
        if (DocumentsContract.getDocumentId(created) in existingIds) {
            throw ReceiveStorageException("destination_collision", "El proveedor devolvió un archivo existente. No se modificó; elige otra carpeta.")
        }
        try {
            val output = resolver.openOutputStream(created, "w")
                ?: throw ReceiveStorageException("destination_write", "No se pudo abrir el archivo nuevo para guardar.")
            output.use { VerifiedCopy.copy(source, it, size, hash, cancelled) }
            val actualName = resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: candidate
            return ReceivedDocument(created, actualName, ReceiveLocation(tree, folderLabel, parent))
        } catch (error: Exception) {
            // Delete only the document created by this operation. Providers may refuse cleanup; expose that case.
            val cleaned = runCatching { DocumentsContract.deleteDocument(resolver, created) }.getOrDefault(false)
            if (!cleaned) throw ReceiveStorageException("destination_cleanup", "No se pudo terminar el guardado ni retirar el archivo incompleto. Revísalo en la carpeta elegida.", error)
            throw error
        }
    }
}
