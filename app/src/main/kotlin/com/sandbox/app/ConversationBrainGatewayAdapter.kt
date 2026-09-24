package com.sandbox.app

import com.brain.conversation.BrainCompletion
import com.brain.conversation.BrainApiGateway as ConversationBrainApiGateway
import com.brain.router.PapelPipeline

/** Adapta o gateway Android existente aos adapters conversacionais do módulo brain. */
class ConversationBrainGatewayAdapter(
    private val gateway: BrainApiGateway
) : ConversationBrainApiGateway {
    override fun complete(prompt: String, pipeline: PapelPipeline, authorizedAccountIds: Set<String>): BrainCompletion {
        val result = gateway.complete(prompt, pipeline, authorizedAccountIds)
        return BrainCompletion(
            text = result.text,
            modelId = result.modelId,
            costTier = result.costClass.name.lowercase(),
            providerId = result.providerId
        )
    }
}
