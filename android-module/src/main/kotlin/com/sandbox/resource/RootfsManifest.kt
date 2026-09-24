package com.sandbox.resource

/**
 * Descreve a versão do rootfs disponível para download.
 * Gerado a partir do rootfs-builder/build.sh (Fase 0.1) e hospedado
 * separadamente do APK (CDN, release do GitHub, etc).
 */
data class RootfsManifest(
    val version: String,
    val arch: String,
    val distro: String,
    override val url: String,
    override val sizeBytes: Long,
    override val sha256: String,
    val minAppVersion: String,
    val signature: String = "",
    val signatureUrl: String = "",
    val signatureKeyId: String = "",
    val signatureAlgorithm: String = "",
    val signatureRequired: Boolean = true
) : DownloadManifest
