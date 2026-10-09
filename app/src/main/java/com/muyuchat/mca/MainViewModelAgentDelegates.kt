package com.muyuchat.mca

import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.feature.agent.AgentTuningMode

fun MainViewModel.scanAgent() = agentTuningCoordinator.scanAgent()
fun MainViewModel.applyAgentRecommendation() = agentTuningCoordinator.applyAgentRecommendation()
fun MainViewModel.rollbackAgentParams() = agentTuningCoordinator.rollbackAgentParams()
fun MainViewModel.loadAgentRecommendedModel() = agentTuningCoordinator.loadAgentRecommendedModel()
fun MainViewModel.runAgentBenchmark() = agentTuningCoordinator.runAgentBenchmark()
fun MainViewModel.runAgentStabilitySmoke() = agentTuningCoordinator.runAgentStabilitySmoke()
fun MainViewModel.startAgentTuning() = agentTuningCoordinator.startAgentTuning()
fun MainViewModel.startAgentTuning(mode: AgentTuningMode, autoApply: Boolean) =
    agentTuningCoordinator.startAgentTuning(mode, autoApply)
fun MainViewModel.pauseAgentTuning() = agentTuningCoordinator.pauseAgentTuning()
fun MainViewModel.resumeAgentTuning() = agentTuningCoordinator.resumeAgentTuning()
fun MainViewModel.cancelAgentTuning() = agentTuningCoordinator.cancelAgentTuning()
fun MainViewModel.queryAgentTuningJob() = agentTuningCoordinator.queryAgentTuningJob()
fun MainViewModel.applyPendingAgentProfile() = agentTuningCoordinator.applyPendingAgentProfile()
fun MainViewModel.discardPendingAgentProfile() = agentTuningCoordinator.discardPendingAgentProfile()
fun MainViewModel.rollbackAgentProfile() = agentTuningCoordinator.rollbackAgentProfile()
fun MainViewModel.runAgentQuickDebug() = agentTuningCoordinator.runAgentQuickDebug()
fun MainViewModel.runAgentStandardDebug() = agentTuningCoordinator.runAgentStandardDebug()
fun MainViewModel.runAgentDeepDebug() = agentTuningCoordinator.runAgentDeepDebug()
fun MainViewModel.runAgentPowerDebug() = agentTuningCoordinator.runAgentPowerDebug()
fun MainViewModel.showAgentDebugExplanation() = agentTuningCoordinator.showAgentDebugExplanation()

fun MainViewModel.currentDeviceProfile(): DeviceProfile = agentTuningCoordinator.currentDeviceProfile()

fun MainViewModel.buildRecommendation(
    preference: UserPreference,
    device: DeviceProfile = currentDeviceProfile()
): AgentRecommendation = agentTuningCoordinator.buildRecommendation(preference, device)
