package com.sandbox.runtime

import java.io.File

/** Resultado do preflight de namespaces do kernel do dispositivo. */
data class NamespaceSupport(
    val userNamespacesAvailable: Boolean,
    val maxUserNamespaces: Long?,
    val unprivilegedUsernsClone: Int?,
    val reason: String
) {
    val compatibilityMode: Boolean get() = !userNamespacesAvailable

    companion object {
        fun detect(procRoot: File = File("/proc")): NamespaceSupport {
            val max = readLong(File(procRoot, "sys/user/max_user_namespaces"))
            val clone = readLong(File(procRoot, "sys/kernel/unprivileged_userns_clone"))?.toInt()
            return when {
                max != null && max <= 0L -> NamespaceSupport(false, max, clone, "max_user_namespaces=$max")
                clone == 0 -> NamespaceSupport(false, max, clone, "unprivileged_userns_clone=0")
                max == null -> NamespaceSupport(false, null, clone, "max_user_namespaces ausente")
                else -> NamespaceSupport(true, max, clone, "user namespaces disponíveis")
            }
        }

        private fun readLong(file: File): Long? = runCatching {
            if (!file.isFile) return null
            file.readText().trim().toLong()
        }.getOrNull()
    }
}
