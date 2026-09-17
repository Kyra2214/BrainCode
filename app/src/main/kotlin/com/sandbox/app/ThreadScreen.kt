package com.sandbox.app

import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sandbox.runtime.SandboxExecutionResult

data class DiffLine(val prefix: Char, val text: String)
data class DiffFile(val path: String, val lines: List<DiffLine>)

sealed interface ThreadEvent {
    data class User(val text: String) : ThreadEvent
    data class Agent(
        val text: String,
        val promptActionId: String? = null,
        val contentType: GeneratedContentType = detectGeneratedContentType(text),
        val researchSources: List<ResearchSourceUi> = emptyList()
    ) : ThreadEvent
    data class Terminal(val result: SandboxExecutionResult, val execution: com.sandbox.runtime.ExecutionLog?) : ThreadEvent
    data class Approval(val id: String) : ThreadEvent
    data class Report(val title: String, val body: String) : ThreadEvent
    data class Diff(val files: List<DiffFile>) : ThreadEvent
    data class System(val text: String, val progress: Float? = null) : ThreadEvent
}

private data class CodeBlock(val language: String, val code: String)

private fun extractCodeBlocks(text: String): List<CodeBlock> {
    val regex = Regex("```([^\\n]*)\\n([\\s\\S]*?)```")
    return regex.findAll(text).map { CodeBlock(it.groupValues[1].trim(), it.groupValues[2].trimEnd()) }.toList()
}

@Composable
fun ThreadScreen(viewModel: SandboxViewModel, onOpenSettings: () -> Unit = {}) {
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar conversa?") },
            text = { Text("A conversa ativa será removida da sessão atual. Essa ação não pode ser desfeita.") },
            confirmButton = {
                Button(onClick = { confirmClear = false; viewModel.clearActiveSession() }) { Text("Limpar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        ThreadTopBar(
            viewModel = viewModel,
            searchOpen = searchOpen,
            onToggleSidebar = { sidebarOpen = !sidebarOpen },
            onOpenSettings = onOpenSettings,
            onSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) query = ""
            },
            onClearChat = { confirmClear = true }
        )
        if (sidebarOpen) {
            TaskSidebar(viewModel, onClose = { sidebarOpen = false })
        } else {
            if (searchOpen) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { searchOpen = false; query = "" }, modifier = Modifier.weight(0.28f)) { Text("← Voltar") }
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar na thread") }, modifier = Modifier.weight(0.72f), singleLine = true)
                }
            }
            // "Sandbox pronto" + Resetar não fica mais fixo no topo do chat — mora em
            // Configurações > Diagnóstico. Mantemos aqui só os estados que bloqueiam o uso
            // (preparar/baixar/erro), para não esconder uma ação necessária do usuário.
            if (viewModel.phase != SandboxPhase.Ready) StatusSection(viewModel)
            val events = threadEvents(viewModel, query)
            val listState = rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(events.size) {
                if (events.isNotEmpty()) {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val lastVisible = visible.maxOfOrNull { it.index } ?: -1
                    val nearEnd = lastVisible >= events.lastIndex - 2
                    if (nearEnd) listState.animateScrollToItem(events.lastIndex)
                }
            }
            if (searchOpen && query.isNotBlank() && events.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nenhum resultado", style = MaterialTheme.typography.titleSmall)
                        Text("Nenhuma mensagem corresponde a \"$query\".", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(events, key = { index, event -> eventKey(event, index) }) { _, event -> ThreadEventCard(event, viewModel) }
                    }
                }
            }
            ThreadComposer(viewModel)
        }
    }
}

/** The index prevents duplicate-content events from colliding in LazyColumn. */
private fun eventKey(event: ThreadEvent, index: Int): String = "$index:${when (event) {
    is ThreadEvent.User -> "user:${event.text.hashCode()}"
    is ThreadEvent.Agent -> "agent:${event.text.hashCode()}:${event.researchSources.size}"
    is ThreadEvent.Terminal -> "terminal:${event.execution?.executionId ?: event.result.hashCode()}"
    is ThreadEvent.Approval -> "approval:${event.id}"
    is ThreadEvent.Report -> "report:${event.title}:${event.body.hashCode()}"
    is ThreadEvent.Diff -> "diff:${event.files.joinToString { it.path }.hashCode()}"
    is ThreadEvent.System -> "system:${event.text}:${event.progress}"
}}"

private fun threadEvents(viewModel: SandboxViewModel, query: String = ""): List<ThreadEvent> = buildList {
    addAll(viewModel.activeThreadEvents)
    if (viewModel.phase == SandboxPhase.Running && viewModel.liveTerminalOutput.isNotBlank()) add(ThreadEvent.Report("Terminal · ao vivo", viewModel.liveTerminalOutput))
    viewModel.lastExecution?.let { execution -> viewModel.lastResult?.let { add(ThreadEvent.Terminal(it, execution)) } }
    viewModel.pendingApprovalId?.let { add(ThreadEvent.Approval(it)) }
    if (viewModel.activeThreadEvents.none { it is ThreadEvent.Report && it.title == "TestLab" }) viewModel.lastTestLabReport?.let { add(ThreadEvent.Report("TestLab", "${if (it.success) "PASS" else "FAIL"} — ${it.passed}/${it.steps.size} etapas")) }
    if (viewModel.activeThreadEvents.none { it is ThreadEvent.Report && it.title == "Security gate" }) viewModel.lastSecurityAssessment?.let { add(ThreadEvent.Report("Security gate", "${if (it.readiness.ready) "APROVADO" else "BLOQUEADO"} — ${it.findings.size} achado(s)")) }
    if (viewModel.activeThreadEvents.none { it is ThreadEvent.Report && it.title == "Git status" }) viewModel.lastGitStatus?.let { add(ThreadEvent.Report("Git status", it)) }
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
private fun ThreadTopBar(viewModel: SandboxViewModel, searchOpen: Boolean, onToggleSidebar: () -> Unit, onOpenSettings: () -> Unit, onSearch: () -> Unit, onClearChat: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleSidebar) { Icon(Icons.Default.Menu, contentDescription = "Tarefas") }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text("BrainCode", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text("Converse. Execute. Comprove.", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            val phaseColor = if (viewModel.phase is SandboxPhase.Blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = phaseColor, shape = MaterialTheme.shapes.small) { Text(phaseLabel(viewModel.phase), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
            IconButton(onClick = onSearch) { Icon(if (searchOpen) Icons.Default.Close else Icons.Default.Search, contentDescription = if (searchOpen) "Fechar busca" else "Buscar") }
            IconButton(onClick = onClearChat) { Icon(Icons.Default.Delete, contentDescription = "Limpar chat") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Configurações") }
        }
    }
}

@Composable
private fun TaskSidebar(viewModel: SandboxViewModel, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tarefas", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button(onClick = { viewModel.createSession() }) { Text("+ Nova") }; TextButton(onClick = onClose) { Text("Thread") } }
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
        is ThreadEvent.User -> Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { viewModel.quoteEvent(event) }, onLongClick = { viewModel.quoteEvent(event) }), horizontalArrangement = Arrangement.End) { Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) { Text(event.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) } }
        is ThreadEvent.Agent -> Column(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { viewModel.quoteEvent(event) }, onLongClick = { viewModel.quoteEvent(event) }), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GeneratedContentCard(event = event, onCopy = ::copy)
            if (event.researchSources.isNotEmpty()) ResearchSourcesCard(event.researchSources)
            event.promptActionId?.let { actionId ->
                var feedbackDado by remember(actionId) { mutableStateOf<Boolean?>(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { feedbackDado = true; viewModel.recordPromptFeedback(actionId, true) }, enabled = feedbackDado == null) { Text(if (feedbackDado == true) "👍 Obrigado" else "👍") }
                    TextButton(onClick = { feedbackDado = false; viewModel.recordPromptFeedback(actionId, false) }, enabled = feedbackDado == null) { Text(if (feedbackDado == false) "👎 Obrigado" else "👎") }
                }
            }
        }
        is ThreadEvent.System -> Card { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(event.text, color = if (event.text.contains("bloqueado", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant); event.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }; if (viewModel.phase is SandboxPhase.Blocked) OutlinedButton(onClick = { viewModel.prepareSandbox() }) { Text("Tentar de novo") } } }
        is ThreadEvent.Terminal -> Card {
            val terminalText = buildString { append("$ "); append(event.execution?.command?.joinToString(" ") ?: ""); append("\nexit="); append(event.execution?.exitCode ?: event.result.exitCode); if (event.result.stdout.isNotEmpty()) { append("\n\n[stdout]\n"); append(event.result.stdout) }; if (event.result.stderr.isNotEmpty()) { append("\n\n[stderr]\n"); append(event.result.stderr) } }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Terminal · ${if (event.result.exitCode == 0) "sucesso" else "falha"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); TextButton(onClick = { copy(terminalText) }) { Text("Copiar") } }; event.execution?.let { Text("${it.durationMs} ms · ${it.terminationReason.name}", style = MaterialTheme.typography.labelSmall) }; SelectionContainer { Text(terminalText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
        }
        is ThreadEvent.Approval -> Card { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Aprovação necessária", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleSmall); Text("ID: ${event.id.take(24)}…", style = MaterialTheme.typography.bodySmall); Button(onClick = { viewModel.approveAndResume() }) { Text("Aprovar e retomar") } } }
        is ThreadEvent.Report -> Card { Column(modifier = Modifier.padding(12.dp)) { Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(event.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); TextButton(onClick = { copy(event.body) }) { Text("Copiar") } }; Text(event.body, fontFamily = if (event.title == "Git status") FontFamily.Monospace else FontFamily.Default, style = MaterialTheme.typography.bodySmall) } }
        is ThreadEvent.Diff -> Card {
            var expanded by remember(event.files) { mutableStateOf(false) }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Git diff · ${event.files.size} arquivo(s)", style = MaterialTheme.typography.titleSmall)
                val files = if (expanded) event.files else event.files.take(3)
                files.forEach { file -> Text(file.path, style = MaterialTheme.typography.labelMedium); val lines = if (expanded) file.lines else file.lines.take(12); lines.forEach { line -> Text("${line.prefix}${line.text}", color = if (line.prefix == '+') MaterialTheme.colorScheme.primary else if (line.prefix == '-') MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Recolher" else "Mostrar diff completo") }
            }
        }
    }
}

@Composable
private fun GeneratedContentCard(event: ThreadEvent.Agent, onCopy: (String) -> Unit) {
    val content = copyPayloadFor(event.text, event.contentType)
    val structured = event.contentType != GeneratedContentType.TEXT
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (event.contentType == GeneratedContentType.PROMPT) "Prompt gerado" else "Resultado", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onCopy(content) }) { Text(if (structured) "Copiar tudo" else "Copiar prompt") }
            }
            if (structured) SelectionContainer { Text(content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            else Text(content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ResearchSourcesCard(sources: List<ResearchSourceUi>) {
    var expanded by rememberSaveable(sources.map { it.url }) { mutableStateOf(false) }
    val label = if (sources.size == 1) "1 fonte" else "${sources.size} fontes"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = if (expanded) "Recolher fontes da pesquisa" else "Expandir fontes da pesquisa" }) {
                Text("Pesquisa web · $label ${if (expanded) "▾" else "▸"}")
            }
            if (expanded) sources.forEach { ResearchSourceRow(it) }
        }
    }
}

@Composable
private fun ResearchSourceRow(source: ResearchSourceUi) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(source.title, style = MaterialTheme.typography.titleSmall)
        Text(source.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(source.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url))) } }) { Text("Abrir fonte ↗") }
    }
}

@Composable
private fun ThreadComposer(viewModel: SandboxViewModel) {
    Column(modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val input = viewModel.chatInput
        if (viewModel.phase == SandboxPhase.Ready && input.startsWith("/")) {
            val matches = viewModel.sugestoesDeComando.filter { it.startsWith(input, ignoreCase = true) && it != input }.take(30)
            if (matches.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(matches, key = { it }) { command -> AssistChip(onClick = { viewModel.chatInput = command }, label = { Text(command) }) } }
        }
        val enabled = !viewModel.chatRunning && viewModel.phase == SandboxPhase.Ready
        val canSend = viewModel.chatInput.isNotBlank() && viewModel.phase == SandboxPhase.Ready
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.weight(1f).heightIn(min = 40.dp).padding(vertical = 8.dp)) {
                    if (viewModel.chatInput.isEmpty()) Text(if (viewModel.chatRunning) "Executando…" else "Descreva a tarefa ou use /comando", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(value = viewModel.chatInput, onValueChange = { viewModel.chatInput = it }, enabled = enabled, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6)
                }
                if (viewModel.chatRunning) FilledIconButton(onClick = { viewModel.cancelCommand() }, shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Filled.Stop, contentDescription = "Parar") }
                else FilledIconButton(onClick = { viewModel.submitThreadInput() }, enabled = canSend, shape = CircleShape) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Enviar") }
            }
        }
    }
}
