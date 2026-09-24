package com.sandbox.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Extrai o rootfs (.tar.gz gerado na Fase 0.1) para uma pasta destino.
 *
 * Usa Apache Commons Compress (Apache 2.0, dependência de biblioteca comum,
 * não é código copiado de nenhum produto proprietário) só porque o Android
 * não tem suporte nativo a TAR na biblioteca padrão.
 */
object TarGzExtractor {

    /**
     * Extrai [archive] para dentro de [destinationDir], preservando permissão
     * de execução quando o entry original tinha (essencial para binários
     * dentro do rootfs, como /bin/bash).
     *
     * Rejeita entradas com ".." no caminho (path traversal) — mesmo padrão
     * de validação usado no importador de workspace do projeto anterior.
     */
    fun extract(archive: File, destinationDir: File, progressListener: ((Long) -> Unit)? = null) {
        if (!destinationDir.exists()) destinationDir.mkdirs()

        var compressedBytesRead = 0L
        val source = object : java.io.FilterInputStream(archive.inputStream()) {
            override fun read(): Int = super.read().also { if (it >= 0) { compressedBytesRead++; progressListener?.invoke(compressedBytesRead) } }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it > 0) { compressedBytesRead += it; progressListener?.invoke(compressedBytesRead) }
            }
        }
        source.use { input -> GZIPInputStream(input).use { gzipStream ->
            TarArchiveInputStream(gzipStream).use { tarStream ->
                var entry: TarArchiveEntry? = tarStream.nextEntry
                while (entry != null) {
                    val safeEntry = entry
                    val outputFile = resolveSafePath(destinationDir, safeEntry.name)

                    when {
                        safeEntry.isDirectory -> outputFile.mkdirs()
                        safeEntry.isSymbolicLink -> {
                            // Um rootfs Linux usa links absolutos como
                            // /lib/... e /etc/.... Fora de um chroot, gravá-los
                            // literalmente apontaria para o Android host. Reescreva
                            // o destino para um link relativo que permaneça dentro
                            // da raiz extraída, preservando a semântica do guest.
                            outputFile.parentFile?.mkdirs()
                            val linkTarget = resolveSymlinkTarget(
                                destinationDir,
                                outputFile,
                                safeEntry.linkName
                            )
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    outputFile.toPath(),
                                    linkTarget
                                )
                            } catch (e: java.nio.file.FileAlreadyExistsException) {
                                // idempotente em re-extrações
                            }
                        }
                        else -> {
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { out ->
                                tarStream.copyTo(out)
                            }
                            if (safeEntry.mode and 0b111_000_000 != 0) {
                                outputFile.setExecutable(true, false)
                            }
                        }
                    }
                    entry = tarStream.nextEntry
                }
            }
        } }
    }

    private fun resolveSafePath(base: File, entryName: String): File {
        val basePath = base.toPath().toAbsolutePath().normalize()
        val target = basePath.resolve(entryName).normalize()
        require(target.startsWith(basePath)) {
            "Entrada de rootfs suspeita (path traversal): $entryName"
        }
        return target.toFile()
    }

    private fun resolveSymlinkTarget(
        base: File,
        linkFile: File,
        linkName: String
    ): Path {
        val basePath = base.toPath().toAbsolutePath().normalize()
        val linkPath = linkFile.toPath().toAbsolutePath().normalize()
        val guestTarget = if (linkName.startsWith('/')) {
            basePath.resolve(linkName.removePrefix("/"))
        } else {
            linkPath.parent.resolve(linkName)
        }.normalize()
        require(guestTarget.startsWith(basePath)) {
            "Symlink de rootfs suspeito (fora da raiz): $linkName"
        }
        // Path.relativize devolve um caminho vazio quando o alvo é o próprio
        // diretório pai. Files.createSymbolicLink não aceita esse caminho;
        // o RootFS usa exatamente esse padrão em /usr/bin/X11 -> . .
        return if (guestTarget == linkPath.parent) {
            java.nio.file.Paths.get(".")
        } else {
            linkPath.parent.relativize(guestTarget)
        }
    }
}
