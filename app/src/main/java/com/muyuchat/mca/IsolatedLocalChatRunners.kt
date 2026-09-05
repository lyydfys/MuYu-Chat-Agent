package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LocalChatRunner

/**
 * Keeps long-lived native text runtimes out of the UI process. Both GenieX
 * transports use the worker boundary as well: the SDK registers native
 * plugins process-wide, so constructing the llama.cpp runner in the UI process
 * can contaminate a later QAIRT/LiteRT load even when that runner is never
 * selected. Device evidence can inform diagnostics but never decides whether
 * a runtime may run.
 */
internal class IsolatedLocalChatRunners(context: Context) : AutoCloseable {
    private val llama = RemoteLocalChatRunner(context, LocalChatRuntime.LLAMA_CPP)
    private val mnn = RemoteLocalChatRunner(context, LocalChatRuntime.MNN_CPU)
    private val qairt = RemoteLocalChatRunner(context, LocalChatRuntime.GENIEX_QAIRT)
    private val liteRtLm = RemoteLocalChatRunner(context, LocalChatRuntime.LITERT_LM)
    private val geniexLlama = RemoteLocalChatRunner(context, LocalChatRuntime.GENIEX_LLAMA_CPP)

    val runners: Map<LocalChatRuntime, LocalChatRunner> = buildMap {
        put(LocalChatRuntime.LLAMA_CPP, llama)
        put(LocalChatRuntime.MNN_CPU, mnn)
        put(LocalChatRuntime.GENIEX_QAIRT, qairt)
        put(LocalChatRuntime.LITERT_LM, liteRtLm)
        put(
            LocalChatRuntime.GENIEX_LLAMA_CPP,
            geniexLlama
        )
    }

    override fun close() {
        llama.close()
        mnn.close()
        qairt.close()
        liteRtLm.close()
        geniexLlama.close()
    }
}
