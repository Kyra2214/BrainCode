package com.sandbox.app

import android.app.Application
import com.brain.conversation.ConversationInterpreterRegistry
import com.brain.conversation.ConversationMetrics
import com.brain.conversation.HybridIntentAdvisor
import com.brain.conversation.IntentAdvisorRegistry
import com.brain.conversation.LlmIntentAdvisor
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
import java.io.File

/** App-level wiring for optional on-device conversation intelligence. */
class BrainCodeApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conversationMetrics = ConversationMetrics()
    private var localGateway: LocalLlmBrainApiGateway? = null

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.E2E_FAKE_ROOTFS) {
            IntentAdvisorRegistry.current = NoOpIntentAdvisor
            ConversationInterpreterRegistry.current = NoOpConversationInterpreter
            return
        }

        val local = LocalLlmBrainApiGateway(this)
        localGateway = local
        val providerGateway = BrainApiGateway(
            runCatching { ApiKeyCatalogLoader.load(this) }.getOrElse { emptyList() },
            ApiKeyStore(this),
            learning = KnowledgeLearningCycle(FileKnowledgeMemory(File(filesDir, "brain/knowledge.jsonl")))
        )
        val cloudAdvisor = LlmIntentAdvisor(ConversationBrainGatewayAdapter(providerGateway), NoOpIntentAdvisor)
        IntentAdvisorRegistry.current = HybridIntentAdvisor(LfmIntentAdvisor(local), cloudAdvisor, metrics = conversationMetrics)
        ConversationInterpreterRegistry.current = LfmEntityInterpreter(local)

        // A LFM model is not bundled in the APK. Provision it automatically only after
        // the Roofts/rootfs installation has completed, so first-run network work follows
        // the same bootstrap order as the sandbox resources. Failure is non-fatal: the
        // Secretary continues with deterministic/cloud paths and the model can retry later.
        scope.launch { provisionLfmAfterRoofts() }
    }

    private suspend fun provisionLfmAfterRoofts() {
        val factory = AndroidSandboxFactory(this@BrainCodeApplication)
        val modelManager = LfmModelManager(this@BrainCodeApplication)
        if (modelManager.isReady()) return

        // Roofts 0.6 is installed as part of sandbox preparation. Do not start the
        // 229 MB LFM transfer before that bootstrap finishes. Polling is deliberately
        // lightweight and bounded; a later app launch retries automatically if needed.
        val deadline = System.currentTimeMillis() + 15 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { factory.isRoofts06Installed() }.getOrDefault(false)) {
                runCatching { modelManager.ensureDownloaded() }
                return
            }
            delay(2_000L)
        }
    }

    override fun onTerminate() {
        localGateway?.close()
        super.onTerminate()
    }
}
