package local.filetransfer

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TransferDiagnosticsTest {
    @Test fun reportDoesNotExposeExceptionMessagesOrConnectionData() {
        val sensitive = "/data/user/0/private.pdf pairing_token=secret credential=bearer 192.168.1.3 content://private/tree"
        val error = IOException(sensitive, SecurityException(sensitive))
        val report = TransferDiagnostics.render("publish", error, "test", "test device")
        assertTrue(report.contains("Etapa: publish"))
        assertTrue(report.contains("IOException -> SecurityException"))
        listOf("private.pdf", "secret", "bearer", "192.168.1.3", "content://", "/data/").forEach { assertFalse(report.contains(it)) }
    }

    @Test fun causeChainIsBoundedAndStorageCodeIsActionable() {
        var error: Exception = IOException("ignored")
        repeat(20) { error = IOException("ignored", error) }
        val report = TransferDiagnostics.render("publish", ReceiveStorageException("destination_cleanup", "ignored", error), "test", "test device")
        assertTrue(report.contains("destination_cleanup"))
        assertEquals(5, report.substringAfter("Tipo: ").split(" -> ").size)
        assertFalse(report.contains("ignored"))
    }
}
