package com.sandbox.resource

/** Metadados mínimos de qualquer artefato baixado com retomada e verificação. */
interface DownloadManifest {
    val url: String
    val sizeBytes: Long
    val sha256: String
}
