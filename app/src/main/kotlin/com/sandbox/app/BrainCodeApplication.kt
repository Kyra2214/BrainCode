package com.sandbox.app

import android.app.Application
import com.brain.conversation.HybridIntentAdvisor
import com.brain.conversation.IntentAdvisorRegistry
import com.brain.conversation.LlmIntentAdvisor
import com.brain.conversation.NoOpIntentAdvisor
import com.brain.memory.KnowledgeLearningCycle
import com.brain.memory.FileKnowledgeMemory
import com.brain.conversation.LlmConversationAdapters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** App-level wiring for optional on-device conversation intelligence. */
class BrainCodeApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var localGateway: LocalLlmBrainApiGateway? = null

    override fun onCreate() {
        super.onCreate()
        val local = LocalLlmBrainApiGateway(this)
        localGateway = local

        val providerGateway = BrainApiGateway(
            runCatching { ApiKeyCatalogLoader.load(this) }.getOrElse { emptyList() },
            ApiKeyStore(this),
            learning = KnowledgeLearningCycle(FileKnowledgeMemory(File(filesDir, "brain/knowledge.jsonl")))
        )
        val cloudAdvisor = LlmIntentAdvisor(ConversationBrainGatewayAdapter(providerGateway), NoOpIntentAdvisor)
        val localAdvisor = LfmIntentAdvisor(local)
        IntentAdvisorRegistry.current = HybridIntentAdvisor(localAdvisor, cloudAdvisor)

        // The 229 MB quantized model is provisioned once in the background. E2E builds
        // deliberately stay model-free and keep their offline determinism.
        if (!BuildConfig.E2E_FAKE_ROOTFS) {
            scope.launch { runCatching { LfmModelManager(this@BrainCodeApplication).ensureDownloaded() } }
        }
    }

    override fun onTerminate() {
        localGateway?.close()
        scope.coroutineContext.cancel()
        super.onTerminate()
    }
}
