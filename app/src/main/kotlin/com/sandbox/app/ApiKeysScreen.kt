package com.sandbox.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Sub-aba "Chaves de API" (dentro de Ferramentas): um cartão por provider
 * do catálogo (ai_api_catalog.json — só camada gratuita), com link de
 * cadastro, campo pra colar a chave, botão salvar e botão testar (chamada
 * real e mínima contra a API, ver [ApiKeyTester]).
 */
@Composable
fun ApiKeysScreen(viewModel: SandboxViewModel) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Chaves de modelos e serviços de pesquisa são armazenadas cifradas no Android Keystore. Consulte os termos e limites de cada API antes de usar.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "brave-search") {
                BraveSearchKeyCard(
                    keyValue = viewModel.apiKeyInput(BRAVE_SEARCH_KEY_ID),
                    onKeyChange = { viewModel.updateApiKeyInput(BRAVE_SEARCH_KEY_ID, it) },
                    hasStoredKey = viewModel.hasStoredApiKey(BRAVE_SEARCH_KEY_ID),
                    onSave = { viewModel.saveApiKey(BRAVE_SEARCH_KEY_ID) },
                    onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                )
            }
            if (viewModel.apiProviders.isEmpty()) {
                item(key = "catalog-warning") {
                    Text("Catálogo de modelos indisponível (falha ao ler ai_api_catalog.json).", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(viewModel.apiProviders, key = { it.id }) { provider ->
                ApiProviderCard(
                    provider = provider,
                    keyValue = viewModel.apiKeyInput(provider.id),
                    onKeyChange = { viewModel.updateApiKeyInput(provider.id, it) },
                    hasStoredKey = viewModel.hasStoredApiKey(provider.id),
                    testState = viewModel.apiKeyTestState[provider.id] ?: ApiKeyTestUiState.Idle,
                    onSave = { viewModel.saveApiKey(provider.id) },
                    onTest = { model -> viewModel.testApiKey(provider.id, model) },
                    onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                )
            }
        }
    }
}

@Composable
private fun BraveSearchKeyCard(
    keyValue: String,
    onKeyChange: (String) -> Unit,
    hasStoredKey: Boolean,
    onSave: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Brave Search", style = MaterialTheme.typography.titleMedium)
            Text("Pesquisa web estruturada. Sem chave, o app usa DuckDuckGo como fallback. O uso do Brave pode exigir um plano ativo e está sujeito a quotas/limites próprios.", style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenUrl("https://api-dashboard.search.brave.com/app/keys") }) { Text("Obter chave") }
                TextButton(onClick = { onOpenUrl("https://api-dashboard.search.brave.com/documentation") }) { Text("Docs e limites") }
            }
            OutlinedTextField(
                value = keyValue,
                onValueChange = onKeyChange,
                label = { Text("Chave Brave Search") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ocultar" else "Mostrar") }
                Button(onClick = onSave, enabled = keyValue.isNotBlank() || hasStoredKey) {
                    Text(if (keyValue.isBlank() && hasStoredKey) "Remover chave" else "Salvar chave")
                }
            }
            Text(if (hasStoredKey) "Chave salva neste dispositivo." else "Chave opcional; DuckDuckGo continua habilitado sem ela.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ApiProviderCard(
    provider: ApiProvider,
    keyValue: String,
    onKeyChange: (String) -> Unit,
    hasStoredKey: Boolean,
    testState: ApiKeyTestUiState,
    onSave: () -> Unit,
    onTest: (ApiProviderModel) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    val primaryModel = provider.models.firstOrNull()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                if (provider.region.isNotBlank()) {
                    Text(provider.region, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (provider.models.size > 1) {
                Text("Modelos: " + provider.models.joinToString(", ") { it.name }, style = MaterialTheme.typography.labelSmall)
            } else if (primaryModel != null) {
                Text(primaryModel.name, style = MaterialTheme.typography.labelSmall)
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenUrl(provider.officialUrl) }) { Text("Cadastrar-se") }
                TextButton(onClick = { onOpenUrl(provider.documentationUrl) }) { Text("Docs") }
            }
            OutlinedTextField(
                value = keyValue,
                onValueChange = onKeyChange,
                label = { Text("Chave de API") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ocultar" else "Mostrar") }
                Button(onClick = onSave, enabled = keyValue.isNotBlank() || hasStoredKey) { Text("Salvar") }
                OutlinedButton(
                    onClick = { primaryModel?.let(onTest) },
                    enabled = hasStoredKey && primaryModel != null && testState != ApiKeyTestUiState.Testing
                ) { Text(if (testState is ApiKeyTestUiState.Testing) "Testando..." else "Testar") }
            }
            when (testState) {
                is ApiKeyTestUiState.Success -> Text(
                    testState.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                is ApiKeyTestUiState.Failure -> Text(
                    testState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                ApiKeyTestUiState.Testing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Testando chamada real...", style = MaterialTheme.typography.bodySmall)
                }
                ApiKeyTestUiState.Idle -> {}
            }
            if (!hasStoredKey) {
                Text("Salve uma chave antes de testar.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
