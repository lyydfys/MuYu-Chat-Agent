package com.muyuchat.core.advisor

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.tuning.TuningEngine
import com.muyuchat.core.tuning.TuningPlan
import com.muyuchat.core.tuning.UserPreference
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class RiskLevel {
    Low,
    Medium,
    High,
    Blocked
}

enum class AgentAction {
    ScanDevice,
    ListModels,
    DownloadModel,
    LoadModel,
    RunBenchmark,
    ApplyParams,
    RollbackParams,
    StopTask
}

data class ModelProfile(
    val id: String,
    val displayName: String,
    val source: String,
    val fileName: String,
    val sizeBytes: Long,
    val parametersB: Double?,
    val quant: String?,
    val architecture: String?,
    val license: String?,
    val totalParametersB: Double? = parametersB,
    val activeParametersB: Double? = parametersB,
    val repoId: String? = null,
    val revision: String? = null,
    val localPath: String? = null,
    val downloadUrl: String? = null,
    val chatTemplateSupported: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("source", source)
        .put("fileName", fileName)
        .put("sizeBytes", sizeBytes)
        .put("parametersB", parametersB)
        .put("totalParametersB", totalParametersB)
        .put("activeParametersB", activeParametersB)
        .put("quant", quant)
        .put("architecture", architecture)
        .put("license", license)
        .put("repoId", repoId)
        .put("revision", revision)
        .put("localPath", localPath)
        .put("downloadUrl", downloadUrl)
        .put("chatTemplateSupported", chatTemplateSupported)

    companion object {
        fun fromLocal(model: ModelManifest): ModelProfile {
            val scale = inferParameterScale(model.displayName, model.sizeBytes)
            return ModelProfile(
                id = model.id,
                displayName = model.displayName,
                source = "local",
                fileName = model.fileName,
                sizeBytes = model.sizeBytes,
                parametersB = scale.active ?: scale.total,
                totalParametersB = scale.total,
                activeParametersB = scale.active,
                quant = model.quant ?: inferQuant(model.displayName),
                architecture = model.architecture,
                license = model.license,
                repoId = model.repoId,
                revision = model.revision,
                localPath = model.path,
                chatTemplateSupported = model.architecture != null || looksChatModel(model.displayName)
            )
        }

        fun fromRemote(file: RemoteModelFile): ModelProfile {
            val scale = inferParameterScale(file.name, file.sizeBytes ?: 0L)
            return ModelProfile(
                id = "${file.repoId}/${file.path}",
                displayName = file.name.removeSuffix(".gguf"),
                source = "modelscope",
                fileName = file.name,
                sizeBytes = file.sizeBytes ?: 0L,
                parametersB = scale.active ?: scale.total,
                totalParametersB = scale.total,
                activeParametersB = scale.active,
                quant = inferQuant(file.name),
                architecture = inferArchitecture(file.name),
                license = file.license,
                repoId = file.repoId,
                revision = file.revision,
                downloadUrl = file.downloadUrl,
                chatTemplateSupported = inferArchitecture(file.name) != null || looksChatModel(file.name)
            )
        }

        private fun inferParameterScale(name: String, sizeBytes: Long): ParameterScale {
            val moe = Regex("""(?i)(\d+(?:\.\d+)?)\s*[bB]\s*[-_/]?\s*[aA](\d+(?:\.\d+)?)\s*[bB]""")
                .find(name)
            if (moe != null) {
                return ParameterScale(
                    total = moe.groupValues.getOrNull(1)?.toDoubleOrNull(),
                    active = moe.groupValues.getOrNull(2)?.toDoubleOrNull()
                )
            }
            val direct = Regex("""(?i)(\d+(?:\.\d+)?)\s*[bB](?:[^A-Za-z]|$)""")
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
            if (direct != null) return ParameterScale(total = direct, active = direct)
            val gb = sizeBytes / GB.toDouble()
            val inferred = when {
                gb <= 0.0 -> null
                gb < 1.2 -> 1.0
                gb < 2.4 -> 1.5
                gb < 4.8 -> 3.0
                gb < 9.5 -> 7.0
                else -> 13.0
            }
            return ParameterScale(total = inferred, active = inferred)
        }

        private fun inferQuant(name: String): String? {
            val quantPattern = Regex("(Q[0-9]_[A-Z]_[A-Z]|Q[0-9]_[A-Z]|Q[0-9]|IQ[0-9]_[A-Z]+|F16|BF16)", RegexOption.IGNORE_CASE)
            return quantPattern.find(name)?.value?.uppercase()
        }

        private fun inferArchitecture(name: String): String? {
            val lower = name.lowercase()
            return when {
                "qwen" in lower -> "qwen"
                "llama" in lower -> "llama"
                "gemma" in lower -> "gemma"
                "mistral" in lower -> "mistral"
                "phi" in lower -> "phi"
                else -> null
            }
        }

        private fun looksChatModel(name: String): Boolean {
            val lower = name.lowercase()
            return listOf("chat", "instruct", "assistant", "it").any { it in lower }
        }

        private const val GB = 1024L * 1024L * 1024L
    }
}

private data class ParameterScale(
    val total: Double?,
    val active: Double?
)

data class AgentCandidate(
    val model: ModelProfile,
    val score: Int,
    val risk: RiskLevel,
    val reason: String,
    val expectedDecodeTpsRange: String,
    val memoryRisk: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("model", model.toJson())
        .put("score", score)
        .put("risk", risk.name.lowercase())
        .put("reason", reason)
        .put("expectedDecodeTpsRange", expectedDecodeTpsRange)
        .put("memoryRisk", memoryRisk)
}

data class AgentRecommendation(
    val recommended: AgentCandidate?,
    val candidates: List<AgentCandidate>,
    val tuningPlan: TuningPlan,
    val risk: RiskLevel,
    val explanation: String,
    val requiredConfirmations: List<String>,
    val actions: List<AgentAction>,
    val preference: UserPreference
) {
    fun toJson(): JSONObject = JSONObject()
        .put("recommended", recommended?.toJson())
        .put("candidates", JSONArray(candidates.map { it.toJson() }))
        .put("tuningPlan", tuningPlan.toJson())
        .put("risk", risk.name.lowercase())
        .put("explanation", explanation)
        .put("requiredConfirmations", JSONArray(requiredConfirmations))
        .put("actions", JSONArray(actions.map { it.name }))
        .put("preference", preference.toJson())
}

data class AdaptiveAgentRecommendation(
    val legacy: AgentRecommendation,
    val adaptive: AdaptiveTuningRecommendation
)

class AgentAdvisor(
    private val tuningEngine: TuningEngine = TuningEngine()
) {
    /**
     * Builds an adaptive profile for the model the user is actually loading.
     *
     * This must not reuse the globally recommended candidate: a smaller model may
     * win the recommendation ranking while the requested model needs family-specific
     * load parameters such as Qwen3.6 A3B MTP.
     */
    fun recommendAdaptiveForModel(
        device: DeviceProfile,
        model: ModelManifest,
        runtimeIdentity: ModelRuntimeIdentity,
        capabilities: ModelTuningCapabilities = ModelTuningCapabilities.forIdentity(runtimeIdentity),
        preference: UserPreference = UserPreference(),
        lastDecodeTps: Double? = null
    ): AdaptiveTuningRecommendation {
        val target = ModelProfile.fromLocal(model)
        return tuningEngine.recommendAdaptive(
            device = device,
            modelParametersB = target.parametersB,
            modelName = target.displayName,
            runtimeIdentity = runtimeIdentity,
            modelKnowledge = capabilities.knowledgeLevel,
            capabilities = capabilities,
            preference = preference,
            lastDecodeTps = lastDecodeTps
        )
    }

    /**
     * Migration entry point for callers that can provide the real model/runtime
     * identity. The legacy recommendation remains available for the current UI,
     * while new code must apply execution/generation/canary outputs separately.
     */
    fun recommendAdaptive(
        device: DeviceProfile,
        localModels: List<ModelManifest>,
        remoteFiles: List<RemoteModelFile>,
        runtimeIdentity: ModelRuntimeIdentity,
        capabilities: ModelTuningCapabilities = ModelTuningCapabilities.forIdentity(runtimeIdentity),
        preference: UserPreference = UserPreference(),
        lastDecodeTps: Double? = null
    ): AdaptiveAgentRecommendation {
        val legacy = recommend(
            device = device,
            localModels = localModels,
            remoteFiles = remoteFiles,
            preference = preference,
            lastDecodeTps = lastDecodeTps
        )
        val targetB = legacy.recommended?.model?.parametersB ?: targetParametersB(device, preference)
        val adaptive = tuningEngine.recommendAdaptive(
            device = device,
            modelParametersB = targetB,
            modelName = legacy.recommended?.model?.displayName,
            runtimeIdentity = runtimeIdentity,
            modelKnowledge = capabilities.knowledgeLevel,
            capabilities = capabilities,
            preference = preference,
            lastDecodeTps = lastDecodeTps
        )
        return AdaptiveAgentRecommendation(legacy = legacy, adaptive = adaptive)
    }

    fun recommend(
        device: DeviceProfile,
        localModels: List<ModelManifest>,
        remoteFiles: List<RemoteModelFile>,
        preference: UserPreference = UserPreference(),
        lastDecodeTps: Double? = null
    ): AgentRecommendation {
        val profiles = localModels.map(ModelProfile::fromLocal) + remoteFiles.map(ModelProfile::fromRemote)
        val targetB = targetParametersB(device, preference)
        val candidates = profiles
            .map { profile -> score(device, profile, targetB, preference) }
            .sortedWith(compareByDescending<AgentCandidate> {
                if (it.risk == RiskLevel.Blocked) 0 else 1
            }.thenByDescending { it.score })
        val recommended = candidates.firstOrNull { it.risk != RiskLevel.Blocked }
        val tuningPlan = tuningEngine.recommend(
            device = device,
            modelParametersB = recommended?.model?.parametersB ?: targetB,
            modelName = recommended?.model?.displayName,
            preference = preference,
            lastDecodeTps = lastDecodeTps
        )
        val confirmations = buildList {
            if (recommended?.model?.source == "modelscope") add("下载 ${recommended.model.displayName}")
            if ((recommended?.model?.totalParametersB ?: recommended?.model?.parametersB ?: 0.0) >= 7.0 ||
                (recommended?.model?.sizeBytes ?: 0L) >= 4L * GB
            ) add("加载高内存模型")
            if (preference.allowExperimentalBackend) add("启用实验后端")
        }
        val explanation = if (recommended == null) {
            "当前没有可推荐的本地推理引擎。请先导入本地模型，或在模型页从 ModelScope 下载 MNN / GGUF 模型。"
        } else {
            buildString {
                append("建议使用 ${recommended.model.displayName}。")
                append(recommended.reason)
                if (lastDecodeTps != null && lastDecodeTps > 0.0) {
                    append(" 短基准 decode=${"%.2f".format(lastDecodeTps)} token/s。")
                }
                append(" 推荐参数：n_ctx=${tuningPlan.nCtx}, threads=${tuningPlan.nThreads}, n_predict=${tuningPlan.nPredict}。")
            }
        }
        return AgentRecommendation(
            recommended = recommended,
            candidates = candidates,
            tuningPlan = tuningPlan,
            risk = recommended?.risk ?: RiskLevel.Medium,
            explanation = explanation,
            requiredConfirmations = confirmations,
            actions = listOf(
                AgentAction.ScanDevice,
                AgentAction.ListModels,
                AgentAction.LoadModel,
                AgentAction.RunBenchmark,
                AgentAction.ApplyParams
            ),
            preference = preference
        )
    }

    private fun score(
        device: DeviceProfile,
        model: ModelProfile,
        targetB: Double,
        preference: UserPreference
    ): AgentCandidate {
        val parametersB = model.parametersB ?: targetB
        val risk = risk(device, model)
        val quantBonus = when {
            model.quant?.contains("Q4", ignoreCase = true) == true -> 18
            model.quant?.contains("Q5", ignoreCase = true) == true -> 15
            model.quant?.contains("Q8", ignoreCase = true) == true -> 4
            model.quant == null -> 0
            else -> 8
        }
        val sourceBonus = if (model.source == "local") 20 else 5
        val fitPenalty = (abs(parametersB - targetB) * 14).roundToInt()
        val riskPenalty = when (risk) {
            RiskLevel.Low -> 0
            RiskLevel.Medium -> 18
            RiskLevel.High -> 45
            RiskLevel.Blocked -> 200
        }
        val chatBonus = if (model.chatTemplateSupported) 8 else -8
        val preferenceBonus = when {
            preference.mode.name == "Quality" && parametersB >= targetB -> 8
            preference.mode.name == "Speed" && parametersB <= targetB -> 8
            preference.mode.name == "LongContext" && parametersB <= 3.5 -> 6
            else -> 0
        }
        val score = 100 + quantBonus + sourceBonus + chatBonus + preferenceBonus - fitPenalty - riskPenalty
        return AgentCandidate(
            model = model,
            score = score,
            risk = risk,
            reason = reason(device, model, targetB, risk),
            expectedDecodeTpsRange = expectedSpeedRange(device, model),
            memoryRisk = memoryRisk(device, model)
        )
    }

    private fun targetParametersB(device: DeviceProfile, preference: UserPreference): Double {
        val ramGb = device.displayTotalRamBytes / GB.toDouble()
        return when (preference.mode.name) {
            "PowerSave", "Speed" -> if (ramGb < 6) 1.5 else 3.0
            "Quality" -> if (ramGb >= 10) 7.0 else if (ramGb >= 6) 3.0 else 1.5
            "LongContext" -> if (ramGb >= 8) 3.0 else 1.5
            else -> when {
                ramGb < 6 -> 1.5
                ramGb < 8 -> 3.0
                ramGb < 10 -> 4.0
                else -> 7.0
            }
        }
    }

    private fun risk(device: DeviceProfile, model: ModelProfile): RiskLevel {
        if (model.sizeBytes > 0 && model.source == "modelscope" && model.sizeBytes > device.storageFreeBytes) {
            return RiskLevel.Blocked
        }
        if (device.thermalStatus >= ThermalStatus.Critical) return RiskLevel.Blocked
        val ramGb = device.displayTotalRamBytes / GB.toDouble()
        val total = device.totalRamBytes.takeIf { it > 0L } ?: device.displayTotalRamBytes
        val budget = memoryBudgetBytes(device)
        val need = estimatedRuntimeMemoryBytes(model)
        val parametersB = model.parametersB ?: 3.0
        val qwen36A3bMtp = model.isQwen36A3bMtp()
        return when {
            parametersB >= 13.0 -> RiskLevel.Blocked
            qwen36A3bMtp && total > 0L && need > (total * 0.92).toLong() -> RiskLevel.High
            total > 0L && need > (total * 0.92).toLong() -> RiskLevel.Blocked
            parametersB >= 7.0 && ramGb < 8 -> RiskLevel.Blocked
            qwen36A3bMtp && need > budget -> RiskLevel.High
            device.isLowMemory && need > budget -> RiskLevel.High
            need > budget && parametersB >= 7.0 -> RiskLevel.High
            need > budget && parametersB >= 3.0 -> RiskLevel.Medium
            device.thermalStatus >= ThermalStatus.Severe -> RiskLevel.High
            device.batteryPercent in 0..14 && !device.isCharging -> RiskLevel.High
            parametersB >= 7.0 -> RiskLevel.Medium
            parametersB >= 3.0 && ramGb < 8 -> RiskLevel.Medium
            else -> RiskLevel.Low
        }
    }

    private fun reason(device: DeviceProfile, model: ModelProfile, targetB: Double, risk: RiskLevel): String {
        val size = if (model.sizeBytes > 0) formatBytes(model.sizeBytes) else "未知大小"
        val scale = when {
            model.totalParametersB != null && model.activeParametersB != null &&
                model.totalParametersB != model.activeParametersB ->
                "总 ${model.totalParametersB}B / 激活 ${model.activeParametersB}B"
            model.parametersB != null -> "${model.parametersB}B"
            else -> "未知参数规模"
        }
        val riskText = when (risk) {
            RiskLevel.Low -> "风险较低"
            RiskLevel.Medium -> "需要关注内存和发热"
            RiskLevel.High -> "可能发热或速度偏慢"
            RiskLevel.Blocked -> "当前设备条件不建议运行"
        }
        return "${device.socFamily.name} 设备目标档位约 ${targetB}B；该模型为 $scale、${model.quant ?: "未知量化"}、$size，$riskText。"
    }

    private fun expectedSpeedRange(device: DeviceProfile, model: ModelProfile): String {
        val scale = model.parametersB ?: 3.0
        val coreFactor = max(1.0, device.estimatedBigCores / 4.0)
        val base = when {
            scale <= 1.6 -> 18.0
            scale <= 3.5 -> 10.0
            scale <= 7.5 -> 4.0
            else -> 1.5
        } * coreFactor
        val low = max(1.0, base * 0.65)
        val high = max(low + 1.0, base * 1.35)
        return "${low.roundToInt()}-${high.roundToInt()} token/s"
    }

    private fun memoryRisk(device: DeviceProfile, model: ModelProfile): String {
        val available = device.availableRamBytes
        val budget = memoryBudgetBytes(device)
        val need = estimatedRuntimeMemoryBytes(model)
        return when {
            device.isLowMemory -> "系统低内存，建议关闭后台"
            need > budget -> "运行预算偏紧，建议短基准后再加载"
            need > budget * 0.82 -> "内存余量较少"
            need > available && budget > available -> "系统可用偏低，但缓存可回收，建议保持前台运行"
            else -> "内存余量正常"
        }
    }

    private fun memoryBudgetBytes(device: DeviceProfile): Long {
        val modelBudget = device.modelMemoryBudgetBytes
        if (modelBudget > 0L) return modelBudget
        val total = device.totalRamBytes.takeIf { it > 0L } ?: device.displayTotalRamBytes
        if (total <= 0L) return device.availableRamBytes
        if (device.isLowMemory) return device.availableRamBytes
        return max(device.availableRamBytes, (total * 0.70).toLong())
    }

    private fun estimatedRuntimeMemoryBytes(model: ModelProfile): Long {
        val activeScale = model.activeParametersB ?: model.parametersB ?: 3.0
        val totalScale = model.totalParametersB ?: model.parametersB ?: activeScale
        val fileResident = when {
            model.sizeBytes > 0L -> (model.sizeBytes * 1.18).toLong()
            totalScale <= 1.6 -> (1.2 * GB).toLong()
            totalScale <= 3.5 -> (2.8 * GB).toLong()
            totalScale <= 7.5 -> (5.2 * GB).toLong()
            else -> (7.5 * GB).toLong()
        }
        val contextAndRuntime = when {
            activeScale <= 1.6 -> 512L * MB
            activeScale <= 3.5 -> 768L * MB
            activeScale <= 7.5 -> 1200L * MB
            else -> 1600L * MB
        }
        return fileResident + contextAndRuntime
    }

    private fun ModelProfile.isQwen36A3bMtp(): Boolean {
        val value = "$displayName/$fileName".lowercase()
        return ("qwen3.6" in value || "qwen36" in value) &&
            ("35b-a3b" in value || "apex" in value || "mtp" in value)
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / GB.toDouble()
        val mb = bytes / MB.toDouble()
        return if (gb >= 1.0) "%.2fGB".format(gb) else "%.0fMB".format(mb)
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val GB = 1024L * 1024L * 1024L
    }
}
