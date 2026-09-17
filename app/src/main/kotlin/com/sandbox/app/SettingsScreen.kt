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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Status do sandbox", style = MaterialTheme.typography.titleMedium) }
        item { StatusSection(viewModel) }
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
        }
        if (kind == 0) {
            ToolCatalog(viewModel)
        } else {
            PluginsScreen(viewModel, ComponentKind.PLUGIN)
        }
    }
}

@Composable
private fun ToolCatalog(viewModel: SandboxViewModel) {
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
        items(BuiltInToolchains.all, key = { it.id }) { profile ->
            val status = viewModel.toolchainStatuses[profile.id]
            val installed = status?.state == ToolchainState.INSTALLED
            val installing = status?.state == ToolchainState.INSTALLING
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                installed -> "Instalado"
                                installing -> "Instalando…"
                                else -> "Não instalado"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                installed -> MaterialTheme.colorScheme.primary
                                status?.state == ToolchainState.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (!installed) {
                        OutlinedButton(
                            onClick = { viewModel.installToolchain(profile.id) },
                            enabled = viewModel.phase == SandboxPhase.Ready && !installing
                        ) {
                            Text(if (installing) "Instalando…" else "Instalar")
                        }
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
