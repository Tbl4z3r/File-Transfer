package local.filetransfer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
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
    private class Session {
        val worker = Executors.newSingleThreadExecutor()
        val poller = Executors.newSingleThreadScheduledExecutor()
        @Volatile var client: ProtocolClient? = null
        @Volatile var transferId: String? = null
        @Volatile var cancelled = false
        @Volatile var operationStage = "idle"
        @Volatile var pendingUploadBatchId: String? = null
        @Volatile var generation = 0L
        val operationGeneration = ThreadLocal<Long>()
        val pair = mutableStateOf<Pairing?>(null)
        val message = mutableStateOf("")
        val destination = mutableStateOf("")
        val busy = mutableStateOf(false)
        val pending = mutableStateOf<Pair<String, String>?>(null)
        val lastReceived = mutableStateOf<ReceivedDocument?>(null)
        val diagnostic = mutableStateOf<String?>(null)
        val offer = mutableStateOf<Transfer?>(null)
        val batchOffer = mutableStateOf<DownloadOfferBatch?>(null)
        val transfer = mutableStateOf<TransferPresentation?>(null)
        val sendQueue = mutableStateOf<SendQueueProgress?>(null)
        val activity = mutableStateOf(listOf<TransferPresentation>())
    }
    private lateinit var session: Session
    private val worker get() = session.worker
    private var client: ProtocolClient?
        get() = session.client
        set(value) { session.client = value }
    private var transferId: String?
        get() = session.transferId
        set(value) { session.transferId = value }
    private var cancelled: Boolean
        get() = session.cancelled || session.operationGeneration.get()?.let { it != session.generation } == true
        set(value) { session.cancelled = value }
    private var operationStage: String
        get() = session.operationStage
        set(value) { session.operationStage = value }
    private var pendingUploadBatchId: String?
        get() = session.pendingUploadBatchId
        set(value) { session.pendingUploadBatchId = value }

    private fun sameGeneration() = session.operationGeneration.get()?.let { it == session.generation } ?: true

    private fun updateUi(action: () -> Unit) {
        val generation = session.operationGeneration.get() ?: session.generation
        runOnUiThread { if (generation == session.generation) action() }
    }

    private fun bestEffortDisconnect(p: Pairing) {
        Thread { runCatching { ProtocolClient(p.host, p.port, p.pin, p.deviceId, p.credential, shortTimeout = true).disconnect() } }.start()
    }

    private fun disconnectSession() {
        val p = session.pair.value
        session.generation++
        session.pair.value = null
        session.cancelled = true
        session.busy.value = false
        session.client?.interrupt()
        session.client = null
        session.transferId = null
        session.offer.value = null
        session.batchOffer.value = null
        session.pending.value = null
        session.pendingUploadBatchId = null
        session.transfer.value?.takeIf { it.result == null }?.let { current ->
            val result = if (current.direction == "download" && current.phase == "Confirmando con el PC")
                "Guardado y verificado; sesión remota terminada" else "Interrumpida; sesión terminada"
            val entry = current.copy(result = result)
            session.transfer.value = entry
            session.activity.value = (session.activity.value.filterNot { entry.id != null && it.id == entry.id } + entry).takeLast(32)
        }
        session.sendQueue.value = session.sendQueue.value?.copy(running = false)
        if (p != null) bestEffortDisconnect(p)
    }

    @Deprecated("Retains in-memory session only")
    override fun onRetainCustomNonConfigurationInstance(): Any = session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = (lastCustomNonConfigurationInstance as? Session) ?: Session()
        enableEdgeToEdge()
        val receiveStorage = ReceiveStorage(applicationContext)
        if (session.destination.value.isEmpty()) session.destination.value = receiveStorage.destinationLabel()
        File(filesDir, "partial").apply { mkdirs(); if (lastCustomNonConfigurationInstance == null) listFiles()?.forEach { it.delete() } }
        PairStore(this) // Discard credentials left by older versions; never restore pairing.
        setContent {
            var pair by remember { session.pair }
            var descriptor by remember { mutableStateOf("") }
            var deviceName by rememberSaveable { mutableStateOf("Android personal") }
            var message by remember { session.message }
            var busy by remember { session.busy }
            var address by remember { mutableStateOf(pair?.host ?: "") }
            var portText by remember { mutableStateOf(pair?.port?.toString() ?: "") }
            var pending by remember { session.pending }
            var lastReceived by remember { session.lastReceived }
            var destination by remember { session.destination }
            var diagnostic by remember { session.diagnostic }
            var offer by remember { session.offer }
            var transfer by remember { session.transfer }
            var sendQueue by remember { session.sendQueue }
            var activity by remember { session.activity }
            var batchOffer by remember { session.batchOffer }
            var dialog by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(pair) { pair?.let { address = it.host; portText = it.port.toString() } }

            fun finish(result: String) {
                updateUi {
                    transfer?.let {
                        val entry = it.copy(result = result)
                        transfer = entry
                        activity = (activity.filterNot { old -> entry.id != null && old.id == entry.id } + entry).takeLast(32)
                    }
                }
            }
            fun post(stage: String = "operation", transferOperation: Boolean = false, block: () -> Unit) {
                val operationGeneration = session.generation
                cancelled = false
                busy = true
                diagnostic = null
                message = ""
                operationStage = stage
                worker.execute {
                    if (operationGeneration != session.generation) return@execute
                    session.operationGeneration.set(operationGeneration)
                    try { block() }
                    catch (error: Exception) {
                        val details = TransferDiagnostics.describe(operationStage, error)
                        val explanation = TransferDiagnostics.userMessage(operationStage, error, cancelled)
                        if (transferOperation) finish(if (cancelled) "Cancelada" else "No completada")
                        updateUi { if (session.generation == operationGeneration) { diagnostic = details; message = explanation } }
                    } finally {
                        transferId = null
                        updateUi { busy = false }
                        session.operationGeneration.remove()
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
                    updateUi { transfer = transfer?.copy(bytes = n) }
                }
            }
            fun phase(label: String) { updateUi { transfer = transfer?.copy(phase = label) } }
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
                            ProtocolClient(d.host, d.port, d.pin, java.util.UUID.randomUUID().toString()).also { client = it }.pair(d, name)
                        }
                    } catch (_: InvalidDescriptor) {
                        updateUi { message = "El código o descriptor no es válido. Genera otro QR en el PC o usa el JSON manual." }
                        return@post
                    } catch (error: ProtocolException) {
                        val explanation = when (error.code) {
                            "pairing_denied" -> "El QR ha caducado o ya se usó. Genera otro en el PC e inténtalo de nuevo."
                            "already_paired" -> "Este teléfono ya está emparejado. Revoca el emparejamiento anterior en el PC y genera otro QR."
                            else -> throw error
                        }
                        updateUi { message = explanation }
                        return@post
                    }
                    val pairingGeneration = session.operationGeneration.get() ?: session.generation
                    runOnUiThread {
                        if (pairingGeneration != session.generation || cancelled || isFinishing) bestEffortDisconnect(p)
                        else { pair = p; address = p.host; portText = p.port.toString(); descriptor = ""; message = "Dispositivo emparejado." }
                    }
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
                    updateUi { destination = label; message = "Carpeta de recepción actualizada." }
                }
            }
            fun chooseFolder() {
                dialog = null
                folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty() && !busy && pair != null && pending == null && offer == null && batchOffer == null) {
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
                            updateUi { sendQueue = queueState }
                            authorization.request({ cancelled }) {
                                updateUi { message = "Espera la aceptación del lote en el PC." }
                            }
                            updateUi { message = "" }
                            SendQueue(prepared.withIndex().toList()).run({ cancelled }, { state ->
                                queueState = state
                                updateUi { sendQueue = state }
                            }) { (index, source) ->
                                val (uri, file) = source
                                val (name, size) = file
                                updateUi { transfer = null }
                                transferId = null
                                progressClock.set(0L)
                                operationStage = "send"
                                check(!cancelled) { "Cancelada" }
                                // Each callback owns a snapshot; it cannot finish or update the next item.
                                var item = TransferPresentation(name, size, "upload", phase = "Solicitando envío al PC")
                                fun show() { val snapshot = item; updateUi { transfer = snapshot } }
                                fun itemPhase(label: String) { item = item.copy(phase = label); show() }
                                fun itemFinish(result: String) {
                                    val entry = item.copy(result = result)
                                    item = entry
                                    updateUi {
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
                                        updateUi { message = "El PC no aceptó el archivo. El lote se ha detenido." }
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
                                        val remoteState = if (sameGeneration()) runCatching { c.status(t.id) }.getOrNull() else null
                                        val details = TransferDiagnostics.describe("confirm", error)
                                        when {
                                            remoteState?.state == "completed" && remoteState.sha256 == hash -> {
                                                itemFinish("Enviado y verificado")
                                                return@run true
                                            }
                                            remoteState?.state in setOf("cancelled", "failed") -> {
                                                itemFinish(if (remoteState?.state == "cancelled") "Cancelada" else "No completada")
                                                updateUi { diagnostic = details; message = "El PC terminó la operación sin confirmar el archivo. El lote se ha detenido." }
                                            }
                                            else -> {
                                                confirmationPending = true
                                                pendingUploadBatchId = authorization.batchId
                                                itemFinish("Confirmación pendiente")
                                                updateUi { diagnostic = details; pending = t.id to hash; message = "Datos enviados; falta confirmar que el PC guardó el archivo. El lote se ha detenido." }
                                            }
                                        }
                                        return@run false
                                    }
                                    itemFinish("Enviado y verificado")
                                    true
                                } catch (e: Exception) { if (!verifiedUpload && sameGeneration()) runCatching { c.cancel(t.id) }; throw e }
                                finally { transferId = null }
                            }
                        } finally {
                            val stopped = queueState.copy(running = false)
                            updateUi { sendQueue = stopped }
                            if (queueState.completed < queueState.total && sameGeneration()) authorization.stop(confirmationPending)
                        }
                    }
                }
            }
            DisposableEffect(pair) {
                val p = pair
                val generation = session.generation
                val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
                var losses = 0
                val pollClient = p?.let { ProtocolClient(it.host, it.port, it.pin, it.deviceId, it.credential, shortTimeout = true) }
                val task = pollClient?.let { c -> session.poller.scheduleWithFixedDelay({
                    try {
                        val inbox = c.inbox()
                        losses = 0
                        updateUi {
                            if (!disposed.get() && session.generation == generation && pair == p && !busy && pending == null) {
                                if (dialog == "batch_detail" && batchOffer != inbox.batch) dialog = null
                                offer = inbox.transfer
                                batchOffer = inbox.batch
                            }
                        }
                    } catch (error: Exception) {
                        val gone = error is ProtocolException && (error.status == 401 || error.code == "session_closed")
                        if (gone || ++losses >= 4) updateUi {
                            if (!disposed.get() && session.generation == generation && pair == p) {
                                disconnectSession()
                                dialog = null
                                message = "La sesión con el PC terminó o se perdió la conexión. Genera otro QR en el PC y escanéalo para reconectar. Los archivos guardados se conservan."
                            }
                        }
                    }
                }, 0, 2, java.util.concurrent.TimeUnit.SECONDS) }
                onDispose { disposed.set(true); task?.cancel(false); pollClient?.interrupt() }
            }
            BackHandler(enabled = pair != null && dialog == null) { disconnectSession(); this@MainActivity.finish() }
            fun receiveFile(c: ProtocolClient, selected: Transfer, accept: Boolean): Boolean {
                transferId = selected.id
                progressClock.set(0L)
                updateUi { transfer = TransferPresentation(selected.name, selected.size, "download", phase = "Preparando recepción", id = selected.id) }
                var temporary: File? = null
                var published = false
                try {
                    receiveStorage.validateDestination()
                    val temp = File.createTempFile("recv-", ".part", File(filesDir, "partial"))
                    temporary = temp
                    check(!cancelled) { "Cancelada" }
                    val ready = if (accept) c.acceptDownload(selected.id) else selected
                    require(ready.id == selected.id)
                    requireReadyDownload(ready, UploadFile(selected.name, selected.size))
                    check(!cancelled) { "Cancelada" }
                    operationStage = "download"
                    phase("Recibiendo del PC")
                    val hash = temp.outputStream().use { c.downloadContent(selected.id, selected.size, it) { n -> check(!cancelled); report(n, selected.size) } }
                    operationStage = "verify"
                    phase("Verificando integridad")
                    var remote = c.status(selected.id)
                    val deadline = System.nanoTime() + 30_000_000_000L
                    while (remote.state == "streaming" && System.nanoTime() < deadline) { check(!cancelled); Thread.sleep(700); remote = c.status(selected.id) }
                    check(!cancelled) { "Cancelada" }
                    require(remote.id == selected.id && remote.direction == "download" && remote.name == selected.name && remote.size == selected.size && remote.state == "awaiting_verification" && remote.sha256 == hash) { "La integridad no coincide" }
                    operationStage = "publish"
                    phase("Guardando archivo verificado")
                    val received = receiveStorage.publish(temp, selected.name, selected.size, hash) { cancelled }
                    published = true
                    temp.delete()
                    updateUi { lastReceived = received }
                    operationStage = "confirm"
                    phase("Confirmando con el PC")
                    try { require(c.complete(selected.id, hash).let { it.id == selected.id && it.state == "completed" && it.sha256 == hash }) }
                    catch (error: Exception) {
                        val remoteState = if (sameGeneration()) runCatching { c.status(selected.id) }.getOrNull() else null
                        val details = TransferDiagnostics.describe("confirm", error)
                        when {
                            remoteState?.state in setOf("cancelled", "failed") -> {
                                finish("Guardado y verificado; sesión remota terminada")
                                updateUi { pending = null; diagnostic = details; message = "El archivo quedó guardado y verificado, pero el PC terminó la operación. El lote se ha detenido; no necesitas descargarlo otra vez." }
                            }
                            remoteState?.state == "completed" && remoteState.sha256 == hash -> { finish("Recibido y verificado"); return true }
                            else -> {
                                finish("Guardado y verificado; confirmación pendiente")
                                updateUi { diagnostic = details; pending = selected.id to hash; message = "Archivo íntegro guardado; confirmación remota pendiente. El lote se ha detenido." }
                            }
                        }
                        return false
                    }
                    finish("Recibido y verificado")
                    return true
                } catch (e: Exception) { if (!published && sameGeneration()) runCatching { c.cancel(selected.id) }; throw e }
                finally { temporary?.delete(); transferId = null }
            }
            fun receive(selected: Transfer) {
                if (offer != selected || busy || pending != null) return
                offer = null
                sendQueue = null
                post("receive", transferOperation = true) { receiveFile(connected(), selected, true) }
            }
            fun receiveBatch(selected: DownloadOfferBatch) {
                if (batchOffer != selected || busy || pending != null) { dialog = null; return }
                dialog = null
                batchOffer = null
                transfer = null
                post("receive", transferOperation = true) {
                    val c = connected()
                    var completed = 0
                    var current = 0
                    try {
                        receiveStorage.validateDestination()
                        check(!cancelled)
                        c.acceptDownloadBatch(selected.id)
                        for ((index, file) in selected.files.withIndex()) {
                            check(!cancelled)
                            current = index + 1
                            val queue = SendQueueProgress(current, selected.files.size, completed, true)
                            updateUi { sendQueue = queue }
                            updateUi { transfer = TransferPresentation(file.name, file.size, "download", phase = "Preparando recepción") }
                            val ready = c.startDownloadBatchFile(selected.id, index)
                            transferId = ready.id
                            requireReadyDownload(ready, file)
                            if (!receiveFile(c, ready, false)) break
                            completed++
                        }
                        if (completed == selected.files.size) {
                            try { require(c.downloadBatchStatus(selected.id).state == "completed") } catch (error: Exception) {
                                updateUi { diagnostic = TransferDiagnostics.describe("confirm", error); message = "Todos los archivos quedaron guardados y verificados; no se pudo confirmar el estado final del lote con el PC." }
                            }
                        }
                    } finally {
                        if (completed < selected.files.size && sameGeneration()) runCatching { c.cancelDownloadBatch(selected.id) }
                        val queue = SendQueueProgress(current, selected.files.size, completed, false)
                        updateUi { sendQueue = queue }
                    }
                }
            }
            EsencialTheme {
                val visibleOffer = offer
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
                    message = message, transfer = transfer, sendQueue = sendQueue, offer = visibleOffer, batchOffer = batchOffer, activity = activity,
                    hasPending = pending != null, hasDiagnostic = diagnostic != null, canOpenReceived = lastReceived != null,
                    onSettings = { dialog = "settings" },
                    onScan = {
                        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) message = "No hay cámara disponible. Usa la conexión manual en Ajustes."
                        else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchScanner()
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    onFolder = { dialog = "folder" }, onSend = { picker.launch(arrayOf("*/*")) },
                    onReceive = { transfer = null; sendQueue = null; post("offers") { val inbox = connected().inbox(); updateUi { offer = inbox.transfer; batchOffer = inbox.batch; if (offer == null && batchOffer == null) message = "La bandeja se actualiza automáticamente. Elige los archivos en el PC; su solicitud aparecerá aquí." } } },
                    onAccept = { visibleOffer?.let { receive(it) } }, onDetail = { dialog = "batch_detail" }, onDecline = {
                        val batch = batchOffer
                        if (batch != null) {
                            batchOffer = null
                            post("reject") { connected().cancelDownloadBatch(batch.id) }
                        }
                        val selected = offer
                        if (selected != null) {
                            offer = null
                            post("reject") {
                                try { connected().cancel(selected.id); updateUi { message = "Solicitud rechazada." } }
                                catch (error: Exception) {
                                    val details = TransferDiagnostics.describe("reject", error)
                                    updateUi { diagnostic = details; message = "Solicitud descartada en este teléfono. No se pudo confirmar el rechazo al PC." }
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
                                if (error.status == 404 && error.code == "not_found") null else throw error
                            }
                            if (transfer?.direction == "download" && (state == null || state.state in setOf("cancelled", "failed"))) {
                                finish("Guardado y verificado; sesión remota terminada")
                                updateUi { pending = null; message = "El archivo guardado y verificado se conserva. El PC terminó la operación; no necesitas descargarlo otra vez." }
                                return@post
                            }
                            if (transfer?.direction == "upload" && (state == null || state.state in setOf("cancelled", "failed"))) {
                                pendingUploadBatchId?.let { runCatching { c.cancelBatch(it) } }
                                pendingUploadBatchId = null
                                finish("Envío sin confirmación remota")
                                updateUi { pending = null; message = "El PC terminó la operación. Comprueba el destino antes de volver a enviar el archivo." }
                                return@post
                            }
                            requireNotNull(state)
                            if (state.state == "completed") require(state.sha256 == hash)
                            else require(c.complete(id, hash).let { it.state == "completed" && it.sha256 == hash })
                            pendingUploadBatchId?.let { runCatching { c.cancelBatch(it) } }
                            pendingUploadBatchId = null
                            finish(if (transfer?.direction == "download") "Recibido y verificado" else "Enviado y verificado")
                            updateUi { pending = null; message = "Confirmación remota completada." }
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
                    "batch_detail" -> EssentialDialog("Detalle del lote", { dialog = null }) {
                        batchOffer?.let { batch ->
                            Text("${batch.files.size} archivos · ${formatBytes(batch.files.sumOf { it.size })}")
                            batch.files.forEach { FileRow(it.name, formatBytes(it.size)) }
                            Text("Destino: $destination")
                            Button(onClick = { receiveBatch(batch) }, enabled = !busy && pending == null) { Text("Aceptar lote") }
                        }
                    }
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
                                    pair = updated
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
                            post("choose_folder") { receiveStorage.usePrivateInbox(); updateUi { destination = receiveStorage.destinationLabel(); message = "Se usará la bandeja privada de la aplicación." } }
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
                    "forget" -> AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { dialog = null }, title = { Text("¿Olvidar este PC?") }, text = { Text("Se cerrará la sesión con el PC. Para volver a conectar tendrás que escanear otro QR.") }, confirmButton = {
                        TextButton(onClick = { disconnectSession(); pair = null; pending = null; offer = null; batchOffer = null; transfer = null; sendQueue = null; dialog = null; message = "Dispositivo olvidado en este teléfono." }) { Text("Olvidar") }
                    }, dismissButton = { TextButton(onClick = { dialog = "settings" }) { Text("Volver") } })
                }
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            disconnectSession()
            worker.shutdownNow()
            session.poller.shutdownNow()
        }
        super.onDestroy()
    }
}
