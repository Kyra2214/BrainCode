package com.sandbox.app

import android.content.Context
import com.sandbox.resource.RootfsManifest
import org.json.JSONObject

/** Carrega a composição ordenada base -> extra -> Android/API do RootFS. */
object ManifestLoader {
    private const val PLACEHOLDER_MARKER = "SEU-HOST-OU-CDN"

    fun load(context: Context): RootfsManifest = load(context, R.raw.rootfs_manifest)

    fun loadAll(context: Context): List<RootfsManifest> = listOf(
        load(context, R.raw.rootfs_manifest),
        load(context, R.raw.rootfs_extra_manifest),
        load(context, R.raw.rootfs_android_manifest)
    )

    private fun load(context: Context, resourceId: Int): RootfsManifest {
        val json = context.resources.openRawResource(resourceId)
            .bufferedReader()
            .use { it.readText() }
        val obj = JSONObject(json)
        val manifest = RootfsManifest(
            version = obj.getString("version"),
            arch = obj.getString("arch"),
            distro = obj.optString("distro", "desconhecida"),
            url = obj.getString("url"),
            sizeBytes = obj.getLong("sizeBytes"),
            sha256 = obj.getString("sha256"),
            minAppVersion = obj.getString("minAppVersion"),
            signature = obj.optString("signature", ""),
            signatureUrl = obj.optString("signatureUrl", ""),
            signatureKeyId = obj.optString("signatureKeyId", ""),
            signatureAlgorithm = obj.optString("signatureAlgorithm", ""),
            signatureRequired = obj.optBoolean("signatureRequired", true)
        )
        check(!manifest.url.contains(PLACEHOLDER_MARKER)) {
            "Manifesto do rootfs ainda não configurado: URL inválida para ${manifest.version}."
        }
        return manifest
    }
}
