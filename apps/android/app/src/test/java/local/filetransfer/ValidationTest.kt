package local.filetransfer

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class ValidationTest {
    @Test fun sharedVectors() {
        val root = JSONObject(File("../../../protocol/fixtures/h1-vectors.json").readText())
        for (key in listOf("sizes", "names", "ids")) {
            val a = root.getJSONArray(key)
            for (i in 0 until a.length()) {
                val v = a.getJSONObject(i); val value = v.opt("value")
                val actual = when (key) {
                    "sizes" -> value is String && runCatching { Validation.size(value) }.isSuccess
                    "names" -> value is String && Validation.name(value)
                    else -> value is String && Validation.id(value)
                }
                assertEquals("$key: $value", v.getBoolean("valid"), actual)
            }
        }
        val hashes = root.getJSONArray("hashes")
        for (i in 0 until hashes.length()) { val v = hashes.getJSONObject(i); val data = v.getString("bytes_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(); assertEquals(v.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(data).hex()) }
        Descriptor.parse(root.getJSONObject("messages").getJSONObject("descriptor").toString())
    }
    @Test fun strictDescriptor() {
        val valid = """{"v":1,"host":"192.168.1.20","port":51337,"server_device_id":"11111111-1111-4111-8111-111111111111","cert_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pairing_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}"""
        assertTrue(runCatching { Descriptor.parse(valid) }.isSuccess)
        assertTrue(runCatching { Descriptor.parse(valid.replace("\"v\":1", "\"v\":1,\"v\":1")) }.isFailure)
        assertTrue(runCatching { Descriptor.parse(valid.replace("\"port\":51337", "\"port\":\"51337\"")) }.isFailure)
        assertTrue(runCatching { Descriptor.parse(valid.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB")) }.isFailure)
    }
    @Test fun scannedDescriptorRejectsUrlsAndMalformedData() {
        val fixture = JSONObject(File("../../../protocol/fixtures/h1-vectors.json").readText())
            .getJSONObject("messages").getJSONObject("descriptor").toString()
        var connections = 0
        val descriptor = withValidatedDescriptor(fixture) { connections++; it }
        assertEquals(1, connections)
        assertTrue(Validation.ipv4(descriptor.host))
        assertTrue(descriptor.port in 1024..65535)
        for (input in listOf("https://example.com/", "file-transfer://$fixture", "{broken", "", fixture + " trailing", fixture.replace("\"host\":\"", "\"host\":\"https://"))) {
            val result = runCatching { withValidatedDescriptor(input) { connections++; it } }
            assertTrue("Rejected input", result.exceptionOrNull() is InvalidDescriptor)
        }
        assertEquals("Invalid scans must not start a connection", 1, connections)
    }
    @Test fun compactConnectionDecodesToTheSameDescriptorAsLegacyJson() {
        val vector = JSONObject(File("../../../protocol/fixtures/h1-vectors.json").readText())
            .getJSONObject("compact_connection")
        val expected = Descriptor.parse(vector.getJSONObject("descriptor").toString())
        assertEquals(expected, withValidatedDescriptor(vector.getString("code")) { it })
    }

    @Test fun rejectsMalformedCompactConnectionBeforeConnecting() {
        val code = JSONObject(File("../../../protocol/fixtures/h1-vectors.json").readText())
            .getJSONObject("compact_connection").getString("code")
        val bytes = Base64.getUrlDecoder().decode(code.removePrefix("FT1-"))
        fun changed(offset: Int, value: Int): String {
            val altered = bytes.copyOf()
            altered[offset] = value.toByte()
            return "FT1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(altered)
        }
        val invalid = listOf(
            code.replace("FT1-", "FT2-"), code + "=", code.dropLast(1), code + "A",
            code.replace("_", "/"), code.take(12) + "\n" + code.drop(12), code.dropLast(1) + "9",
            changed(0, 224), changed(4, 0), changed(12, 0), changed(14, 0),
        )
        for (input in invalid) {
            assertThrows(InvalidDescriptor::class.java) {
                withValidatedDescriptor(input) { fail("Invalid code started a connection") }
            }
        }
    }

    @Test fun acceptsDeviceNamesWithinThePairingProtocolLimits() {
        assertTrue(Validation.deviceName("Teléfono personal"))
        assertTrue(Validation.deviceName("📱".repeat(64)))
        assertFalse(Validation.deviceName("📱".repeat(65)))
        assertFalse(Validation.deviceName("Teléfono\npersonal"))
        assertFalse(Validation.deviceName(""))
    }

    @Test fun rejectInvalidDescriptorAndNames() {
        assertFalse(Validation.ipv4("192.168.01.1")); assertFalse(Validation.ipv4("224.0.0.1")); assertFalse(Validation.name("COM³.txt")); assertEquals(4294967297, Validation.size("4294967297"))
    }
}
