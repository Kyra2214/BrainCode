package com.sandbox.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSnapshotStoreTest {
    private fun component(id: String, state: InstallationState = InstallationState.INSTALLED) =
        InstalledComponent(id, "1.0", 10L, emptyList(), state)

    @Test
    fun `snapshots sao versionados e persistem componentes`() {
        val dir = Files.createTempDirectory("plugin-snapshots").toFile()
        val snapshotFile = File(dir, "snapshots.json")
        val historyFile = File(dir, "history.jsonl")
        val first = PluginSnapshotStore(snapshotFile, historyFile)
        val snapshot = first.create("antes", listOf(component("alpha")))
        val reopened = PluginSnapshotStore(snapshotFile, historyFile)

        assertEquals(1L, snapshot.version)
        assertEquals(listOf("alpha"), reopened.get(1L)?.components?.map { it.componentId })
        assertEquals(2L, reopened.create("depois", emptyList()).version)
    }

    @Test
    fun `manager cria snapshot antes da instalacao e rollback restaura estado`() {
        val dir = Files.createTempDirectory("plugin-manager").toFile()
        val repository = JsonComponentRepository(File(dir, "components.json"))
        val store = PluginSnapshotStore(File(dir, "snapshots.json"), File(dir, "history.jsonl"))
        val catalog = listOf(SandboxComponent("tool", "Tool", "Tool", ComponentKind.PLUGIN, packages = listOf("tool"), validationCommand = listOf("tool")))
        val manager = PluginManager(FakeExecutor(), repository, catalog, snapshotStore = store)

        assertEquals(InstallationState.INSTALLED, manager.install("tool").state)
        assertTrue(manager.snapshots().isNotEmpty())
        assertTrue(manager.history().any { it.operation == "install" && it.success })
        manager.rollback(manager.snapshots().first().version)
        assertEquals(null, repository.get("tool"))
        assertTrue(manager.history().any { it.operation == "rollback" && it.success })
    }
}
