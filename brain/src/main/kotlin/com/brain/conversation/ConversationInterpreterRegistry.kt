package com.brain.conversation

/** Process-local injection point for optional structured conversation interpretation. */
object ConversationInterpreterRegistry {
    @Volatile
    var current: ConversationInterpreter = NoOpConversationInterpreter
}
