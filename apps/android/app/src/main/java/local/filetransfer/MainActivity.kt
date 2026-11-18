package local.filetransfer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var client: ProtocolClient? = null
    @Volatile private var transferId: String? = null
    @Volatile private var cancelled = false
    @Volatile private var operationStage = "idle"
    @Volatile private var pendingUploadBatchId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val receiveStorage = ReceiveStorage(this)
        File(filesDir, "partial").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val store = PairStore(this)
        setContent {
            var pair by remember { mutableStateOf(runCatching { store.load() }.getOrNull()) }
            var descriptor by remember { mutableStateOf("") }
            var deviceName by rememberSaveable { mutableStateOf("Android personal") }
            var message by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var address by remember { mutableStateOf(pair?.host ?: "") }
            var portText by remember { mutableStateOf(pair?.port?.toString() ?: "") }
            var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
            var lastReceived by remember { mutableStateOf<ReceivedDocument?>(null) }
            var destination by remember { mutableStateOf(receiveStorage.destinationLabel()) }
            var diagnostic by remember { mutableStateOf<String?>(null) }
            var offer by remember { mutableStateOf<Transfer?>(null) }
            var transfer by remember { mutableStateOf<TransferPresentation?>(null) }
            var sendQueue by remember { mutableStateOf<SendQueueProgress?>(null) }
            var activity by remember { mutableStateOf(listOf<TransferPresentation>()) }
            var dialog by remember { mutableStateOf<String?>(null) }

            fun finish(result: String) {
                runOnUiThread {
                    transfer?.let {
                        val entry = it.copy(result = result)
                        transfer = entry
                        activity = (activity.filterNot { old -> entry.id != null && old.id == entry.id } + entry).takeLast(32)
                    }
                }
            }
            fun post(stage: String = "operation", transferOperation: Boolean = false, block: () -> Unit) {
                cancelled = false
                busy = true
                diagnostic = null
                message = ""
                operationStage = stage
                worker.execute {
                    try { block() }
                    catch (error: Exception) {
                        val details = TransferDiagnostics.describe(operationStage, error)
                        val explanation = TransferDiagnostics.userMessage(operationStage, error, cancelled)
                        if (transferOperation) finish(if (cancelled) "Cancelada" else "No completada")
                        runOnUiThread { diagnostic = details; message = explanation }
                    } finally {
                        transferId = null
                        runOnUiThread { busy = false }
                    }
                }
            }
            fun connected(): ProtocolClient {
                val p = requireNotNull(pair)
                return ProtocolClient(p.host, p.port, p.pin, p.deviceId, p.credential).also { client = it }
            }
            val progressClock = remember { java.util.concurrent.atomic.AtomicLong(0L) }
            fun report(n: Long, total: Long) {
                val now = System.nanoTime()
                if (n == total || now - progressClock.get() >= 100_000_000L) {
                    progressClock.set(now)
                    runOnUiThread { transfer = transfer?.copy(bytes = n) }
                }
            }
            fun phase(label: String) { runOnUiThread { transfer = transfer?.copy(phase = label) } }
            fun cancel() {
                cancelled = true
                val id = transferId
                client?.interrupt()
                if (id != null && operationStage != "send") {
                    val p = pair
                    if (p != null) Thread { runCatching { ProtocolClient(p.host, p.port, p.pin, p.deviceId, p.credential).cancel(id) } }.start()
                }
                message = "Cancelando…"
            }
            fun startPair(raw: String) {
                val name = deviceName
                dialog = null
                post("pair") {
                    val p = try {
                        withValidatedDescriptor(raw) { d ->
                            ProtocolClient(d.host, d.port, d.pin, store.deviceId()).also { client = it }.pair(d, name)
                        }
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
                if (raw == null) message = "Escaneo cancelado o cámara no disponible. Inténtalo de nuevo o usa la conexión manual en Ajustes."
                else if (!busy && pair == null) startPair(raw)
            }
            fun launchScanner() {
                runCatching {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setCaptureActivity(QrCaptureActivity::class.java).setOrientationLocked(true)
                        .setBeepEnabled(false).setBarcodeImageEnabled(false)
                        .setPrompt("Apunta al QR mostrado en el PC"))
                }.onFailure { message = "No se pudo iniciar la cámara. Usa la conexión manual en Ajustes." }
            }
            val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted && pair == null && !busy) launchScanner()
                else if (!granted) message = "No se concedió acceso a la cámara. Puedes permitirlo en los ajustes de Android o usar la conexión manual en Ajustes."
            }
            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val uri = result.data?.data
                if (result.resultCode == Activity.RESULT_OK && uri != null) post("choose_folder") {
                    val label = receiveStorage.selectTree(uri, result.data!!.flags)
                    runOnUiThread { destination = label; message = "Carpeta de recepción actualizada." }
                }
            }
            fun chooseFolder() {
                dialog = null
                folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty() && !busy && pair != null && pending == null && offer == null) {
                    transfer = null
                    sendQueue = null
                    post("send", transferOperation = true) {
                        val selected = uris.distinct()
                        if (selected.size > 64) throw SendSelectionException("Selecciona como máximo 64 archivos por envío.")
                        // Freeze every name/size before any network request, including legacy fallback.
                        val prepared = selected.map { uri ->
                            check(!cancelled) { "Cancelada" }
                            val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                                ?: throw SendSelectionException("El proveedor no permite leer este archivo. Vuelve a seleccionarlo desde Archivos.")
                            val (name, size) = cursor.use {
                                val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
                                if (!it.moveToFirst() || nameColumn < 0 || sizeColumn < 0 || it.isNull(nameColumn) || it.isNull(sizeColumn) || it.getType(sizeColumn) != android.database.Cursor.FIELD_TYPE_INTEGER)
                                    throw SendSelectionException("El proveedor no ofrece nombre y tamaño de este archivo. Guárdalo localmente y vuelve a seleccionarlo.")
                                it.getString(nameColumn) to it.getLong(sizeColumn)
                            }
                            if (!Validation.name(name)) throw SendSelectionException("Este nombre de archivo no es válido para Windows. Cámbialo y vuelve a seleccionarlo.")
                            try { Validation.size(size.toString()) }
                            catch (_: IllegalArgumentException) { throw SendSelectionException("El tamaño de este archivo no es válido o supera 1 TiB.") }
                            uri to UploadFile(name, size)
                        }
                        if (prepared.size > 1) try { uploadManifest(prepared.map { it.second }) }
                        catch (_: IllegalArgumentException) { throw SendSelectionException("Los nombres del lote superan el límite permitido. Selecciona menos archivos.") }
                        val c = connected()
                        val authorization = UploadAuthorization(c, prepared.map { it.second })
                        var confirmationPending = false
                        var queueState = SendQueueProgress(0, prepared.size, 0, true)
                        try {
                            runOnUiThread { sendQueue = queueState }
                            authorization.request({ cancelled }) {
                                runOnUiThread { message = "Espera la aceptación del lote en el PC." }
                            }
                            runOnUiThread { message = "" }
                            SendQueue(prepared.withIndex().toList()).run({ cancelled }, { state ->
                                queueState = state
                                runOnUiThread { sendQueue = state }
                            }) { (index, source) ->
                                val (uri, file) = source
                                val (name, size) = file
                                runOnUiThread { transfer = null }
                                transferId = null
                                progressClock.set(0L)
                                operationStage = "send"
                                check(!cancelled) { "Cancelada" }
                                // Each callback owns a snapshot; it cannot finish or update the next item.
                                var item = TransferPresentation(name, size, "upload", phase = "Solicitando envío al PC")
                                fun show() { val snapshot = item; runOnUiThread { transfer = snapshot } }
                                fun itemPhase(label: String) { item = item.copy(phase = label); show() }
                                fun itemFinish(result: String) {
                                    val entry = item.copy(result = result)
                                    item = entry
                                    runOnUiThread {
                                        transfer = entry
                                        activity = (activity.filterNot { old -> entry.id != null && old.id == entry.id } + entry).takeLast(32)
                                    }
                                }
                                show()
                                check(!cancelled) { "Cancelada" }
                                val t = authorization.start(index) { cancelled }
                                transferId = t.id
                                item = item.copy(id = t.id)
                                show()
                                var verifiedUpload = false
                                try {
                                    itemPhase("Espera la aceptación en el PC")
                                    var state = t
                                    while (state.state == "awaiting_acceptance") {
                                        check(!cancelled) { "Cancelada" }; Thread.sleep(700)
                                        check(!cancelled) { "Cancelada" }; state = c.status(t.id)
                                    }
                                    check(!cancelled) { "Cancelada" }
                                    if (state.state != "ready") {
                                        itemFinish(if (state.state == "cancelled") "Rechazada o cancelada en el PC" else "No completada")
                                        runOnUiThread { message = "El PC no aceptó el archivo. El lote se ha detenido." }
                                        return@run false
                                    }
                                    require(state.id == t.id)
                                    requireReadyUpload(state, file)
                                    itemPhase("Enviando al PC")
                                    val inputStream = contentResolver.openInputStream(uri)
                                        ?: throw SendSelectionException("No se pudo abrir este archivo. Vuelve a seleccionarlo desde Archivos.")
                                    val (remote, hash) = inputStream.use { input ->
                                        c.uploadContent(t.id, input, size) { n ->
                                            check(!cancelled) { "Cancelada" }
                                            val now = System.nanoTime()
                                            if (n == size || now - progressClock.get() >= 100_000_000L) {
                                                progressClock.set(now); item = item.copy(bytes = n); show()
                                            }
                                        }
                                    }
                                    itemPhase("Verificando integridad")
                                    require(remote.id == t.id && remote.direction == "upload" && remote.name == name && remote.size == size && remote.state == "awaiting_verification" && remote.sha256 == hash) { "La integridad no coincide" }
                                    verifiedUpload = true
                                    itemPhase("Confirmando con el PC")
                                    try {
                                        val completed = c.complete(t.id, hash)
                                        require(completed.state == "completed" && completed.sha256 == hash)
                                    } catch (error: Exception) {
                                        val remoteState = runCatching { c.status(t.id) }.getOrNull()
                                        val details = TransferDiagnostics.describe("confirm", error)
                                        when {
                                            remoteState?.state == "completed" && remoteState.sha256 == hash -> {
                                                itemFinish("Enviado y verificado")
                                                return@run true
                                            }
                                            remoteState?.state in setOf("cancelled", "failed") -> {
                                                itemFinish(if (remoteState?.state == "cancelled") "Cancelada" else "No completada")
                                                runOnUiThread { diagnostic = details; message = "El PC terminó la operación sin confirmar el archivo. El lote se ha detenido." }
                                            }
                                            else -> {
                                                confirmationPending = true
                                                pendingUploadBatchId = authorization.batchId
                                                itemFinish("Confirmación pendiente")
                                                runOnUiThread { diagnostic = details; pending = t.id to hash; message = "Datos enviados; falta confirmar que el PC guardó el archivo. El lote se ha detenido." }
                                            }
                                        }
                                        return@run false
                                    }
                                    itemFinish("Enviado y verificado")
                                    true
                                } catch (e: Exception) { if (!verifiedUpload) runCatching { c.cancel(t.id) }; throw e }
                                finally { transferId = null }
                            }
                        } finally {
                            val stopped = queueState.copy(running = false)
                            runOnUiThread { sendQueue = stopped }
                            if (queueState.completed < queueState.total) authorization.stop(confirmationPending)
                        }
                    }
                }
            }
            fun receive() {
                val selected = offer ?: return
                offer = null
                sendQueue = null
                transfer = TransferPresentation(selected.name, selected.size, "download", phase = "Preparando recepción", id = selected.id)
                post("receive", transferOperation = true) {
                    check(!cancelled) { "Cancelada" }
                    val c = connected()
                    transferId = selected.id
                    var temporary: File? = null
                    var published = false
                    try {
                        receiveStorage.validateDestination()
                        val temp = File.createTempFile("recv-", ".part", File(filesDir, "partial"))
                        temporary = temp
                        check(!cancelled) { "Cancelada" }
                        require(c.acceptDownload(selected.id).state == "ready")
                        check(!cancelled) { "Cancelada" }
                        operationStage = "download"
                        phase("Recibiendo del PC")
                        val hash = temp.outputStream().use { c.downloadContent(selected.id, selected.size, it) { n -> report(n, selected.size) } }
                        operationStage = "verify"
                        phase("Verificando integridad")
                        var remote = c.status(selected.id)
                        val deadline = System.nanoTime() + 30_000_000_000L
                        while (remote.state == "streaming" && System.nanoTime() < deadline) { Thread.sleep(700); remote = c.status(selected.id) }
                        check(!cancelled) { "Cancelada" }
                        require(remote.state == "awaiting_verification" && remote.sha256 == hash) { "La integridad no coincide" }
                        operationStage = "publish"
                        phase("Guardando archivo verificado")
                        val received = receiveStorage.publish(temp, selected.name, selected.size, hash) { cancelled }
                        published = true
                        temp.delete()
                        runOnUiThread { lastReceived = received }
                        operationStage = "confirm"
                        phase("Confirmando con el PC")
                        try { require(c.complete(selected.id, hash).state == "completed") }
                        catch (error: Exception) {
                            val remoteState = runCatching { c.status(selected.id) }.getOrNull()
                            val details = TransferDiagnostics.describe("confirm", error)
                            when {
                                remoteState?.state in setOf("cancelled", "failed") -> {
                                    finish("Guardado y verificado; sesión remota terminada")
                                    runOnUiThread { pending = null; diagnostic = details; message = "El archivo quedó guardado y verificado, pero el PC terminó la operación. No necesitas descargarlo otra vez." }
                                }
                                remoteState?.state == "completed" && remoteState.sha256 == hash -> finish("Recibido y verificado")
                                else -> {
                                    finish("Guardado y verificado; confirmación pendiente")
                                    runOnUiThread { diagnostic = details; pending = selected.id to hash; message = "Archivo íntegro guardado; confirmación remota pendiente." }
                                }
                            }
                            return@post
                        }
                        finish("Recibido y verificado")
                    } catch (e: Exception) { if (!published) runCatching { c.cancel(selected.id) }; throw e }
                    finally { temporary?.delete() }
                }
            }
            EsencialTheme {
                EsencialScreen(
                    pairedName = pair?.serverName?.takeIf { it.isNotBlank() }, pairedHost = pair?.let { "${it.host}:${it.port}" }, destination = destination, busy = busy,
                    busyLabel = when (operationStage) {
                        "choose_folder" -> "Actualizando carpeta…"
                        "offers" -> "Consultando archivos del PC…"
                        "reject" -> "Rechazando solicitud…"
                        "confirm" -> "Confirmando con el PC…"
                        "pair" -> "Conectando con el PC…"
                        else -> "Preparando transferencia…"
                    },
                    cancellable = busy && operationStage != "choose_folder",
                    message = message, transfer = transfer, sendQueue = sendQueue, offer = offer, activity = activity,
                    hasPending = pending != null, hasDiagnostic = diagnostic != null, canOpenReceived = lastReceived != null,
                    onSettings = { dialog = "settings" },
                    onScan = {
                        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) message = "No hay cámara disponible. Usa la conexión manual en Ajustes."
                        else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchScanner()
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    onFolder = { dialog = "folder" }, onSend = { picker.launch(arrayOf("*/*")) },
                    onReceive = { transfer = null; sendQueue = null; post("offers") { val offers = connected().offers(); runOnUiThread { offer = offers.firstOrNull(); if (offer == null) message = "No hay archivos ofrecidos por el PC. Elige un archivo allí y vuelve a pulsar Recibir." } } },
                    onAccept = { receive() }, onDecline = {
                        val selected = offer
                        if (selected != null) {
                            offer = null
                            post("reject") {
                                try { connected().cancel(selected.id); runOnUiThread { message = "Solicitud rechazada." } }
                                catch (error: Exception) {
                                    val details = TransferDiagnostics.describe("reject", error)
                                    runOnUiThread { diagnostic = details; message = "Solicitud descartada en este teléfono. No se pudo confirmar el rechazo al PC." }
                                }
                            }
                        }
                    },
                    onCancel = { cancel() }, onDismissPending = { dialog = "pending" }, onRetry = {
                        val item = pending
                        if (item != null) post("confirm") {
                            val (id, hash) = item
                            val c = connected()
                            val state = try { c.status(id) } catch (error: ProtocolException) {
                                if (transfer?.direction == "upload" && error.status == 404 && error.code == "not_found") null else throw error
                            }
                            if (transfer?.direction == "upload" && (state == null || state.state in setOf("cancelled", "failed"))) {
                                pendingUploadBatchId?.let { runCatching { c.cancelBatch(it) } }
                                pendingUploadBatchId = null
                                finish("Envío sin confirmación remota")
                                runOnUiThread { pending = null; message = "El PC terminó la operación. Comprueba el destino antes de volver a enviar el archivo." }
                                return@post
                            }
                            requireNotNull(state)
                            if (state.state == "completed") require(state.sha256 == hash)
                            else require(c.complete(id, hash).let { it.state == "completed" && it.sha256 == hash })
                            pendingUploadBatchId?.let { runCatching { c.cancelBatch(it) } }
                            pendingUploadBatchId = null
                            finish(if (transfer?.direction == "download") "Recibido y verificado" else "Enviado y verificado")
                            runOnUiThread { pending = null; message = "Confirmación remota completada." }
                        }
                    },
                    onDiagnostic = {
                        diagnostic?.let { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Diagnóstico File Transfer", it)) }
                        message = "Diagnóstico copiado; no incluye credenciales ni rutas de archivos."
                    },
                    onActivity = { dialog = "activity" }, onOpenReceived = {
                        lastReceived?.let { received -> runCatching {
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(received.uri, contentResolver.getType(received.uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(Intent.createChooser(intent, "Abrir archivo"))
                        }.onFailure { message = "No se pudo abrir el archivo. Comprueba que siga disponible y que haya una aplicación compatible." } }
                    },
                )
                when (dialog) {
                    "settings" -> EssentialDialog("Ajustes", { dialog = null }) {
                        TextButton(onClick = { dialog = "folder" }, enabled = !busy) { Text("Carpeta de recepción") }
                        if (pair == null) {
                            OutlinedTextField(deviceName, { deviceName = it }, label = { Text("Nombre de este dispositivo") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = { dialog = "manual" }, enabled = !busy) { Text("Conexión manual con JSON") }
                        } else {
                            Text("Conexión", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(address, { address = it }, label = { Text("IP del PC") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(portText, { portText = it }, label = { Text("Puerto") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            TextButton(enabled = !busy, onClick = {
                                runCatching {
                                    val port = portText.toInt()
                                    require(Validation.ipv4(address) && port in 1024..65535)
                                    val updated = pair!!.copy(host = address, port = port)
                                    store.save(updated); pair = updated
                                    message = "Dirección actualizada; se conserva la identidad emparejada."
                                    dialog = null
                                }.onFailure { message = "IP o puerto no válido."; dialog = null }
                            }) { Text("Actualizar dirección") }
                            TextButton(enabled = !busy, onClick = { dialog = "forget" }) { Text("Olvidar dispositivo") }
                        }
                        Text("Versión ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                    }
                    "manual" -> EssentialDialog("Conexión manual", { descriptor = ""; dialog = null }) {
                        Text("Copia el descriptor JSON que muestra tu PC.")
                        OutlinedTextField(descriptor, { descriptor = it }, label = { Text("Descriptor JSON") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && descriptor.isNotBlank(), onClick = { startPair(descriptor) }) { Text("Emparejar con JSON") }
                    }
                    "folder" -> EssentialDialog("Carpeta de recepción", { dialog = null }) {
                        Text(destination)
                        Button(onClick = { chooseFolder() }, enabled = !busy) { Text("Elegir carpeta") }
                        if (receiveStorage.selectedTree() != null) TextButton(enabled = !busy, onClick = {
                            dialog = null
                            post("choose_folder") { receiveStorage.usePrivateInbox(); runOnUiThread { destination = receiveStorage.destinationLabel(); message = "Se usará la bandeja privada de la aplicación." } }
                        }) { Text("Usar bandeja privada") }
                        Text("Si Android no permite elegir la raíz de Descargas, crea una subcarpeta como File Transfer.", style = MaterialTheme.typography.bodySmall)
                    }
                    "activity" -> EssentialDialog("Actividad de esta sesión", { dialog = null }) {
                        if (activity.isEmpty()) Text("Todavía no hay transferencias.")
                        activity.asReversed().forEach { FileRow(it.name, "${it.result ?: it.phase} · ${formatBytes(it.size)}") }
                    }
                    "pending" -> AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { dialog = null }, title = { Text("¿Cerrar la confirmación pendiente?") }, text = {
                        Text(if (transfer?.direction == "download") "El archivo recibido y verificado se conservará. Puedes cerrar este aviso y realizar otra transferencia." else "El PC aún no ha confirmado que guardó el archivo. Cerrar abandona esta confirmación y cancela el resto del lote. Comprueba el destino antes de volver a enviarlo.")
                    }, confirmButton = {
                        TextButton(onClick = {
                            val abandonedBatch = pendingUploadBatchId
                            val abandonedTransfer = pending?.first
                            if (transfer?.direction == "upload" && abandonedTransfer != null) {
                                post("abandon") {
                                    val c = connected()
                                    if (abandonedBatch != null) c.cancelBatch(abandonedBatch) else c.cancel(abandonedTransfer)
                                }
                            }
                            pendingUploadBatchId = null
                            pending = null
                            finish(if (transfer?.direction == "download") "Guardado y verificado; sin confirmación remota" else "Envío sin confirmación remota")
                            dialog = null
                            message = "Confirmación cerrada en este teléfono."
                        }) { Text("Cerrar confirmación") }
                    }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Volver") } })
                    "forget" -> AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { dialog = null }, title = { Text("¿Olvidar este PC?") }, text = { Text("Se eliminará la conexión de este teléfono. También puedes revocar el dispositivo en el PC.") }, confirmButton = {
                        TextButton(onClick = { store.forget(); pair = null; pending = null; offer = null; transfer = null; sendQueue = null; dialog = null; message = "Dispositivo olvidado en este teléfono." }) { Text("Olvidar") }
                    }, dismissButton = { TextButton(onClick = { dialog = "settings" }) { Text("Volver") } })
                }
            }
        }
    }

    override fun onDestroy() { cancelled = true; client?.interrupt(); worker.shutdownNow(); super.onDestroy() }
}
