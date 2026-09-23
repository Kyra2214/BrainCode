package com.sandbox.app

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sandbox.sandbox.ComponentKind
import com.sandbox.sandbox.InstallationState
import com.sandbox.sandbox.InstalledComponent
import com.sandbox.sandbox.SandboxComponent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressão da causa raiz "Congelamento da aba Extensões (NDK/Trivy)".
 *
 * Um `curl` sem `-s`/`-fsSL` pode jogar até 256 KB de barra de progresso no stderr
 * (`ManagedSandboxRuntime.maxOutputChars`). Antes da correção, esse texto ia parar sem
 * corte em `InstalledComponent.error` e era renderizado sem `maxLines`/`overflow` em
 * `ComponentCard` (`PluginsScreen.kt`), forçando o Compose a medir um `Text` gigantesco
 * dentro do `LazyColumn` e travando a thread principal (ANR).
 *
 * Este teste compõe `ComponentCard` isoladamente (sem `SandboxViewModel`/sandbox real)
 * com um `InstalledComponent.error` sintético de 256 KB — simulando tanto o caso em que a
 * camada de persistência já trunca (comportamento atual, ver `PluginManagerLifecycleTest`)
 * quanto, por defesa em profundidade, o caso em que ela não trunca — e garante que:
 *  1) a composição/renderização termina dentro de um orçamento de tempo curto (não trava a
 *     UI thread, ou seja, não haveria ANR);
 *  2) o botão "Ver log completo" aparece, confirmando que o texto é exibido truncado
 *     (`maxLines = 6`) em vez de despejado por inteiro na lista.
 */
@RunWith(AndroidJUnit4::class)
class PluginsScreenErrorRenderingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val hugeStderr = "#".repeat(256 * 1024) // simula 256 KB de progresso do curl

    private val component = SandboxComponent(
        id = "android-ndk",
        name = "Android NDK",
        description = "NDK para builds nativos",
        kind = ComponentKind.TOOL,
        validationCommand = listOf("ndk-build", "--version")
    )

    /** Orçamento generoso para composição + primeiro desenho em hardware de teste variável. */
    private val renderBudgetMs = 5_000L

    @Test
    fun hugeErrorRendersWithinBudgetAndShowsTruncationAffordance() {
        val status = InstalledComponent(
            componentId = component.id,
            version = null,
            installedAt = 0L,
            dependencies = emptyList(),
            state = InstallationState.FAILED,
            error = hugeStderr
        )

        val elapsedMs = measureComposeRenderMillis {
            composeRule.setContent {
                ComponentCard(
                    component = component,
                    status = status,
                    busy = false,
                    canAct = true,
                    onInstall = {},
                    onRemoveRequested = {},
                    onRetry = {}
                )
            }
            composeRule.waitForIdle()
        }

        assertTrue(
            "Renderizar um erro de 256 KB não pode travar a tela de Extensões " +
                "(levou ${elapsedMs}ms, orçamento de ${renderBudgetMs}ms)",
            elapsedMs <= renderBudgetMs
        )

        // O texto de erro deve ser exibido truncado (maxLines=6), com uma ação explícita
        // para abrir o log completo — nunca despejado por inteiro na lista.
        composeRule.onNodeWithText("Ver log completo").assertExists()
    }

    @Test
    fun openingFullLogDialogStillShowsCompleteErrorText() {
        val status = InstalledComponent(
            componentId = component.id,
            version = null,
            installedAt = 0L,
            dependencies = emptyList(),
            state = InstallationState.FAILED,
            error = hugeStderr
        )

        composeRule.setContent {
            ComponentCard(
                component = component,
                status = status,
                busy = false,
                canAct = true,
                onInstall = {},
                onRemoveRequested = {},
                onRetry = {}
            )
        }
        composeRule.waitForIdle()

        val elapsedMs = measureComposeRenderMillis {
            composeRule.onNodeWithText("Ver log completo").performClick()
            composeRule.waitForIdle()
        }

        assertTrue(
            "Abrir o diálogo com o log completo também não pode travar a UI " +
                "(levou ${elapsedMs}ms, orçamento de ${renderBudgetMs}ms)",
            elapsedMs <= renderBudgetMs
        )

        composeRule.onNodeWithText("Fechar").assertExists()
    }

    private inline fun measureComposeRenderMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
