package com.sandbox.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchablePluginManagerTest {

    private val catalog = listOf(
        SandboxComponent("python-dev", "Python Dev", "Python, pip, venv", ComponentKind.PLUGIN),
        SandboxComponent("nodejs-dev", "Node.js Dev", "Node.js e npm para web", ComponentKind.PLUGIN, packages = listOf("nodejs")),
        SandboxComponent("android-build", "Android Build", "Ferramentas base para builds Android", ComponentKind.PLUGIN, dependencies = listOf("java-gradle")),
        SandboxComponent("ripgrep", "ripgrep", "Ferramenta de busca rápida", ComponentKind.TOOL)
    )

    private fun manager(repo: ComponentRepository = JsonComponentRepository(tempComponentsFile())) =
        SearchablePluginManager(FakeExecutor(), repo, catalog)

    @Test fun searchFiltersPluginsCorrectlyByName() {
        val results = manager().search("node")
        assertEquals(listOf("nodejs-dev"), results.map { it.id })
    }

    @Test fun searchFiltersPluginsCorrectlyByDescription() {
        val results = manager().search("web")
        assertEquals(listOf("nodejs-dev"), results.map { it.id })
    }

    @Test fun searchIsCaseInsensitive() {
        val results = manager().search("PYTHON")
        assertEquals(listOf("python-dev"), results.map { it.id })
    }

    @Test fun blankQueryReturnsEverything() {
        assertEquals(catalog.size, manager().search("   ").size)
    }

    @Test fun filterByKindShowsOnlyMatches() {
        val tools = manager().filterByKind(ComponentKind.TOOL)
        assertEquals(listOf("ripgrep"), tools.map { it.id })
    }

    @Test fun filterByDependenciesSeparatesComponentsWithDeps() {
        val withDeps = manager().filterByDependencies(true)
        val withoutDeps = manager().filterByDependencies(false)
        assertEquals(listOf("android-build"), withDeps.map { it.id })
        assertTrue("python-dev" in withoutDeps.map { it.id })
        assertFalse("android-build" in withoutDeps.map { it.id })
    }

    @Test fun filterByInstalledUsesRepositoryState() {
        val repo = JsonComponentRepository(tempComponentsFile())
        repo.save(InstalledComponent("python-dev", "3.11", 1L, emptyList(), InstallationState.INSTALLED))
        val mgr = manager(repo)

        val installed = mgr.filterByInstalled(true)
        val notInstalled = mgr.filterByInstalled(false)

        assertEquals(listOf("python-dev"), installed.map { it.id })
        assertFalse("python-dev" in notInstalled.map { it.id })
        assertTrue("nodejs-dev" in notInstalled.map { it.id })
    }

    @Test fun filterByInstalledIgnoresNonInstalledStates() {
        val repo = JsonComponentRepository(tempComponentsFile())
        repo.save(InstalledComponent("python-dev", null, 1L, emptyList(), InstallationState.FAILED))
        val mgr = manager(repo)

        assertFalse("python-dev" in mgr.filterByInstalled(true).map { it.id })
    }

    @Test fun queryCombinesSearchKindAndInstalledFilters() {
        val repo = JsonComponentRepository(tempComponentsFile())
        repo.save(InstalledComponent("nodejs-dev", "20", 1L, emptyList(), InstallationState.INSTALLED))
        val mgr = manager(repo)

        val result = mgr.query(text = "dev", kind = ComponentKind.PLUGIN, installedOnly = true)

        assertEquals(listOf("nodejs-dev"), result.map { it.id })
    }

    @Test fun sortingByNameOrdersAlphabetically() {
        val sorted = manager().search("").sortedBy { it.name.lowercase() }
        assertEquals(listOf("Android Build", "Node.js Dev", "Python Dev", "ripgrep"), sorted.map { it.name })
    }

    @Test fun installDelegatesToUnderlyingManagerAndPersists() {
        val repo = JsonComponentRepository(tempComponentsFile())
        val mgr = SearchablePluginManager(FakeExecutor(succeed = true), repo, catalog)

        val result = mgr.install("nodejs-dev")

        assertEquals(InstallationState.INSTALLED, result.state)
        assertEquals(InstallationState.INSTALLED, repo.get("nodejs-dev")?.state)
    }

    @Test fun installRecordsFailureFromExecutor() {
        val repo = JsonComponentRepository(tempComponentsFile())
        val mgr = SearchablePluginManager(FakeExecutor(succeed = false), repo, catalog)

        val result = mgr.install("nodejs-dev")

        assertEquals(InstallationState.FAILED, result.state)
        assertFalse(result.validationStatus)
        assertEquals("erro simulado", result.error)
    }

    @Test fun acceptedRemoteComponentsBecomeSearchableAndInstallable() {
        val bytes = "remote-artifact".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val remote = RemoteComponentManifest(
            component = SandboxComponent("remote-tool", "Remote Tool", "Ferramenta remota", ComponentKind.TOOL),
            sourceId = "trusted",
            manifestUrl = "https://93.184.216.34/catalog.json",
            artifactSha256 = digest,
            artifactBytes = bytes,
            officialSource = true
        )
        val remoteCatalog = RemotePluginCatalog(setOf("trusted"))
        remoteCatalog.importSnapshot(
            RemoteCatalogSnapshot("trusted", "https://93.184.216.34/catalog.json", listOf(remote), 1L)
        )
        val repo = JsonComponentRepository(tempComponentsFile())
        val mgr = SearchablePluginManager(
            FakeExecutor(succeed = true),
            repo,
            catalog,
            catalogProvider = { catalog + remoteCatalog.components() }
        )

        assertEquals(listOf("remote-tool"), mgr.search("remote").map { it.id })
        assertEquals(InstallationState.INSTALLED, mgr.install("remote-tool").state)
    }
}
