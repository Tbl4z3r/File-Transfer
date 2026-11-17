package local.filetransfer

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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
    @Test fun rejectInvalidDescriptorAndNames() {
        assertFalse(Validation.ipv4("192.168.01.1")); assertFalse(Validation.ipv4("224.0.0.1")); assertFalse(Validation.name("COM³.txt")); assertEquals(4294967297, Validation.size("4294967297"))
    }
}
