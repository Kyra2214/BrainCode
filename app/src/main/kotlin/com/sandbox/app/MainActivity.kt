package com.sandbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sandbox.sandbox.SelfCheckStatus

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

open class MainActivity : ComponentActivity() {
    private val viewModel: SandboxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SandboxMobileApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun SandboxMobileApp(viewModel: SandboxViewModel) {
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen) {
        SettingsScreen(viewModel, onBack = { settingsOpen = false })
    } else {
        ThreadScreen(viewModel, onOpenSettings = { settingsOpen = true })
    }
}

@Composable
fun StatusSection(viewModel: SandboxViewModel) {
    var expandedManual by remember { mutableStateOf(false) }
    val expanded = expandedManual
    androidx.compose.runtime.LaunchedEffect(viewModel.selfCheckReport) {
        if (viewModel.selfCheckReport != null) expandedManual = true
    }
    androidx.compose.runtime.LaunchedEffect(viewModel.diagnosticsReport) {
        if (viewModel.diagnosticsReport != null) expandedManual = true
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusHeadline(viewModel),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (viewModel.phase is SandboxPhase.Blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expandedManual = !expandedManual }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                    Text(if (expanded) "menos ▲" else "mais ▼", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPrimaryActions(viewModel)
                }
            }
            if (expanded) {
                StatusDetails(viewModel)
            }
        }
    }
}

private fun statusHeadline(viewModel: SandboxViewModel): String {
    viewModel.selfCheckStage?.let { return it }
    return when (val phase = viewModel.phase) {
        is SandboxPhase.NotReady -> "Sandbox não preparado"
        is SandboxPhase.Downloading -> {
            val pct = if (phase.totalBytes > 0) " ${(phase.bytesDownloaded * 100 / phase.totalBytes)}%" else ""
            "Baixando rootfs…$pct"
        }
        is SandboxPhase.Preparing -> {
            val pct = if (phase.totalBytes > 0) " ${(phase.bytesCompleted * 100 / phase.totalBytes)}%" else ""
            "${phase.stage}$pct"
        }
        is SandboxPhase.Ready -> "Sandbox pronto"
        is SandboxPhase.Running -> "Executando comando…"
        is SandboxPhase.Blocked -> "Bloqueado: ${phase.reason}"
    }
}

@Composable
private fun StatusPrimaryActions(viewModel: SandboxViewModel) {
    when (val phase = viewModel.phase) {
        is SandboxPhase.NotReady -> Button(onClick = { viewModel.prepareSandbox() }) { Text("Preparar sandbox") }
        is SandboxPhase.Downloading, is SandboxPhase.Preparing -> LinearProgressIndicator(modifier = Modifier.width(140.dp))
        is SandboxPhase.Ready -> {
            OutlinedButton(onClick = { viewModel.resetSandbox() }) { Text("Resetar") }
            OutlinedButton(onClick = { viewModel.runDiagnostics() }, enabled = !viewModel.diagnosticsRunning) { Text("Diagnóstico") }
            if (viewModel.diagnosticsRunning) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Button(onClick = { viewModel.runFullSelfCheck() }, enabled = !viewModel.selfCheckRunning) { Text("Teste geral") }
            if (viewModel.selfCheckRunning) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Button(onClick = { viewModel.runBrainHealthCheck() }) { Text("Verificar Brain") }
        }
        is SandboxPhase.Running -> {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            OutlinedButton(onClick = { viewModel.cancelCommand() }) { Text("Cancelar") }
        }
        is SandboxPhase.Blocked -> Button(onClick = { viewModel.prepareSandbox() }) { Text("Tentar de novo") }
    }
}

@Composable
private fun StatusDetails(viewModel: SandboxViewModel) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val phase = viewModel.phase) {
            is SandboxPhase.Downloading -> if (phase.totalBytes > 0) Text("${phase.bytesDownloaded / 1024} KB / ${phase.totalBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
            is SandboxPhase.Preparing -> {
                if (phase.totalBytes > 0L) Text("${phase.bytesCompleted / (1024 * 1024)} MiB / ${phase.totalBytes / (1024 * 1024)} MiB", style = MaterialTheme.typography.bodySmall)
                Text("Não feche o aplicativo durante esta etapa.", style = MaterialTheme.typography.bodySmall)
            }
            else -> {}
        }
        viewModel.lastBrainCycle?.let { cycle ->
            val result = cycle.passos.singleOrNull()
            Text(
                if (cycle.aprovado) "Brain → Policy → Sandbox: aprovado" else "Brain → Policy → Sandbox: ${result?.motivo ?: "reprovado"}",
                color = if (cycle.aprovado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        viewModel.selfCheckReport?.let { report ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Teste geral", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val text = report.sections.joinToString("\n") { s -> "${s.title}:\n" + s.items.joinToString("\n") { "  ${it.status} ${it.name} — ${it.detail}" } }
                        clipboard.setText(AnnotatedString(text)); Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("Copiar", style = MaterialTheme.typography.labelSmall) }
                }
                report.sections.forEach { section ->
                    Text("${section.title}: ${section.items.count { it.status == SelfCheckStatus.OK }}/${section.items.size} OK", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        viewModel.diagnosticsReport?.let { text ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Diagnóstico", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)); Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("Copiar", style = MaterialTheme.typography.labelSmall) }
                }
                SelectionContainer { Text(text.take(2000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
