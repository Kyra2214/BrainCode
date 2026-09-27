package com.sandbox.app

import android.app.Application
import android.util.Log
import com.brain.conversation.ConversationInterpreterRegistry
import com.brain.conversation.ConversationMetrics
import com.brain.conversation.HybridIntentAdvisor
import com.brain.conversation.IntentAdvisorRegistry
import com.brain.conversation.NoOpConversationInterpreter
import com.brain.conversation.NoOpIntentAdvisor
import com.brain.memory.FileKnowledgeMemory
import com.brain.memory.KnowledgeLearningCycle
import com.sandbox.android.AndroidSandboxFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import java.io.File

/** App-level wiring for optional on-device conversation intelligence. */
class BrainCodeApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conversationMetrics = ConversationMetrics()
    val lfmModelManager: LfmModelManager by lazy { LfmModelManager(this) }
    private var localGateway: LocalLlmBrainApiGateway? = null

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.E2E_FAKE_ROOTFS) {
            IntentAdvisorRegistry.current = NoOpIntentAdvisor
            ConversationInterpreterRegistry.current = NoOpConversationInterpreter
            return
        }

        lfmModelManager.refreshState()
        val local = LocalLlmBrainApiGateway(this, lfmModelManager)
        localGateway = local
        // Current runtime is strictly local-first: the LFM arm is the only advisory model.
        // Cloud adapters remain available as future/explicit integrations but are not wired
        // into the Secretary path, avoiding recurring token/network cost by default.
        IntentAdvisorRegistry.current = HybridIntentAdvisor(LfmIntentAdvisor(local), NoOpIntentAdvisor, metrics = conversationMetrics)
        ConversationInterpreterRegistry.current = LfmEntityInterpreter(local)

        // A LFM model is not bundled in the APK. Provision it automatically only after
        // the Roofts/rootfs installation has completed, so first-run network work follows
        // the same bootstrap order as the sandbox resources. Failure is non-fatal: the
        // Secretary continues with deterministic/cloud paths and the model can retry later.
        scope.launch { provisionLfmAfterRoofts() }
    }

    private suspend fun provisionLfmAfterRoofts() {
        val factory = AndroidSandboxFactory(this@BrainCodeApplication)
        val modelManager = lfmModelManager
        // Provisioning is persistent: there is no fixed 15-minute window. If Roofts is
        // still installing, wait; if the transfer fails, keep retrying later. A failed
        // model must never block the deterministic Secretary path.
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (modelManager.refreshState() in setOf(LfmModelState.READY, LfmModelState.LOADED)) {
                val preload = localGateway?.preload()
                if (preload?.isSuccess == true && modelManager.isLoaded()) return
                preload?.exceptionOrNull()?.let {
                    Log.w("BrainCode.LFM", "falha ao carregar LFM local; nova tentativa será feita", it)
                }
                delay(30_000L)
                continue
            }

            val rooftsReady = runCatching { factory.isRoofts06Installed() }.getOrDefault(false)
            if (rooftsReady) {
                val download = runCatching { modelManager.ensureDownloaded() }
                download.exceptionOrNull()?.let {
                    Log.w("BrainCode.LFM", "falha ao provisionar LFM local; nova tentativa será feita", it)
                }
                if (modelManager.refreshState() == LfmModelState.READY) {
                    val preload = localGateway?.preload()
                    if (preload?.isSuccess == true && modelManager.isLoaded()) return
                    preload?.exceptionOrNull()?.let {
                        Log.w("BrainCode.LFM", "falha ao aquecer LFM local; nova tentativa será feita", it)
                    }
                }
                delay(30_000L)
            } else {
                delay(2_000L)
            }
        }
    }

    override fun onTerminate() {
        scope.cancel()
        localGateway?.close()
        super.onTerminate()
    }
}
