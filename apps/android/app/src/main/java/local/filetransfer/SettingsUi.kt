package local.filetransfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    destination: String,
    paired: Boolean,
    busy: Boolean,
    address: String,
    port: String,
    lastError: String?,
    onBack: () -> Unit,
    onFolder: () -> Unit,
    onManual: () -> Unit,
    onAddress: (String) -> Unit,
    onPort: (String) -> Unit,
    onSaveAddress: () -> Unit,
    onForget: () -> Unit,
    onCopyError: () -> Unit,
) {
    PreferencesPage("Ajustes", onBack) {
        SettingsSection("Conexión") {
            if (paired) {
                OutlinedTextField(address, onAddress, label = { Text("IP del PC") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, onPort, label = { Text("Puerto") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onSaveAddress, enabled = !busy) { Text("Actualizar dirección") }
                TextButton(onClick = onForget, enabled = !busy) { Text("Olvidar dispositivo") }
            } else {
                Text("Conecta Android con el código que muestra File Transfer en Windows.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onManual, enabled = !busy) { Text("Conexión manual") }
            }
        }
        SettingsSection("Carpeta de recepción") {
            Text(destination, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onFolder, enabled = !busy) { Text("Gestionar carpeta") }
        }
        SettingsSection("Registro de errores") {
            if (lastError == null) {
                Text("Todavía no hay errores registrados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Último error", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onCopyError) { Text("Copiar diagnóstico") }
            }
        }
        Text("File Transfer ${BuildConfig.VERSION_NAME} (compilación ${BuildConfig.VERSION_CODE})",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
internal fun ManualConnectionScreen(
    input: String,
    busy: Boolean,
    onBack: () -> Unit,
    onInput: (String) -> Unit,
    onConnect: () -> Unit,
) {
    PreferencesPage("Conexión manual", onBack) {
        SettingsSection("Código del PC") {
            Text("Abre el QR en File Transfer para Windows y pulsa «Copiar código». Pega aquí el código o el JSON de una versión anterior.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(input, onInput, label = { Text("Código o JSON") }, placeholder = { Text("FT1-…") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                minLines = 3, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button(onClick = onConnect, enabled = !busy && input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Conectar") }
        }
    }
}

@Composable
private fun PreferencesPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) { Glyph("back") }
                Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.onBackground,
                    modifier = Modifier.semantics { heading() })
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}
