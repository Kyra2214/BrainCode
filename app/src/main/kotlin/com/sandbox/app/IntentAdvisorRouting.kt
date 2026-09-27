package com.sandbox.app

import com.brain.conversation.HybridIntentAdvisor
import com.brain.conversation.IntentAdvisor

internal object IntentAdvisorRouting {
    fun select(
        local: IntentAdvisor,
        localOnly: IntentAdvisor,
        cloud: IntentAdvisor,
        hasCloudAccount: Boolean
    ): IntentAdvisor = if (hasCloudAccount) {
        HybridIntentAdvisor(local = local, cloud = cloud)
    } else {
        localOnly
    }
}
