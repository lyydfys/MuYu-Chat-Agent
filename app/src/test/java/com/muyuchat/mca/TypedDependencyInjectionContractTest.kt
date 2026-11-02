package com.muyuchat.mca

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.composition.MainRuntimeAssembly
import java.lang.reflect.Constructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The JVM constructor boundary must reject a wrong injected dependency before any application or
 * native owner can be created. Null remains the explicit request for the normal production factory.
 */
class TypedDependencyInjectionContractTest {
    @Test
    fun `main view model injection slots have concrete dependency types`() {
        assertEquals(
            listOf(
                LocalApiRuntimeOwner::class.java,
                ChatAppearanceCoordinator::class.java,
                MainPostStartupCoordinator::class.java,
                ChatSessionManagementCoordinator::class.java,
                ChatInputAndAttachmentCoordinator::class.java,
                ChatImageWorkflowCoordinator::class.java,
                ChatWorkflowCoordinator::class.java,
                DocumentImportCoordinator::class.java,
                AppLifecycleCoordinator::class.java,
                CacheManagementCoordinator::class.java,
            ),
            mainConstructor().parameterTypes.drop(3).dropLast(1),
        )
    }

    @Test
    fun `wrong injected dependencies cannot reach main view model initialization`() {
        val constructor = mainConstructor()
        for (slot in 3 until constructor.parameterCount - 1) {
            val arguments = arrayOfNulls<Any>(constructor.parameterCount)
            arguments[1] = true
            arguments[2] = true
            arguments[slot] = Any()

            // The other reference arguments deliberately stay null. Entering the Kotlin body
            // would therefore throw InvocationTargetException; argument rejection must come first.
            assertThrows("Injection slot $slot must reject an unrelated object", IllegalArgumentException::class.java) {
                constructor.newInstance(*arguments)
            }
        }
    }

    @Test
    fun `runtime assembly rejects a wrong engine before creating a production service`() {
        val constructor = MainRuntimeAssembly::class.java.declaredConstructors.single {
            !it.isSynthetic && it.parameterCount == 5
        }.apply { isAccessible = true }
        assertEquals(McaInferenceService::class.java, constructor.parameterTypes.last())
        val arguments = arrayOfNulls<Any>(constructor.parameterCount)
        arguments[arguments.lastIndex] = Any()

        assertThrows(IllegalArgumentException::class.java) {
            constructor.newInstance(*arguments)
        }
    }

    private fun mainConstructor(): Constructor<*> =
        MainViewModel::class.java.declaredConstructors.single {
            !it.isSynthetic && it.parameterTypes.lastOrNull() == MainViewModelStartupContext::class.java
        }.apply { isAccessible = true }
}
