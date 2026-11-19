package local.filetransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** One user-started operation, including its entire sequential batch. No restart after process death. */
internal object ActiveTransfer {
    private val main = Handler(Looper.getMainLooper())
    private val operation = TransferOperation()
    private var lastNotification = 0L
    private var notificationStage: String? = null
    private var foregroundVisible = false
    var notification: Notification? = null
        private set
    val running get() = operation.running
    val currentId get() = operation.currentId

    // Android 15+ dataSync has a six hour budget. Older versions use the same bounded window.
    const val MAX_DURATION_MS = 6 * 60 * 60 * 1000L

    fun start(context: Context, ready: () -> Unit, cancel: (Boolean) -> Unit, failed: (Exception) -> Unit, sourceUris: List<android.net.Uri> = emptyList()): Long {
        val id = operation.begin(ready, cancel, failed)
        foregroundVisible = false
        context.getSystemService(NotificationManager::class.java).cancel(TransferForegroundService.FINISHED_NOTIFICATION)
        notification = TransferForegroundService.notification(context, "Preparando transferencia", null)
        lastNotification = 0L
        notificationStage = null
        try {
            ContextCompat.startForegroundService(context, sourceIntent(context, id, sourceUris))
        } catch (error: Exception) {
            operation.fail(id, error)
            notification = null
        }
        return id
    }

    /** Forward temporary Activity grants to the running service until its private copies are closed. */
    private fun sourceIntent(context: Context, id: Long, sources: List<android.net.Uri>) =
        Intent(context, TransferForegroundService::class.java).putExtra("operation_id", id).apply {
            if (sources.isNotEmpty()) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (sources.any(android.provider.DocumentsContract::isTreeUri)) addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                clipData = ClipData.newRawUri("Archivos en cola", sources.first()).apply {
                    sources.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
        }

    fun retainSources(context: Context, sources: List<android.net.Uri>) {
        val id = currentId ?: return
        ContextCompat.startForegroundService(context, sourceIntent(context, id, sources))
    }

    fun ready(id: Long?) {
        if (id != currentId) return
        foregroundVisible = true
        operation.ready(id)
    }
    fun stopped(id: Long?) { if (id == currentId) foregroundVisible = false }
    fun cancel(id: Long?, timeout: Boolean = false) = operation.cancel(id, timeout)
    fun failed(id: Long?, error: Exception) {
        if (id != currentId) return
        foregroundVisible = false
        operation.fail(id, error)
        notification = null
    }
    fun finish(context: Context, id: Long, result: String? = null) {
        val finish = {
            if (operation.finish(id)) {
                foregroundVisible = false
                notification = null
                context.getSystemService(NotificationManager::class.java).cancel(TransferForegroundService.NOTIFICATION)
                context.stopService(Intent(context, TransferForegroundService::class.java))
                if (result != null) {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    if (manager.areNotificationsEnabled()) manager.notify(TransferForegroundService.FINISHED_NOTIFICATION,
                        TransferForegroundService.notification(context, result, null, finished = true))
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) finish() else main.post { finish() }
    }
    fun progress(context: Context, transfer: TransferPresentation?, queue: SendQueueProgress?) {
        if (!operation.started || !foregroundVisible) return
        val now = android.os.SystemClock.elapsedRealtime()
        val stage = "${queue?.current}:${transfer?.name}:${transfer?.phase}"
        if (stage == notificationStage && now - lastNotification < 1_000L) return
        notificationStage = stage
        lastNotification = now
        val prefix = queue?.takeIf { it.total > 1 }?.let {
            if (it.current > 0) "Archivo ${it.current} de ${it.total} · " else "${it.total} archivos · "
        }.orEmpty()
        val total = queue?.totalBytes ?: transfer?.size
        val bytes = queue?.takeIf { it.totalBytes != null }?.bytesWith(transfer?.bytes ?: 0) ?: transfer?.bytes
        val counts = if (bytes != null && total != null) "${formatBytes(bytes)} de ${formatBytes(total)}" else null
        val text = prefix + (transfer?.phase ?: "Preparando transferencia") + counts?.let { " · $it" }.orEmpty()
        val progress = total?.takeIf { it > 0L }?.let { ((bytes ?: 0).toDouble() / it * 1_000).toInt().coerceIn(0, 1_000) }
        notification = TransferForegroundService.notification(context, text, progress)
        context.getSystemService(NotificationManager::class.java).notify(TransferForegroundService.NOTIFICATION, notification)
    }
}

/** Keeps only active, explicitly started transfers alive when the activity is hidden. */
class TransferForegroundService : Service() {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val deadline = Runnable { cancelAndStop(true) }
    private var startedId: Long? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            val id = intent.getLongExtra("operation_id", -1L)
            if (id == ActiveTransfer.currentId) cancelAndStop(false, id)
            else if (!ActiveTransfer.running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val requestedId = intent?.getLongExtra("operation_id", -1L)
        if (!ActiveTransfer.running) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (requestedId != ActiveTransfer.currentId) return START_NOT_STICKY
        if (startedId == ActiveTransfer.currentId) return START_NOT_STICKY
        releaseResources()
        stopping = false
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transferencias activas", NotificationManager.IMPORTANCE_LOW))
            val notice = requireNotNull(ActiveTransfer.notification)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notice)
            cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileTransfer:active").apply {
                setReferenceCounted(false)
                acquire(ActiveTransfer.MAX_DURATION_MS)
            }
            // Streaming over Wi-Fi must remain connected with the display off (API 28/J4+).
            @Suppress("DEPRECATION")
            val wifiMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(wifiMode, "FileTransfer:active").apply {
                setReferenceCounted(false)
                acquire()
            }
            main.postDelayed(deadline, ActiveTransfer.MAX_DURATION_MS)
            startedId = requestedId
            ActiveTransfer.ready(startedId)
        } catch (error: Exception) {
            ActiveTransfer.failed(requestedId, error)
            releaseResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15+ may exhaust the shared dataSync budget before our local deadline.
    override fun onTimeout(startId: Int, fgsType: Int) { cancelAndStop(true) }

    private fun cancelAndStop(timeout: Boolean, id: Long? = startedId) {
        if (id == null || id != ActiveTransfer.currentId) return
        stopping = true
        ActiveTransfer.stopped(id)
        ActiveTransfer.cancel(id, timeout)
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun releaseResources() {
        main.removeCallbacks(deadline)
        wifi?.let { if (it.isHeld) it.release() }
        wifi = null
        cpu?.let { if (it.isHeld) it.release() }
        cpu = null
    }
    override fun onDestroy() {
        // A system/task shutdown must interrupt streams too; a normal finish removes the operation first.
        ActiveTransfer.stopped(startedId)
        if (!stopping && startedId != null && startedId == ActiveTransfer.currentId) ActiveTransfer.cancel(startedId, false)
        releaseResources()
        super.onDestroy()
    }

    companion object {
        internal const val NOTIFICATION = 101
        internal const val FINISHED_NOTIFICATION = 102
        private const val CHANNEL = "active_transfers"
        private const val CANCEL = "local.filetransfer.CANCEL_TRANSFER"
        internal fun notification(context: Context, text: String, progress: Int?, finished: Boolean = false): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cancel = PendingIntent.getService(context, 1, Intent(context, TransferForegroundService::class.java).setAction(CANCEL)
                .setData(android.net.Uri.parse("filetransfer://operation/${ActiveTransfer.currentId}"))
                .putExtra("operation_id", ActiveTransfer.currentId ?: -1L), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_transfer_status)
                .setContentTitle("File Transfer")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(!finished)
                .setAutoCancel(finished)
                .setOnlyAlertOnce(true)
                .setCategory(if (finished) Notification.CATEGORY_STATUS else Notification.CATEGORY_PROGRESS)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
            if (!finished) builder.setProgress(1_000, progress ?: 0, progress == null)
                .addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
            return builder.build()
        }
    }
}
