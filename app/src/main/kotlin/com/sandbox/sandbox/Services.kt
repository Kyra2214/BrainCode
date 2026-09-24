package com.sandbox.sandbox

import java.io.File

data class SandboxService(val id: String, val command: List<String>, val workingDir: String = "/home/sandbox", val port: Int? = null)
data class ServiceStatus(val service: SandboxService, val running: Boolean, val pid: Long? = null, val logFile: File? = null)

object BuiltInServices {
    fun fastApi(project: String) = SandboxService("fastapi", listOf("uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"), project, 8000)
    fun flask(project: String) = SandboxService("flask", listOf("flask", "run", "--host=0.0.0.0", "--port=5000"), project, 5000)
    fun node(project: String) = SandboxService("node", listOf("npm", "start"), project, 3000)
    fun vite(project: String) = SandboxService("vite", listOf("npm", "run", "dev", "--", "--host", "0.0.0.0"), project, 5173)
    fun sqlite(project: String) = SandboxService("sqlite", listOf("sqlite3", "sandbox.db"), project)
    fun postgres() = SandboxService("postgres", listOf("postgres", "-D", "/home/sandbox/workspace/postgres"), port = 5432)
    fun redis() = SandboxService("redis", listOf("redis-server", "--daemonize", "no"), port = 6379)
}

class ServiceManager(
    private val executor: SandboxCommandExecutor,
    private val stateDir: File,
    private val networkPolicy: NetworkPolicyBroker = NetworkPolicyBroker()
) {
    init { stateDir.mkdirs() }
    fun start(service: SandboxService, networkRequest: NetworkAccessRequest? = null): ServiceStatus {
        require(service.id.matches(Regex("[A-Za-z0-9._-]+"))) { "ID de serviço inválido" }
        if (service.port != null) {
            val request = checkNotNull(networkRequest) { "Serviço com porta exige autorização explícita de rede" }
            check(request.serviceId == service.id && request.port == service.port) { "Autorização não corresponde ao serviço" }
            check(networkPolicy.decide(request).allowed) { "Rede negada para ${service.id}: ${networkPolicy.decide(request).reason}" }
        } else {
            check(networkRequest == null) { "Autorização de rede não pode ser usada por serviço sem porta" }
        }
        val pidFile = File(stateDir, "${service.id}.pid")
        val logFile = File(stateDir, "${service.id}.log")
        if (status(service).running) return status(service)
        val command = service.command.joinToString(" ") { shellEscape(it) }
        val script = "nohup $command >${shellEscape(logFile.absolutePath)} 2>&1 & echo ${'$'}! > ${shellEscape(pidFile.absolutePath)}"
        val result = executor.execute(listOf("bash", "-c", script), 30, service.workingDir)
        check(result.succeeded) { "Falha ao iniciar ${service.id}: ${result.stderr}" }
        return status(service)
    }
    fun stop(service: SandboxService): ServiceStatus {
        val pid = readPid(service) ?: return status(service)
        executor.execute(listOf("kill", pid.toString()), 30, service.workingDir)
        File(stateDir, "${service.id}.pid").delete()
        return status(service)
    }
    fun restart(service: SandboxService, networkRequest: NetworkAccessRequest? = null): ServiceStatus {
        stop(service)
        return start(service, networkRequest)
    }
    fun status(service: SandboxService): ServiceStatus {
        val pid = readPid(service)
        val running = pid != null && executor.execute(listOf("bash", "-c", "kill -0 $pid"), 10, service.workingDir).succeeded
        return ServiceStatus(service, running, if (running) pid else null, File(stateDir, "${service.id}.log").takeIf(File::exists))
    }
    fun logs(service: SandboxService, maxChars: Int = 64 * 1024): String = File(stateDir, "${service.id}.log").takeIf(File::isFile)?.readText()?.takeLast(maxChars) ?: ""
    fun knownServices(): List<String> = stateDir.listFiles()?.filter { it.extension == "pid" }?.map { it.nameWithoutExtension } ?: emptyList()
    private fun readPid(service: SandboxService): Long? = File(stateDir, "${service.id}.pid").takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()
    private fun shellEscape(value: String): String = if (value.matches(Regex("^[A-Za-z0-9_./:=+-]+$"))) value else "'${value.replace("'", "'\\''")}'"
}
