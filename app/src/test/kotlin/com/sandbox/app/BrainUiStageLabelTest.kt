package com.sandbox.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BrainUiStageLabelTest {
    @Test
    fun exposesEveryOperationalStageWithoutCollapsingToGenericResponse() {
        assertEquals("PLANEJANDO", brainStageLabel(BrainUiStage.PLANEJANDO))
        assertEquals("EXECUTANDO", brainStageLabel(BrainUiStage.EXECUTANDO))
        assertEquals("VERIFICANDO", brainStageLabel(BrainUiStage.VERIFICANDO))
        assertEquals("CRITICANDO", brainStageLabel(BrainUiStage.CRITICANDO))
        assertEquals("REVISE", brainStageLabel(BrainUiStage.REVISE))
        assertEquals("CORRIGINDO", brainStageLabel(BrainUiStage.CORRIGINDO))
        assertEquals("REEXECUTANDO", brainStageLabel(BrainUiStage.REEXECUTANDO))
        assertEquals("READY", brainStageLabel(BrainUiStage.READY))
    }

    @Test
    fun exposesTerminalOutcomes() {
        assertEquals("PASS", brainStageLabel(BrainUiStage.PASS))
        assertEquals("BLOCKED", brainStageLabel(BrainUiStage.BLOCKED))
        assertEquals("FAILED", brainStageLabel(BrainUiStage.FAILED))
    }
}
