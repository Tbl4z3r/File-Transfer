package local.filetransfer

import android.net.Uri
import android.content.Intent
import androidx.core.content.FileProvider
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var client: ProtocolClient? = null
    @Volatile private var transferId: String? = null
    @Volatile private var cancelled = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inbox = File(filesDir, "inbox").apply { mkdirs() }
        File(filesDir, "partial").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val store = PairStore(this)
        setContent {
            var pair by remember { mutableStateOf(runCatching { store.load() }.getOrNull()) }
            var descriptor by remember { mutableStateOf("") }
            var deviceName by remember { mutableStateOf("Android personal") }
            var message by remember { mutableStateOf("Introduce el descriptor mostrado en el PC.") }
            var busy by remember { mutableStateOf(false) }
            var progress by remember { mutableStateOf("") }
            var address by remember { mutableStateOf(pair?.host ?: "") }
            var portText by remember { mutableStateOf(pair?.port?.toString() ?: "") }
            var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
            var lastFile by remember { mutableStateOf<File?>(null) }
            var offer by remember { mutableStateOf<Transfer?>(null) }
            fun post(block: () -> Unit) { cancelled = false; busy = true; worker.execute { try { block() } catch (e: Exception) { runOnUiThread { message = "Transferencia interrumpida: ${e.message ?: "error"}. Vuelve a intentarlo." } } finally { transferId = null; runOnUiThread { busy = false } } } }
            fun connected(): ProtocolClient { val p = requireNotNull(pair); return ProtocolClient(p.host, p.port, p.pin, p.deviceId, p.credential).also { client = it } }
            fun report(n: Long, total: Long) { runOnUiThread { progress = "$n / $total bytes" } }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) post {
                    val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null) ?: error("No se pudo leer la selección")
                    val (name, size) = cursor.use { require(it.moveToFirst()); it.getString(0) to it.getLong(1).also { _ -> require(!it.isNull(1)) } }
                    require(Validation.name(name)) { "Nombre no válido para Windows" }; Validation.size(size.toString())
                    check(!cancelled) { "Cancelada" }; val c = connected(); val t = c.createUpload(name, size); transferId = t.id
                    try {
                    runOnUiThread { message = "Espera la aceptación en el PC." }
                    var state = t; while (state.state == "awaiting_acceptance") { check(!cancelled) { "Cancelada" }; Thread.sleep(700); state = c.status(t.id) }
                    check(!cancelled) { "Cancelada" }; require(state.state == "ready") { "El PC no aceptó la transferencia" }
                    val (remote, hash) = requireNotNull(contentResolver.openInputStream(uri)).use { c.uploadContent(t.id, it, size) { n -> report(n, size) } }
                    check(!cancelled) { "Cancelada" }; require(remote.state == "awaiting_verification" && remote.sha256 == hash) { "La integridad no coincide" }
                    try { require(c.complete(t.id, hash).state == "completed") } catch (e: Exception) { runOnUiThread { pending = t.id to hash; message = "Archivo enviado y verificado; confirmación remota pendiente." }; return@post }
                    runOnUiThread { message = "Archivo enviado y verificado." }
                    } catch (e: Exception) { runCatching { c.cancel(t.id) }; throw e }
                }
            }
            MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Transferencia local", style = MaterialTheme.typography.headlineSmall)
                Text(message); if (progress.isNotEmpty()) Text(progress)
                if (pair == null) {
                    Text("Copia el descriptor desde el PC y verifica la huella por un canal físico.")
                    OutlinedTextField(descriptor, { descriptor = it }, label = { Text("Descriptor JSON") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(deviceName, { deviceName = it }, label = { Text("Nombre de este dispositivo") })
                    Button(enabled = !busy, onClick = { val raw = descriptor; post { val d = Descriptor.parse(raw); val p = ProtocolClient(d.host, d.port, d.pin, store.deviceId()).pair(d, deviceName); store.save(p); runOnUiThread { pair = p; address = p.host; portText = p.port.toString(); descriptor = ""; message = "Dispositivo emparejado." } } }) { Text("Emparejar") }
                } else {
                    Text("PC emparejado: ${pair!!.host}:${pair!!.port}")
                    OutlinedTextField(address, { address = it }, label = { Text("IP del PC") }, enabled = !busy)
                    OutlinedTextField(portText, { portText = it }, label = { Text("Puerto") }, enabled = !busy)
                    TextButton(enabled = !busy, onClick = { runCatching { val port = portText.toInt(); require(Validation.ipv4(address) && port in 1024..65535); val updated = pair!!.copy(host = address, port = port); store.save(updated); pair = updated; message = "Dirección actualizada; se conserva la identidad emparejada." }.onFailure { message = "IP o puerto no válido." } }) { Text("Actualizar dirección") }
                    pending?.let { (id, hash) -> Button(enabled = !busy, onClick = { post { val c = connected(); val state = c.status(id); if (state.state == "completed") require(state.sha256 == hash) else require(c.complete(id, hash).state == "completed"); runOnUiThread { pending = null; message = "Confirmación remota completada." } } }) { Text("Reintentar confirmación remota") } }
                    Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Enviar archivo") }
                    Button(enabled = !busy, onClick = { post { val offers = connected().offers(); runOnUiThread { offer = offers.firstOrNull(); message = if (offer == null) "No hay ofertas disponibles en el PC." else "Confirma si deseas recibir el archivo." } } }) { Text("Buscar archivo para recibir") }
                    offer?.let { selected ->
                        Text("${selected.name} · ${selected.size} bytes")
                        Button(enabled = !busy, onClick = { offer = null; post {
                            check(!cancelled) { "Cancelada" }; val c = connected(); transferId = selected.id
                            val temp = File.createTempFile("recv-", ".part", File(filesDir, "partial")); var published = false
                            try {
                                check(!cancelled) { "Cancelada" }; require(c.acceptDownload(selected.id).state == "ready"); check(!cancelled) { "Cancelada" }
                                val hash = temp.outputStream().use { c.downloadContent(selected.id, selected.size, it) { n -> report(n, selected.size) } }
                                var remote = c.status(selected.id); val deadline = System.nanoTime() + 30_000_000_000L
                                while (remote.state == "streaming" && System.nanoTime() < deadline) { Thread.sleep(700); remote = c.status(selected.id) }
                                check(!cancelled) { "Cancelada" }; require(remote.state == "awaiting_verification" && remote.sha256 == hash) { "La integridad no coincide" }
                                var index = 0; var dest: File
                                while (true) { val ext = selected.name.lastIndexOf('.').takeIf { it > 0 } ?: selected.name.length; val name = if (index == 0) selected.name else selected.name.substring(0, ext) + " ($index)" + selected.name.substring(ext); dest = File(inbox, name); index++; try { java.nio.file.Files.createLink(dest.toPath(), temp.toPath()); break } catch (_: java.nio.file.FileAlreadyExistsException) {} }
                                published = true
                                temp.delete()
                                runOnUiThread { lastFile = dest; message = "Archivo íntegro guardado en la bandeja privada; confirmando al PC." }
                                try { require(c.complete(selected.id, hash).state == "completed") } catch (e: Exception) { runOnUiThread { pending = selected.id to hash; message = "Archivo íntegro guardado; confirmación remota pendiente." }; return@post }
                                runOnUiThread { message = "Recibido y verificado en la bandeja privada: ${dest.name}" }
                            } catch (e: Exception) { if (!published) { temp.delete(); runCatching { c.cancel(selected.id) } }; throw e }
                        } }) { Text("Aceptar recepción") }
                        TextButton(enabled = !busy, onClick = { offer = null }) { Text("Ahora no") }
                    }
                    lastFile?.let { file -> TextButton(onClick = { val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.inbox", file); val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); runCatching { startActivity(Intent.createChooser(intent, "Abrir archivo")) }.onFailure { message = "No hay una aplicación para abrir este archivo." } }) { Text("Abrir último archivo recibido") } }
                    if (busy) Button(onClick = { cancelled = true; val id = transferId; client?.interrupt(); if (id != null) Thread { runCatching { connected().cancel(id) } }.start(); message = "Cancelando…" }) { Text("Cancelar") }
                    TextButton(enabled = !busy, onClick = { store.forget(); pair = null; message = "Olvidado en este teléfono. También puedes revocarlo en el PC." }) { Text("Olvidar dispositivo") }
                }
                Text("Mantén la aplicación abierta durante la transferencia. Los archivos recibidos se guardan en el almacenamiento privado de la aplicación.")
            } }
        }
    }
    override fun onDestroy() { client?.interrupt(); worker.shutdownNow(); super.onDestroy() }
}
