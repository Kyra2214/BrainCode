package com.sandbox.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

/** Bootstrap separado do APK para o banco inicial de prompts distribuído no Release. */
class PromptDatabaseInstaller(
    private val context: Context,
    private val stateDir: File,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    data class Manifest(
        val version: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String
    )

    sealed interface InstallResult {
        data class Success(val version: String, val file: File, val promptRows: Long) : InstallResult
        data class Failure(val reason: String, val retainedValidDatabase: Boolean) : InstallResult
    }

    fun ensureInstalled(manifest: Manifest = DEFAULT_MANIFEST): InstallResult {
        val directory = File(stateDir, "prompt-db")
        directory.mkdirs()
        val target = File(directory, ARTIFACT_NAME)
        val metadata = File(directory, "version.properties")
        val installedVersion = loadVersion(metadata)
        if (installedVersion == manifest.version && validate(target, manifest) != null) {
            return success(target, manifest.version, metadata)
        }

        var lastFailure = "falha desconhecida"
        repeat(MAX_RETRIES) { attempt ->
            val part = File(directory, "$ARTIFACT_NAME.part.$attempt")
            runCatching {
                download(manifest, part)
                require(validate(part, manifest) != null) { "Prompt DB inválido após download" }
                if (target.exists() && validate(target, manifest) == null) target.delete()
                require(part.renameTo(target)) { "não foi possível instalar o Prompt DB" }
                writeVersion(metadata, manifest.version)
                return success(target, manifest.version, metadata)
            }.onFailure { lastFailure = it.message ?: "erro de download" }
            part.delete()
        }
        return InstallResult.Failure(
            reason = "Prompt DB obrigatório não pôde ser validado: $lastFailure",
            retainedValidDatabase = validate(target, manifest) != null
        )
    }

    private fun download(manifest: Manifest, part: File) {
        val url = URL(manifest.url)
        require(url.protocol.equals("https", ignoreCase = true)) { "Prompt DB exige HTTPS" }
        val connection = connectionFactory(url)
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            val status = connection.responseCode
            require(status in 200..299) { "HTTP $status ao baixar Prompt DB" }
            val length = connection.contentLengthLong
            require(length <= 0L || length == manifest.sizeBytes) { "tamanho HTTP inesperado: $length" }
            part.outputStream().use { output ->
                connection.inputStream.use { input -> input.copyTo(output, BUFFER_SIZE) }
            }
            require(part.length() == manifest.sizeBytes) { "tamanho baixado inesperado: ${part.length()}" }
        } finally {
            connection.disconnect()
        }
    }

    private fun validate(file: File, manifest: Manifest): Long? {
        if (!file.isFile || file.length() != manifest.sizeBytes) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) if (read > 0) digest.update(buffer, 0, read)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(manifest.sha256, ignoreCase = true)) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                require(db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" })
                db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use {
                    require(it.moveToFirst() && it.getLong(0) == EXPECTED_TABLES)
                }
                db.rawQuery("SELECT SUM(rows) FROM (SELECT COUNT(*) AS rows FROM prompts UNION ALL SELECT COUNT(*) FROM extra_prompts)", null).use {
                    // A lightweight schema check; the complete library is queried through the signed SQL source.
                    require(it.moveToFirst())
                }
                manifest.sizeBytes
            }
        }.getOrNull()
    }

    private fun success(file: File, version: String, metadata: File): InstallResult {
        val rows = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT SUM(n) FROM (SELECT COUNT(*) AS n FROM prompts UNION ALL SELECT COUNT(*) FROM extra_prompts)", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            }
        }.getOrDefault(0L)
        writeVersion(metadata, version)
        return InstallResult.Success(version, file, rows)
    }

    private fun loadVersion(file: File): String? = runCatching {
        Properties().also { file.inputStream().use(it::load) }.getProperty("version")
    }.getOrNull()

    private fun writeVersion(file: File, version: String) {
        Properties().apply { setProperty("version", version) }.store(file.outputStream(), "BrainCode Prompt DB")
    }

    companion object {
        const val ARTIFACT_NAME = "braincode-prompts-initial.sqlite"
        const val RELEASE_TAG = "braincode-prompts-initial-v1.0.0"
        const val EXPECTED_TABLES = 24L
        private const val MAX_RETRIES = 3
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        val DEFAULT_MANIFEST = Manifest(
            version = "1.0.0",
            url = "https://github.com/Kyra2214/BrainCode/releases/download/$RELEASE_TAG/$ARTIFACT_NAME",
            sizeBytes = 14_630_912L,
            sha256 = "c1ec9bb9bbf60e12cb0c2f40768cc09cdace8735d571f5cb40d9f45aa1eb9ca1"
        )
    }
}
