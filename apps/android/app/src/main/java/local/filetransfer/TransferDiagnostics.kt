package local.filetransfer

import android.os.Build
import android.system.ErrnoException
import android.util.Log
import java.io.IOException
import java.io.InterruptedIOException

/** Diagnostics deliberately exclude exception messages, paths, names, IPs and credentials. */
object TransferDiagnostics {
    fun describe(stage: String, error: Exception): String {
        val diagnostic = render(stage, error, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "Android API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.e("FileTransfer", diagnostic.replace('\n', ' '))
        return diagnostic
    }

    internal fun render(stage: String, error: Exception, version: String, device: String): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(5).toList()
        val errno = causes.filterIsInstance<ErrnoException>().firstOrNull()?.errno
        val code = (error as? ReceiveStorageException)?.code ?: when (error) {
            is InterruptedIOException -> "interrupted"
            is SecurityException -> "permission_denied"
            is IOException -> "io_error"
            else -> "operation_failed"
        }
        return "File Transfer $version\n$device\nEtapa: $stage\nCódigo: $code\nTipo: ${causes.joinToString(" -> ") { it.javaClass.simpleName }}${errno?.let { "\nerrno: $it" } ?: ""}"
    }

    fun userMessage(stage: String, error: Exception, cancelled: Boolean): String {
        if (error is ReceiveStorageException) return error.message ?: "No se pudo guardar el archivo. Elige otra carpeta."
        if (cancelled) return "Transferencia cancelada."
        if (error is SendSelectionException) return requireNotNull(error.message)
        if (stage == "pair") return "No se pudo emparejar. Comprueba la conexión o genera otro QR en el PC si ha caducado; también puedes usar el JSON manual."
        if (stage == "publish") return "Se recibió el archivo, pero no se pudo guardar en el destino. Comprueba el espacio y vuelve a elegir la carpeta."
        if (error is SecurityException) return "Se perdió un permiso de acceso. Vuelve a elegir el archivo o la carpeta."
        return "No se pudo completar la operación. Comprueba la conexión y vuelve a intentarlo. Puedes copiar el diagnóstico para revisar el fallo."
    }
}
