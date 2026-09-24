package com.brain.runtime

/** Diagnóstico operacional separado de policy, roteamento e execução. */
data class RuntimeDiagnosis(
    val healthy: Boolean,
    val symptoms: List<String> = emptyList(),
    val recommendedRepair: String? = null
)

interface RuntimeDoctor {
    fun diagnose(runId: String, taskId: String, error: String?): RuntimeDiagnosis
    fun repair(runId: String, taskId: String, diagnosis: RuntimeDiagnosis): Boolean
}

/** Implementação segura para ambientes sem reparo automático configurado. */
object NoopRuntimeDoctor : RuntimeDoctor {
    override fun diagnose(runId: String, taskId: String, error: String?): RuntimeDiagnosis =
        RuntimeDiagnosis(healthy = error.isNullOrBlank(), symptoms = listOfNotNull(error))

    override fun repair(runId: String, taskId: String, diagnosis: RuntimeDiagnosis): Boolean = false
}
