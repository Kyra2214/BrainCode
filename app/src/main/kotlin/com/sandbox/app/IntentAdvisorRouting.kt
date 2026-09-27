package com.sandbox.app

import com.brain.conversation.HybridIntentAdvisor
import com.brain.conversation.IntentAdvisor
import com.brain.secretary.Door
import com.brain.secretary.OrderIntent

internal object IntentAdvisorRouting {
    fun select(
        local: IntentAdvisor,
        cloud: IntentAdvisor,
        hasCloudAccount: Boolean
    ): IntentAdvisor = if (hasCloudAccount) {
        HybridIntentAdvisor(local = local, cloud = cloud)
    } else {
        LocalOnlyIntentAdvisor(when (local) {
            is LocalOnlyIntentAdvisor -> local
            else -> local
        }.let { advisor ->
            // LocalOnlyIntentAdvisor needs a BrainApiGateway, so callers that already
            // provide the deterministic local advisor keep it directly when no cloud exists.
            advisor
        })
    }
}
