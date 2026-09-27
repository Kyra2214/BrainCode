package com.brain.conversation

/** Process-local injection point for optional advisory implementations. */
object IntentAdvisorRegistry {
    @Volatile
    var current: IntentAdvisor = NoOpIntentAdvisor
}
