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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.sandbox.sandbox.BuiltInToolchains
import com.sandbox.sandbox.ComponentKind
import com.sandbox.sandbox.ToolchainState

@Composable
fun SettingsScreen(viewModel: SandboxViewModel, onBack: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
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
            listOf("Provedores", "Extensões", "Workspace", "Diagnóstico").forEachIndexed { index, label ->
                FilterChip(selected = section == index, onClick = { section = index }, label = { Text(label) })
            }
        }
        when (section) {
            0 -> ApiKeysScreen(viewModel)
            1 -> ExtensionsSettings(viewModel)
            2 -> WorkspaceSettings(viewModel)
            else -> DiagnosticsSettings(viewModel)
        }
    }
}

@Composable
private fun DiagnosticsSettings(viewModel: SandboxViewModel) {
    val ready = viewModel.phase == SandboxPhase.Ready
    val lfmManager = (LocalContext.current.applicationContext as BrainCodeApplication).lfmModelManager
    val lfmState by lfmManager.state.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Status do sandbox", style = MaterialTheme.typography.titleMedium) }
        item { StatusSection(viewModel) }
        item { LfmDiagnosticCard(state = lfmState) }
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
        item { Button(onClick = { viewModel.runBrainHealthCheck() }, enabled = ready) { Text("Verificar Brain") } }
        if (!ready) item { Text("Disponível quando o sandbox estiver pronto.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ExtensionsSettings(viewModel: SandboxViewModel) {
    var kind by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == 0, onClick = { kind = 0 }, label = { Text("Tools") })
            FilterChip(selected = kind == 1, onClick = { kind = 1 }, label = { Text("Plugins") })
            FilterChip(selected = kind == 2, onClick = { kind = 2 }, label = { Text("LLM") })
        }
        when (kind) {
            0 -> ToolCatalog(viewModel)
            1 -> PluginsScreen(viewModel, ComponentKind.PLUGIN)
            else -> LlmExtensionsSettings()
        }
    }
}

@Composable
private fun LlmExtensionsSettings() {
    val manager = (LocalContext.current.applicationContext as BrainCodeApplication).lfmModelManager
    val state by manager.state.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Modelos locais", style = MaterialTheme.typography.titleMedium)
            Text("A LFM é provisionada automaticamente após o Roofts. Não é necessário iniciar o download manualmente.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("LFM2.5-350M", style = MaterialTheme.typography.titleSmall)
                        Text("Braço local do Secretário • Q4_K_M", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = {}, enabled = false) {
                        Text(lfmStateLabel(state))
                    }
                }
            }
        }
    }
}

private fun lfmStateLabel(state: LfmModelState): String = when (state) {
    LfmModelState.NOT_INSTALLED -> "Não instalado"
    LfmModelState.DOWNLOADING -> "Baixando…"
    LfmModelState.VERIFYING -> "Verificando…"
    LfmModelState.READY -> "Instalado"
    LfmModelState.CORRUPTED -> "Corrompido"
    LfmModelState.UNAVAILABLE -> "Indisponível"
}

private fun lfmIntegrityLabel(state: LfmModelState): String = when (state) {
    LfmModelState.READY -> "Q4_K_M • SHA-256 verificado"
    LfmModelState.VERIFYING -> "Q4_K_M • verificando integridade"
    LfmModelState.CORRUPTED -> "Q4_K_M • SHA-256 inválido"
    LfmModelState.DOWNLOADING -> "Q4_K_M • download em andamento"
    LfmModelState.NOT_INSTALLED -> "Q4_K_M • aguardando instalação"
    LfmModelState.UNAVAILABLE -> "Q4_K_M • indisponível no momento"
}

@Composable
private fun LfmDiagnosticCard(state: LfmModelState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("LFM2.5-350M", style = MaterialTheme.typography.titleSmall)
                Text("LLM local do Secretário", style = MaterialTheme.typography.bodySmall)
                Text(lfmIntegrityLabel(state), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = {}, enabled = false) {
                Text(lfmStateLabel(state))
            }
        }
    }
}

@Composable
private fun ToolCatalog(viewModel: SandboxViewModel) {
    var errorDialogFor by rememberSaveable { mutableStateOf<String?>(null) }
    val dialogText = errorDialogFor
    if (dialogText != null) {
        AlertDialog(
            onDismissRequest = { errorDialogFor = null },
            title = { Text("Log de erro") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item { Text(dialogText, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { errorDialogFor = null }) { Text("Fechar") } }
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Ferramentas disponíveis no Sandbox. A lista mostra apenas o nome e o estado de instalação.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        val toolMessage = viewModel.lastToolMessage
        val toolError = viewModel.lastToolError
        if (toolMessage != null || toolError != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            toolError ?: toolMessage.orEmpty(),
                            color = if (toolError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        if (toolError != null && toolError.length > 200) {
                            TextButton(onClick = { errorDialogFor = toolError }) { Text("Ver log") }
                        }
                        TextButton(onClick = { viewModel.clearToolMessages() }) { Text("Ok") }
                    }
                }
            }
        }
        items(BuiltInToolchains.all, key = { it.id }) { profile ->
            val status = viewModel.toolchainStatuses[profile.id]
            val installed = status?.state == ToolchainState.INSTALLED
            val installing = status?.state == ToolchainState.INSTALLING
            val failed = status?.state == ToolchainState.FAILED
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(profile.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    installed -> "Instalado"
                                    installing -> "Instalando…"
                                    failed -> "❌ Falhou"
                                    else -> "Não instalado"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    installed -> MaterialTheme.colorScheme.primary
                                    failed -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (installing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else if (failed) {
                            OutlinedButton(
                                onClick = { viewModel.installToolchain(profile.id) },
                                enabled = viewModel.phase == SandboxPhase.Ready
                            ) { Text("Tentar novamente") }
                        } else if (!installed) {
                            OutlinedButton(
                                onClick = { viewModel.installToolchain(profile.id) },
                                enabled = viewModel.phase == SandboxPhase.Ready
                            ) { Text("Instalar") }
                        }
                    }
                    if (failed && !status?.error.isNullOrBlank()) {
                        Text(
                            "Erro: ${status?.error}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { errorDialogFor = status?.error }) { Text("Ver log completo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSettings(viewModel: SandboxViewModel) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Workspace", style = MaterialTheme.typography.titleMedium) }
        item { Text("Projeto ativo: ${viewModel.workspaceProjectName}") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { viewModel.refreshWorkspace() }, enabled = viewModel.phase == SandboxPhase.Ready, modifier = Modifier.fillMaxWidth()) { Text("Atualizar projetos") }
                OutlinedButton(onClick = { viewModel.refreshBrainCatalogs() }, enabled = viewModel.phase == SandboxPhase.Ready, modifier = Modifier.fillMaxWidth()) { Text("Atualizar Brain") }
            }
        }
        items(viewModel.workspaceProjects, key = { it.name }) { project ->
            OutlinedButton(onClick = { viewModel.workspaceProjectName = project.name }, modifier = Modifier.fillMaxWidth()) { Text(project.name) }
        }
    }
}
