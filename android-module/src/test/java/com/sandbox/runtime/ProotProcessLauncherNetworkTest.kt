package com.sandbox.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotProcessLauncherNetworkTest {
    private fun launcher(unshare: String? = "/system/bin/unshare", userNamespacesAvailable: Boolean = true): ProotProcessLauncher {
        val rootfs = createTempDir(prefix = "rootfs-")
        val proot = File.createTempFile("proot-", ".bin").apply { setExecutable(true) }
        val tmp = createTempDir(prefix = "tmp-")
        return ProotProcessLauncher(
            prootExecutable = proot.absolutePath,
            rootfsDir = rootfs,
            tmpDir = tmp,
            executableFinder = { candidates ->
                if (candidates.contains("/system/bin/unshare")) unshare else "/system/bin/setsid"
            },
            namespaceSupportProvider = { NamespaceSupport(userNamespacesAvailable, null, null, "teste") }
        )
    }

    @Test
    fun `rede permitida nao adiciona unshare nem n`() {
        val launcher = launcher()
        val args = launcher.buildArgs(listOf("echo", "ok"), "/tmp", "/system/bin/setsid", null)

        assertFalse(args.contains("/system/bin/unshare"))
        assertFalse(args.contains("-n"))
    }

    @Test
    fun `rede proibida adiciona unshare n e separador na ordem quando disponivel`() {
        val launcher = launcher()
        val args = launcher.buildArgs(listOf("echo", "ok"), "/tmp", "/system/bin/setsid", "/system/bin/unshare")

        assertEquals("/system/bin/setsid", args[0])
        assertEquals("/system/bin/unshare", args[1])
        assertEquals("-n", args[2])
        assertEquals("--", args[3])
        assertTrue(args[4].endsWith(".bin"))
    }

    @Test
    fun `rede proibida sem unshare continua compativel e inicia sem isolamento de rede`() {
        val launcher = launcher(unshare = null)
        val args = launcher.buildArgs(listOf("echo", "ok"), "/tmp", "/system/bin/setsid", null)

        assertFalse(args.contains("/system/bin/unshare"))
        assertFalse(args.contains("-n"))
        assertTrue(args.any { it.endsWith(".bin") })
    }

    @Test
    fun `modo compatibilidade nao tenta unshare mesmo com binario presente`() {
        // Regressão: em kernels sem user namespaces (ex.: max_user_namespaces
        // ausente), o binário unshare existe mas falha com "Operation not
        // permitted" ao tentar CLONE_NEWNET. Isso não pode derrubar a cadeia
        // Brain → Policy → Sandbox inteira.
        val launcher = launcher(unshare = "/system/bin/unshare", userNamespacesAvailable = false)

        assertEquals(null, launcher.resolveUnshare(networkAllowed = false))
    }

    @Test
    fun `namespaces disponiveis ainda tenta unshare quando rede proibida`() {
        val launcher = launcher(unshare = "/system/bin/unshare", userNamespacesAvailable = true)

        assertEquals("/system/bin/unshare", launcher.resolveUnshare(networkAllowed = false))
    }
}
