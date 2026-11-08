package com.muyuchat.mca.onboarding

import com.muyuchat.core.designsystem.onboarding.McaOnboardingPathChoice
import com.muyuchat.core.designsystem.onboarding.McaOnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract for the `:app` <-> `:core:designsystem` onboarding mapping.
 *
 * The design system cannot depend on `:app`, so the two enum pairs are joined only by these extension
 * functions. Every property that has to agree across the boundary is pinned here — ordinal (the progress row
 * reads it), wire name (the preference file stores it) and identity through a double conversion — so an added
 * page or a renamed wire value fails a test instead of rendering the wrong screen or losing a saved answer.
 */
class OnboardingMappingTest {

    @Test
    fun `双向映射无损`() {
        OnboardingStep.entries.forEach { step ->
            assertEquals("app -> 设计系统 -> app 必须回到原点：$step", step, step.toMcaStep().toOnboardingStep())
        }
        OnboardingPath.entries.forEach { path ->
            assertEquals("app -> 设计系统 -> app 必须回到原点：$path", path, path.toMcaChoice().toOnboardingPath())
        }
        McaOnboardingStep.entries.forEach { step ->
            assertEquals("设计系统 -> app -> 设计系统必须回到原点：$step", step, step.toOnboardingStep().toMcaStep())
        }
        McaOnboardingPathChoice.entries.forEach { choice ->
            assertEquals(
                "设计系统 -> app -> 设计系统必须回到原点：$choice",
                choice,
                choice.toOnboardingPath().toMcaChoice()
            )
        }
    }

    @Test
    fun `步骤顺序在两个模块间一致`() {
        // The progress row is `ordinal + 1`, so reordering one enum would mark the wrong dot as current
        // without breaking anything else.
        assertEquals(McaOnboardingStep.entries.size, OnboardingStep.entries.size)
        OnboardingStep.entries.forEach { step ->
            assertEquals("步骤 $step 的序号必须与设计系统一致", step.ordinal, step.toMcaStep().ordinal)
        }
        assertEquals(McaOnboardingPathChoice.entries.size, OnboardingPath.entries.size)
        OnboardingPath.entries.forEach { path ->
            assertEquals("路径 $path 的序号必须与设计系统一致", path.ordinal, path.toMcaChoice().ordinal)
        }
    }

    @Test
    fun `wireName 在两个模块间一致`() {
        // The persisted wire name and the design system's stable identifier are written by two modules but
        // land in the same preference file, so they must never drift apart.
        OnboardingPath.entries.forEach { path ->
            assertEquals("路径 $path 的 wireName 必须与设计系统一致", path.wireName, path.toMcaChoice().wireName)
        }
        assertEquals("experience", OnboardingPath.EXPERIENCE.toMcaChoice().wireName)
        assertEquals("production", OnboardingPath.PRODUCTION.toMcaChoice().wireName)
    }

    @Test
    fun `设计系统的路径选择可以通过 wireName 持久化回来`() {
        // Guards the round trip a restored flow performs: the page hands back its choice, the app persists a
        // wire name, and reading that wire name must produce the same choice again.
        McaOnboardingPathChoice.entries.forEach { choice ->
            assertEquals(
                "设计系统的 $choice 必须能通过 wireName 读回同一个答案",
                choice.toOnboardingPath(),
                OnboardingPath.fromWire(choice.wireName)
            )
        }
    }

    @Test
    fun `未选路径在投影中为空`() {
        // `null` is the "not answered" value on both sides of the boundary: the app enum's `Unset` and the
        // unresolved design system choice. A flow that has not answered the path page must project `null`,
        // never a defaulted EXPERIENCE, or the page would render a preselected answer.
        val fresh = OnboardingFlowController(OnboardingPreferences(RecordingOnboardingStore())).load()

        assertNull(fresh.path)
        assertNull(OnboardingPath.fromWire(null))
        assertNull(McaOnboardingPathChoice.fromWire(null))
    }
}
