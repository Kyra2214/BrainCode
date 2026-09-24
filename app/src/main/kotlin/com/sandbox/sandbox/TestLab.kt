package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog

data class TestLabStep(val name: String, val command: List<String>, val timeoutSeconds: Long = 300)
data class TestLabStepResult(val step: TestLabStep, val execution: ExecutionLog)
data class TestLabReport(val projectPath: String, val startedAt: Long, val finishedAt: Long, val steps: List<TestLabStepResult>) {
    val passed: Int get() = steps.count { it.execution.succeeded }
    val failed: Int get() = steps.size - passed
    val success: Boolean get() = steps.isNotEmpty() && failed == 0
    val warnings: List<String> get() = steps.filter { it.execution.stderr.isNotBlank() && it.execution.succeeded }.map { "${it.step.name}: ${it.execution.stderr.take(500)}" }
}

class TestLab(private val executor: SandboxCommandExecutor) {
    fun run(projectPath: String, steps: List<TestLabStep> = defaultSteps()): TestLabReport {
        val started = System.currentTimeMillis()
        val results = mutableListOf<TestLabStepResult>()
        for (step in steps) {
            val execution = executor.execute(step.command, step.timeoutSeconds, projectPath)
            results += TestLabStepResult(step, execution)
            if (!execution.succeeded) break
        }
        return TestLabReport(projectPath, started, System.currentTimeMillis(), results)
    }
    fun defaultSteps(): List<TestLabStep> = listOf(
        TestLabStep("dependências", listOf("bash", "-c", "if [ -f package.json ]; then npm install --no-audit --no-fund; elif [ -f requirements.txt ]; then python3 -m pip install -r requirements.txt; elif [ -f Cargo.toml ]; then cargo fetch; else true; fi")),
        TestLabStep("build", listOf("bash", "-c", "if [ -f package.json ]; then npm run build --if-present; elif [ -f Makefile ]; then make; elif [ -f Cargo.toml ]; then cargo build; elif [ -f go.mod ]; then go build ./...; else true; fi")),
        TestLabStep("testes", listOf("bash", "-c", "if [ -f package.json ]; then npm test --if-present; elif [ -f Cargo.toml ]; then cargo test; elif [ -f go.mod ]; then go test ./...; else true; fi")),
        TestLabStep("lint", listOf("bash", "-c", "if [ -f package.json ]; then npm run lint --if-present; else true; fi"))
    )
}
