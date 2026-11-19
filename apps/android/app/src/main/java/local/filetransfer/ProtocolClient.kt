package local.filetransfer

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import org.json.JSONArray
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.Strictness
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction


private fun JSONObject.text(key: String): String = get(key) as? String ?: throw IllegalArgumentException("Tipo inválido: $key")
private fun JSONObject.integer(key: String): Int = get(key) as? Int ?: throw IllegalArgumentException("Tipo inválido: $key")
/** A single strict JSON parser is used on Android and JVM; never coerce types or accept duplicate keys. */
private fun strictObject(bytes: ByteArray): JSONObject {
    require(bytes.size <= 16384)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
    fun read(depth: Int): Any {
        require(depth <= 16)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> { reader.beginObject(); val obj = JSONObject(); val names = mutableSetOf<String>(); while (reader.hasNext()) { val name = reader.nextName(); require(names.add(name)); obj.put(name, read(depth + 1)) }; reader.endObject(); obj }
            JsonToken.BEGIN_ARRAY -> { reader.beginArray(); val array = JSONArray(); while (reader.hasNext()) array.put(read(depth + 1)); reader.endArray(); array }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> { val n = reader.nextString(); require(Regex("0|[1-9][0-9]*").matches(n)); n.toInt() }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> throw IllegalArgumentException("JSON inválido")
        }
    }
    val result = read(0) as? JSONObject ?: throw IllegalArgumentException("Se esperaba un objeto")
    require(reader.peek() == JsonToken.END_DOCUMENT)
    return result
}

object Validation {
    private val id = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val hash = Regex("[0-9a-f]{64}")
    private val token = Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
    fun id(s: String) = id.matches(s)
    fun hash(s: String) = hash.matches(s)
    fun token(s: String) = token.matches(s)
    const val MAX_SIZE = 1099511627776L
    fun size(s: String): Long { require(Regex("0|[1-9][0-9]*").matches(s)); return s.toLong().also { require(it <= MAX_SIZE) } }
    fun deviceName(s: String) = s.codePointCount(0, s.length) in 1..64 &&
        s.none { it.code < 32 || it.code == 127 } && Charsets.UTF_8.newEncoder().canEncode(s)
    fun name(s: String): Boolean {
        if (s.isEmpty() || s.toByteArray(Charsets.UTF_8).size > 180 || s == "." || s == ".." || s.last() == '.' || s.last() == ' ') return false
        if (s.any { it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|" }) return false
        return !Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])$").matches(s.substringBefore('.'))
    }
    fun ipv4(s: String): Boolean { val p = s.split('.'); if (p.size != 4 || p.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) || it.toInt() > 255 }) return false; val a = p[0].toInt(); return a in 1..223 && !(a == 169 && p[1] == "254") }
}
fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

data class Descriptor(val host: String, val port: Int, val serverId: String, val pin: String, val token: String) {
    companion object {
        private const val CODE_PREFIX = "FT1-"
        private val codePayload = Regex("[A-Za-z0-9_-]{115}")
        private val base64url = Base64.getUrlEncoder().withoutPadding()

        fun parse(raw: String): Descriptor {
            val descriptor = if (raw.startsWith(CODE_PREFIX)) parseCode(raw.substring(CODE_PREFIX.length)) else parseJson(raw)
            require(Validation.ipv4(descriptor.host) && descriptor.port in 1024..65535 &&
                Validation.id(descriptor.serverId) && Validation.hash(descriptor.pin) && Validation.token(descriptor.token))
            return descriptor
        }

        private fun parseCode(payload: String): Descriptor {
            require(codePayload.matches(payload))
            val bytes = Base64.getUrlDecoder().decode(payload)
            require(bytes.size == 86 && base64url.encodeToString(bytes) == payload)
            val host = bytes.sliceArray(0 until 4).joinToString(".") { (it.toInt() and 255).toString() }
            val port = ((bytes[4].toInt() and 255) shl 8) or (bytes[5].toInt() and 255)
            val idBytes = bytes.sliceArray(6 until 22).hex()
            val serverId = listOf(0..7, 8..11, 12..15, 16..19, 20..31).joinToString("-") { idBytes.substring(it) }
            val pin = bytes.sliceArray(22 until 54).hex()
            val token = base64url.encodeToString(bytes.sliceArray(54 until 86))
            return Descriptor(host, port, serverId, pin, token)
        }

        private fun parseJson(raw: String): Descriptor {
            val o = strictObject(raw.toByteArray(Charsets.UTF_8))
            require(o.keys().asSequence().toSet() == setOf("v", "host", "port", "server_device_id", "cert_sha256", "pairing_token"))
            require(o.integer("v") == 1)
            return Descriptor(o.text("host"), o.integer("port"), o.text("server_device_id"),
                o.text("cert_sha256").filterNot(Char::isWhitespace).lowercase(), o.text("pairing_token"))
        }
    }
}
class InvalidDescriptor : IllegalArgumentException()

/** Validate the complete input before invoking any network operation. */
internal fun <T> withValidatedDescriptor(raw: String, connect: (Descriptor) -> T): T {
    val descriptor = try { Descriptor.parse(raw) } catch (_: Exception) { throw InvalidDescriptor() }
    return connect(descriptor)
}

data class Pairing(val host: String, val port: Int, val serverId: String, val pin: String, val deviceId: String, val credential: String, val serverName: String = "")
data class Transfer(val id: String, val direction: String, val name: String, val size: Long, val state: String, val bytes: Long, val sha256: String?) {
    companion object { fun parse(o: JSONObject): Transfer {
        val keys = o.keys().asSequence().toSet(); require(keys.containsAll(setOf("transfer_id", "direction", "file_name", "size_bytes", "state", "bytes_transferred")) && keys.all { it in setOf("transfer_id", "direction", "file_name", "size_bytes", "state", "bytes_transferred", "sha256", "error_code") })
        val id = o.text("transfer_id"); val direction = o.text("direction"); val name = o.text("file_name"); val size = Validation.size(o.text("size_bytes")); val bytes = Validation.size(o.text("bytes_transferred")); val state = o.text("state"); val sha = if (o.has("sha256")) o.text("sha256") else null
        if (o.has("error_code")) { o.text("error_code"); require(state in setOf("failed", "cancelled")) }; require(state != "failed" || o.has("error_code"))
        require(Validation.id(id) && direction in setOf("upload", "download") && Validation.name(name) && bytes <= size && state in setOf("awaiting_acceptance", "ready", "streaming", "awaiting_verification", "completed", "failed", "cancelled"))
        require(if (state in setOf("awaiting_verification", "completed")) sha != null && Validation.hash(sha) && bytes == size else sha == null)
        return Transfer(id, direction, name, size, state, bytes, sha)
    } }
}

data class UploadFile(val name: String, val size: Long, val relativePath: String? = null) {
    init { require(Validation.name(name)); Validation.size(size.toString()); require(relativePath == null || validRelativePath(relativePath) && relativePath.substringAfterLast('/') == name) }
    internal fun json() = JSONObject().put("file_name", name).put("size_bytes", size.toString()).apply { relativePath?.let { put("relative_path", it) } }
}

data class UploadBatch(val id: String, val state: String) {
    companion object {
        fun parse(o: JSONObject): UploadBatch {
            require(o.keys().asSequence().toSet() == setOf("batch_id", "state"))
            val id = o.text("batch_id"); val state = o.text("state")
            require(Validation.id(id) && state in setOf("awaiting_acceptance", "accepted", "completed", "cancelled", "failed"))
            return UploadBatch(id, state)
        }
    }
}

/** Validate the encoded JSON, including escaping, before opening a connection. */
internal fun uploadManifest(files: List<UploadFile>, directories: List<String> = emptyList()): JSONObject {
    validateFolderManifest(files, directories)
    val body = JSONObject().put("files", JSONArray().also { a -> files.forEach { a.put(it.json()) } }).apply { if (directories.isNotEmpty()) put("directories", JSONArray(directories)) }
    require(body.toString().toByteArray(Charsets.UTF_8).size <= 16384) { "Los nombres del lote superan el límite permitido." }
    return body
}

data class DownloadOfferBatch(val id: String, val files: List<UploadFile>, val directories: List<String> = emptyList()) {
    companion object {
        fun parse(o: JSONObject): DownloadOfferBatch {
            require(o.keys().asSequence().toSet() in listOf(setOf("batch_id", "files"), setOf("batch_id", "files", "directories")))
            val id = o.text("batch_id"); require(Validation.id(id))
            val dirs = if (o.has("directories")) o.getJSONArray("directories").let { a ->
                require(a.length() in 1..64)
                (0 until a.length()).map { require(a.get(it) is String); a.getString(it) }
            } else emptyList()
            val a = o.getJSONArray("files"); require(a.length() <= 64)
            val files = (0 until a.length()).map { index ->
                val f = a.getJSONObject(index)
                require(f.keys().asSequence().toSet() in listOf(setOf("file_name", "size_bytes"), setOf("file_name", "size_bytes", "relative_path")))
                UploadFile(f.text("file_name"), Validation.size(f.text("size_bytes")), if (f.has("relative_path")) f.text("relative_path") else null)
            }
            validateFolderManifest(files, dirs)
            return DownloadOfferBatch(id, files, dirs)
        }
    }
}
data class Inbox(val transfer: Transfer?, val batch: DownloadOfferBatch?) {
    companion object {
        fun parse(o: JSONObject): Inbox {
            require(o.keys().asSequence().toSet() == setOf("transfer", "batch"))
            val transfer = if (o.isNull("transfer")) null else Transfer.parse(o.getJSONObject("transfer")).also {
                require(it.direction == "download" && it.state == "awaiting_acceptance")
            }
            val batch = if (o.isNull("batch")) null else DownloadOfferBatch.parse(o.getJSONObject("batch"))
            require(transfer == null || batch == null)
            return Inbox(transfer, batch)
        }
    }
}
internal fun requireReadyDownload(t: Transfer, file: UploadFile) {
    require(t.direction == "download" && t.state == "ready" && t.bytes == 0L && t.name == file.name && t.size == file.size)
}

class ProtocolException(val code: String, val status: Int = 0) : IllegalStateException("Error remoto")

/** Dedicated TLS client; check pin, dates and PKIX before any HTTP data is sent. */
class ProtocolClient(private val host: String, private val port: Int, pin: String, private val deviceId: String, private var credential: String? = null, private val shortTimeout: Boolean = false) {
    private val client: OkHttpClient
    @Volatile private var activeCall: Call? = null
    init {
        require(Validation.ipv4(host) && port in 1024..65535 && Validation.hash(pin) && Validation.id(deviceId))
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Cliente no admitido")
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("Cadena vacía")
                val leaf = chain[0]; leaf.checkValidity(); leaf.verify(leaf.publicKey)
                if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(leaf.encoded), pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray())) throw java.security.cert.CertificateException("Huella incorrecta")
                if (leaf.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") != true) throw java.security.cert.CertificateException("Certificado sin serverAuth")
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null); ks.setCertificateEntry("peer", leaf)
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(ks)
                (factory.trustManagers.filterIsInstance<X509TrustManager>().single()).checkServerTrusted(chain, authType)
            }
        }
        val ssl = SSLContext.getInstance("TLS"); ssl.init(null, arrayOf(tm), null)
        client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm).dns { name -> if (name != "file-transfer.local") throw java.net.UnknownHostException(name); listOf(InetAddress.getByAddress(host.split('.').map { it.toInt().toByte() }.toByteArray())) }.proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(if (shortTimeout) 3 else 15, TimeUnit.SECONDS).readTimeout(if (shortTimeout) 3 else 60, TimeUnit.SECONDS).writeTimeout(if (shortTimeout) 3 else 60, TimeUnit.SECONDS).callTimeout(if (shortTimeout) 4 else 0, TimeUnit.SECONDS).build()
    }
    fun interrupt() { activeCall?.cancel() }
    private fun req(path: String, method: String = "GET", body: RequestBody? = null, auth: Boolean = true): Response {
        val b = Request.Builder().url("https://file-transfer.local:$port/v1/$path").method(method, body).header("Accept-Encoding", "identity")
        if (path == "pair" && method == "POST") b.header("X-FT-Inbox", "v1").header("X-FT-Folders", "v1")
        if (auth) { b.header("X-Device-Id", deviceId); b.header("Authorization", "Bearer ${requireNotNull(credential)}") }
        val call = client.newCall(b.build()); activeCall = call
        return call.execute()
    }
    private fun json(path: String, method: String = "GET", value: JSONObject? = null, auth: Boolean = true): JSONObject {
        val body = value?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        req(path, method, body, auth).use { response ->
            val source = response.body.source(); source.request(16385); val bytes = source.readByteArray(minOf(source.buffer.size, 16385))
            require(bytes.size <= 16384)
            val obj = strictObject(bytes); if (!response.isSuccessful) throw ProtocolException(obj.optJSONObject("error")?.optString("code") ?: "unknown", response.code)
            return obj
        }
    }
    fun pair(d: Descriptor, name: String): Pairing {
        require(Validation.deviceName(name))
        val o = json("pair", "POST", JSONObject().put("pairing_token", d.token).put("device_id", deviceId).put("device_name", name), false)
        require(o.keys().asSequence().toSet() == setOf("protocol_version", "server_device_id", "server_name", "credential") && o.integer("protocol_version") == 1 && o.text("server_device_id") == d.serverId)
        val serverName = o.text("server_name"); require(Validation.deviceName(serverName))
        val secret = o.text("credential"); require(Validation.token(secret)); credential = secret
        return Pairing(host, port, d.serverId, d.pin, deviceId, secret, serverName)
    }
    fun createUpload(name: String, size: Long): Transfer { require(Validation.name(name)); Validation.size(size.toString()); return Transfer.parse(json("transfers", "POST", JSONObject().put("file_name", name).put("size_bytes", size.toString()))) }
    private fun batchPath(id: String): String { require(Validation.id(id)); return "upload-batches/$id" }
    fun createUploadBatch(files: List<UploadFile>, directories: List<String> = emptyList()): UploadBatch {
        val manifest = uploadManifest(files, directories)
        if (directories.isNotEmpty()) {
            val support = try { json("folder-support") } catch (error: ProtocolException) {
                if (error.status == 404 && error.code == "not_found") throw SendSelectionException("Actualiza File Transfer en el PC para enviar carpetas.")
                throw error
            }
            require(support.keys().asSequence().toSet() == setOf("folders"))
            if (support.get("folders") == false) throw SendSelectionException("El PC no admite carpetas. Actualiza File Transfer en el PC.")
            require(support.get("folders") == true)
        }
        return UploadBatch.parse(json("upload-batches", "POST", manifest))
    }
    fun batchStatus(id: String) = UploadBatch.parse(json(batchPath(id))).also { require(it.id == id) }
    fun startBatchFile(id: String, index: Int): Transfer {
        require(index in 0..63)
        return Transfer.parse(json("${batchPath(id)}/start", "POST", JSONObject().put("index", index)))
    }
    fun cancelBatch(id: String) = UploadBatch.parse(json("${batchPath(id)}/cancel", "POST", JSONObject())).also { require(it.id == id) }
    fun offers(): List<Transfer> { val o = json("offers"); require(o.keys().asSequence().toSet() == setOf("transfers")); val a = o.getJSONArray("transfers"); require(a.length() <= 1); return (0 until a.length()).map { Transfer.parse(a.getJSONObject(it)).also { t -> require(t.direction == "download" && t.state == "awaiting_acceptance") } } }
    /** Compatibility is allowed only after an authenticated route-not-found response. */
    fun inbox(): Inbox = try { Inbox.parse(json("inbox")) } catch (error: ProtocolException) {
        if (error.status != 404 || error.code != "not_found") throw error
        Inbox(offers().firstOrNull(), null)
    }
    private fun downloadBatchPath(id: String): String { require(Validation.id(id)); return "download-batches/$id" }
    fun downloadBatchStatus(id: String) = UploadBatch.parse(json(downloadBatchPath(id))).also { require(it.id == id) }
    fun acceptDownloadBatch(id: String, empty: Boolean = false) = UploadBatch.parse(json("${downloadBatchPath(id)}/accept", "POST", JSONObject())).also { require(it.id == id && it.state == if (empty) "completed" else "accepted") }
    fun startDownloadBatchFile(id: String, index: Int): Transfer {
        require(index in 0..63)
        return Transfer.parse(json("${downloadBatchPath(id)}/start", "POST", JSONObject().put("index", index)))
    }
    fun cancelDownloadBatch(id: String) = UploadBatch.parse(json("${downloadBatchPath(id)}/cancel", "POST", JSONObject())).also { require(it.id == id) }
    fun disconnect() { val o = json("disconnect", "POST", JSONObject()); require(o.keys().asSequence().toSet() == setOf("disconnected") && o.get("disconnected") == true) }
    private fun path(id: String): String { require(Validation.id(id)); return "transfers/$id" }
    fun status(id: String) = Transfer.parse(json(path(id)))
    fun acceptDownload(id: String) = Transfer.parse(json("${path(id)}/accept", "POST", JSONObject()))
    fun complete(id: String, sha: String): Transfer { require(Validation.hash(sha)); return Transfer.parse(json("${path(id)}/complete", "POST", JSONObject().put("sha256", sha))) }
    fun cancel(id: String) = Transfer.parse(json("${path(id)}/cancel", "POST", JSONObject()))
    fun uploadContent(id: String, input: InputStream, size: Long, progress: (Long) -> Unit): Pair<Transfer, String> {
        Validation.size(size.toString()); val digest = MessageDigest.getInstance("SHA-256")
        val body = object : RequestBody() { override fun contentType() = "application/octet-stream".toMediaType(); override fun contentLength() = size; override fun writeTo(sink: BufferedSink) { val buf = ByteArray(65536); var count = 0L; while (count < size) { val n = input.read(buf, 0, minOf(buf.size.toLong(), size-count).toInt()); if (n < 0) throw java.io.EOFException(); if (n == 0) continue; sink.write(buf, 0, n); digest.update(buf, 0, n); count += n; progress(count) }; if (input.read() != -1) throw java.io.IOException("Fuente cambió de tamaño") } }
        req("${path(id)}/content", "PUT", body).use { r -> if (!r.isSuccessful) throw IllegalStateException("Error al enviar: ${r.code}"); val source = r.body.source(); source.request(16385); val bytes = source.readByteArray(minOf(source.buffer.size, 16385)); require(bytes.size <= 16384); return Transfer.parse(strictObject(bytes)) to digest.digest().hex() }
    }
    fun downloadContent(id: String, size: Long, output: OutputStream, progress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        req("${path(id)}/content").use { r -> require(r.isSuccessful && r.body.contentLength() == size && r.header("Content-Encoding", "identity") == "identity"); val input = r.body.byteStream(); val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; count += n; require(count <= size); output.write(buf, 0, n); digest.update(buf, 0, n); progress(count) }; require(count == size) }
        return digest.digest().hex()
    }
}
