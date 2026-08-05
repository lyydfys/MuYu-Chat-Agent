package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LocalChatRunner
import com.muyuchat.core.engine.defaultLocalChatRunner

/**
 * Keeps long-lived native text runtimes out of the UI process. QAIRT uses the
 * same generic worker boundary as the other compatible text runtimes; device
 * evidence can inform diagnostics but never decides whether it may run.
 */
internal class IsolatedLocalChatRunners(context: Context) : AutoCloseable {
    private val llama = RemoteLocalChatRunner(context, LocalChatRuntime.LLAMA_CPP)
    private val mnn = RemoteLocalChatRunner(context, LocalChatRuntime.MNN_CPU)
    private val qairt = RemoteLocalChatRunner(context, LocalChatRuntime.GENIEX_QAIRT)
    private val geniexLlama = defaultLocalChatRunner(LocalChatRuntime.GENIEX_LLAMA_CPP, context)

    val runners: Map<LocalChatRuntime, LocalChatRunner> = buildMap {
        put(LocalChatRuntime.LLAMA_CPP, llama)
        put(LocalChatRuntime.MNN_CPU, mnn)
        put(LocalChatRuntime.GENIEX_QAIRT, qairt)
        put(
            LocalChatRuntime.GENIEX_LLAMA_CPP,
            geniexLlama
        )
    }

    override fun close() {
        llama.close()
        mnn.close()
        qairt.close()
        Thread {
            listOf(geniexLlama).forEach { runner ->
                runCatching { runner.shutdown() }
            }
        }.apply {
            isDaemon = true
            name = "mca-geniex-runner-cleanup"
            start()
        }
    }
}
