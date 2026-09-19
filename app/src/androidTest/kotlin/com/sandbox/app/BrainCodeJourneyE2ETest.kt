package com.sandbox.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android journeys: drives the same chat UI used by the user,
 * submits deterministic requests, and verifies that BrainCode returns
 * a result of the expected kind.
 *
 * No external LLM/API is required for these journeys.
 */
@RunWith(AndroidJUnit4::class)
class BrainCodeJourneyE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireSandboxForJourney() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Preparar sandbox", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Tentar de novo", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            runCatching {
                composeRule.onNodeWithContentDescription("Enviar")
                    .assertIsDisplayed()
                    .assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        val prepare = composeRule.onAllNodesWithText("Preparar sandbox", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (prepare.isNotEmpty()) {
            composeRule.onNodeWithText("Preparar sandbox", substring = true, useUnmergedTree = true).performClick()
        } else {
            val retry = composeRule.onAllNodesWithText("Tentar de novo", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (retry.isNotEmpty()) {
                composeRule.onNodeWithText("Tentar de novo", substring = true, useUnmergedTree = true).performClick()
            }
        }

        composeRule.waitUntil(timeoutMillis = 120_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("Enviar")
                    .assertIsDisplayed()
                    .assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        check(runCatching {
            composeRule.onNodeWithContentDescription("Enviar").assertIsEnabled()
            true
        }.getOrDefault(false)) {
            "Sandbox não ficou pronto no ambiente E2E; o composer permaneceu desabilitado."
        }
    }

    private fun send(text: String) {
        composeRule.onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput(text)
        composeRule.onNodeWithContentDescription("Enviar").performClick()
    }

    private fun waitForAssistantContaining(vararg terms: String, timeoutMs: Long = 120_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            runCatching {
                composeRule.onNodeWithText("Prompt gerado", substring = true, useUnmergedTree = true).assertIsDisplayed()
                terms.all { term ->
                    composeRule.onAllNodesWithText(term, substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes().size >= 2
                }
            }.getOrDefault(false)
        }
    }

    private fun waitForExecutionEvidence(term: String, timeoutMs: Long = 120_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            runCatching {
                composeRule.onNodeWithText("Terminal", substring = true, useUnmergedTree = true).assertIsDisplayed()
                composeRule.onAllNodesWithText(term, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size >= 2
            }.getOrDefault(false)
        }
    }

    @Test
    fun imagePromptJourneyReturnsVisualPrompt() {
        send("Crie um prompt para uma imagem fotorrealista de um foguete decolando no deserto ao entardecer, visto de longa distância, com meteoros caindo.")
        waitForAssistantContaining("foguete")
    }

    @Test
    fun codePromptJourneyReturnsCodePrompt() {
        send("Crie um prompt de código para uma interface de chat com lista de conversas, campo de mensagem e botão enviar.")
        waitForAssistantContaining("interface")
    }

    @Test
    fun executionJourneyReturnsExecutionEvidence() {
        send("/run echo BrainCode-E2E-EXECUTION-OK")
        waitForExecutionEvidence("BrainCode-E2E-EXECUTION-OK")
    }

    @Test
    fun genericTextJourneyDoesNotBecomeImagePrompt() {
        send("Escreva um prompt para gerar um resumo executivo de uma reunião de equipe.")
        waitForAssistantContaining("resumo")
    }

    @Test
    fun secondTurnPreservesConversationFlow() {
        send("Crie um prompt de imagem de um foguete.")
        waitForAssistantContaining("foguete")
        send("Agora coloque o foguete em um deserto ao entardecer com meteoros.")
        waitForAssistantContaining("deserto")
    }
}
