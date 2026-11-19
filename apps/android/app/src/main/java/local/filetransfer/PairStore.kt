package local.filetransfer

import android.content.Context
import java.security.KeyStore

/** Pairing is intentionally limited to this process; old persisted credentials are discarded. */
class PairStore(context: Context) {
    init {
        context.getSharedPreferences("private_pair", Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("pair-key") }
    }
}
