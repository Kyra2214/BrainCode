package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog

class GitManager(private val executor: SandboxCommandExecutor) {
    fun clone(url: String, destination: String, timeoutSeconds: Long = 600): ExecutionLog = run(listOf("git", "clone", url, destination), "/home/sandbox", timeoutSeconds)
    fun pull(projectPath: String): ExecutionLog = run(listOf("git", "pull", "--ff-only"), projectPath)
    fun push(projectPath: String): ExecutionLog = run(listOf("git", "push"), projectPath)
    fun branch(projectPath: String, name: String? = null): ExecutionLog = run(if (name == null) listOf("git", "branch") else listOf("git", "branch", name), projectPath)
    fun checkout(projectPath: String, name: String): ExecutionLog = run(listOf("git", "checkout", name), projectPath)
    fun commit(projectPath: String, message: String): ExecutionLog = run(listOf("git", "add", "-A"), projectPath).let {
        if (!it.succeeded) it else run(listOf("git", "commit", "-m", message), projectPath)
    }
    fun diff(projectPath: String): ExecutionLog = run(listOf("git", "diff"), projectPath)
    fun status(projectPath: String): ExecutionLog = run(listOf("git", "status", "--short", "--branch"), projectPath)
    private fun run(command: List<String>, workingDir: String, timeoutSeconds: Long = 120): ExecutionLog = executor.execute(command, timeoutSeconds, workingDir)
}
