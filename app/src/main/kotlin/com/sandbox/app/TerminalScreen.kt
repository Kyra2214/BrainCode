package com.sandbox.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun TerminalScreen(viewModel: SandboxViewModel, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Terminal", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text("Voltar") }
        }
        Text("Executa comandos dentro do RootFS em /home/sandbox.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = viewModel.commandInput,
            onValueChange = { viewModel.commandInput = it },
            label = { Text("Comando") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.runCommand() }, enabled = viewModel.phase == SandboxPhase.Ready) { Text("Executar") }
            OutlinedButton(onClick = { viewModel.clearTerminal() }) { Text("Limpar") }
        }
        viewModel.lastExecution?.let { execution ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Resultado", style = MaterialTheme.typography.titleMedium)
                    Text("exit=${execution.exitCode ?: "?"}", style = MaterialTheme.typography.labelSmall)
                    val output = buildString {
                        if (execution.stdout.isNotBlank()) append(execution.stdout)
                        if (execution.stderr.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(execution.stderr)
                        }
                    }.ifBlank { "(sem saída)" }
                    Text(output, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
    }
}
