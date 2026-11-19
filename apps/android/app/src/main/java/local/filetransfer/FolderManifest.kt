package local.filetransfer

import java.util.Locale

/** Paths are protocol names, never filesystem paths. Parent lookup is exact; folding detects collisions only. */
internal fun validRelativePath(path: String): Boolean = path.toByteArray(Charsets.UTF_8).size <= 512 &&
    path.split('/').let { it.size in 1..16 && it.all(Validation::name) }

internal fun validateFolderManifest(files: List<UploadFile>, directories: List<String>) {
    if (directories.isEmpty()) {
        require(files.size in 2..64 && files.none { it.relativePath != null })
        return
    }
    require(files.size <= 64 && directories.size <= 64)
    val exact = directories.toSet()
    require(exact.size == directories.size)
    val occupied = HashSet<String>()
    directories.forEach { path ->
        require(validRelativePath(path))
        require(occupied.add(path.lowercase(Locale.ROOT)))
        require('/' !in path || path.substringBeforeLast('/') in exact)
    }
    files.forEach { file ->
        val path = file.relativePath
        if (path == null) {
            // Loose duplicate names retain v1 collision numbering, but cannot occupy a folder root.
            require(file.name.lowercase(Locale.ROOT) !in directories.map { it.lowercase(Locale.ROOT) }.toSet())
        } else {
            require(validRelativePath(path) && path.substringAfterLast('/') == file.name && '/' in path)
            require(path.substringBeforeLast('/') in exact)
            require(occupied.add(path.lowercase(Locale.ROOT)))
        }
    }
}

internal fun numberedFolderName(name: String, index: Int): String {
    if (index == 0) return name.also { require(Validation.name(it)) }
    val suffix = " ($index)"
    val budget = 180 - suffix.toByteArray(Charsets.UTF_8).size
    val stem = StringBuilder()
    for (point in name.codePoints().toArray()) {
        val chars = String(Character.toChars(point))
        if ((stem.toString() + chars).toByteArray(Charsets.UTF_8).size > budget) break
        stem.append(chars)
    }
    return "$stem$suffix".also { require(Validation.name(it)) }
}
