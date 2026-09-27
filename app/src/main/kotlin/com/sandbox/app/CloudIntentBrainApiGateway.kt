package com.sandbox.app

import android.content.Context
import com.brain.conversation.BrainApiGateway
import com.brain.conversation.BrainCompletion
import com.brain.router.PapelPipeline

/**
 * Adapter from the app's provider/catalog gateway to the conversation contract.
 * It is only instantiated for the optional cloud intent-advisory hop; the user-facing
 * Secretary never receives provider text directly.
 */
class CloudIntentBrainApiGateway(context: Context) : BrainApiGateway {
    private val appContext = context.applicationContext
    private val keyStore = ApiKeyStore(appContext)
    private val providers = ApiKeyCatalogLoader.load(appContext)
    private val gateway = com.sandbox.app.BrainApiGateway(providers, keyStore)

    override fun complete(
        prompt: String,
        pipeline: PapelPipeline,
        authorizedAccountIds: Set<String>
    ): BrainCompletion {
        val providerPipeline = if (pipeline == PapelPipeline.CONVERSACAO) PapelPipeline.ESCRITA_DE_PROMPT else pipeline
        val result = gateway.complete(prompt, providerPipeline, authorizedAccountIds)
        return BrainCompletion(
            text = result.text,
            modelId = result.modelId,
            costTier = result.costClass.name.lowercase(),
            providerId = result.providerId
        )
    }

    fun authorizedAccounts(): Set<String> = providers.asSequence()
        .map { provider -> "android:${provider.id}" }
        .filter { accountId -> keyStore.get(accountId.removePrefix("android:"))?.isNotBlank() == true }
        .toSet()
}
