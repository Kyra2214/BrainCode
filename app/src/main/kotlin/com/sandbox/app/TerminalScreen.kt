package com.sandbox.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun TerminalScreen(viewModel: SandboxViewModel, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val history = viewModel.terminalHistory
    val tailLength = history.lastOrNull()?.output?.length ?: 0

    LaunchedEffect(history.size, tailLength) {
        if (history.isNotEmpty()) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val lastVisible = visible.maxOfOrNull { it.index } ?: -1
            if (lastVisible >= history.lastIndex - 1 || visible.isEmpty()) {
                listState.animateScrollToItem(history.lastIndex)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Terminal", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text("Voltar") }
        }
        Text("Console do RootFS · /home/sandbox", style = MaterialTheme.typography.bodySmall)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(history, key = { entry -> "${entry.command}:${entry.hashCode()}" }) { entry ->
                SelectionContainer {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("sandbox:~$ ${entry.command}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        if (entry.output.isNotEmpty()) {
                            Text(entry.output, fontFamily = FontFamily.Monospace)
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
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = { viewModel.runCommand() }, enabled = viewModel.phase == SandboxPhase.Ready && viewModel.commandInput.isNotBlank()) { Text("Executar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.clearTerminal() }) { Text("Limpar scrollback") }
        }
    }
}
