package local.filetransfer

import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Streaming copy that validates the exact byte count and SHA-256 before returning. */
object VerifiedCopy {
    private const val BUFFER_SIZE = 64 * 1024

    fun copy(
        source: File,
        output: OutputStream,
        expectedSize: Long,
        expectedSha256: String,
        cancelled: () -> Boolean = { false }
    ) {
        require(expectedSize >= 0) { "El tamaño esperado no puede ser negativo" }
        require(Validation.hash(expectedSha256)) { "SHA-256 esperado inválido" }

        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        FileInputStream(source).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (cancelled()) throw InterruptedIOException("Copia cancelada")
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (copied > expectedSize - count.toLong()) {
                    throw IllegalStateException("El tamaño de la fuente cambió")
                }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                copied += count.toLong()
            }
        }

        if (cancelled()) throw InterruptedIOException("Copia cancelada")
        if (copied != expectedSize) throw IllegalStateException("El tamaño de la fuente cambió")
        if (digest.digest().hex() != expectedSha256) throw IllegalStateException("La integridad de la fuente no coincide")
        output.flush()
    }
}

/** Publishes verified private content with an exclusive new file and no overwrite or rename. */
object PrivateInboxPublisher {
    private const val MAX_COLLISIONS = 10_000

    fun publish(
        source: File,
        inbox: File,
        requestedName: String,
        expectedSize: Long,
        expectedSha256: String,
        cancelled: () -> Boolean = { false }
    ): File {
        require(Validation.name(requestedName)) { "Nombre de archivo inválido" }
        require(expectedSize >= 0) { "El tamaño esperado no puede ser negativo" }
        require(Validation.hash(expectedSha256)) { "SHA-256 esperado inválido" }
        require(inbox.isDirectory) { "La carpeta de recepción no existe" }

        val (stem, extension) = splitName(requestedName)
        for (suffix in 0..MAX_COLLISIONS) {
            val candidateName = if (suffix == 0) requestedName else numberedName(stem, extension, suffix)
            require(Validation.name(candidateName)) { "El nombre numerado excede el límite permitido" }
            val target = File(inbox, candidateName)
            var output: OutputStream? = null
            try {
                output = Files.newOutputStream(
                    target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                )
            } catch (_: FileAlreadyExistsException) {
                continue
            }

            // CREATE_NEW established ownership of this path; cleanup is limited to this file.
            try {
                output.use { VerifiedCopy.copy(source, it, expectedSize, expectedSha256, cancelled) }
                return target
            } catch (failure: Throwable) {
                runCatching { Files.deleteIfExists(target.toPath()) }
                throw failure
            }
        }
        throw FileAlreadyExistsException("No hay un nombre disponible en la carpeta de recepción")
    }

    private fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private fun numberedName(stem: String, extension: String, suffix: Int): String {
        val marker = " ($suffix)"
        val budget = 180 - (marker + extension).toByteArray(Charsets.UTF_8).size
        require(budget >= 0) { "El nombre numerado excede el límite permitido" }
        val shortenedStem = StringBuilder()
        for (codePoint in stem.codePoints().toArray()) {
            val chars = String(Character.toChars(codePoint))
            if ((shortenedStem.toString() + chars).toByteArray(Charsets.UTF_8).size > budget) break
            shortenedStem.append(chars)
        }
        val result = "$shortenedStem$marker$extension"
        require(Validation.name(result)) { "El nombre numerado no es válido" }
        return result
    }
}
