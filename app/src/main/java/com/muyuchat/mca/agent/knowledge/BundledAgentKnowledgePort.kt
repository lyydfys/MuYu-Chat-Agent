package com.muyuchat.mca.agent.knowledge

import android.content.Context
import com.muyuchat.core.agent.contract.AgentKnowledgePort

/**
 * Production [AgentKnowledgePort]: the bundled operation knowledge, queried by the run's goal.
 *
 * This is the *caller* the index never had. The index itself decides relevance and excerpt size;
 * this adapter only adds three things the runtime contract needs and the index does not do:
 *
 *  - **Lazy load.** Assets are read on first use, not at composition time, so an app that never runs
 *    an agent task never pays for them. A failed load yields an empty index, not a crash.
 *  - **Cheap per step.** The loop asks once per step with the same goal; the resolved answer is
 *    cached by query string, so step 2..N cost a map lookup instead of a re-search.
 *  - **Pure text.** Whatever the index finds is returned verbatim, or `""`. Nothing here grants a
 *    permission, and the prompt builder caps the length again.
 */
class BundledAgentKnowledgePort(
    private val loadIndex: () -> AgentKnowledgeIndex
) : AgentKnowledgePort {

    private val index: AgentKnowledgeIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { loadIndex() }.getOrElse { AgentKnowledgeIndex.load { null } }
    }

    /** One entry per distinct goal string; a task's goal does not change during a run. */
    private val cache = HashMap<String, String>()

    override fun relevantKnowledge(query: String): String {
        if (query.isBlank()) return ""
        cache[query]?.let { return it }
        val resolved = runCatching { index.promptText(query) }.getOrDefault("")
        cache[query] = resolved
        return resolved
    }

    companion object {
        /** Production factory: reads the bundled `agent-knowledge` assets from the APK. */
        fun from(context: Context): BundledAgentKnowledgePort {
            val appContext = context.applicationContext
            return BundledAgentKnowledgePort { AgentKnowledgeIndex.load(appContext) }
        }
    }
}
