package local.filetransfer

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in Linux loopback against the real Rust core; no secrets are printed. */
class CoreInteropTest {
    @Test fun realCorePairUploadDownload() {
        val binary = System.getenv("FT_CORE_BINARY")
        assumeTrue(!binary.isNullOrBlank())
        val root = Files.createTempDirectory("ft-interop-").toFile()
        val inbox = File(root, "inbox").apply { mkdir() }
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(binary!!, "serve", "--data", File(root, "state").path, "--bind", "127.0.0.1", "--port", port.toString(), "--ephemeral-peers").redirectError(File(root, "core-stderr")).start()
        val reader = process.inputStream.bufferedReader(); val writer = process.outputStream.bufferedWriter()
        val executor = Executors.newSingleThreadExecutor()
        fun receive(): JSONObject { val line = executor.submit<String?> { reader.readLine() }.get(30, TimeUnit.SECONDS); return JSONObject(requireNotNull(line)) }
        fun command(name: String, vararg values: Pair<String, Any>): JSONObject {
            val request = JSONObject().put("command", name); values.forEach { request.put(it.first, it.second) }
            writer.write(request.toString()); writer.newLine(); writer.flush()
            val result = receive(); check(result.getBoolean("ok")) { "Falló comando local $name" }; return result.getJSONObject("result")
        }
        try {
            assertEquals("started", receive().getString("event"))
            val descriptor = Descriptor.parse(command("pairing").toString()); val id = UUID.randomUUID().toString()
            val c = ProtocolClient(descriptor.host, descriptor.port, descriptor.pin, id)
            val pair = c.pair(descriptor, "Prueba JVM")
            assertEquals(descriptor.serverId, pair.serverId)
            assertEquals("PC personal", pair.serverName)
            assertTrue(command("snapshot").getJSONArray("peers").getJSONObject(0).getBoolean("supports_inbox"))
            command("receive", "enabled" to true)
            val bytes = ByteArray(1024 * 1024 + 31) { (it % 251).toByte() }
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
            val uploads = listOf("muestra.bin" to bytes, "segundo-vacío.bin" to byteArrayOf())
            val completedIds = mutableListOf<String>()
            val batch = c.createUploadBatch(uploads.map { UploadFile(it.first, it.second.size.toLong()) })
            assertEquals("awaiting_acceptance", batch.state)
            val denied = runCatching { c.startBatchFile(batch.id, 0) }.exceptionOrNull()
            assertTrue(denied is ProtocolException)
            assertEquals("invalid_state", (denied as ProtocolException).code)
            // Exactly one local PC authorization covers both authenticated uploads.
            command("accept_upload_batch", "batch_id" to batch.id, "destination" to inbox.path)
            assertEquals("accepted", c.batchStatus(batch.id).state)
            SendQueue(uploads.withIndex().toList()).run({ false }, {}) { (index, source) ->
                val (name, content) = source
                val upload = c.startBatchFile(batch.id, index)
                requireReadyUpload(upload, UploadFile(name, content.size.toLong()))
                val (sent, hash) = c.uploadContent(upload.id, content.inputStream(), content.size.toLong()) {}
                assertEquals(MessageDigest.getInstance("SHA-256").digest(content).hex(), hash)
                assertEquals(hash, sent.sha256)
                val completed = c.complete(upload.id, hash)
                assertEquals("completed", completed.state)
                assertEquals(hash, completed.sha256)
                assertArrayEquals(content, File(inbox, name).readBytes())
                completedIds += upload.id
                true
            }
            assertEquals(2, completedIds.distinct().size)
            assertEquals("completed", c.batchStatus(batch.id).state)
            val source = File(root, "origen.bin").apply { writeBytes(bytes) }
            val offered = command("offer", "peer_id" to id, "source" to source.path)
            val transfer = c.offers().single(); assertEquals(offered.getString("transfer_id"), transfer.id)
            assertEquals("ready", c.acceptDownload(transfer.id).state)
            val partial = File(root, "download.part")
            val receivedHash = partial.outputStream().use { output -> c.downloadContent(transfer.id, transfer.size, output) {} }
            assertEquals(expected, receivedHash)
            // Exercise the actual publication code too: transport success alone missed the Android hardlink failure.
            val received = PrivateInboxPublisher.publish(partial, inbox, "muestra.bin", transfer.size, receivedHash)
            assertEquals("muestra (1).bin", received.name)
            assertArrayEquals(bytes, received.readBytes())
            assertArrayEquals(bytes, File(inbox, "muestra.bin").readBytes())
            assertTrue(partial.delete())
            var state = c.status(transfer.id); val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (state.state == "streaming" && System.nanoTime() < limit) { Thread.sleep(100); state = c.status(transfer.id) }
            assertEquals("awaiting_verification", state.state); assertEquals(receivedHash, state.sha256)
            // Model slow local SAF publication: no content/status requests renew the 45 s lease.
            // The independent service heartbeat must keep authentication alive until confirmation.
            val heartbeat = ProtocolClient(pair.host, pair.port, pair.pin, pair.deviceId, pair.credential, shortTimeout = true)
            val heartbeatWorker = Executors.newSingleThreadScheduledExecutor()
            val heartbeatFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val heartbeatTask = heartbeatWorker.scheduleWithFixedDelay({
                try { heartbeat.inbox() } catch (error: Exception) { heartbeatFailure.compareAndSet(null, error) }
            }, 0, 2, TimeUnit.SECONDS)
            try {
                val publicationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(47)
                while (System.nanoTime() < publicationDeadline) Thread.sleep(100)
                assertNull("Authenticated heartbeat failed during local publication", heartbeatFailure.get())
                assertEquals("completed", c.complete(transfer.id, receivedHash).state)
            } finally {
                heartbeatTask.cancel(false)
                heartbeat.interrupt()
                heartbeatWorker.shutdownNow()
            }
            val empty = File(root, "vacío.bin").apply { writeBytes(byteArrayOf()) }
            assertEquals(Inbox(null, null), c.inbox())
            val sessionId = command("snapshot").getJSONObject("session").getString("session_id")
            val incoming = command("offer_download_batch", "peer_id" to id,
                "sources" to JSONArray(listOf(source.path, empty.path)), "session_id" to sessionId)
            val offeredBatch = requireNotNull(c.inbox().batch)
            assertEquals(incoming.getString("batch_id"), offeredBatch.id)
            assertEquals(listOf(UploadFile("origen.bin", bytes.size.toLong()), UploadFile("vacío.bin", 0)), offeredBatch.files)
            val premature = runCatching { c.startDownloadBatchFile(offeredBatch.id, 0) }.exceptionOrNull() as ProtocolException
            assertEquals("invalid_state", premature.code)
            assertEquals("accepted", c.acceptDownloadBatch(offeredBatch.id).state)
            // One acceptance covers the exact approved manifest. Completion gates the following file.
            offeredBatch.files.forEachIndexed { index, file ->
                val ready = c.startDownloadBatchFile(offeredBatch.id, index)
                requireReadyDownload(ready, file)
                val temp = File(root, "batch-$index.part")
                val digest = temp.outputStream().use { c.downloadContent(ready.id, ready.size, it) {} }
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                var remote = c.status(ready.id)
                while (remote.state == "streaming" && System.nanoTime() < limit) { Thread.sleep(100); remote = c.status(ready.id) }
                assertEquals("awaiting_verification", remote.state)
                assertEquals(digest, remote.sha256)
                val saved = PrivateInboxPublisher.publish(temp, inbox, ready.name, ready.size, digest)
                assertArrayEquals(if (index == 0) bytes else byteArrayOf(), saved.readBytes())
                assertEquals("completed", c.complete(ready.id, digest).state)
                temp.delete()
            }
            assertEquals("completed", c.downloadBatchStatus(offeredBatch.id).state)
            c.disconnect()
            val expired = runCatching { c.inbox() }.exceptionOrNull() as ProtocolException
            assertEquals(401, expired.status)
            assertEquals(0, command("snapshot").getJSONArray("peers").length())
            // A fresh scan uses a fresh UUID and works without recovering previous credentials.
            val freshDescriptor = Descriptor.parse(command("pairing").toString())
            val fresh = ProtocolClient(freshDescriptor.host, freshDescriptor.port, freshDescriptor.pin, UUID.randomUUID().toString())
            fresh.pair(freshDescriptor, "Nuevo intento JVM")
            assertEquals(Inbox(null, null), fresh.inbox())
            fresh.disconnect()
            command("stop")
        } finally {
            process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            executor.shutdownNow(); root.deleteRecursively()
        }
    }
}
