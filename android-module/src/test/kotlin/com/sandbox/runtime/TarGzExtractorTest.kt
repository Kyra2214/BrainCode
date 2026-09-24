package com.sandbox.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class TarGzExtractorTest {
    @Test
    fun rewritesAbsoluteSymlinkIntoRootfsRelativeLink() {
        val archive = createArchive(
            listOf(
                Entry("bin", null, "/usr/bin"),
                Entry("usr/bin", null, null, directory = true),
                Entry("usr/bin/sh", null, "dash"),
                Entry("usr/bin/dash", "#!/bin/sh\n", null)
            )
        )
        val destination = Files.createTempDirectory("sandbox-rootfs-").toFile()
        try {
            TarGzExtractor.extract(archive, destination)
            assertEquals("usr/bin", Files.readSymbolicLink(File(destination, "bin").toPath()).toString())
            assertEquals("dash", Files.readSymbolicLink(File(destination, "usr/bin/sh").toPath()).toString())
            assertEquals("#!/bin/sh\n", File(destination, "usr/bin/dash").readText())
            assertTrue(File(destination, "bin/sh").exists())
        } finally {
            archive.delete()
            destination.deleteRecursively()
        }
    }

    @Test
    fun preservesGuestSelfDirectorySymlinkUsedByUsrBinX11() {
        val archive = createArchive(
            listOf(
                Entry("usr/bin", null, null, directory = true),
                Entry("usr/bin/X11", null, ".")
            )
        )
        val destination = Files.createTempDirectory("sandbox-rootfs-x11-").toFile()
        try {
            TarGzExtractor.extract(archive, destination)
            val link = File(destination, "usr/bin/X11").toPath()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals(".", Files.readSymbolicLink(link).toString())
            assertTrue(File(destination, "usr/bin/X11").isDirectory)
        } finally {
            archive.delete()
            destination.deleteRecursively()
        }
    }

    private fun createArchive(entries: List<Entry>): File {
        val archive = Files.createTempFile("sandbox-fixture-", ".tar.gz").toFile()
        GZIPOutputStream(FileOutputStream(archive)).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                entries.forEach { item ->
                    val entry = if (item.directory) {
                        TarArchiveEntry(item.name, TarConstants.LF_DIR)
                    } else if (item.link != null) {
                        TarArchiveEntry(item.name, TarConstants.LF_SYMLINK)
                    } else {
                        TarArchiveEntry(item.name)
                    }
                    if (item.directory) {
                        entry.mode = 0b111_101_101
                        entry.size = 0
                    } else if (item.link != null) {
                        entry.linkName = item.link
                        entry.mode = 0b111_101_101
                    } else {
                        entry.mode = 0b110_100_100
                        entry.size = item.content!!.toByteArray().size.toLong()
                    }
                    tar.putArchiveEntry(entry)
                    if (!item.directory && item.link == null) {
                        tar.write(item.content!!.toByteArray())
                    }
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return archive
    }

    private data class Entry(
        val name: String,
        val content: String?,
        val link: String?,
        val directory: Boolean = false
    )
}
