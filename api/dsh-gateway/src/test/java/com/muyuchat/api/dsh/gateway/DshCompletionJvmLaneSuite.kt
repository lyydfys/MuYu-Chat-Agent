package com.muyuchat.api.dsh.gateway

import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses

/**
 * One JVM lane that executes every DSH completion unit and integration suite in a single JVM:
 * the gateway wire/protocol suites, the runtime owner/service/payload suites, the contract suites
 * and the MCA-side adapter suites. It exists so a completion run can prove "N classes / M tests
 * really executed" in one report instead of merging several Gradle invocations.
 */
@RunWith(Suite::class)
@SuiteClasses(
    DshGatewayJsonTest::class,
    DshGatewaySecretTest::class,
    DshOpenAiRequestTest::class,
    DshOpenAiWireTest::class,
    DshToolCallAssemblerTest::class,
    DshGatewayCallsTest::class,
    DshGatewayAuthRoutingIntegrationTest::class,
    DshGatewayC06IntegrationTest::class,
    DshGatewayCancellationIntegrationTest::class,
    DshGatewayCatalogIntegrationTest::class,
    DshContextCapacityTest::class,
    DshGatewayFidelityIntegrationTest::class
)
class DshCompletionJvmLaneSuite
