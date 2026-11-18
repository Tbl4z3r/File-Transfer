package local.filetransfer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
)

internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    return if (unit == 0) "$bytes B" else String.format(Locale.forLanguageTag("es"), "%.1f %s", value, units[unit])
}

@Composable
internal fun EsencialTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF7692FF), onPrimary = Color(0xFF091540),
        primaryContainer = Color(0xFF243C72), onPrimaryContainer = Color(0xFFABD2FA),
        secondary = Color(0xFF7692FF), onSecondary = Color(0xFF091540),
        secondaryContainer = Color(0xFF243C72), onSecondaryContainer = Color(0xFFABD2FA),
        background = Color(0xFF091540), onBackground = Color(0xFFF4F7FE),
        surface = Color(0xFF162653), onSurface = Color(0xFFF4F7FE),
        onSurfaceVariant = Color(0xFFABD2FA), surfaceVariant = Color(0xFF243C72),
        outlineVariant = Color(0xFF3D518C), outline = Color(0xFFABD2FA),
    ) else lightColorScheme(
        primary = Color(0xFF1B2CC1), onPrimary = Color.White,
        primaryContainer = Color(0xFFE4EDFD), onPrimaryContainer = Color(0xFF1B2CC1),
        secondary = Color(0xFF1B2CC1), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE4EDFD), onSecondaryContainer = Color(0xFF1B2CC1),
        background = Color(0xFFF4F7FE), onBackground = Color(0xFF091540),
        surface = Color.White, onSurface = Color(0xFF091540),
        onSurfaceVariant = Color(0xFF3D518C), surfaceVariant = Color(0xFFE4EDFD),
        outlineVariant = Color(0xFFD5DFF1), outline = Color(0xFF3D518C),
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
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                box(7f, 4f, 3f, 4f); box(14f, 10f, 3f, 4f); box(7f, 16f, 3f, 4f)
            }
            "pc" -> { box(3f, 4f, 18f, 13f); line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f) }
            "folder" -> { line(3f, 7f, 3f, 20f); line(3f, 20f, 21f, 20f); line(21f, 20f, 21f, 7f); line(21f, 7f, 11f, 7f); line(11f, 7f, 9f, 4f); line(9f, 4f, 3f, 4f); line(3f, 4f, 3f, 7f) }
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
    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Glyph(name) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun EsencialScreen(
    pairedName: String?, pairedHost: String?, destination: String, busy: Boolean, busyLabel: String, cancellable: Boolean, message: String,
    transfer: TransferPresentation?, sendQueue: SendQueueProgress?, offer: Transfer?, batchOffer: DownloadOfferBatch?, activity: List<TransferPresentation>,
    hasPending: Boolean, hasDiagnostic: Boolean, canOpenReceived: Boolean,
    onSettings: () -> Unit, onScan: () -> Unit, onFolder: () -> Unit,
    onSend: () -> Unit, onReceive: () -> Unit, onAccept: () -> Unit, onDetail: () -> Unit, onDecline: () -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, onDismissPending: () -> Unit, onDiagnostic: () -> Unit,
    onActivity: () -> Unit, onOpenReceived: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Glyph("swap", tint = MaterialTheme.colorScheme.onPrimary) }
                Text("File Transfer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Ajustes" }) { Glyph("settings") }
            }
            if (pairedHost == null) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Conecta tu PC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    TextButton(onClick = onScan, enabled = !busy, shape = RoundedCornerShape(16.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Glyph("qr", Modifier.size(42.dp)) }
                            Text("Escanear QR del PC", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                Surface(onClick = onSettings, shape = RoundedCornerShape(14.dp), color = Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconTile("pc")
                        Column(Modifier.weight(1f)) { Text(pairedName ?: "PC emparejado", fontWeight = FontWeight.Medium); Text(pairedHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) { Text("Vinculado", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("Enviar archivos", "send", true, !busy && offer == null && batchOffer == null && !hasPending, Modifier.weight(1f), onSend)
                    ActionTile("Bandeja del PC", "receive", false, !busy && offer == null && batchOffer == null && !hasPending, Modifier.weight(1f), onReceive)
                }
            }
            Surface(onClick = onFolder, enabled = !busy, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconTile("folder")
                    Column(Modifier.weight(1f)) {
                        Text("Carpeta de recepción", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(destination, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Glyph("chevron") }
                }
            }
            if (pairedHost != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Transferencia")
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (sendQueue != null) {
                                val status = when {
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
                                        FileRow("${batchOffer.files.size} archivos del PC", "${formatBytes(batchOffer.files.sumOf { it.size })} · Pendiente de tu aceptación")
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
                                transfer != null -> {
                                    FileRow(transfer.name, transfer.result ?: transfer.phase, if (transfer.result in setOf("Enviado y verificado", "Recibido y verificado")) "check" else "file")
                                    if (transfer.result == null) {
                                        if (transfer.phase in setOf("Enviando al PC", "Recibiendo del PC") && transfer.size > 0) {
                                            LinearProgressIndicator(progress = { (transfer.bytes.toDouble() / transfer.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Avance de transferencia" })
                                            Text("${formatBytes(transfer.bytes)} de ${formatBytes(transfer.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (hasDiagnostic) TextButton(onClick = onDiagnostic) { Text("Copiar diagnóstico") }
            if (pairedHost != null || activity.isNotEmpty() || canOpenReceived) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Esta sesión")
                        TextButton(onClick = onActivity) { Text("Ver actividad") }
                    }
                    val latest = activity.lastOrNull()
                    if (latest == null) Text("Todavía no hay transferencias.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else FileRow(latest.name, latest.result ?: latest.phase, if (latest.result in setOf("Enviado y verificado", "Recibido y verificado")) "check" else "file")
                    if (canOpenReceived) TextButton(onClick = onOpenReceived) { Text("Abrir último archivo recibido") }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(label: String, icon: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(18.dp), color = if (primary) colors.primary else colors.surface, contentColor = if (primary) colors.onPrimary else colors.onSurface) {
        Column(Modifier.padding(14.dp).heightIn(min = 64.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph(icon, tint = if (primary) colors.onPrimary else colors.primary)
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
internal fun EssentialDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
}
