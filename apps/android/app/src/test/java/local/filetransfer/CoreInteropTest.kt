package local.filetransfer

import org.json.JSONObject
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
        val process = ProcessBuilder(binary!!, "serve", "--data", File(root, "state").path, "--bind", "127.0.0.1", "--port", port.toString()).redirectError(File(root, "core-stderr")).start()
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
            command("receive", "enabled" to true)
            val bytes = ByteArray(1024 * 1024 + 31) { (it % 251).toByte() }
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
            val uploads = listOf("muestra.bin" to bytes, "segundo-vacío.bin" to byteArrayOf())
            val completedIds = mutableListOf<String>()
            SendQueue(uploads).run({ false }, {}) { (name, content) ->
                // The real core rejects a second nonterminal transfer, so this also checks ordering.
                val upload = c.createUpload(name, content.size.toLong())
                command("accept_upload", "transfer_id" to upload.id, "destination" to inbox.path)
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
            assertEquals("completed", c.complete(transfer.id, receivedHash).state)
            command("stop")
        } finally {
            process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            executor.shutdownNow(); root.deleteRecursively()
        }
    }
}
