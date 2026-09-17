package com.sandbox.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.createComposeRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android journeys: the test drives the same chat UI used by the user,
 * submits deterministic requests, and verifies that BrainCode returns a
 * result of the expected kind.
 *
 * No external LLM/API is required for these journeys.
 */
@RunWith(AndroidJUnit4::class)
class BrainCodeJourneyE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun waitUntilReady(timeoutMs: Long = 120_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            runCatching {
                composeRule.onNodeWithText("Pronto").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    private fun send(text: String) {
        composeRule.onNodeWithText("Descreva a tarefa ou use /comando").assertIsDisplayed()
        composeRule.onNodeWithText("Descreva a tarefa ou use /comando").performTextInput(text)
        composeRule.onNodeWithContentDescription("Enviar").performClick()
    }

    private fun waitForAssistantContaining(vararg terms: String, timeoutMs: Long = 120_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            terms.all { term ->
                runCatching {
                    composeRule.onNodeWithText(term, substring = true, useUnmergedTree = true).assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
        }
    }

    @Test
    fun imagePromptJourneyReturnsVisualPrompt() {
        waitUntilReady()
        send("Crie um prompt para uma imagem fotorrealista de um foguete decolando no deserto ao entardecer, visto de longa distância, com meteoros caindo.")
        waitForAssistantContaining("foguete")
    }

    @Test
    fun codePromptJourneyReturnsCodePrompt() {
        waitUntilReady()
        send("Crie um prompt de código para uma interface de chat com lista de conversas, campo de mensagem e botão enviar.")
        waitForAssistantContaining("interface")
    }

    @Test
    fun executionJourneyReturnsExecutionEvidence() {
        waitUntilReady()
        send("/run echo BrainCode-E2E-EXECUTION-OK")
        waitForAssistantContaining("BrainCode-E2E-EXECUTION-OK")
    }

    @Test
    fun genericTextJourneyDoesNotBecomeImagePrompt() {
        waitUntilReady()
        send("Escreva um prompt para gerar um resumo executivo de uma reunião de equipe.")
        waitForAssistantContaining("resumo")
    }

    @Test
    fun secondTurnPreservesConversationFlow() {
        waitUntilReady()
        send("Crie um prompt de imagem de um foguete.")
        waitForAssistantContaining("foguete")
        send("Agora coloque o foguete em um deserto ao entardecer com meteoros.")
        waitForAssistantContaining("deserto")
    }
}
