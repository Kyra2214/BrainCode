package com.sandbox.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(viewModel: SandboxViewModel, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val history = viewModel.terminalHistory
    val tailLength = history.lastOrNull()?.output?.length ?: 0
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var unseenOutput by remember { mutableStateOf(false) }

    // O campo de comando ficava com foco (e o teclado aberto) o tempo todo,
    // inclusive enquanto o usuário rolava a lista para selecionar uma saída
    // antiga. Como o campo fica logo abaixo da lista, o gesto de seleção
    // acabava caindo em cima do teclado/campo e o texto selecionado era
    // "colado" ali sem o usuário pedir. Tirando o foco assim que a lista
    // começa a rolar, o teclado some e a seleção/cópia fica isolada — tocar
    // no campo de novo volta a focar normalmente.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    val nearBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            history.isEmpty() || lastVisible >= history.lastIndex - 1
        }
    }

    LaunchedEffect(history.size, tailLength) {
        if (history.isNotEmpty()) {
            if (nearBottom || listState.layoutInfo.visibleItemsInfo.isEmpty()) {
                listState.animateScrollToItem(history.lastIndex)
                unseenOutput = false
            } else {
                unseenOutput = true
            }
        }
    }

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
    }

    fun copyCommand(entry: TerminalEntry) = copy(entry.command)
    fun copyOutput(entry: TerminalEntry) = copy(entry.output)
    fun copyEntry(entry: TerminalEntry) = copy("$ ${entry.command}\n${entry.output}")

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Terminal", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text("Voltar") }
        }
        Text("Console do RootFS · /home/sandbox", style = MaterialTheme.typography.bodySmall)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { if (history.isNotEmpty()) scope.launch { listState.animateScrollToItem(0); unseenOutput = false } }) { Text("Início") }
            TextButton(onClick = { if (history.isNotEmpty()) scope.launch { listState.animateScrollToItem(history.lastIndex); unseenOutput = false } }) { Text("Fim") }
            if (unseenOutput) {
                Button(onClick = { unseenOutput = false }) { Text("↓ Nova saída") }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(history, key = { entry -> "${entry.command}:${entry.hashCode()}" }) { entry ->
                SelectionContainer {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("sandbox:~$ ${entry.command}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { copyCommand(entry) }) { Text("Comando") }
                        }
                        if (entry.output.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(entry.output, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Column {
                                    TextButton(onClick = { copyOutput(entry) }) { Text("Saída") }
                                    TextButton(onClick = { copyEntry(entry) }) { Text("Tudo") }
                                }
                            }
                        } else if (entry.running) {
                            Text("▌", fontFamily = FontFamily.Monospace)
                        }
                        if (!entry.running) {
                            Text("[exit ${entry.exitCode ?: "?"}]", fontFamily = FontFamily.Monospace, color = if (entry.exitCode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { viewModel.recallPreviousCommand() }, enabled = viewModel.commandHistory.isNotEmpty()) { Text("↑") }
            OutlinedTextField(
                value = viewModel.commandInput,
                onValueChange = { viewModel.commandInput = it },
                label = { Text("sandbox:~$") },
                modifier = Modifier.weight(1f).focusRequester(inputFocusRequester),
                singleLine = true
            )
            Button(onClick = { viewModel.runCommand() }, enabled = viewModel.phase == SandboxPhase.Ready && viewModel.commandInput.isNotBlank()) { Text("Executar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.clearTerminal() }) { Text("Limpar scrollback") }
        }
    }
}
