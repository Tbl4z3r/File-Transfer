package local.filetransfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class PairStore(context: Context) {
    private val prefs = context.getSharedPreferences("private_pair", Context.MODE_PRIVATE)
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): javax.crypto.SecretKey {
        (ks.getKey("pair-key", null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder("pair-key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    fun deviceId(): String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).commit() }
    fun save(p: Pairing) {
        val json = JSONObject().put("host", p.host).put("port", p.port).put("server", p.serverId).put("pin", p.pin).put("device", p.deviceId).put("credential", p.credential).put("server_name", p.serverName).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("pair", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
    }
    fun load(): Pairing? {
        val value = prefs.getString("pair", null) ?: return null
        val bytes = Base64.decode(value, Base64.NO_WRAP); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val o = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return Pairing(o.getString("host"), o.getInt("port"), o.getString("server"), o.getString("pin"), o.getString("device"), o.getString("credential"), o.optString("server_name", ""))
    }
    fun forget() { prefs.edit().remove("pair").apply(); ks.deleteEntry("pair-key") }
}
