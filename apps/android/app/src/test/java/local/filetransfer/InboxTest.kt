package local.filetransfer

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class InboxTest {
    private val batchId = "11111111-1111-4111-8111-111111111111"
    private val deviceId = "22222222-2222-4222-8222-222222222222"
    private val transferId = "33333333-3333-4333-8333-333333333333"
    private val credential = "A".repeat(43)
    private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()
    private fun transfer(name: String = "uno.bin", size: String = "3", direction: String = "download", state: String = "ready") =
        """{"transfer_id":"$transferId","direction":"$direction","file_name":"$name","size_bytes":"$size","bytes_transferred":"0","state":"$state"}"""
    private fun withServer(block: (MockWebServer, ProtocolClient, () -> ProtocolClient) -> Unit) {
        val cert = TlsTest().certificate("file-transfer.local")
        val pin = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).hex()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory()); server.start()
            fun client() = ProtocolClient("127.0.0.1", server.port, pin, deviceId, credential, shortTimeout = true)
            block(server, ProtocolClient("127.0.0.1", server.port, pin, deviceId, credential), ::client)
        }
    }
    @Test fun pairingAdvertisesInboxWithoutSendingBearerAndOtherRequestsDoNotAdvertiseIt() = withServer { server, c, _ ->
        server.enqueue(response("""{"protocol_version":1,"server_device_id":"$batchId","server_name":"PC","credential":"$credential"}""", 201))
        c.pair(Descriptor("127.0.0.1", server.port, batchId, "a".repeat(64), credential), "Android prueba")
        val pair = server.takeRequest()
        assertEquals("/v1/pair", pair.url.encodedPath)
        assertEquals("v1", pair.headers["X-FT-Inbox"])
        assertNull(pair.headers["Authorization"])
        server.enqueue(response("""{"transfer":null,"batch":null}"""))
        c.inbox()
        assertNull(server.takeRequest().headers["X-FT-Inbox"])
    }
    @Test fun inboxAllowsEmptySingleOrBatchAndNeverAcceptsIt() = withServer { server, c, _ ->
        server.enqueue(response("""{"transfer":null,"batch":null}"""))
        assertEquals(Inbox(null, null), c.inbox())
        server.enqueue(response("""{"transfer":${transfer(state = "awaiting_acceptance")},"batch":null}"""))
        assertEquals(transferId, c.inbox().transfer!!.id)
        server.enqueue(response("""{"transfer":null,"batch":{"batch_id":"$batchId","files":[{"file_name":"uno.bin","size_bytes":"3"},{"file_name":"vacío.bin","size_bytes":"0"}]}}"""))
        assertEquals(listOf(UploadFile("uno.bin", 3), UploadFile("vacío.bin", 0)), c.inbox().batch!!.files)
        repeat(3) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/inbox", request.url.encodedPath)
            assertEquals("Bearer $credential", request.headers["Authorization"])
            assertEquals(deviceId, request.headers["X-Device-Id"])
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun legacyFallbackOnlyForAuthenticatedNotFound() = withServer { server, c, _ ->
        server.enqueue(response("""{"error":{"code":"not_found","message":"missing"}}""", 404))
        server.enqueue(response("""{"transfers":[]}"""))
        assertEquals(Inbox(null, null), c.inbox())
        assertEquals("/v1/inbox", server.takeRequest().url.encodedPath)
        val fallback = server.takeRequest()
        assertEquals("/v1/offers", fallback.url.encodedPath)
        assertEquals("Bearer $credential", fallback.headers["Authorization"])
        for ((code, status) in listOf("unauthorized" to 401, "unsupported_version" to 404, "io_error" to 500)) {
            server.enqueue(response("""{"error":{"code":"$code","message":"error"}}""", status))
            val error = runCatching { c.inbox() }.exceptionOrNull() as ProtocolException
            assertEquals(code, error.code)
        }
        assertEquals(5, server.requestCount)
    }
    @Test fun invalidManifestAndReadyMetadataAreRejected() = withServer { server, c, _ ->
        for (size in listOf("-1", "01", "1099511627777")) {
            server.enqueue(response("""{"transfer":null,"batch":{"batch_id":"$batchId","files":[{"file_name":"uno.bin","size_bytes":"$size"},{"file_name":"dos.bin","size_bytes":"0"}]}}"""))
            assertTrue(runCatching { c.inbox() }.isFailure)
        }
        for (t in listOf(transfer(name = "cambiado.bin"), transfer(size = "4"), transfer(direction = "upload"), transfer(state = "awaiting_acceptance"))) {
            server.enqueue(response(t))
            val ready = c.startDownloadBatchFile(batchId, 0)
            assertTrue(runCatching { requireReadyDownload(ready, UploadFile("uno.bin", 3)) }.isFailure)
        }
        assertEquals(7, server.requestCount) // No content requests after mismatched metadata.
    }
    @Test fun batchActionsUseExactRequestsAndValidateIdentity() = withServer { server, c, _ ->
        fun batch(state: String, id: String = batchId) = """{"batch_id":"$id","state":"$state"}"""
        server.enqueue(response(batch("accepted")))
        assertEquals("accepted", c.acceptDownloadBatch(batchId).state)
        server.enqueue(response(transfer()))
        requireReadyDownload(c.startDownloadBatchFile(batchId, 0), UploadFile("uno.bin", 3))
        server.enqueue(response(batch("cancelled")))
        assertEquals("cancelled", c.cancelDownloadBatch(batchId).state)
        server.enqueue(response(batch("completed")))
        assertEquals("completed", c.downloadBatchStatus(batchId).state)
        server.enqueue(response("""{"disconnected":true}"""))
        c.disconnect()
        for ((path, body) in listOf("download-batches/$batchId/accept" to "{}", "download-batches/$batchId/start" to "{\"index\":0}", "download-batches/$batchId/cancel" to "{}", "download-batches/$batchId" to "", "disconnect" to "{}")) {
            val request = server.takeRequest()
            assertEquals("/v1/$path", request.url.encodedPath)
            assertEquals(body, request.body?.utf8().orEmpty())
        }
        server.enqueue(response(batch("accepted", transferId)))
        assertTrue(runCatching { c.acceptDownloadBatch(batchId) }.isFailure)
    }
    @Test fun independentInboxCanAnswerWhileContentIsInFlight() = withServer { server, c, newClient ->
        server.enqueue(MockResponse.Builder().body("abc").bodyDelay(5, TimeUnit.SECONDS).build())
        server.enqueue(response("""{"transfer":null,"batch":null}"""))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val download = executor.submit<String> { c.downloadContent(transferId, 3, java.io.ByteArrayOutputStream()) {} }
            assertEquals("/v1/transfers/$transferId/content", server.takeRequest().url.encodedPath)
            val before = System.nanoTime()
            assertEquals(Inbox(null, null), newClient().inbox())
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(4))
            assertFalse(download.isDone)
            assertEquals(MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).hex(), download.get(10, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
