package com.brain.delivery

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Empacotamento local determinístico; não conhece rede, provider ou credenciais. */
object LocalDeliveryPackager {
    fun packageRoot(root: File, output: File): File {
        require(root.isDirectory) { "raiz de entrega não é um diretório: ${root.path}" }
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            root.walkTopDown()
                .filter { it.isFile && it != output }
                .forEach { file ->
                    val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(relative))
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return output
    }
}
