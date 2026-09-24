package com.sandbox.sandbox

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Persistência local de transações/cache de toolchains. Sem rede e sem servidor. */
class ToolchainTransactionStore(private val directory: File) {
    init { directory.mkdirs() }

    data class Snapshot(
        val profileId: String,
        val state: ToolchainState,
        val versionOutput: String,
        /** Pacotes que já estavam instalados, no formato pkg=versão. */
        val installedPackages: List<String>,
        val transactionId: String,
        val createdAt: Long
    )

    fun saveBeforeInstall(
        profile: ToolchainProfile,
        previous: ToolchainStatus,
        installedPackagesBefore: List<String>
    ): Snapshot {
        val snapshot = Snapshot(
            profile.id,
            previous.state,
            previous.versionOutput,
            installedPackagesBefore.distinct().sorted(),
            transactionId(profile.id, previous.updatedAt),
            System.currentTimeMillis()
        )
        write(snapshotFile(profile.id), encode(snapshot))
        return snapshot
    }

    /** Compatibilidade: snapshot vazio significa que nenhum pacote pré-existente foi observado. */
    fun saveBeforeInstall(profile: ToolchainProfile, previous: ToolchainStatus): Snapshot =
        saveBeforeInstall(profile, previous, emptyList())

    fun loadSnapshot(profileId: String): Snapshot? = read(snapshotFile(profileId))?.let(::decode)
    fun clearSnapshot(profileId: String) { snapshotFile(profileId).delete() }

    fun cache(status: ToolchainStatus) {
        write(cacheFile(status.profileId), listOf(status.state.name, status.versionOutput, status.error.orEmpty(), status.updatedAt.toString(), status.installedBytes.toString()).joinToString("\n"))
    }

    fun cached(profileId: String): ToolchainStatus? {
        val values = read(cacheFile(profileId))?.lines() ?: return null
        if (values.size < 4) return null
        return runCatching {
            ToolchainStatus(profileId, ToolchainState.valueOf(values[0]), values[1], values[2].ifBlank { null }, values.getOrNull(4)?.toLongOrNull() ?: 0L, values[3].toLong())
        }.getOrNull()
    }

    private fun snapshotFile(id: String) = File(directory, "$id.transaction")
    private fun cacheFile(id: String) = File(directory, "$id.cache")

    private fun transactionId(id: String, timestamp: Long): String {
        val bytes = "$id:$timestamp".toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun encode(snapshot: Snapshot): String = buildString {
        appendLine(snapshot.profileId)
        appendLine(snapshot.state.name)
        appendLine(snapshot.versionOutput.replace("\n", " ").take(4096))
        appendLine(snapshot.installedPackages.joinToString(" "))
        appendLine(snapshot.transactionId)
        appendLine(snapshot.createdAt)
    }

    private fun decode(text: String): Snapshot? {
        val v = text.lines()
        if (v.size < 6) return null
        return runCatching {
            Snapshot(v[0], ToolchainState.valueOf(v[1]), v[2], v[3].split(' ').filter(String::isNotBlank), v[4], v[5].toLong())
        }.getOrNull()
    }

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(content, Charsets.UTF_8)
        check(temp.renameTo(file)) { "Não foi possível confirmar cache/transação: ${file.name}" }
    }

    private fun read(file: File): String? = file.takeIf(File::isFile)?.readText(Charsets.UTF_8)
}
