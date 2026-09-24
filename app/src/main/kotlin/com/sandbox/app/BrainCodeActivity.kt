package com.sandbox.app

import android.os.Bundle

/**
 * Thin launcher wrapper that starts the foreground execution anchor before
 * the Compose Activity lifecycle begins.
 */
class BrainCodeActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { BrainCodeExecutionService.start(this) }
    }
}
