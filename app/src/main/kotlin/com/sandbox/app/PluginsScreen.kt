package com.sandbox.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import com.sandbox.sandbox.ComponentKind
import com.sandbox.sandbox.InstallationState
import com.sandbox.sandbox.SandboxComponent

/**
 * Tela de Plugins / Ferramentas — Expansão Fase 1.
 * Mesma tela é reaproveitada para as duas abas, variando apenas [kind].
 */
@Composable
fun PluginsScreen(viewModel: SandboxViewModel, kind: ComponentKind) {
    var query by remember(kind) { mutableStateOf("") }
    var showInstalledOnly by remember(kind) { mutableStateOf(false) }
    var pendingRemoval by remember(kind) { mutableStateOf<SandboxComponent?>(null) }

    val components = viewModel.pluginComponents(kind, query, showInstalledOnly)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!viewModel.sandboxReadyForPlugins) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Prepare o sandbox na aba Validação para instalar ou remover componentes. Você já pode buscar no catálogo.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        viewModel.lastPluginError?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                    TextButton(onClick = { viewModel.clearPluginError() }) { Text("Ok") }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(if (kind == ComponentKind.PLUGIN) "Buscar plugin" else "Buscar ferramenta") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        FilterChip(
            selected = showInstalledOnly,
            onClick = { showInstalledOnly = !showInstalledOnly },
            label = { Text("Só instalados") }
        )

        if (kind == ComponentKind.PLUGIN && viewModel.sandboxReadyForPlugins && viewModel.pluginSnapshots.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Histórico offline", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${viewModel.pluginSnapshots.size} snapshot(s) local(is) · ${viewModel.pluginHistory.size} operação(ões)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.pluginSnapshots.takeLast(3).forEach { snapshot ->
                            OutlinedButton(onClick = { viewModel.rollbackPlugins(snapshot.version) }) {
                                Text("Rollback v${snapshot.version}")
                            }
                        }
                    }
                }
            }
        }

        if (components.isEmpty()) {
            Text(
                if (kind == ComponentKind.TOOL) {
                    "As ferramentas já vêm no RootFS do Sandbox; não é necessário instalá-las aqui."
                } else {
                    "Nenhum resultado para essa busca."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(components, key = { it.id }) { component ->
                ComponentCard(
                    component = component,
                    status = viewModel.pluginStatus(component.id),
                    busy = component.id in viewModel.installingComponentIds,
                    canAct = viewModel.sandboxReadyForPlugins,
                    onInstall = { viewModel.installComponent(component.id) },
                    onRemoveRequested = { pendingRemoval = component },
                    onRetry = { viewModel.installComponent(component.id) }
                )
            }
        }
    }

    pendingRemoval?.let { component ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remover ${component.name}?") },
            text = { Text("Isso vai desinstalar os pacotes associados a este componente do sandbox.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeComponent(component.id)
                    pendingRemoval = null
                }) { Text("Remover") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancelar") }
            }
        )
    }
}

/**
 * Visibilidade elevada (não `private`) de propósito: permite que testes de UI em
 * `androidTest` (ex.: [PluginsScreenErrorRenderingTest]) componham este card isoladamente,
 * sem precisar de um [SandboxViewModel] real nem de um sandbox preparado, para exercer o
 * cenário de regressão de um `InstalledComponent.error` muito grande (ex.: stderr de curl
 * sem `-fsSL`) sem travar o Compose. Ver item 8 do plano de correção (fase 3).
 */
@Composable
fun ComponentCard(
    component: SandboxComponent,
    status: com.sandbox.sandbox.InstalledComponent?,
    busy: Boolean,
    canAct: Boolean,
    onInstall: () -> Unit,
    onRemoveRequested: () -> Unit,
    onRetry: () -> Unit
) {
    val persistedOperation = status?.state == InstallationState.INSTALLING ||
        status?.state == InstallationState.REMOVING
    val operationActive = busy || persistedOperation
    var showErrorDialog by remember { mutableStateOf(false) }

    if (showErrorDialog && !status?.error.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("Log de erro — ${component.name}") },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(status?.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) { Text("Fechar") }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(component.name, style = MaterialTheme.typography.titleMedium)
                StatusBadge(status?.state ?: InstallationState.NOT_INSTALLED, busy)
            }
            Text(component.description, style = MaterialTheme.typography.bodySmall)
            component.version?.let { version ->
                Text("Versão: $version", style = MaterialTheme.typography.labelSmall)
            }
            if (component.dependencies.isNotEmpty()) {
                Text("Depende de: ${component.dependencies.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)
            }
            if (status?.state == InstallationState.FAILED && !status.error.isNullOrBlank()) {
                Text(
                    "Erro: ${status.error}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { showErrorDialog = true }) { Text("Ver log completo") }
            }
            if (operationActive) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    if (status?.state == InstallationState.REMOVING) {
                        "Removendo pacotes do RootFS…"
                    } else {
                        "Instalando no RootFS… isso pode levar alguns minutos"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    operationActive -> { /* progresso já exibido acima; sem ações durante a operação */ }
                    status?.state == InstallationState.INSTALLED -> {
                        OutlinedButton(onClick = onRemoveRequested, enabled = canAct) { Text("Remover") }
                    }
                    status?.state == InstallationState.FAILED -> {
                        Button(onClick = onRetry, enabled = canAct) { Text("Tentar novamente") }
                    }
                    else -> {
                        Button(onClick = onInstall, enabled = canAct) { Text("Instalar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(state: InstallationState, busy: Boolean) {
    val (label, color) = when {
        busy && state == InstallationState.INSTALLED -> "⏳ Removendo..." to MaterialTheme.colorScheme.tertiary
        busy || state == InstallationState.INSTALLING -> "⏳ Instalando..." to MaterialTheme.colorScheme.tertiary
        state == InstallationState.REMOVING -> "⏳ Removendo..." to MaterialTheme.colorScheme.tertiary
        state == InstallationState.INSTALLED -> "✅ Instalado" to MaterialTheme.colorScheme.primary
        state == InstallationState.FAILED -> "❌ Falhou" to MaterialTheme.colorScheme.error
        else -> "Não instalado" to MaterialTheme.colorScheme.outline
    }
    Row {
        if (busy || state == InstallationState.INSTALLING || state == InstallationState.REMOVING) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        }
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}
