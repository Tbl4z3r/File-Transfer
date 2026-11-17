package local.filetransfer

import mockwebserver3.MockWebServer
import mockwebserver3.MockResponse
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class TlsTest {
    private fun certificate(name: String, expired: Boolean = false): HeldCertificate {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = System.currentTimeMillis(); val dn = X500Name("CN=$name")
        val builder = JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(now), Date(now - 86400000), Date(now + if (expired) -1000 else 86400000), dn, pair.public)
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, name)))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)))
        return HeldCertificate(pair, cert)
    }
    private fun runCase(name: String, expired: Boolean = false, wrongPin: Boolean = false, succeeds: Boolean = false) {
        val cert = certificate(name, expired); val pin = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).hex()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory()); server.enqueue(MockResponse.Builder().body("{\"transfers\":[]}").build()); server.start()
            val client = ProtocolClient("127.0.0.1", server.port, if (wrongPin) "00".repeat(32) else pin, "22222222-2222-4222-8222-222222222222", "A".repeat(43))
            val result = runCatching { client.offers() }
            assertEquals(result.exceptionOrNull()?.toString(), succeeds, result.isSuccess)
            assertEquals(if (succeeds) 1 else 0, server.requestCount)
        }
    }
    @Test fun streamedBodiesHashBothWays() {
        val cert = certificate("file-transfer.local"); val pin = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).hex()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory()); server.start()
            val bytes = ByteArray(200000) { (it % 251).toByte() }; val hash = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
            val id = "33333333-3333-4333-8333-333333333333"
            val response = """{"transfer_id":"$id","direction":"upload","file_name":"a.bin","size_bytes":"200000","state":"awaiting_verification","bytes_transferred":"200000","sha256":"$hash"}"""
            server.enqueue(MockResponse.Builder().body(response).build())
            val c = ProtocolClient("127.0.0.1", server.port, pin, "22222222-2222-4222-8222-222222222222", "A".repeat(43))
            val (state, digest) = c.uploadContent(id, bytes.inputStream(), bytes.size.toLong()) {}
            assertEquals(hash, digest); assertEquals(hash, state.sha256); assertArrayEquals(bytes, requireNotNull(server.takeRequest().body).toByteArray())
            server.enqueue(MockResponse.Builder().body(okio.Buffer().write(bytes)).addHeader("Content-Type", "application/octet-stream").build())
            val out = java.io.ByteArrayOutputStream(); assertEquals(hash, c.downloadContent(id, bytes.size.toLong(), out) {}); assertArrayEquals(bytes, out.toByteArray())
        }
    }
    @Test fun validCertAndPin() = runCase("file-transfer.local", succeeds = true)
    @Test fun wrongPin() = runCase("file-transfer.local", wrongPin = true)
    @Test fun wrongHostname() = runCase("other.local")
    @Test fun expired() = runCase("file-transfer.local", expired = true)
}
