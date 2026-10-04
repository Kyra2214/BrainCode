package com.sandbox.app

import com.brain.conversation.BrainCompletion
import com.brain.conversation.ToolAwareBrainApiGateway
import com.brain.conversation.BrainApiGateway as ConversationBrainApiGateway
import com.brain.provider.ProviderToolDefinition
import com.brain.provider.ToolCompletionTurn
import com.brain.router.PapelPipeline

/** Adapta o gateway Android existente aos adapters conversacionais do módulo brain. */
class ConversationBrainGatewayAdapter(
    private val gateway: BrainApiGateway
) : ToolAwareBrainApiGateway {
    override fun complete(prompt: String, pipeline: PapelPipeline, authorizedAccountIds: Set<String>): BrainCompletion {
        val result = gateway.complete(prompt, pipeline, authorizedAccountIds)
        return BrainCompletion(
            text = result.text,
            modelId = result.modelId,
            costTier = result.costClass.name.lowercase(),
            providerId = result.providerId
        )
    }

    override fun completeWithTools(
        prompt: String,
        pipeline: PapelPipeline,
        authorizedAccountIds: Set<String>,
        tools: List<ProviderToolDefinition>
    ): ToolCompletionTurn {
        val result = gateway.complete(prompt, pipeline, authorizedAccountIds, tools)
        return if (result.toolCalls.isNotEmpty()) ToolCompletionTurn(toolCalls = result.toolCalls)
        else ToolCompletionTurn(text = result.text)
    }
}
