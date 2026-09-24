package com.sandbox.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonComponentRepositoryTest {

    @Test fun savePersistsAllExpandedFields() {
        val file = tempComponentsFile()
        val repo = JsonComponentRepository(file)
        val component = InstalledComponent(
            componentId = "python-dev",
            version = "3.11",
            installedAt = 1_694_348_400_000L,
            dependencies = emptyList(),
            state = InstallationState.INSTALLED,
            updatedAt = 1_694_348_500_000L,
            downloadedBytes = 187_654_321L,
            totalBytes = 187_654_321L,
            installDurationMs = 135_000L,
            installedByUser = "user",
            packageVersion = "3.11.5-1ubuntu0.1",
            validationStatus = true,
            notes = "Venv automático configurado"
        )
        repo.save(component)

        val reloaded = JsonComponentRepository(file).get("python-dev")
        assertEquals(component, reloaded)
    }

    @Test fun allReturnsEmptyListWhenFileMissing() {
        val file = tempComponentsFile()
        file.delete()
        assertTrue(JsonComponentRepository(file).all().isEmpty())
    }

    @Test fun removeDeletesOnlyMatchingComponent() {
        val file = tempComponentsFile()
        val repo = JsonComponentRepository(file)
        repo.save(InstalledComponent("python-dev", "3.11", 1L, emptyList(), InstallationState.INSTALLED))
        repo.save(InstalledComponent("nodejs-dev", "20", 2L, emptyList(), InstallationState.INSTALLED))

        repo.remove("python-dev")

        assertNull(repo.get("python-dev"))
        assertEquals(1, repo.all().size)
        assertEquals("nodejs-dev", repo.all().first().componentId)
    }

    @Test fun saveOverwritesPreviousStateForSameComponent() {
        val file = tempComponentsFile()
        val repo = JsonComponentRepository(file)
        repo.save(InstalledComponent("java-gradle", null, 1L, emptyList(), InstallationState.INSTALLING))
        repo.save(InstalledComponent("java-gradle", "17", 2L, emptyList(), InstallationState.INSTALLED))

        val result = repo.get("java-gradle")
        assertEquals(InstallationState.INSTALLED, result?.state)
        assertEquals("17", result?.version)
        assertEquals(1, repo.all().size)
    }

    @Test fun handlesStringsThatNeedJsonEscaping() {
        val file = tempComponentsFile()
        val repo = JsonComponentRepository(file)
        repo.save(
            InstalledComponent(
                "cpp-dev", null, 1L, emptyList(), InstallationState.FAILED,
                error = "linha 1\nlinha 2 com \"aspas\" e \\ barra",
                notes = "nota com \t tab"
            )
        )

        val reloaded = JsonComponentRepository(file).get("cpp-dev")
        assertEquals("linha 1\nlinha 2 com \"aspas\" e \\ barra", reloaded?.error)
        assertEquals("nota com \t tab", reloaded?.notes)
    }

    @Test fun migratesFromLegacyTsvOnFirstRead() {
        val tsv = tempTsvFile()
        FileComponentRepository(tsv).save(
            InstalledComponent("rust", "1.80", 1_000L, listOf("cpp-dev"), InstallationState.INSTALLED, error = null)
        )
        val jsonFile = tempComponentsFile()
        jsonFile.delete() // repositório JSON ainda não existe -> deve migrar

        val migrated = JsonComponentRepository(jsonFile, legacyTsvFile = tsv).get("rust")

        assertEquals("1.80", migrated?.version)
        assertEquals(InstallationState.INSTALLED, migrated?.state)
        assertTrue(jsonFile.isFile)
    }

    @Test fun doesNotMigrateWhenJsonFileAlreadyExists() {
        val tsv = tempTsvFile()
        FileComponentRepository(tsv).save(
            InstalledComponent("rust", "1.80", 1_000L, emptyList(), InstallationState.INSTALLED)
        )
        val jsonFile = tempComponentsFile()
        JsonComponentRepository(jsonFile).save(
            InstalledComponent("go", "1.22", 2_000L, emptyList(), InstallationState.INSTALLED)
        )

        val repo = JsonComponentRepository(jsonFile, legacyTsvFile = tsv)

        assertNull(repo.get("rust"))
        assertEquals("go", repo.all().single().componentId)
    }

    @Test fun recoversInterruptedOperationsAsRetryableFailures() {
        val file = tempComponentsFile()
        JsonComponentRepository(file).save(
            InstalledComponent("java-gradle", null, 1L, emptyList(), InstallationState.INSTALLING)
        )

        val recovered = JsonComponentRepository(file).get("java-gradle")

        assertEquals(InstallationState.FAILED, recovered?.state)
        assertTrue(recovered?.error.orEmpty().contains("interrompida"))
    }
}
