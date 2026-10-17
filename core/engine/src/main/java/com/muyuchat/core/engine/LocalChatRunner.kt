package com.muyuchat.core.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend as LiteRtBackend
import com.google.ai.edge.litertlm.Contents as LiteRtContents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.MessageCallback as LiteRtMessageCallback
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig as LiteRtSamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LLMTokenCallback
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmGenerateResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.RuntimeIdValue
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmCapabilities
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.muyuchat.core.engine.contract.RuntimeEpoch
import com.muyuchat.core.engine.runtime.BackendRuntimeOwner
import com.muyuchat.core.engine.runtime.MnnChatEnginePort
import com.muyuchat.core.engine.runtime.MnnChatNativeLeaf
import com.muyuchat.core.engine.runtime.MnnChatSessionPort
import com.muyuchat.core.modelstore.QairtBundleReadiness
import com.muyuchat.core.modelstore.QairtBundleReadinessAnalyzer
import com.muyuchat.core.modelstore.isLiteRtLmVisionModel
import com.muyuchat.core.modelstore.liteRtLmModelTypes
import com.muyuchat.core.modelstore.validateLiteRtLmLoadPreflight
import com.muyuchat.core.nativebridge.NativeLlamaBridge
import com.muyuchat.core.nativebridge.NativeMnnBridge
import java.io.File
import java.net.URI
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject


/**
 * Creates exactly one runner. Callers that isolate ordinary native runners can
 * therefore avoid loading unrelated JNI libraries in the product process.
 */
fun defaultLocalChatRunner(
    runtime: LocalChatRuntime,
    context: Context? = null
): LocalChatRunner = when (runtime) {
    LocalChatRuntime.MNN_CPU -> MnnCpuChatRunner()
    LocalChatRuntime.LLAMA_CPP -> LlamaCppChatRunner()
    LocalChatRuntime.GENIEX_LLAMA_CPP -> GenieXLlamaCppChatRunner(context)
    LocalChatRuntime.GENIEX_QAIRT -> GenieXChatRunner(
        runtime = LocalChatRuntime.GENIEX_QAIRT,
        requestedRuntimeId = RuntimeIdValue.QAIRT.value.orEmpty(),
        defaultComputeUnit = ComputeUnitValue.NPU.value.orEmpty(),
        defaultBackendDevices = "QAIRT NPU",
        appContext = context
    )
    LocalChatRuntime.LITERT_LM -> LiteRtLmChatRunner()
}

fun defaultLocalChatRunners(context: Context? = null): Map<LocalChatRuntime, LocalChatRunner> =
    LocalChatRuntime.entries.associateWith { runtime -> defaultLocalChatRunner(runtime, context) }

internal fun unavailableStats(runtime: LocalChatRuntime, error: Throwable?): JSONObject =
    JSONObject()
        .put("backend", runtime.backendId)
        .put("loaded", false)
        .put("runnerReady", false)
        .put("nCtx", 0)
        .put("maxAllTokens", 0)
        .put("maxNewTokens", 0)
        .put(
            "lastError",
            buildString {
                append(runtime.label).append("不可用：当前 APK 尚未打包对应 native runner")
                val message = error?.message
                if (!message.isNullOrBlank()) append("；").append(message)
            }
        )
