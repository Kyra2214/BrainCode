package com.sandbox.resource

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Responsável por baixar, retomar, validar e manter o rootfs do sandbox.
 *
 * Este arquivo é independente de Android Framework de propósito (só usa
 * java.net/java.io/java.security) para poder ser testado em JVM pura,
 * sem precisar de emulador. A integração com Context/armazenamento do
 * Android entra como uma camada fina por cima (targetFile já resolvido
 * a partir de filesDir, por exemplo).
 *
 * Nunca baixa para dentro de assets/ nem do APK — sempre para armazenamento
 * privado do app (equivalente a filesDir/sandbox/rootfs/).
 */
class SandboxResourceManager(
    private val targetFile: File,
    private val rootfsSignatureVerifier: RootfsSignatureVerifier? = null,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val addressResolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
) {
    private companion object {
        const val MAX_REDIRECTS = 5
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Failure(val reason: String, val cause: Throwable? = null) : DownloadResult()
    }

    /**
     * Callback de progresso: bytes já baixados e total esperado.
     */
    fun interface ProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long)
    }

    /**
     * Garante que o recurso descrito pelo manifesto está disponível e válido
     * em [targetFile]. Se já existir e o hash conferir, não baixa de novo.
     * Se existir parcialmente, tenta retomar via header Range.
     */
    fun ensureAvailable(
        manifest: DownloadManifest,
        progressListener: ProgressListener? = null
    ): DownloadResult {
        if (targetFile.exists() && verifyArtifact(targetFile, manifest)) {
            return DownloadResult.Success(targetFile)
        }

        return try {
            downloadWithResume(manifest, progressListener)
            if (!verifyArtifact(targetFile, manifest)) {
                targetFile.delete()
                return DownloadResult.Failure(
                    "Hash SHA-256 ou assinatura não confere após download. Arquivo removido."
                )
            }
            DownloadResult.Success(targetFile)
        } catch (e: Exception) {
            DownloadResult.Failure("Falha no download: ${e.message}", e)
        }
    }

    private fun downloadWithResume(
        manifest: DownloadManifest,
        progressListener: ProgressListener?
    ) {
        val partialFile = File(targetFile.parentFile, "${targetFile.name}.part")
        var existingBytes = if (partialFile.exists()) partialFile.length() else 0L

        val connection = openFollowingHttpsRedirects(URL(manifest.url), existingBytes)

        val supportsResume = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!supportsResume) {
            // Servidor não suporta Range: começa do zero.
            existingBytes = 0L
            if (partialFile.exists()) partialFile.delete()
        }

        val totalBytes = manifest.sizeBytes
        val output = RandomAccessFile(partialFile, "rw")
        // Evita deixar bytes antigos no final quando uma retomada falha ou
        // quando o servidor responde 200 (arquivo completo) ao invés de 206.
        output.setLength(existingBytes)
        output.seek(existingBytes)

        connection.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalDownloaded = existingBytes
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalDownloaded += bytesRead
                progressListener?.onProgress(totalDownloaded, totalBytes)
            }
        }
        output.close()

        // File.renameTo() usa rename(2) cru: no Linux ele falha (retorna
        // false, sem detalhe do motivo) tanto quando origem e destino estão
        // em filesystems diferentes (EXDEV — comum quando targetFile fica
        // em armazenamento externo/adotável montado via FUSE) quanto, em
        // algumas implementações de JVM, quando o destino já existe. Files.move
        // com REPLACE_EXISTING cobre o caso de destino existente; a
        // tentativa ATOMIC_MOVE cobre o caminho comum (mesmo filesystem) sem
        // custo extra, e o fallback sem ATOMIC_MOVE deixa o NIO copiar +
        // apagar quando os arquivos estão em filesystems diferentes, algo
        // que rename(2)/renameTo nunca conseguem fazer.
        runCatching {
            java.nio.file.Files.move(
                partialFile.toPath(), targetFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        }.recoverCatching {
            java.nio.file.Files.move(
                partialFile.toPath(), targetFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            throw IllegalStateException("Não foi possível mover .part para o arquivo final: ${it.message}", it)
        }
    }

    private fun openFollowingHttpsRedirects(initialUrl: URL, existingBytes: Long): HttpURLConnection {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(url.protocol.equals("https", ignoreCase = true)) { "RootFS exige HTTPS" }
            require(url.userInfo == null && url.ref == null) {
                "destino RootFS inválido ou reservado"
            }
            val validatedAddress = resolveValidatedAddress(url.host)
            val pinnedUrl = URL(url.protocol, validatedAddress.hostAddress, url.port, url.file)
            val connection = connectionFactory(pinnedUrl).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Host", hostHeader(url))
                if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
            }
            if (connection is HttpsURLConnection) {
                connection.hostnameVerifier = HostnameVerifier { _, session ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, session)
                }
                connection.sslSocketFactory = SniSocketFactory(connection.sslSocketFactory, url.host)
            }
            connection.connect()
            val response = connection.responseCode
            if (response !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) { "redirect de RootFS sem Location" }
            require(redirectCount < MAX_REDIRECTS) { "limite de redirects do RootFS excedido" }
            val nextUrl = URL(url, location)
            require(nextUrl.protocol.equals("https", ignoreCase = true)) {
                "redirect de RootFS para HTTP bloqueado"
            }
            url = nextUrl
        }
        error("limite de redirects do RootFS excedido")
    }

    private fun verifySha256(file: File, expectedHash: String): Boolean {
        if (!file.exists()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        return actualHash.equals(expectedHash, ignoreCase = true)
    }

    private fun verifyArtifact(file: File, manifest: DownloadManifest): Boolean {
        if (!verifySha256(file, manifest.sha256)) return false
        val rootfs = manifest as? RootfsManifest ?: return true
        if (!rootfs.signatureRequired) return true
        return rootfsSignatureVerifier?.verify(file, rootfs) == true
    }

    private fun resolveValidatedAddress(host: String): InetAddress {
        if (host.isBlank() || host.equals("localhost", true) || host.endsWith(".localhost", true)) {
            error("destino RootFS inválido ou reservado")
        }
        val addresses = runCatching { addressResolver(host) }.getOrNull()
            ?: error("hostname RootFS não pôde ser resolvido")
        require(addresses.isNotEmpty() && addresses.none { address ->
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress || isMappedPrivate(address.address) || isUla(address.address)
        }) { "destino RootFS inválido ou reservado" }
        return addresses.first()
    }

    private fun hostHeader(url: URL): String = if (url.port == -1 || (url.protocol == "https" && url.port == 443)) url.host else "${url.host}:${url.port}"

    private fun isMappedPrivate(bytes: ByteArray): Boolean {
        if (bytes.size != 16 || !bytes.copyOfRange(0, 10).all { it == 0.toByte() } || bytes[10] != 0xff.toByte() || bytes[11] != 0xff.toByte()) return false
        val a = bytes[12].toInt() and 0xff
        val b = bytes[13].toInt() and 0xff
        return a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun isUla(bytes: ByteArray): Boolean =
        bytes.size == 16 && ((bytes[0].toInt() and 0xff) in 0xfc..0xfd)

    /**
     * Remove o rootfs baixado (usado para reset completo do sandbox).
     */
    fun purge() {
        if (targetFile.exists()) targetFile.delete()
        val partial = File(targetFile.parentFile, "${targetFile.name}.part")
        if (partial.exists()) partial.delete()
    }
}

private class SniSocketFactory(
    private val delegate: SSLSocketFactory,
    private val hostname: String
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(host: String, port: Int): java.net.Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localAddress: java.net.InetAddress, localPort: Int): java.net.Socket = configure(delegate.createSocket(host, port, localAddress, localPort))
    override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): java.net.Socket = configure(delegate.createSocket(address, port, localAddress, localPort))
    override fun createSocket(socket: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket = configure(delegate.createSocket(socket, host, port, autoClose))

    private fun configure(socket: java.net.Socket): java.net.Socket {
        if (socket is SSLSocket) {
            val parameters = socket.sslParameters
            parameters.serverNames = listOf(SNIHostName(hostname))
            socket.sslParameters = parameters
        }
        return socket
    }
}
