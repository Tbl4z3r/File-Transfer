package local.filetransfer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private data class RetryConnection(val peer: RetryPeer, val refreshInbox: Boolean)

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
        val diagnostic = mutableStateOf<String?>(null)
        val offer = mutableStateOf<Transfer?>(null)
        val batchOffer = mutableStateOf<DownloadOfferBatch?>(null)
        val transfer = mutableStateOf<TransferPresentation?>(null)
        val uploadRetry = mutableStateOf<UploadRetry<Uri>?>(null)
        val inboxRetry = mutableStateOf<RetryPeer?>(null)
        var pendingUploadSource: Uri? = null
        val reconnectFor = mutableStateOf<RetryConnection?>(null)
        val sendQueue = mutableStateOf<SendQueueProgress?>(null)
        val activity = mutableStateOf(listOf<TransferPresentation>())

        fun record(entry: TransferPresentation) {
            activity.value = (activity.value.filterNot { entry.id != null && it.id == entry.id } + entry).takeLast(32)
        }

        fun historyTrees() = activity.value.mapNotNull { it.receipt?.location?.tree }.toSet()
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

    private val preferences by lazy { getSharedPreferences("local_preferences", MODE_PRIVATE) }
    private var diagnostic: String?
        get() = session.diagnostic.value
        set(value) {
            session.diagnostic.value = value
            if (value != null) preferences.edit().putString("last_error", value).apply()
        }

    private fun sameGeneration() = session.operationGeneration.get()?.let { it == session.generation } ?: true

    private fun updateUi(action: () -> Unit) {
        val generation = session.operationGeneration.get() ?: session.generation
        runOnUiThread { if (generation == session.generation) action() }
    }

    private fun bestEffortDisconnect(p: Pairing) {
        Thread { runCatching { ProtocolClient(p.host, p.port, p.pin, p.deviceId, p.credential, shortTimeout = true).disconnect() } }.start()
    }

    private fun disconnectSession(preserveRetry: Boolean = false, notifyPeer: Boolean = true) {
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
            val result = if (current.receipt != null)
                "Guardado y verificado; sesión remota terminada" else "Interrumpida; sesión terminada"
            val entry = current.copy(result = result)
            session.transfer.value = entry
            session.record(entry)
        }
        session.sendQueue.value = session.sendQueue.value?.copy(running = false)
        session.pendingUploadSource = null
        if (!preserveRetry) {
            session.uploadRetry.value = null
            session.reconnectFor.value = null
            session.inboxRetry.value = null
        }
        if (p != null && notifyPeer) bestEffortDisconnect(p)
    }

    @Deprecated("Retains in-memory session only")
    override fun onRetainCustomNonConfigurationInstance(): Any = session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = (lastCustomNonConfigurationInstance as? Session) ?: Session()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val receiveStorage = ReceiveStorage(applicationContext)
        if (session.destination.value.isEmpty()) {
            receiveStorage.pruneHistoryGrants(emptySet())
            session.destination.value = receiveStorage.destinationLabel()
        }
        File(filesDir, "partial").apply { mkdirs(); if (lastCustomNonConfigurationInstance == null) listFiles()?.forEach { it.delete() } }
        PairStore(this) // Discard credentials left by older versions; never restore pairing.
        val savedDeviceName = preferences.getString("device_name", null)?.takeIf(Validation::deviceName) ?: "Android personal"
        if (session.diagnostic.value == null) session.diagnostic.value = preferences.getString("last_error", null)
        setContent {
            var pair by remember { session.pair }
            var descriptor by remember { mutableStateOf("") }
            var deviceName by remember { mutableStateOf(savedDeviceName) }
            var nameDraft by remember { mutableStateOf("") }
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            var manualOpen by rememberSaveable { mutableStateOf(false) }
            var message by remember { session.message }
            var busy by remember { session.busy }
            var address by remember { mutableStateOf(pair?.host ?: "") }
            var portText by remember { mutableStateOf(pair?.port?.toString() ?: "") }
            var pending by remember { session.pending }
            var destination by remember { session.destination }
            var offer by remember { session.offer }
            var transfer by remember { session.transfer }
            var uploadRetry by remember { session.uploadRetry }
            var inboxRetry by remember { session.inboxRetry }
            var reconnectFor by remember { session.reconnectFor }
            var sendQueue by remember { session.sendQueue }
            val activity by remember { session.activity }
            var batchOffer by remember { session.batchOffer }
            var dialog by remember { mutableStateOf<String?>(null) }
            var folderLocation by remember { mutableStateOf<ReceiveLocation?>(null) }
            var folderPath by remember { mutableStateOf(listOf<ReceiveFolderEntry>()) }
            var folderEntries by remember { mutableStateOf<List<ReceiveFolderEntry>?>(null) }
            var folderError by remember { mutableStateOf(false) }
            var folderOrigin by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(dialog, folderLocation, folderPath) {
                if (dialog != "browse_folder") return@LaunchedEffect
                val location = folderLocation ?: return@LaunchedEffect
                val folder = folderPath.lastOrNull()?.uri
                folderEntries = null
                folderError = false
                val result = withContext(Dispatchers.IO) { runCatching { receiveStorage.listFolder(location, folder) } }
                folderEntries = result.getOrNull()
                folderError = result.isFailure
            }

            fun openFile(uri: Uri, tree: Uri? = null) {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val document = if (tree != null && !DocumentsContract.isTreeUri(uri))
                                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(uri)) else uri
                            val exists = contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { it.moveToFirst() } == true
                            check(exists)
                            val mime = contentResolver.getType(document) ?: "application/octet-stream"
                            Intent(Intent.ACTION_VIEW).setDataAndType(document, mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .apply { clipData = ClipData.newRawUri("Archivo recibido", document) }
                        }
                    }
                    if (runCatching { startActivity(result.getOrThrow()) }.isFailure) {
                        Toast.makeText(this@MainActivity, "No se pudo abrir el archivo. Comprueba que siga disponible y que haya una aplicación compatible.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            fun openLocation(location: ReceiveLocation) {
                if (location.tree != null && runCatching {
                        val directory = receiveStorage.directory(location.tree)
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(directory, DocumentsContract.Document.MIME_TYPE_DIR)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply { clipData = ClipData.newRawUri("Carpeta de recepción", directory) })
                    }.isSuccess) return
                folderPath = emptyList()
                folderEntries = null
                folderError = false
                folderLocation = location
                folderOrigin = dialog
                dialog = "browse_folder"
            }
            LaunchedEffect(pair) { pair?.let { address = it.host; portText = it.port.toString() } }

            fun finish(result: String) {
                updateUi {
                    transfer?.let {
                        val entry = it.copy(result = result)
                        transfer = entry
                        session.record(entry)
                    }
                }
            }
            fun post(stage: String = "operation", transferOperation: Boolean = false, block: () -> Unit) {
                val operationGeneration = session.generation
                cancelled = false
                busy = true
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
            fun report(n: Long, total: Long, started: Long) {
                val now = System.nanoTime()
                if (n == total || now - progressClock.get() >= 100_000_000L) {
                    progressClock.set(now)
                    updateUi { transfer = transfer?.copy(bytes = n, bytesPerSecond = transferRate(n, started, now)) }
                }
            }
            fun phase(label: String) { updateUi { transfer = transfer?.copy(phase = label) } }
            fun cancel() {
                cancelled = true
                val id = transferId
                client?.interrupt()
                if (id != null && operationStage in setOf("receive", "download", "verify", "publish")) {
                    val p = pair
                    if (p != null) Thread { runCatching { ProtocolClient(p.host, p.port, p.pin, p.deviceId, p.credential).cancel(id) } }.start()
                }
                message = "Cancelando…"
            }
            fun refreshInbox(c: ProtocolClient) {
                val inbox = c.inbox()
                updateUi {
                    offer = inbox.transfer
                    batchOffer = inbox.batch
                    if (offer == null && batchOffer == null) {
                        message = "No hay una oferta pendiente. Elige los archivos en el PC y vuelve a consultar la bandeja."
                    } else {
                        inboxRetry = null
                    }
                }
            }
            fun queryInbox() {
                val peer = pair?.retryPeer() ?: return
                if (busy || pending != null) return
                inboxRetry = peer
                post("offers") { refreshInbox(connected()) }
            }
            fun startPair(raw: String) {
                if (busy || pair != null) return
                val name = deviceName
                val reconnect = reconnectFor
                descriptor = ""
                manualOpen = false
                settingsOpen = false
                dialog = null
                post("pair") {
                    val p = try {
                        withValidatedDescriptor(raw) { d ->
                            if (reconnect != null && d.retryPeer() != reconnect.peer) throw RetryPeerMismatch()
                            ProtocolClient(d.host, d.port, d.pin, java.util.UUID.randomUUID().toString()).also { client = it }.pair(d, name)
                        }
                    } catch (error: RetryPeerMismatch) {
                        val details = TransferDiagnostics.describe("pair", error)
                        updateUi { diagnostic = details; message = "Usa un QR o código del mismo PC para reintentar." }
                        return@post
                    } catch (error: InvalidDescriptor) {
                        val details = TransferDiagnostics.describe("pair", error)
                        updateUi { diagnostic = details; message = "Los datos de conexión no son válidos. Abre un nuevo QR en el PC e inténtalo de nuevo." }
                        return@post
                    } catch (error: ProtocolException) {
                        val explanation = when (error.code) {
                            "pairing_denied" -> "El QR o código ha caducado o ya se usó. Abre otro en el PC e inténtalo de nuevo."
                            "already_paired" -> "Este teléfono ya está emparejado. Revoca el vínculo anterior en el PC y abre otro QR."
                            else -> throw error
                        }
                        val details = TransferDiagnostics.describe("pair", error)
                        updateUi { diagnostic = details; message = explanation }
                        return@post
                    }
                    val pairingGeneration = session.operationGeneration.get() ?: session.generation
                    runOnUiThread {
                        if (pairingGeneration != session.generation || cancelled || isFinishing) bestEffortDisconnect(p)
                        else { reconnectFor = null; pair = p; address = p.host; portText = p.port.toString(); message = "Dispositivo emparejado." }
                    }
                    if (reconnect?.refreshInbox == true && !cancelled) {
                        operationStage = "offers"
                        refreshInbox(ProtocolClient(p.host, p.port, p.pin, p.deviceId, p.credential).also { client = it })
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
                    val label = receiveStorage.selectTree(uri, result.data!!.flags, session.historyTrees())
                    updateUi { destination = label; message = "Carpeta de recepción actualizada." }
                }
            }
            fun chooseFolder() {
                dialog = null
                folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            }
            fun startUpload(uris: List<Uri>, uncertain: Uri? = null, requiredPeer: RetryPeer? = null) {
                val peer = pair?.retryPeer() ?: return
                if (requiredPeer != null && requiredPeer != peer) return
                if (uris.isNotEmpty() && !busy && pending == null && offer == null && batchOffer == null) {
                    uploadRetry = UploadRetry(peer, uris.distinct(), uncertain)
                    inboxRetry = null
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
                            catch (_: IllegalArgumentException) { throw SendSelectionException("El tamaño de este archivo no es válido o supera ${formatBytes(Validation.MAX_SIZE)}.") }
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
                                        session.record(entry)
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
                                    val started = System.nanoTime()
                                    val (remote, hash) = inputStream.use { input ->
                                        c.uploadContent(t.id, input, size) { n ->
                                            check(!cancelled) { "Cancelada" }
                                            val now = System.nanoTime()
                                            if (n == size || now - progressClock.get() >= 100_000_000L) {
                                                progressClock.set(now); item = item.copy(bytes = n, bytesPerSecond = transferRate(n, started, now)); show()
                                            }
                                        }
                                    }
                                    itemPhase("Verificando integridad")
                                    require(remote.id == t.id && remote.direction == "upload" && remote.name == name && remote.size == size && remote.state == "awaiting_verification" && remote.sha256 == hash) { "La integridad no coincide" }
                                    itemPhase("Confirmando con el PC")
                                    val generation = session.operationGeneration.get()
                                    val markUncertain = FutureTask {
                                        if (generation == session.generation && !cancelled) {
                                            uploadRetry = checkNotNull(uploadRetry).confirming(uri)
                                            true
                                        } else false
                                    }
                                    runOnUiThread(markUncertain)
                                    try {
                                        check(markUncertain.get(5, TimeUnit.SECONDS) && !cancelled) { "Cancelada" }
                                    } catch (error: Exception) {
                                        markUncertain.cancel(false)
                                        throw error
                                    }
                                    verifiedUpload = true
                                    try {
                                        val completed = c.complete(t.id, hash)
                                        require(completed.state == "completed" && completed.sha256 == hash)
                                    } catch (error: Exception) {
                                        val remoteState = if (sameGeneration()) runCatching { c.status(t.id) }.getOrNull() else null
                                        val details = TransferDiagnostics.describe("confirm", error)
                                        when {
                                            remoteState?.state == "completed" && remoteState.sha256 == hash -> {
                                                itemFinish("Enviado y verificado")
                                                updateUi { uploadRetry = uploadRetry?.confirmed(uri) }
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
                                                updateUi { session.pendingUploadSource = uri; diagnostic = details; pending = t.id to hash; message = "Datos enviados; falta confirmar que el PC guardó el archivo. El lote se ha detenido." }
                                            }
                                        }
                                        return@run false
                                    }
                                    itemFinish("Enviado y verificado")
                                    updateUi { uploadRetry = uploadRetry?.confirmed(uri) }
                                    true
                                } catch (e: Exception) { if (!verifiedUpload && sameGeneration()) runCatching { c.cancel(t.id) }; throw e }
                                finally { transferId = null }
                            }
                        } finally {
                            val stopped = queueState.copy(running = false)
                            updateUi {
                                sendQueue = stopped
                                if (uploadRetry?.remaining?.isEmpty() == true) uploadRetry = null
                            }
                            if (queueState.completed < queueState.total && sameGeneration()) authorization.stop(confirmationPending)
                        }
                    }
                }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { startUpload(it) }
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
                            if (!disposed.get() && session.generation == generation && pair == p && !busy) {
                                disconnectSession(preserveRetry = true, notifyPeer = false)
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
                    val started = System.nanoTime()
                    val hash = temp.outputStream().use { c.downloadContent(selected.id, selected.size, it) { n -> check(!cancelled); report(n, selected.size, started) } }
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
                    val generation = session.operationGeneration.get()
                    runOnUiThread {
                        val connected = generation == session.generation
                        val entry = TransferPresentation(received.name, selected.size, "download", bytes = selected.size,
                            phase = "Confirmando con el PC", id = selected.id, receipt = received,
                            result = if (connected) null else "Guardado y verificado; sesión remota terminada")
                        if (connected) transfer = entry
                        session.record(entry)
                    }
                    operationStage = "confirm"
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
                inboxRetry = pair?.retryPeer()
                post("receive", transferOperation = true) {
                    if (receiveFile(connected(), selected, true)) updateUi { inboxRetry = null }
                }
            }
            fun receiveBatch(selected: DownloadOfferBatch) {
                if (batchOffer != selected || busy || pending != null) { dialog = null; return }
                dialog = null
                batchOffer = null
                transfer = null
                sendQueue = null
                inboxRetry = pair?.retryPeer()
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
                            updateUi { inboxRetry = null }
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
            fun scanForRetry(peer: RetryPeer, refreshInbox: Boolean = false) {
                reconnectFor = RetryConnection(peer, refreshInbox)
                message = if (refreshInbox) "Escanea un QR vigente del mismo PC para consultar la bandeja."
                    else "Escanea un QR vigente del mismo PC y vuelve a pulsar Reintentar."
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                    message = "Introduce el descriptor del mismo PC en Ajustes."
                } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchScanner()
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
            fun closeManual() {
                descriptor = ""
                manualOpen = false
            }
            BackHandler(enabled = settingsOpen && dialog == null) {
                if (manualOpen) closeManual() else settingsOpen = false
            }
            EsencialTheme {
                val visibleOffer = offer
                if (settingsOpen && manualOpen && pair == null) {
                    ManualConnectionScreen(descriptor, busy, ::closeManual, { descriptor = it }) { startPair(descriptor.trim()) }
                } else if (settingsOpen) {
                    SettingsScreen(
                        destination = destination, paired = pair != null, busy = busy, address = address, port = portText,
                        lastError = diagnostic, onBack = { settingsOpen = false },
                        onFolder = { dialog = "folder" }, onManual = { manualOpen = true },
                        onAddress = { address = it }, onPort = { portText = it },
                        onSaveAddress = {
                            val port = portText.toIntOrNull()
                            val current = pair
                            if (current != null && Validation.ipv4(address) && port != null && port in 1024..65535) {
                                pair = current.copy(host = address, port = port)
                                message = "Dirección actualizada."
                            } else message = "IP o puerto no válido."
                            settingsOpen = false
                        },
                        onForget = { dialog = "forget" },
                        onCopyError = {
                            diagnostic?.let {
                                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Diagnóstico File Transfer", it))
                                Toast.makeText(this@MainActivity, "Diagnóstico copiado", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                } else EsencialScreen(
                    pairedName = pair?.serverName?.takeIf { it.isNotBlank() }, pairedHost = pair?.let { "${it.host}:${it.port}" },
                    deviceName = deviceName, destination = destination, busy = busy,
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
                    hasPending = pending != null,
                    canRetrySend = uploadRetry?.let { pair == null || pair?.retryPeer() == it.peer } == true,
                    canRetryInbox = inboxRetry?.let { pair == null || pair?.retryPeer() == it } == true,
                    onSettings = {
                        pair?.let { address = it.host; portText = it.port.toString() }
                        settingsOpen = true
                    },
                    onEditName = { nameDraft = deviceName; dialog = "device_name" },
                    onScan = {
                        reconnectFor = null
                        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) message = "No hay cámara disponible. Usa la conexión manual en Ajustes."
                        else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchScanner()
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    onFolder = { dialog = "folder" }, onSend = { picker.launch(arrayOf("*/*")) },
                    onReceive = ::queryInbox,
                    onRetrySend = {
                        val retry = uploadRetry
                        if (retry != null) {
                            if (pair == null) scanForRetry(retry.peer)
                            else if (pair?.retryPeer() == retry.peer) {
                                if (retry.uncertain == null) startUpload(retry.remaining, requiredPeer = retry.peer)
                                else dialog = "uncertain_upload"
                            }
                        }
                    },
                    onRetryInbox = {
                        inboxRetry?.let { peer ->
                            if (pair == null) scanForRetry(peer, refreshInbox = true)
                            else if (pair?.retryPeer() == peer) queryInbox()
                        }
                    },
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
                                updateUi { session.pendingUploadSource = null; pending = null; message = "El PC terminó la operación. Comprueba el destino antes de volver a enviar el archivo." }
                                return@post
                            }
                            requireNotNull(state)
                            if (state.state == "completed") require(state.sha256 == hash)
                            else require(c.complete(id, hash).let { it.state == "completed" && it.sha256 == hash })
                            pendingUploadBatchId?.let { runCatching { c.cancelBatch(it) } }
                            pendingUploadBatchId = null
                            finish(if (transfer?.direction == "download") "Recibido y verificado" else "Enviado y verificado")
                            updateUi {
                                session.pendingUploadSource?.let { uploadRetry = uploadRetry?.confirmed(it) }
                                session.pendingUploadSource = null
                                if (uploadRetry?.remaining?.isEmpty() == true) uploadRetry = null
                                if (transfer?.direction == "download" && sendQueue == null) inboxRetry = null
                                pending = null
                                message = "Confirmación remota completada."
                            }
                        }
                    },
                    onActivity = { dialog = "activity" },
                    onOpenReceived = { openFile(it.uri, it.location.tree) }, onOpenLocation = ::openLocation,
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
                    "device_name" -> EssentialDialog("Nombre del teléfono", { dialog = null }) {
                        val name = nameDraft.trim()
                        val valid = Validation.deviceName(name)
                        OutlinedTextField(nameDraft, { nameDraft = it }, label = { Text("Nombre del teléfono") },
                            isError = !valid, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(
                            if (valid) "El PC verá este nombre la próxima vez que conectes."
                            else "Escribe un nombre de 1 a 64 caracteres, sin caracteres de control.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(enabled = valid && !busy, onClick = {
                            deviceName = name
                            preferences.edit().putString("device_name", name).apply()
                            dialog = null
                        }) { Text("Guardar nombre") }
                    }
                    "folder" -> EssentialDialog("Carpeta de recepción", { dialog = null }) {
                        Text(destination)
                        TextButton(onClick = { openLocation(ReceiveLocation(receiveStorage.selectedTree(), destination)) }) { Text("Abrir carpeta") }
                        Button(onClick = { chooseFolder() }, enabled = !busy) { Text("Elegir carpeta") }
                        if (receiveStorage.selectedTree() != null) TextButton(enabled = !busy, onClick = {
                            dialog = null
                            post("choose_folder") { receiveStorage.usePrivateInbox(session.historyTrees()); updateUi { destination = receiveStorage.destinationLabel(); message = "Se usará la bandeja privada de la aplicación." } }
                        }) { Text("Usar bandeja privada") }
                        Text("Si Android no permite elegir la raíz de Descargas, crea una subcarpeta como File Transfer.", style = MaterialTheme.typography.bodySmall)
                    }
                    "activity" -> EssentialDialog("Actividad de esta sesión", { dialog = null }) {
                        if (activity.isEmpty()) Text("Todavía no hay transferencias.")
                        activity.asReversed().forEach { entry ->
                            ActivityRow(entry, { openFile(it.uri, it.location.tree) }, ::openLocation)
                        }
                    }
                    "browse_folder" -> folderLocation?.let { location ->
                        ReceiveFolderDialog(
                            title = folderPath.lastOrNull()?.name ?: location.label,
                            entries = folderEntries,
                            failed = folderError,
                            onParent = if (folderPath.isEmpty()) null else ({ folderPath = folderPath.dropLast(1) }),
                            onEntry = { entry ->
                                if (entry.isDirectory) folderPath = folderPath + entry else openFile(entry.uri, location.tree)
                            },
                            onDismiss = { folderLocation = null; dialog = folderOrigin },
                        )
                    }
                    "uncertain_upload" -> uploadRetry?.let { retry ->
                        fun continueUpload(includeUncertain: Boolean) {
                            dialog = null
                            val selected = retry.nextSelection(includeUncertain)
                            if (selected.isEmpty()) uploadRetry = null else startUpload(selected, retry.uncertain.takeIf { includeUncertain }, retry.peer)
                        }
                        AlertDialog(
                            containerColor = MaterialTheme.colorScheme.surface,
                            onDismissRequest = { dialog = null },
                            title = { Text("¿Incluir el archivo sin confirmar?") },
                            text = { Text("El PC pudo guardar el archivo. Comprueba la carpeta de destino antes de reenviarlo. Si ya está allí, omítelo para evitar otra copia.") },
                            confirmButton = { TextButton(onClick = { continueUpload(true) }) { Text("Incluir archivo") } },
                            dismissButton = { TextButton(onClick = { continueUpload(false) }) { Text("Omitir archivo") } },
                        )
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
                            session.pendingUploadSource = null
                            pending = null
                            finish(if (transfer?.direction == "download") "Guardado y verificado; sin confirmación remota" else "Envío sin confirmación remota")
                            dialog = null
                            message = "Confirmación cerrada en este teléfono."
                        }) { Text("Cerrar confirmación") }
                    }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Volver") } })
                    "forget" -> AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { dialog = null }, title = { Text("¿Olvidar este PC?") }, text = { Text("Se cerrará la sesión con el PC. Para volver a conectar, abre otro QR o código en el PC.") }, confirmButton = {
                        TextButton(onClick = { disconnectSession(); pair = null; pending = null; offer = null; batchOffer = null; transfer = null; sendQueue = null; dialog = null; settingsOpen = false; message = "Dispositivo olvidado en este teléfono." }) { Text("Olvidar") }
                    }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Volver") } })
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
