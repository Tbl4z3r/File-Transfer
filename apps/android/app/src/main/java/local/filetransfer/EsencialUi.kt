package local.filetransfer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Local presentation state. Completion is set only after the existing integrity checks. */
internal data class TransferPresentation(
    val name: String,
    val size: Long,
    val direction: String,
    val bytes: Long = 0,
    val phase: String = "Preparando…",
    val result: String? = null,
    val id: String? = null,
    val receipt: ReceivedDocument? = null,
    val bytesPerSecond: Long? = null,
)

internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "kB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    return if (unit == 0) "$bytes B" else String.format(Locale.forLanguageTag("es"), "%.1f %s", value, units[unit])
}

@Composable
internal fun EsencialTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFA4CBC7), onPrimary = Color(0xFF163833),
        primaryContainer = Color(0xFF29423F), onPrimaryContainer = Color(0xFFC7E9E3),
        secondary = Color(0xFFB6C8D0), onSecondary = Color(0xFF233941),
        secondaryContainer = Color(0xFF2B3B43), onSecondaryContainer = Color(0xFFDCE8EE),
        background = Color(0xFF192529), onBackground = Color(0xFFEDF4F2),
        surface = Color(0xFF222E33), onSurface = Color(0xFFEDF4F2),
        surfaceVariant = Color(0xFF303D46), onSurfaceVariant = Color(0xFFB7C6C9),
        outline = Color(0xFF819397), outlineVariant = Color(0xFF3A4A50),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

/** Small vector glyphs drawn locally; no font, network or additional icon dependency. */
@Composable
internal fun Glyph(name: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(22.dp)) {
        val scale = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, p(x, y), p(x2, y2), 1.8f * scale, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(tint, p(x, y), Size(w * scale, h * scale), CornerRadius(scale), style = Stroke(1.8f * scale))
        when (name) {
            "qr" -> {
                box(3f, 3f, 6f, 6f); box(15f, 3f, 6f, 6f); box(3f, 15f, 6f, 6f)
                box(5.4f, 5.4f, 1.2f, 1.2f); box(17.4f, 5.4f, 1.2f, 1.2f); box(5.4f, 17.4f, 1.2f, 1.2f)
                line(15f, 15f, 18f, 15f); line(18f, 15f, 18f, 18f); line(21f, 15f, 21f, 21f); line(15f, 18f, 15f, 21f); line(15f, 21f, 18f, 21f)
            }
            "settings" -> {
                val outline = Path().apply {
                    val tooth = listOf(-0.5 to 7.5f, -0.25 to 9.5f, 0.25 to 9.5f, 0.5 to 7.5f)
                    for (i in 0 until 8) {
                        tooth.forEachIndexed { point, (step, radius) ->
                            val angle = (i + step) * Math.PI / 4
                            val x = (12f + radius * kotlin.math.cos(angle)).toFloat() * scale
                            val y = (12f + radius * kotlin.math.sin(angle)).toFloat() * scale
                            if (i == 0 && point == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    close()
                }
                drawPath(outline, tint, style = Stroke(1.5f * scale))
                drawCircle(tint, 3.3f * scale, p(12f, 12f), style = Stroke(1.5f * scale))
            }
            "pc" -> { box(3f, 4f, 18f, 13f); line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f) }
            "open" -> { box(4f, 8f, 13f, 12f); line(12f, 4f, 20f, 4f); line(20f, 4f, 20f, 12f); line(20f, 4f, 11f, 13f) }
            "folder" -> { line(3f, 7f, 3f, 20f); line(3f, 20f, 21f, 20f); line(21f, 20f, 21f, 7f); line(21f, 7f, 11f, 7f); line(11f, 7f, 9f, 4f); line(9f, 4f, 3f, 4f); line(3f, 4f, 3f, 7f) }
            "back" -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 12f, 5f); line(5f, 12f, 12f, 19f) }
            "edit" -> { line(5f, 19f, 7f, 13f); line(7f, 13f, 16f, 4f); line(16f, 4f, 20f, 8f); line(20f, 8f, 11f, 17f); line(11f, 17f, 5f, 19f) }
            "chevron" -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            "send" -> { line(5f, 19f, 19f, 5f); line(9f, 5f, 19f, 5f); line(19f, 5f, 19f, 15f) }
            "receive" -> { line(19f, 5f, 5f, 19f); line(5f, 9f, 5f, 19f); line(5f, 19f, 15f, 19f) }
            "check" -> { drawCircle(tint, 9f * scale, p(12f, 12f), style = Stroke(1.8f * scale)); line(7f, 12f, 10f, 15f); line(10f, 15f, 17f, 8f) }
            "swap" -> { line(3f, 7f, 21f, 7f); line(17f, 3f, 21f, 7f); line(21f, 7f, 17f, 11f); line(21f, 17f, 3f, 17f); line(7f, 13f, 3f, 17f); line(3f, 17f, 7f, 21f) }
            else -> { box(5f, 3f, 14f, 18f); line(8f, 10f, 16f, 10f); line(8f, 14f, 16f, 14f) }
        }
    }
}


@Composable
private fun IconTile(name: String) {
    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Glyph(name) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun EsencialScreen(
    pairedName: String?, pairedHost: String?, deviceName: String, destination: String, busy: Boolean, busyLabel: String, cancellable: Boolean, message: String,
    transfer: TransferPresentation?, sendQueue: SendQueueProgress?, offer: Transfer?, batchOffer: DownloadOfferBatch?, activity: List<TransferPresentation>,
    outgoing: OutgoingState, queuedForOtherPeer: Boolean, onChooseOutgoingFolder: () -> Unit, onRemoveOutgoingFolder: (OutgoingFolder) -> Unit, onSendOutgoing: () -> Unit, onHoldOutgoing: () -> Unit, onRemoveOutgoing: (OutgoingFile) -> Unit,
    hasPending: Boolean, canRetrySend: Boolean, canRetryInbox: Boolean,
    onSettings: () -> Unit, onEditName: () -> Unit, onScan: () -> Unit, onFolder: () -> Unit,
    onSend: () -> Unit, onReceive: () -> Unit, onAccept: () -> Unit, onDetail: () -> Unit, onDecline: () -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, onDismissPending: () -> Unit,
    onRetrySend: () -> Unit, onRetryInbox: () -> Unit,
    onActivity: () -> Unit, onOpenReceived: (ReceivedDocument) -> Unit, onOpenLocation: (ReceiveLocation) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Glyph("swap")
                }
                Text("File Transfer", color = colors.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = "Ajustes" }) { Glyph("settings") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = onEditName, enabled = !busy, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.semantics { contentDescription = "Editar nombre del teléfono" }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Nombre del teléfono", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(deviceName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Glyph("edit")
                    }
                }
                Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (pairedHost == null) {
                            FileRow("Conecta tu PC", "Abre el QR de conexión en File Transfer en el PC.", "pc")
                            Button(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Glyph("qr", Modifier.size(18.dp), colors.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text("Escanear QR")
                            }
                            OutlinedButton(onClick = onSend, enabled = !busy && !hasPending, modifier = Modifier.fillMaxWidth()) { Text("Elegir archivos") }
                        } else {
                            FileRow(pairedName ?: "PC emparejado", "Vinculado · $pairedHost", "pc")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val enabled = !busy && offer == null && batchOffer == null && !hasPending
                                ActionTile("Enviar archivos", "send", true, enabled, Modifier.weight(1f), onSend)
                                ActionTile("Bandeja del PC", "receive", false, enabled, Modifier.weight(1f), onReceive)
                            }
                        }
                        OutlinedButton(onClick = onChooseOutgoingFolder, enabled = !busy && !hasPending && offer == null && batchOffer == null, modifier = Modifier.fillMaxWidth()) { Text("Elegir carpeta para enviar") }
                    }
                }
                if (outgoing.files.isNotEmpty() || outgoing.folders.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("Cola de envío · ${outgoing.files.size} archivos")
                            outgoing.folders.forEach { folder ->
                                FileRow(folder.directories.first(), "${folder.directories.size} carpetas · " + when { folder.uncertain -> "Sin confirmar; comprueba el destino"; folder.armed -> "Preparada para enviar"; else -> "En espera" }, "folder")
                                folder.directories.drop(1).forEach { path -> Text(path, style = MaterialTheme.typography.bodySmall) }
                                TextButton(onClick = { onRemoveOutgoingFolder(folder) }, enabled = !busy && !hasPending) { Text("Eliminar carpeta de la cola") }
                            }
                            Text(when { queuedForOtherPeer -> "Hay archivos preparados para otro PC. Pulsa Enviar cola para elegir este PC."; outgoing.armed -> "Preparada para enviar al conectar con el PC y abrir esta pantalla."; else -> "En espera · se conserva al cerrar la aplicación." }, style = MaterialTheme.typography.bodySmall)
                            outgoing.files.forEach { entry ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.relativePath ?: entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatBytes(entry.size) + when { entry.uncertain -> " · Sin confirmar; comprueba el PC"; entry.armed -> " · Preparado para enviar"; else -> " · En espera" }, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { onRemoveOutgoing(entry) }, enabled = !busy && !hasPending) { Text("Eliminar") }
                                }
                            }
                            Button(onClick = onSendOutgoing, enabled = !busy && !hasPending && offer == null && batchOffer == null) {
                                Text(if (pairedHost == null) "Enviar al conectar" else "Enviar cola")
                            }
                            if (outgoing.armed) TextButton(onClick = onHoldOutgoing, enabled = !busy) { Text("Dejar en espera") }
                            Text("Copias privadas: eliminarlas de la cola libera espacio; los originales se conservan.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (pairedHost != null || canRetrySend || canRetryInbox || busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Transferencia actual")
                        Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (sendQueue != null) {
                                    val status = when {
                                        sendQueue.total == 0 -> if (sendQueue.running) "Esperando autorización de carpetas" else "Preparación de carpetas terminada; consulta el resultado"
                                        sendQueue.completed == sendQueue.total -> "Lote completado"
                                        sendQueue.running && sendQueue.current == 0 -> "Esperando aceptación del lote"
                                        sendQueue.running -> "Archivo ${sendQueue.current} de ${sendQueue.total}"
                                        sendQueue.current > 0 -> "Lote detenido · archivo ${sendQueue.current} de ${sendQueue.total}"
                                        else -> "Lote detenido · ${sendQueue.total} archivos"
                                    }
                                    Text("$status · ${sendQueue.pending} pendientes", style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                                when {
                                    batchOffer != null -> {
                                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                            FileRow(if (batchOffer.directories.isEmpty()) "${batchOffer.files.size} archivos del PC" else "${batchOffer.directories.size} carpetas · ${batchOffer.files.size} archivos del PC", "${formatBytes(batchOffer.files.sumOf { it.size })} · Pendiente de tu aceptación")
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = onDecline, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Rechazar") }
                                            Button(onClick = onDetail, enabled = !busy && !hasPending, modifier = Modifier.weight(1f)) { Text("Detalle") }
                                        }
                                    }
                                    offer != null -> {
                                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                            FileRow(offer.name, "El PC quiere enviarte ${formatBytes(offer.size)}")
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = onDecline, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Rechazar") }
                                            Button(onClick = onAccept, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Aceptar") }
                                        }
                                    }
                                    transfer != null && (transfer.result == null || busy || hasPending || canRetrySend || canRetryInbox) -> {
                                        FileRow(transfer.name, transfer.result ?: transfer.phase, if (transfer.result in setOf("Enviado y verificado", "Recibido y verificado")) "check" else "file")
                                        if (transfer.result == null) {
                                            if (transfer.phase in setOf("Enviando al PC", "Recibiendo del PC") && transfer.size > 0) {
                                                LinearProgressIndicator(progress = { (transfer.bytes.toDouble() / transfer.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Avance de transferencia" })
                                                val rate = transfer.bytesPerSecond?.let { " · ${formatBytes(it)}/s" }.orEmpty()
                                                Text("${formatBytes(transfer.bytes)} de ${formatBytes(transfer.size)}$rate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            } else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = transfer.phase })
                                        } else Text(formatBytes(transfer.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    busy -> Text(busyLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    else -> Text("Sin transferencias en curso.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (busy && transfer?.result != null) Text(busyLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (cancellable) TextButton(onClick = onCancel) { Text(if (sendQueue?.running == true && sendQueue.total > 1) "Cancelar lote" else "Cancelar") }
                                if (hasPending) {
                                    Button(onClick = onRetry, enabled = !busy) { Text("Reintentar confirmación") }
                                    TextButton(onClick = onDismissPending, enabled = !busy) { Text("Cerrar confirmación pendiente") }
                                } else if (!busy && offer == null && batchOffer == null) {
                                    if (canRetrySend) Button(onClick = onRetrySend) { Text("Reintentar envío") }
                                    if (canRetryInbox) TextButton(onClick = onRetryInbox) { Text("Reintentar bandeja") }
                                }
                            }
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (pairedHost == null && busy) {
                    Text(busyLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (pairedHost != null || activity.isNotEmpty()) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Actividad reciente")
                            if (activity.isNotEmpty()) TextButton(onClick = onActivity) { Text("Ver historial") }
                        }
                        val latest = activity.lastOrNull()
                        if (latest == null) Text("Todavía no hay transferencias.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        else ActivityRow(latest, onOpenReceived, onOpenLocation, compact = true)
                    }
                }
            }
            Surface(onClick = onFolder, enabled = !busy, color = colors.surface, shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconTile("folder")
                    Column(Modifier.weight(1f)) {
                        Text("Carpeta de recepción", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Text(destination, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Glyph("chevron", tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityRow(
    entry: TransferPresentation,
    onOpenReceived: (ReceivedDocument) -> Unit,
    onOpenLocation: (ReceiveLocation) -> Unit,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = if (compact) colors.surface else Color.Transparent,
        border = if (compact) BorderStroke(1.dp, colors.outlineVariant) else null) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 0.dp)) {
            val outcome = entry.result ?: entry.phase
            val sent = entry.direction == "upload"
            val direction = if (sent) "Envío" else "Recepción"
            FileRow(entry.name, "$direction · ${formatBytes(entry.size)}\n$outcome", if (sent) "send" else "receive")
            entry.receipt?.let { receipt ->
                FlowRow {
                    TextButton(onClick = { onOpenReceived(receipt) }) {
                        Glyph("open", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Abrir archivo")
                    }
                    TextButton(onClick = { onOpenLocation(receipt.location) }) {
                        Glyph("folder", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ir a la carpeta")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(label: String, icon: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = when {
            !enabled -> colors.surfaceVariant
            primary -> colors.primary
            else -> colors.secondaryContainer
        },
        contentColor = when {
            !enabled -> colors.onSurfaceVariant
            primary -> colors.onPrimary
            else -> colors.onSecondaryContainer
        }) {
        val tint = LocalContentColor.current
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, Modifier.size(17.dp), tint)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun FileRow(name: String, detail: String, icon: String = "file") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconTile(icon)
        Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun ReceiveFolderDialog(
    title: String,
    entries: List<ReceiveFolderEntry>?,
    failed: Boolean,
    onParent: (() -> Unit)?,
    onEntry: (ReceiveFolderEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (onParent != null) TextButton(onClick = onParent) { Text("Subir un nivel") }
                when {
                    failed -> Text("No se pudo leer esta carpeta. Comprueba que siga disponible y que Android conserve el permiso de acceso.")
                    entries == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    entries.isEmpty() -> Text("Esta carpeta está vacía.")
                    else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(entries) { entry ->
                            TextButton(onClick = { onEntry(entry) }, modifier = Modifier.fillMaxWidth()) {
                                FileRow(entry.name, if (entry.isDirectory) "Carpeta" else "Abrir archivo", if (entry.isDirectory) "folder" else "file")
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun EssentialDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
}
