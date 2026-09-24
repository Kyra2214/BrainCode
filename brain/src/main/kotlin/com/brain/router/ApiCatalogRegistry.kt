package com.brain.router

/**
 * Ponte única entre o catálogo descoberto no módulo Android e o Router do Brain.
 * O Brain nunca conhece Android, SharedPreferences ou onde a chave é armazenada.
 */
object ApiCatalogRegistry {
    @Volatile
    private var installed: ApiCatalog? = null

    fun install(catalog: ApiCatalog) {
        installed = catalog
    }

    fun current(): ApiCatalog? = installed
}
