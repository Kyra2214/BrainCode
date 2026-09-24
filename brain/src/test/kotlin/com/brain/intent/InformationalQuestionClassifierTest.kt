package com.brain.intent

import com.brain.text.InformationalQuestionClassifier
import org.junit.Assert.assertTrue
import org.junit.Test

class InformationalQuestionClassifierTest {
    @Test
    fun `classifica fala sobre e perguntas tradicionais de forma igual`() {
        assertTrue(InformationalQuestionClassifier.isRecoverable("fale sobre Flamengo"))
        assertTrue(InformationalQuestionClassifier.isRecoverable("o que é IPTV?"))
        assertTrue(InformationalQuestionClassifier.isRecoverable("me explique como funciona IPTV"))
    }
}
