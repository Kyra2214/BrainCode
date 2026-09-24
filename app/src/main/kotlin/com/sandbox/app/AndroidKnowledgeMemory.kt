package com.sandbox.app

import android.content.Context
import com.brain.memory.KnowledgeEntry
import com.brain.memory.KnowledgeMemory
import com.brain.memory.KnowledgeScope
import com.brain.memory.KnowledgeSource

/**
 * Fachada de compatibilidade. A persistência real agora é SQLite em
 * [SqlKnowledgeMemory], preservando a interface usada pelos consumidores.
 */
class AndroidKnowledgeMemory(context: Context) : KnowledgeMemory by SqlKnowledgeMemory(context)
