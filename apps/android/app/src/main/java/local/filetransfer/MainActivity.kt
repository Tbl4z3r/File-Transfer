package local.filetransfer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var client: ProtocolClient? = null
    @Volatile private var transferId: String? = null
    @Volatile private var cancelled = false
    @Volatile private var operationStage = "idle"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receiveStorage = ReceiveStorage(this)
        File(filesDir, "partial").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val store = PairStore(this)
        setContent {
            var pair by remember { mutableStateOf(runCatching { store.load() }.getOrNull()) }
            var descriptor by remember { mutableStateOf("") }
            var deviceName by rememberSaveable { mutableStateOf("Android personal") }
            var message by remember { mutableStateOf("Escanea el QR mostrado en tu PC para conectar.") }
            var busy by remember { mutableStateOf(false) }
            var progress by remember { mutableStateOf("") }
            var address by remember { mutableStateOf(pair?.host ?: "") }
            var portText by remember { mutableStateOf(pair?.port?.toString() ?: "") }
            var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
            var lastReceived by remember { mutableStateOf<ReceivedDocument?>(null) }
            var destination by remember { mutableStateOf(receiveStorage.destinationLabel()) }
            var diagnostic by remember { mutableStateOf<String?>(null) }
            var offer by remember { mutableStateOf<Transfer?>(null) }
            fun post(stage: String = "operation", block: () -> Unit) {
                cancelled = false; busy = true; diagnostic = null; operationStage = stage
                worker.execute {
                    try { block() }
                    catch (error: Exception) {
                        val details = TransferDiagnostics.describe(operationStage, error)
                        val explanation = TransferDiagnostics.userMessage(operationStage, error, cancelled)
                        runOnUiThread { diagnostic = details; message = explanation }
                    } finally { transferId = null; runOnUiThread { busy = false } }
                }
            }
            fun connected(): ProtocolClient { val p = requireNotNull(pair); return ProtocolClient(p.host, p.port, p.pin, p.deviceId, p.credential).also { client = it } }
            fun report(n: Long, total: Long) { runOnUiThread { progress = "$n / $total bytes" } }
            fun startPair(raw: String) {
                val name = deviceName
                post("pair") {
                    val p = try {
                        withValidatedDescriptor(raw) { d -> ProtocolClient(d.host, d.port, d.pin, store.deviceId()).pair(d, name) }
                    } catch (_: InvalidDescriptor) {
                        runOnUiThread { message = "El código o descriptor no es válido. Genera otro QR en el PC o usa el JSON manual." }
                        return@post
                    } catch (error: ProtocolException) {
                        val explanation = when (error.code) {
                            "pairing_denied" -> "El QR ha caducado o ya se usó. Genera otro en el PC e inténtalo de nuevo."
                            "already_paired" -> "Este teléfono ya está emparejado. Revoca el emparejamiento anterior en el PC y genera otro QR."
                            else -> throw error
                        }
                        runOnUiThread { message = explanation }
                        return@post
                    }
                    store.save(p)
                    runOnUiThread { pair = p; address = p.host; portText = p.port.toString(); descriptor = ""; message = "Dispositivo emparejado." }
                }
            }
            val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
                val raw = result.contents
                if (raw == null) message = "Escaneo cancelado o cámara no disponible. Inténtalo de nuevo o introduce el JSON manualmente."
                else if (!busy && pair == null) startPair(raw)
            }
            fun launchScanner() {
                runCatching {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setCaptureActivity(QrCaptureActivity::class.java).setOrientationLocked(true)
                        .setBeepEnabled(false).setBarcodeImageEnabled(false)
                        .setPrompt("Apunta al QR mostrado en el PC"))
                }.onFailure { message = "No se pudo iniciar la cámara. Inténtalo de nuevo o introduce el JSON manualmente." }
            }
            val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted && pair == null && !busy) launchScanner()
                else if (!granted) message = "No se concedió acceso a la cámara. Puedes permitirlo en Ajustes o introducir el JSON manualmente."
            }
            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val uri = result.data?.data
                if (result.resultCode == Activity.RESULT_OK && uri != null) post("choose_folder") {
                    val label = receiveStorage.selectTree(uri, result.data!!.flags)
                    runOnUiThread { destination = label; message = "Los próximos archivos se guardarán en $label." }
                }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) post("send") {
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
                diagnostic?.let { details -> TextButton(onClick = {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Diagnóstico File Transfer", details))
                    message = "Diagnóstico copiado; no incluye credenciales ni rutas de archivos."
                }) { Text("Copiar diagnóstico") } }
                if (pair == null) {
                    Text("Escanea el QR que muestra tu PC o copia su descriptor JSON.")
                    Button(enabled = !busy, onClick = {
                        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                            message = "No hay cámara disponible. Introduce el JSON manualmente."
                        } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            launchScanner()
                        } else cameraPermission.launch(Manifest.permission.CAMERA)
                    }) { Text("Escanear QR del PC") }
                    OutlinedTextField(descriptor, { descriptor = it }, label = { Text("Descriptor JSON") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(deviceName, { deviceName = it }, label = { Text("Nombre de este dispositivo") })
                    Button(enabled = !busy, onClick = { startPair(descriptor) }) { Text("Emparejar con JSON") }
                } else {
                    Text("PC emparejado: ${pair!!.host}:${pair!!.port}")
                    OutlinedTextField(address, { address = it }, label = { Text("IP del PC") }, enabled = !busy)
                    OutlinedTextField(portText, { portText = it }, label = { Text("Puerto") }, enabled = !busy)
                    TextButton(enabled = !busy, onClick = { runCatching { val port = portText.toInt(); require(Validation.ipv4(address) && port in 1024..65535); val updated = pair!!.copy(host = address, port = port); store.save(updated); pair = updated; message = "Dirección actualizada; se conserva la identidad emparejada." }.onFailure { message = "IP o puerto no válido." } }) { Text("Actualizar dirección") }
                    pending?.let { (id, hash) -> Button(enabled = !busy, onClick = { post { val c = connected(); val state = c.status(id); if (state.state == "completed") require(state.sha256 == hash) else require(c.complete(id, hash).state == "completed"); runOnUiThread { pending = null; message = "Confirmación remota completada." } } }) { Text("Reintentar confirmación remota") } }
                    Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Enviar archivo") }
                    Text("Carpeta de recepción: $destination")
                    TextButton(enabled = !busy, onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                        folderPicker.launch(intent)
                    }) { Text("Elegir carpeta de recepción") }
                    if (receiveStorage.selectedTree() != null) TextButton(enabled = !busy, onClick = { post("choose_folder") {
                        receiveStorage.usePrivateInbox()
                        runOnUiThread { destination = receiveStorage.destinationLabel(); message = "Se usará la bandeja privada de la aplicación." }
                    } }) { Text("Usar bandeja privada") }
                    Button(enabled = !busy, onClick = { post { val offers = connected().offers(); runOnUiThread { offer = offers.firstOrNull(); message = if (offer == null) "No hay ofertas disponibles en el PC." else "Confirma si deseas recibir el archivo." } } }) { Text("Buscar archivo para recibir") }
                    offer?.let { selected ->
                        Text("${selected.name} · ${selected.size} bytes")
                        Button(enabled = !busy, onClick = { offer = null; post("receive") {
                            check(!cancelled) { "Cancelada" }; val c = connected(); transferId = selected.id
                            receiveStorage.validateDestination()
                            val temp = File.createTempFile("recv-", ".part", File(filesDir, "partial")); var published = false
                            try {
                                check(!cancelled) { "Cancelada" }; require(c.acceptDownload(selected.id).state == "ready"); check(!cancelled) { "Cancelada" }
                                operationStage = "download"
                                val hash = temp.outputStream().use { c.downloadContent(selected.id, selected.size, it) { n -> report(n, selected.size) } }
                                operationStage = "verify"
                                var remote = c.status(selected.id); val deadline = System.nanoTime() + 30_000_000_000L
                                while (remote.state == "streaming" && System.nanoTime() < deadline) { Thread.sleep(700); remote = c.status(selected.id) }
                                check(!cancelled) { "Cancelada" }; require(remote.state == "awaiting_verification" && remote.sha256 == hash) { "La integridad no coincide" }
                                operationStage = "publish"
                                runOnUiThread { message = "Guardando el archivo verificado en $destination…" }
                                val received = receiveStorage.publish(temp, selected.name, selected.size, hash) { cancelled }
                                published = true
                                temp.delete()
                                runOnUiThread { lastReceived = received; message = "Archivo íntegro guardado; confirmando al PC." }
                                operationStage = "confirm"
                                try { require(c.complete(selected.id, hash).state == "completed") } catch (error: Exception) {
                                    val remoteState = runCatching { c.status(selected.id) }.getOrNull()
                                    val details = TransferDiagnostics.describe("confirm", error)
                                    runOnUiThread {
                                        diagnostic = details
                                        if (remoteState?.state in setOf("cancelled", "failed")) {
                                            pending = null
                                            message = "El archivo quedó guardado y verificado, pero el PC terminó la operación. No necesitas descargarlo otra vez."
                                        } else if (remoteState?.state == "completed" && remoteState.sha256 == hash) {
                                            message = "Recibido y verificado: ${received.name}"
                                        } else {
                                            pending = selected.id to hash
                                            message = "Archivo íntegro guardado; confirmación remota pendiente."
                                        }
                                    }
                                    return@post
                                }
                                runOnUiThread { message = "Recibido y verificado: ${received.name}" }
                            } catch (e: Exception) { if (!published) { runCatching { c.cancel(selected.id) } }; throw e }
                            finally { temp.delete() }
                        } }) { Text("Aceptar recepción") }
                        TextButton(enabled = !busy, onClick = { offer = null }) { Text("Ahora no") }
                    }
                    lastReceived?.let { received -> TextButton(onClick = { runCatching {
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(received.uri, contentResolver.getType(received.uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(intent, "Abrir archivo"))
                    }.onFailure { message = "No se pudo abrir el archivo. Comprueba que siga disponible y que haya una aplicación compatible." } }) { Text("Abrir último archivo recibido") } }
                    if (busy) Button(onClick = { cancelled = true; val id = transferId; client?.interrupt(); if (id != null) Thread { runCatching { connected().cancel(id) } }.start(); message = "Cancelando…" }) { Text("Cancelar") }
                    TextButton(enabled = !busy, onClick = { store.forget(); pair = null; message = "Olvidado en este teléfono. También puedes revocarlo en el PC." }) { Text("Olvidar dispositivo") }
                }
                Text("Mantén la aplicación abierta durante la transferencia. Puedes elegir la carpeta donde recibir los archivos. Si Android no permite elegir la raíz de Descargas, crea una subcarpeta como File Transfer.")
            } }
        }
    }
    override fun onDestroy() { cancelled = true; client?.interrupt(); worker.shutdownNow(); super.onDestroy() }
}
