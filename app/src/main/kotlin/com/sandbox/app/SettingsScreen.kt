package com.sandbox.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sandbox.sandbox.BuiltInToolchains
import com.sandbox.sandbox.ComponentKind

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun SettingsScreen(viewModel: SandboxViewModel, onBack: () -> Unit) {
    var section by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Configurações do projeto", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text("Voltar") }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Provedores", "Extensões", "Workspace", "Toolchains", "Diagnóstico").forEachIndexed { index, label ->
                FilterChip(selected = section == index, onClick = { section = index }, label = { Text(label) })
            }
        }
        when (section) {
            0 -> ApiKeysScreen(viewModel)
            1 -> ExtensionsSettings(viewModel)
            2 -> WorkspaceSettings(viewModel)
            3 -> ToolchainSettings(viewModel)
            else -> DiagnosticsSettings(viewModel)
        }
    }
}

/**
 * Diagnóstico, Teste geral e Verificação do Brain moraram na tela principal
 * (StatusPrimaryActions) e agora vivem aqui — são ferramentas de manutenção, não
 * parte do fluxo de chat. Os resultados completos aparecem como mensagens no chat
 * (ThreadEvent.Report); aqui só ficam os botões que disparam cada checagem.
 */
@Composable
private fun DiagnosticsSettings(viewModel: SandboxViewModel) {
    val ready = viewModel.phase == SandboxPhase.Ready
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Diagnóstico e testes", style = MaterialTheme.typography.titleMedium) }
        item { Text("Os resultados aparecem como mensagens no chat.", style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = { viewModel.runDiagnostics() }, enabled = ready && !viewModel.diagnosticsRunning) { Text("Diagnóstico") }
                if (viewModel.diagnosticsRunning) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(onClick = { viewModel.runFullSelfCheck() }, enabled = ready && !viewModel.selfCheckRunning) { Text("Teste geral") }
                if (viewModel.selfCheckRunning) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        item {
            Button(onClick = { viewModel.runBrainHealthCheck() }, enabled = ready) { Text("Verificar Brain") }
        }
        if (!ready) item { Text("Disponível quando o sandbox estiver pronto.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ExtensionsSettings(viewModel: SandboxViewModel) {
    var kind by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == 0, onClick = { kind = 0 }, label = { Text("Tools") })
            FilterChip(selected = kind == 1, onClick = { kind = 1 }, label = { Text("Plugins") })
        }
        PluginsScreen(viewModel, if (kind == 0) ComponentKind.TOOL else ComponentKind.PLUGIN)
    }
}

@Composable
private fun WorkspaceSettings(viewModel: SandboxViewModel) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Workspace", style = MaterialTheme.typography.titleMedium) }
        item { Text("Projeto ativo: ${viewModel.workspaceProjectName}") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.refreshWorkspace() }, enabled = viewModel.phase == SandboxPhase.Ready) { Text("Atualizar projetos") }
                OutlinedButton(onClick = { viewModel.refreshBrainCatalogs() }, enabled = viewModel.phase == SandboxPhase.Ready) { Text("Atualizar Brain") }
            }
        }
        items(viewModel.workspaceProjects) { project ->
            OutlinedButton(onClick = { viewModel.workspaceProjectName = project.name }, modifier = Modifier.fillMaxWidth()) { Text(project.name) }
        }
    }
}

@Composable
private fun ToolchainSettings(viewModel: SandboxViewModel) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Toolchains", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { viewModel.refreshToolchains() }, enabled = viewModel.phase == SandboxPhase.Ready) { Text("Atualizar") }
            }
        }
        items(BuiltInToolchains.all) { profile ->
            val status = viewModel.toolchainStatuses[profile.id]
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(profile.displayName)
                    Text(
                        buildString {
                            append(status?.versionOutput.orEmpty())
                            if (status != null && status.installedBytes > 0L) {
                                if (isNotEmpty()) append(" · ")
                                append(formatBytes(status.installedBytes))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    status?.error?.takeIf { it.isNotBlank() }?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(onClick = { viewModel.installToolchain(profile.id) }, enabled = viewModel.phase == SandboxPhase.Ready) { Text(status?.state?.name ?: "Instalar") }
            }
        }
    }
}
