package local.filetransfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

internal data class SendFolderSelection(val sources: List<OutgoingSource>, val directories: List<String>)

/** Enumerates metadata within protocol limits; content is opened later and copied with a bounded buffer. */
internal fun readSendFolder(resolver: ContentResolver, tree: Uri, cancelled: () -> Boolean): SendFolderSelection {
    val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val name = resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
        check(it.moveToFirst() && it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR)
        it.getString(0)
    } ?: throw SendSelectionException("No se pudo abrir la carpeta elegida. Vuelve a seleccionarla.")
    val directories = mutableListOf<String>()
    val sources = mutableListOf<OutgoingSource>()
    val visited = HashSet<String>()
    fun visit(uri: Uri, path: String) {
        check(!cancelled()) { "Cancelada" }
        if (!validRelativePath(path)) throw SendSelectionException("La carpeta contiene nombres o rutas incompatibles. Usa hasta 16 niveles y rutas de 512 bytes.")
        if (!visited.add(DocumentsContract.getDocumentId(uri))) throw SendSelectionException("El proveedor repite una carpeta. Selecciona otra ubicación.")
        directories += path
        if (directories.size > 64) throw SendSelectionException("Selecciona una carpeta con hasta 64 directorios y 64 archivos.")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(uri))
        val entries = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            buildList<Triple<Uri, String, String>> {
                while (cursor.moveToNext()) {
                    check(!cancelled()) { "Cancelada" }
                    if (size >= 128) throw SendSelectionException("La carpeta supera el límite de 64 archivos y 64 directorios.")
                    add(Triple(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), cursor.getString(1) ?: throw SendSelectionException("El proveedor no ofrece el nombre de un archivo de la carpeta."), cursor.getString(2)))
                }
            }
        } ?: throw SendSelectionException("El proveedor no permite recorrer esta carpeta.")
        for ((child, childName, mime) in entries) {
            val relative = "$path/$childName"
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) visit(child, relative)
            else {
                if (!validRelativePath(relative) || sources.size >= 64) throw SendSelectionException("La carpeta supera los límites de nombres, rutas o 64 archivos.")
                sources += OutgoingSource(childName, relative) { resolver.openInputStream(child) ?: throw SendSelectionException("No se pudo leer un archivo de la carpeta. Vuelve a seleccionarla.") }
            }
        }
    }
    visit(root, name)
    try { uploadManifest(sources.map { UploadFile(it.name, 0, it.relativePath) }, directories) }
    catch (_: IllegalArgumentException) { throw SendSelectionException("La carpeta contiene rutas duplicadas o demasiados nombres largos. Selecciona una carpeta más pequeña o cambia los nombres.") }
    return SendFolderSelection(sources, directories)
}
