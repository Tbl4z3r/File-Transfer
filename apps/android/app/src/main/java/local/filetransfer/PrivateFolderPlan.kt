package local.filetransfer

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** Owns only directories exclusively created for this manifest; never traverses an existing root. */
internal object PrivateFolderPlan {
    fun prepare(inbox: File, paths: List<String>, cancelled: () -> Boolean): Map<String, File> {
        validateFolderManifest(emptyList(), paths)
        check((inbox.isDirectory || inbox.mkdirs()) && !Files.isSymbolicLink(inbox.toPath()))
        val created = LinkedHashMap<String, File>()
        try {
            for (path in paths.sortedBy { it.count { c -> c == '/' } }) {
                check(!cancelled()) { "Cancelada" }
                val parentPath = path.substringBeforeLast('/', "")
                val parent = if (parentPath.isEmpty()) inbox else checkNotNull(created[parentPath])
                check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()))
                val requested = path.substringAfterLast('/')
                var target: File? = null
                for (n in 0..10_000) {
                    val candidate = File(parent, numberedFolderName(requested, n))
                    try { Files.createDirectory(candidate.toPath()); target = candidate; break }
                    catch (error: FileAlreadyExistsException) { if (parentPath.isNotEmpty()) throw error }
                }
                created[path] = target ?: throw IOException("No hay un nombre de carpeta disponible")
            }
            check(!cancelled()) { "Cancelada" }
            return created
        } catch (error: Exception) {
            created.values.toList().asReversed().forEach { it.delete() }
            throw error
        }
    }
}
