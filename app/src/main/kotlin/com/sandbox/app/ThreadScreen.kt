package com.sandbox.app

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sandbox.runtime.SandboxExecutionResult

data class DiffLine(val prefix: Char, val text: String)
data class DiffFile(val path: String, val lines: List<DiffLine>)

sealed interface ThreadEvent {
    data class User(val text: String) : ThreadEvent
    data class Agent(val text: String) : ThreadEvent
    data class Terminal(val result: SandboxExecutionResult, val execution: com.sandbox.runtime.ExecutionLog?) : ThreadEvent
    data class Approval(val id: String) : ThreadEvent
    data class Report(val title: String, val body: String) : ThreadEvent
    data class Diff(val files: List<DiffFile>) : ThreadEvent
    data class System(val text: String, val progress: Float? = null) : ThreadEvent
}

private data class CodeBlock(val language: String, val code: String)

private fun extractCodeBlocks(text: String): List<CodeBlock> {
    val regex = Regex("```([^\\n]*)\\n([\\s\\S]*?)```")
    return regex.findAll(text).map { match ->
        CodeBlock(match.groupValues[1].trim(), match.groupValues[2].trimEnd())
    }.toList()
}

private fun hasCodeBlocks(text: String): Boolean = extractCodeBlocks(text).isNotEmpty()

@Composable
fun ThreadScreen(viewModel: SandboxViewModel, onOpenSettings: () -> Unit = {}) {
    var sidebarOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ThreadTopBar(viewModel, searchOpen = searchOpen, onToggleSidebar = { sidebarOpen = !sidebarOpen }, onOpenSettings = onOpenSettings, onSearch = { searchOpen = !searchOpen })
        if (sidebarOpen) {
            TaskSidebar(viewModel, onClose = { sidebarOpen = false })
        } else {
            if (searchOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { searchOpen = false }, modifier = Modifier.weight(0.28f)) { Text("← Voltar") }
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar na thread") }, modifier = Modifier.weight(0.72f), singleLine = true)
                }
            }
            StatusSection(viewModel)
            val events = threadEvents(viewModel, query)
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(events.size) {
                if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events) { event -> ThreadEventCard(event, viewModel) }
            }
            ThreadComposer(viewModel)
        }
    }
}

private fun threadEvents(viewModel: SandboxViewModel, query: String = ""): List<ThreadEvent> = buildList {
    addAll(viewModel.activeThreadEvents)
    if (viewModel.phase == SandboxPhase.Running && viewModel.liveTerminalOutput.isNotBlank()) {
        add(ThreadEvent.Report("Terminal · ao vivo", viewModel.liveTerminalOutput))
    }
    viewModel.lastExecution?.let { execution ->
        viewModel.lastResult?.let { add(ThreadEvent.Terminal(it, execution)) }
    }
    viewModel.pendingApprovalId?.let { add(ThreadEvent.Approval(it)) }
    if (viewModel.activeThreadEvents.none { it is ThreadEvent.Report && it.title == "TestLab" }) {
        viewModel.lastTestLabReport?.let { add(ThreadEvent.Report("TestLab", "${if (it.success) "PASS" else "FAIL"} — ${it.passed}/${it.steps.size} etapas")) }
    }
    if (viewModel.activeThreadEvents.none { it is ThreadEvent.Report && it.title == "Security gate" }) {
        viewModel.lastSecurityAssessment?.let { add(ThreadEvent.Report("Security gate", "${if (it.readiness.ready) "APROVADO" else "BLOQUEADO"} — ${it.findings.size} achado(s)")) }
    }
    if (viewModel.activeThreadEvents.none { it is ThreadEvent.Report && it.title == "Git status" }) {
        viewModel.lastGitStatus?.let { add(ThreadEvent.Report("Git status", it)) }
    }
}.filter { query.isBlank() || eventText(it).contains(query, ignoreCase = true) }

private fun eventText(event: ThreadEvent): String = when (event) {
    is ThreadEvent.User -> event.text
    is ThreadEvent.Agent -> event.text
    is ThreadEvent.System -> event.text
    is ThreadEvent.Terminal -> "${event.execution?.command?.joinToString(" ")} ${event.result.stdout} ${event.result.stderr}"
    is ThreadEvent.Approval -> event.id
    is ThreadEvent.Report -> "${event.title} ${event.body}"
    is ThreadEvent.Diff -> event.files.joinToString(" ") { file -> "${file.path} ${file.lines.joinToString { it.text }}" }
}

@Composable
private fun ThreadTopBar(viewModel: SandboxViewModel, searchOpen: Boolean, onToggleSidebar: () -> Unit, onOpenSettings: () -> Unit, onSearch: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleSidebar) { Icon(Icons.Default.Menu, contentDescription = "Tarefas") }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text("BrainCode", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text("Converse. Execute. Comprove.", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            AssistChip(
                onClick = { viewModel.runDiagnostics() },
                label = { Text(phaseLabel(viewModel.phase)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = if (viewModel.phase is SandboxPhase.Blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            IconButton(onClick = onSearch) { Icon(if (searchOpen) Icons.Default.Close else Icons.Default.Search, contentDescription = if (searchOpen) "Fechar busca" else "Buscar") }
            IconButton(onClick = { viewModel.clearActiveSession() }) { Icon(Icons.Default.Delete, contentDescription = "Limpar chat") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Configurações") }
        }
    }
}

@Composable
private fun TaskSidebar(viewModel: SandboxViewModel, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tarefas", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { viewModel.createSession() }) { Text("+ Nova") }
                TextButton(onClick = onClose) { Text("Thread") }
            }
        }
        viewModel.sessionSummaries.forEach { session ->
            Card(onClick = { viewModel.switchSession(session.id); onClose() }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(session.title, style = MaterialTheme.typography.titleSmall)
                    Text("${session.status.name} · ${session.workspaceProjectName ?: "sem workspace"}", style = MaterialTheme.typography.labelSmall)
                    Text(session.lastEventPreview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}

private fun phaseLabel(phase: SandboxPhase): String = when (phase) {
    SandboxPhase.NotReady -> "Não pronto"
    is SandboxPhase.Downloading -> "Baixando"
    is SandboxPhase.Preparing -> "Preparando"
    SandboxPhase.Ready -> "Pronto"
    SandboxPhase.Running -> "Executando"
    is SandboxPhase.Blocked -> "Bloqueado"
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ThreadEventCard(event: ThreadEvent, viewModel: SandboxViewModel) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun copy(text: String) { clipboard.setText(AnnotatedString(text)); Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show() }
    when (event) {
        is ThreadEvent.User -> Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { viewModel.quoteEvent(event) }),
            horizontalArrangement = Arrangement.End
        ) {
            Text(event.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
        is ThreadEvent.Agent -> Column(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { viewModel.quoteEvent(event) }),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val blocks = extractCodeBlocks(event.text)
            if (blocks.isEmpty()) {
                Text(event.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            } else {
                var cursor = 0
                Regex("```([^\\n]*)\\n([\\s\\S]*?)```").findAll(event.text).forEach { match ->
                    val prose = event.text.substring(cursor, match.range.first).trim()
                    if (prose.isNotEmpty()) Text(prose, style = MaterialTheme.typography.bodyMedium)
                    val language = match.groupValues[1].trim().ifBlank { "arquivo" }
                    val code = match.groupValues[2].trimEnd()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(language, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { copy(code) }) { Text("Copiar") }
                            }
                            SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    cursor = match.range.last + 1
                }
                val tail = event.text.substring(cursor).trim()
                if (tail.isNotEmpty()) Text(tail, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is ThreadEvent.System -> Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(event.text, color = if (event.text.contains("bloqueado", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                event.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                if (viewModel.phase is SandboxPhase.Blocked) OutlinedButton(onClick = { viewModel.prepareSandbox() }) { Text("Tentar de novo") }
            }
        }
        is ThreadEvent.Terminal -> Card {
            val terminalText = buildString {
                append("$ ")
                append(event.execution?.command?.joinToString(" ") ?: "")
                append("\nexit=")
                append(event.execution?.exitCode ?: event.result.exitCode)
                if (event.result.stdout.isNotEmpty()) {
                    append("\n\n[stdout]\n")
                    append(event.result.stdout)
                }
                if (event.result.stderr.isNotEmpty()) {
                    append("\n\n[stderr]\n")
                    append(event.result.stderr)
                }
            }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Terminal · ${if (event.result.exitCode == 0) "sucesso" else "falha"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copy(terminalText) }) { Text("Copiar") }
                }
                event.execution?.let { Text("${it.durationMs} ms · ${it.terminationReason.name}", style = MaterialTheme.typography.labelSmall) }
                SelectionContainer { Text(terminalText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
        is ThreadEvent.Approval -> Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Aprovação necessária", color = androidx.compose.ui.graphics.Color(0xFFFFB300), style = MaterialTheme.typography.titleSmall)
                Text("ID: ${event.id.take(24)}…", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { viewModel.approveAndResume() }) { Text("Aprovar e retomar") }
            }
        }
        is ThreadEvent.Report -> Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(event.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copy(event.body) }) { Text("Copiar") }
                }
                SelectionContainer { Text(event.body, fontFamily = if (event.title == "Git status") FontFamily.Monospace else FontFamily.Default, style = MaterialTheme.typography.bodySmall) }
            }
        }
        is ThreadEvent.Diff -> Card {
            var expanded by remember(event.files) { mutableStateOf(false) }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Git diff · ${event.files.size} arquivo(s)", style = MaterialTheme.typography.titleSmall)
                val files = if (expanded) event.files else event.files.take(3)
                files.forEach { file ->
                    Text(file.path, style = MaterialTheme.typography.labelMedium)
                    val lines = if (expanded) file.lines else file.lines.take(12)
                    lines.forEach { line ->
                        Text("${line.prefix}${line.text}", color = if (line.prefix == '+') androidx.compose.ui.graphics.Color(0xFF4CAF50) else if (line.prefix == '-') MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Recolher" else "Mostrar diff completo") }
            }
        }
    }
}

@Composable
private fun ThreadComposer(viewModel: SandboxViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val input = viewModel.chatInput
        if (viewModel.phase == SandboxPhase.Ready && input.startsWith("/")) {
            val matches = viewModel.sugestoesDeComando.filter { it.startsWith(input, ignoreCase = true) && it != input }.take(30)
            if (matches.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(matches) { command -> AssistChip(onClick = { viewModel.chatInput = command }, label = { Text(command) }) }
                }
            }
        }
        OutlinedTextField(
            value = viewModel.chatInput,
            onValueChange = { viewModel.chatInput = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !viewModel.chatRunning && viewModel.phase == SandboxPhase.Ready,
            label = { Text(if (viewModel.chatRunning) "Executando…" else "Descreva a tarefa ou use /comando") },
            minLines = 2,
            maxLines = 5
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (viewModel.chatRunning) {
                CircularProgressIndicator()
                OutlinedButton(onClick = { viewModel.cancelCommand() }) { Text("Parar") }
            } else {
                Button(onClick = { viewModel.submitThreadInput() }, enabled = viewModel.chatInput.isNotBlank() && viewModel.phase == SandboxPhase.Ready, modifier = Modifier.fillMaxWidth()) { Text("Enviar") }
            }
        }
    }
}

// Lista de sugestões agora vem de viewModel.sugestoesDeComando (operacionais + catálogo /comandos, ver SandboxViewModel.kt).