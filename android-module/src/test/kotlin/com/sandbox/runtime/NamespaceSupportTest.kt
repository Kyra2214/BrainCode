package com.sandbox.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NamespaceSupportTest {
    @Test
    fun reportsCompatibilityModeWhenKernelLimitIsZero() {
        val proc = Files.createTempDirectory("proc-").toFile()
        try {
            java.io.File(proc, "sys/user").mkdirs()
            java.io.File(proc, "sys/user/max_user_namespaces").writeText("0\n")
            assertFalse(NamespaceSupport.detect(proc).userNamespacesAvailable)
            assertTrue(NamespaceSupport.detect(proc).compatibilityMode)
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun reportsAvailableWhenPositiveLimitAndClonePolicyAllowIt() {
        val proc = Files.createTempDirectory("proc-").toFile()
        try {
            java.io.File(proc, "sys/user").mkdirs()
            java.io.File(proc, "sys/kernel").mkdirs()
            java.io.File(proc, "sys/user/max_user_namespaces").writeText("128\n")
            java.io.File(proc, "sys/kernel/unprivileged_userns_clone").writeText("1\n")
            assertTrue(NamespaceSupport.detect(proc).userNamespacesAvailable)
        } finally {
            proc.deleteRecursively()
        }
    }
}
