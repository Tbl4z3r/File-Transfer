package local.filetransfer

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class UploadAuthorizationTest {
    private val batchId = "11111111-1111-4111-8111-111111111111"
    private val transferId = "33333333-3333-4333-8333-333333333333"
    private val files = listOf(UploadFile("uno.bin", 3), UploadFile("vacío.bin", 0))
    private fun batch(state: String = "accepted") = """{"batch_id":"$batchId","state":"$state"}"""
    private fun transfer(file: UploadFile = files.first(), state: String = "ready", direction: String = "upload") =
        """{"transfer_id":"$transferId","direction":"$direction","file_name":"${file.name}","size_bytes":"${file.size}","bytes_transferred":"0","state":"$state"}"""
    private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()
    private fun withServer(block: (MockWebServer, ProtocolClient) -> Unit) {
        val cert = TlsTest().certificate("file-transfer.local")
        val pin = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).hex()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory()); server.start()
            block(server, ProtocolClient("127.0.0.1", server.port, pin, "22222222-2222-4222-8222-222222222222", "A".repeat(43)))
        }
    }

    @Test fun manifestLimitsAreCheckedBeforeAnyRequest() = withServer { server, client ->
        for (bad in listOf(emptyList(), files.take(1), List(65) { files.first() }, List(64) { UploadFile("\u2028".repeat(60), 0) })) {
            assertTrue(runCatching { client.createUploadBatch(bad) }.isFailure)
        }
        assertEquals(0, server.requestCount)
        assertTrue(runCatching { uploadManifest(List(64) { files.first() }) }.isSuccess)
        assertTrue(runCatching { UploadFile("../fuera", 0) }.isFailure)
        assertTrue(runCatching { UploadFile("desconocido", -1) }.isFailure)
    }

    @Test fun acceptedManifestUsesOnlyIndexAndPendingDoesNotCancel() = withServer { server, client ->
        server.enqueue(response(batch(), 201)); server.enqueue(response(transfer()))
        val authorization = UploadAuthorization(client, files)
        authorization.request({ false }, {})
        requireReadyUpload(authorization.start(0) { false }, files.first())
        authorization.stop(confirmationPending = true)
        assertEquals(2, server.requestCount)
        val create = server.takeRequest()
        assertEquals("/v1/upload-batches", create.url.encodedPath)
        assertEquals("Bearer ${"A".repeat(43)}", create.headers["Authorization"])
        val start = server.takeRequest()
        assertEquals("/v1/upload-batches/$batchId/start", start.url.encodedPath)
        assertEquals("{\"index\":0}", start.body!!.utf8())
        server.enqueue(response(batch("cancelled")))
        authorization.stop(confirmationPending = false)
        assertEquals("/v1/upload-batches/$batchId/cancel", server.takeRequest().url.encodedPath)
    }

    @Test fun onlyCreate404NotFoundFallsBackAndSingleFileIsLegacy() = withServer { server, client ->
        server.enqueue(response("""{"error":{"code":"not_found"}}""", 404))
        server.enqueue(response(transfer(state = "awaiting_acceptance"), 201))
        val authorization = UploadAuthorization(client, files)
        authorization.request({ false }, {})
        authorization.start(0) { false }
        assertNull(authorization.batchId)
        assertEquals("/v1/upload-batches", server.takeRequest().url.encodedPath)
        assertEquals("/v1/transfers", server.takeRequest().url.encodedPath)
        server.enqueue(response(transfer(state = "awaiting_acceptance"), 201))
        val single = UploadAuthorization(client, files.take(1))
        single.request({ false }, {}); single.start(0) { false }
        assertEquals("/v1/transfers", server.takeRequest().url.encodedPath)
        for ((status, code) in listOf(401 to "not_found", 409 to "busy", 404 to "unsupported_version", 413 to "limit_exceeded")) {
            server.enqueue(response("""{"error":{"code":"$code"}}""", status))
            assertTrue(runCatching { UploadAuthorization(client, files).request({ false }, {}) }.exceptionOrNull() is ProtocolException)
            assertEquals("/v1/upload-batches", server.takeRequest().url.encodedPath)
        }
        server.enqueue(response("{"))
        assertTrue(runCatching { UploadAuthorization(client, files).request({ false }, {}) }.isFailure)
        assertEquals("/v1/upload-batches", server.takeRequest().url.encodedPath)
        assertEquals(8, server.requestCount)
    }

    @Test fun cancellationBeforeRequestAndBetweenFilesDoesNotStartNext() = withServer { server, client ->
        val untouched = UploadAuthorization(client, files)
        assertTrue(runCatching { untouched.request({ true }, {}) }.isFailure)
        assertEquals(0, server.requestCount)
        server.enqueue(response(batch(), 201))
        val authorization = UploadAuthorization(client, files)
        authorization.request({ false }, {})
        assertTrue(runCatching { authorization.start(1) { true } }.isFailure)
        server.enqueue(response(batch("cancelled")))
        authorization.stop(false)
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellationWhileAwaitingPcCancelsManifestWithoutPreparingAFile() = withServer { server, client ->
        server.enqueue(response(batch("awaiting_acceptance"), 201))
        server.enqueue(response(batch("cancelled")))
        var cancelled = false
        val authorization = UploadAuthorization(client, files)
        assertTrue(runCatching { authorization.request({ cancelled }, { cancelled = true }) }.isFailure)
        authorization.stop(false)
        assertEquals(2, server.requestCount)
        assertEquals("/v1/upload-batches", server.takeRequest().url.encodedPath)
        assertEquals("/v1/upload-batches/$batchId/cancel", server.takeRequest().url.encodedPath)
    }

    @Test fun changedPreparedMetadataNeverStreams() = withServer { server, client ->
        for (body in listOf(transfer(UploadFile("otro.bin", 3)), transfer(UploadFile("uno.bin", 4)), transfer(direction = "download"), transfer(state = "awaiting_acceptance"))) {
            server.enqueue(response(batch(), 201)); server.enqueue(response(body))
            val authorization = UploadAuthorization(client, files)
            authorization.request({ false }, {})
            assertTrue(runCatching { authorization.start(0) { false } }.isFailure)
        }
        assertEquals(8, server.requestCount)
    }
}
