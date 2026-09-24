package com.sandbox.runtime

import java.io.File

/**
 * Android-compatible proot launcher.
 *
 * The functional baseline follows SandBox: proot is started directly, with
 * /dev, /proc and /sys available inside the guest. Network namespace
 * isolation is opportunistic only; Android devices commonly do not expose
 * unshare(CLONE_NEWNET), and its absence must not prevent startup.
 */
class ProotProcessLauncher(
    private val prootExecutable: String,
    private val rootfsDir: File,
    private val tmpDir: File,
    private val disableSeccompAcceleration: Boolean = true,
    private val nativeLibraryDir: String? = null,
    private val prootLoader: String? = null,
    private val executableFinder: (List<String>) -> String? = { candidates -> candidates.firstOrNull { File(it).canExecute() } },
    private val namespaceSupportProvider: () -> NamespaceSupport = { NamespaceSupport.detect() },
    override val resourceLimits: ProotResourceLimits = ProotResourceLimits.DEFAULT
) : SandboxProcessLauncher {

    // unshare -n (CLONE_NEWNET) exige a mesma capacidade de namespace sem
    // privilégio que o proot precisa pro isolamento geral. Em modo de
    // compatibilidade (kernel sem user namespaces) o binário costuma
    // existir no disco mas falha com "Operation not permitted" em tempo de
    // execução — então nem tentamos, em vez de deixar isso derrubar a
    // cadeia inteira Brain → Policy → Sandbox.
    private val networkNamespaceSupported: Boolean by lazy { namespaceSupportProvider().userNamespacesAvailable }

    override val processGroupManaged: Boolean = findSetsid() != null

    init {
        require(File(prootExecutable).isFile) { "Binário proot não encontrado em $prootExecutable" }
        require(rootfsDir.isDirectory) { "Rootfs não encontrado em ${rootfsDir.path}" }
        tmpDir.mkdirs()
        listOf("dev", "proc", "sys", "tmp").forEach { File(rootfsDir, it).mkdirs() }
    }

    /** Functional/default path: network is available like the SandBox baseline. */
    override fun launch(command: List<String>, workingDir: String): Process =
        launch(command, workingDir, networkAllowed = true)

    override fun launch(command: List<String>, workingDir: String, networkAllowed: Boolean): Process {
        val setsid = findSetsid()
        val unshare = resolveUnshare(networkAllowed)
        val args = buildArgs(command, workingDir, setsid, unshare)
        return ProcessBuilder(args).redirectErrorStream(false).apply {
            environment().clear()
            environment()["LD_LIBRARY_PATH"] = nativeLibraryDir ?: File(prootExecutable).parentFile?.absolutePath.orEmpty()
            environment()["PATH"] = "/home/sandbox/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            environment()["HOME"] = workingDir
            environment()["USER"] = "sandbox"
            environment()["TERM"] = "xterm-256color"
            environment()["LANG"] = "C.UTF-8"
            environment()["TMPDIR"] = "/tmp"
            environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
            prootLoader?.let { environment()["PROOT_LOADER"] = it }
            if (disableSeccompAcceleration) environment()["PROOT_NO_SECCOMP"] = "1"
        }.start()
    }

    internal fun buildArgs(command: List<String>, workingDir: String, setsid: String?, unshare: String?): List<String> = buildList {
        setsid?.let { add(it) }
        unshare?.let { add(it); add("-n"); add("--") }
        add(prootExecutable)
        add("-r"); add(rootfsDir.absolutePath)
        add("-w"); add(workingDir)
        add("-0")
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("--link2symlink")
        add("--kill-on-exit")
        add("/bin/bash"); add("-c")
        add(resourceLimits.verifiedPreamble() + "exec " + command.joinToString(" ") { shellEscape(it) })
    }

    // Network isolation is best-effort on Android. If unshare exists AND the
    // kernel actually supports unprivileged namespaces we honor
    // networkAllowed=false; otherwise keep the runtime usable instead of
    // failing on unshare's "Operation not permitted".
    internal fun resolveUnshare(networkAllowed: Boolean): String? =
        if (networkAllowed || !networkNamespaceSupported) null else findUnshare()

    private fun findSetsid(): String? = executableFinder(listOf("/system/bin/setsid", "/usr/bin/setsid", "/bin/setsid"))

    private fun findUnshare(): String? = executableFinder(listOf("/system/bin/unshare", "/usr/bin/unshare", "/bin/unshare"))

    private fun shellEscape(arg: String): String = if (arg.matches(Regex("^[A-Za-z0-9_\\-./=]+$"))) arg
    else "'" + arg.replace("'", "'\\''") + "'"
}
