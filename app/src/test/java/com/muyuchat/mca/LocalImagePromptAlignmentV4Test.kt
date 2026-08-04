package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImagePromptAlignmentV4Test {
    @Test
    fun `phase A plan uses opaque randomized ids without exposing canary provenance`() {
        val first = LocalImagePromptAlignmentV4(CountingEntropy(0))
            .createTranslationPlan(fixtureClauses())
        val second = LocalImagePromptAlignmentV4(CountingEntropy(91))
            .createTranslationPlan(fixtureClauses())
        val request = first.phaseARequestJson()
        val items = request.getJSONArray("items")
        val ids = mutableSetOf<String>()
        val sources = mutableSetOf<String>()

        assertEquals(
            setOf("contract_version", "items"),
            request.keys().asSequence().toSet()
        )
        assertEquals(LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION,
            request.getInt("contract_version"))
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            assertEquals(
                setOf("slot_id", "role", "source"),
                item.keys().asSequence().toSet()
            )
            assertTrue(Regex("a_[0-9a-f]{32}").matches(item.getString("slot_id")))
            assertTrue(ids.add(item.getString("slot_id")))
            assertTrue(item.getString("role") in setOf("positive", "negative"))
            sources += item.getString("source")
        }

        assertEquals(fixtureSourceTexts(), sources)
        assertFalse(request.toString().lowercase().contains("canary"))
        assertFalse(request.toString().lowercase().contains("origin"))
        assertEquals(first.planSha256, second.planSha256)
        assertNotEquals(first.phaseARequestJson().toString(), second.phaseARequestJson().toString())
        assertTrue(Regex("[0-9a-f]{64}").matches(first.planSha256))
    }

    @Test
    fun `phase A accepts arbitrary Chinese clauses and reconstructs every source channel`() {
        val alignment = LocalImagePromptAlignmentV4(CountingEntropy(7))
        val plan = alignment.createTranslationPlan(fixtureClauses())
        val result = alignment.parsePhaseAResponse(plan, phaseAResponse(plan).toString())

        assertEquals(
            listOf("a castle under moonlight", "three white birds above the castle"),
            result.translatedPromptClauses()
        )
        assertEquals(listOf("watermark"), result.translatedNegativePromptClauses())
        assertEquals(
            listOf(
                "one red cup on a blue table",
                "two green apples to the left of the cup"
            ),
            result.translatedCanaryPromptClauses()
        )
        assertEquals(
            listOf("people", "text", "extra fruit"),
            result.translatedCanaryNegativePromptClauses()
        )
    }

    @Test
    fun `phase A response requires exact slots fields ASCII and pass through clauses`() {
        val clauses = fixtureClauses().copy(promptClauses = listOf("保留 BREAK", "literal_tag"))
        val alignment = LocalImagePromptAlignmentV4(CountingEntropy(13))
        val plan = alignment.createTranslationPlan(clauses)
        val valid = phaseAResponse(plan, mapOf(
            "保留 BREAK" to "keep BREAK",
            "literal_tag" to "literal_tag"
        ))
        alignment.parsePhaseAResponse(plan, valid.toString())

        val items = valid.getJSONArray("translations")
        val firstSlot = items.getJSONObject(0).getString("slot_id")
        assertTrue(plan.units.none { unit -> unit.sourceText == "literal_tag" })
        assertEquals(
            listOf("keep BREAK", "literal_tag"),
            alignment.parsePhaseAResponse(plan, valid.toString()).translatedPromptClauses()
        )
        assertFalse(plan.phaseARequestJson().toString().contains("literal_tag"))

        assertThrows(IllegalArgumentException::class.java) {
            alignment.parsePhaseAResponse(
                plan,
                JSONObject(valid.toString()).put("unexpected", true).toString()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val duplicate = JSONObject(valid.toString())
            duplicate.getJSONArray("translations").getJSONObject(1)
                .put("slot_id", firstSlot)
            alignment.parsePhaseAResponse(plan, duplicate.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val nonAscii = JSONObject(valid.toString())
            nonAscii.getJSONArray("translations").getJSONObject(0)
                .put("translation", "仍有中文")
            alignment.parsePhaseAResponse(plan, nonAscii.toString())
        }
    }

    @Test
    fun `pure ASCII tag clauses bypass both model phases and remain byte exact`() {
        val asciiTags = List(60) { index -> "tag_$index" }
        val clauses = fixtureClauses().copy(
            promptClauses = asciiTags + "猫",
            negativePromptClauses = listOf("bad_hands")
        )
        val alignment = LocalImagePromptAlignmentV4(CountingEntropy(17))
        val plan = alignment.createTranslationPlan(clauses)
        val result = alignment.parsePhaseAResponse(
            plan,
            phaseAResponse(plan, mapOf("猫" to "cat")).toString()
        )

        assertEquals(6, plan.units.size)
        assertTrue(plan.units.none { unit -> unit.sourceText.startsWith("tag_") })
        assertTrue(plan.units.none { unit -> unit.sourceText == "bad_hands" })
        assertEquals(asciiTags + "cat", result.translatedPromptClauses())
        assertEquals(listOf("bad_hands"), result.translatedNegativePromptClauses())
        val challenge = alignment.createVerificationChallenge(result)
        alignment.verifyPhaseBResponse(challenge, phaseBResponse(challenge).toString())
    }

    @Test
    fun `maximum product phase B shape fits the shared response budget`() {
        val clauses = LocalImagePromptAlignmentClauseSet(
            promptClauses = List(27) { index -> "中文子句$index" },
            negativePromptClauses = null,
            canaryPromptClauses = listOf("中文金杯", "中文蓝桌"),
            canaryNegativePromptClauses = listOf("不要人物", "不要文字", "不要水印")
        )
        val alignment = LocalImagePromptAlignmentV4(CountingEntropy(23))
        val plan = alignment.createTranslationPlan(clauses)
        val result = LocalImagePromptTranslationResultV4(
            plan = plan,
            translationsByUnit = plan.units.associate { unit ->
                unit.ordinal to "translated clause ${unit.ordinal}"
            }
        )
        val challenge = alignment.createVerificationChallenge(result)
        val response = phaseBResponse(challenge).toString()

        assertEquals(32, plan.units.size)
        assertTrue(response.length <= LOCAL_IMAGE_PROMPT_ALIGNMENT_MAX_RESPONSE_CHARS)
        alignment.verifyPhaseBResponse(challenge, response)
    }

    @Test
    fun `phase B independently randomizes both pools and returns a canonical receipt`() {
        val alignment = LocalImagePromptAlignmentV4(CountingEntropy(29))
        val plan = alignment.createTranslationPlan(fixtureClauses())
        val result = alignment.parsePhaseAResponse(plan, phaseAResponse(plan).toString())
        val challenge = alignment.createVerificationChallenge(result)
        val request = challenge.phaseBRequestJson()
        val phaseAIds = plan.phaseARequestJson().getJSONArray("items").strings("slot_id")
        val sourceIds = request.getJSONArray("sources").strings("source_id")
        val candidateIds = request.getJSONArray("candidates").strings("candidate_id")

        assertTrue(sourceIds.all { id -> Regex("s_[0-9a-f]{32}").matches(id) })
        assertTrue(candidateIds.all { id -> Regex("c_[0-9a-f]{32}").matches(id) })
        assertTrue((phaseAIds intersect sourceIds).isEmpty())
        assertTrue((phaseAIds intersect candidateIds).isEmpty())
        assertEquals(sourceIds.size, sourceIds.toSet().size)
        assertEquals(candidateIds.size, candidateIds.toSet().size)
        assertFalse(request.toString().lowercase().contains("canary"))
        assertFalse(request.toString().lowercase().contains("ordinal"))
        assertFalse(request.toString().lowercase().contains("slot_id"))

        val receipt = alignment.verifyPhaseBResponse(
            challenge,
            phaseBResponse(challenge).toString()
        )
        assertEquals(plan.planSha256, receipt.planSha256)
        assertEquals(challenge.sources.size, receipt.verifiedUnitCount)
        assertTrue(Regex("[0-9a-f]{64}").matches(receipt.receiptSha256))
    }

    @Test
    fun `phase B rejects swaps repeated ids and non equivalent verdicts`() {
        val alignment = LocalImagePromptAlignmentV4(CountingEntropy(41))
        val plan = alignment.createTranslationPlan(fixtureClauses())
        val result = alignment.parsePhaseAResponse(plan, phaseAResponse(plan).toString())
        val challenge = alignment.createVerificationChallenge(result)
        val positiveSources = challenge.sources
            .filter { source -> source.role == LocalImagePromptAlignmentRole.POSITIVE }
            .take(2)
        val swappedOrdinals = mapOf(
            positiveSources[0].unitOrdinal to positiveSources[1].unitOrdinal,
            positiveSources[1].unitOrdinal to positiveSources[0].unitOrdinal
        )

        assertThrows(IllegalArgumentException::class.java) {
            alignment.verifyPhaseBResponse(
                challenge,
                phaseBResponse(challenge, swappedOrdinals).toString()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val duplicate = phaseBResponse(challenge)
            val matches = duplicate.getJSONArray("matches")
            matches.getJSONObject(1).put(
                "candidate_id",
                matches.getJSONObject(0).getString("candidate_id")
            )
            alignment.verifyPhaseBResponse(challenge, duplicate.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val wrongVerdict = phaseBResponse(challenge)
            wrongVerdict.getJSONArray("matches").getJSONObject(0)
                .put("verdict", "similar")
            alignment.verifyPhaseBResponse(challenge, wrongVerdict.toString())
        }
    }

    @Test
    fun `phase B rejects cross ordinal matches even when English text is identical`() {
        val clauses = fixtureClauses().copy(promptClauses = listOf("猫", "小猫"))
        val alignment = LocalImagePromptAlignmentV4(CountingEntropy(57))
        val plan = alignment.createTranslationPlan(clauses)
        val result = alignment.parsePhaseAResponse(
            plan,
            phaseAResponse(plan, mapOf("猫" to "cat", "小猫" to "cat")).toString()
        )
        val challenge = alignment.createVerificationChallenge(result)
        val userOrdinals = plan.units
            .filter { unit -> unit.occurrences.any { occurrence ->
                occurrence.origin == LocalImagePromptAlignmentOrigin.USER_PROMPT
            } }
            .map(LocalImagePromptTranslationUnitV4::ordinal)
        val swappedOrdinals = mapOf(
            userOrdinals[0] to userOrdinals[1],
            userOrdinals[1] to userOrdinals[0]
        )

        assertThrows(IllegalArgumentException::class.java) {
            alignment.verifyPhaseBResponse(
                challenge,
                phaseBResponse(challenge, swappedOrdinals).toString()
            )
        }
    }

    @Test
    fun `canonical plan and receipt hashes ignore entropy but bind source and translation`() {
        val firstAlignment = LocalImagePromptAlignmentV4(CountingEntropy(3))
        val secondAlignment = LocalImagePromptAlignmentV4(CountingEntropy(103))
        val firstPlan = firstAlignment.createTranslationPlan(fixtureClauses())
        val secondPlan = secondAlignment.createTranslationPlan(fixtureClauses())
        val firstResult = firstAlignment.parsePhaseAResponse(
            firstPlan,
            phaseAResponse(firstPlan).toString()
        )
        val secondResult = secondAlignment.parsePhaseAResponse(
            secondPlan,
            phaseAResponse(secondPlan).toString()
        )
        val firstChallenge = firstAlignment.createVerificationChallenge(firstResult)
        val firstReceipt = firstAlignment.verifyPhaseBResponse(
            firstChallenge,
            phaseBResponse(firstChallenge).toString()
        )
        val secondChallenge = secondAlignment.createVerificationChallenge(secondResult)
        val secondReceipt = secondAlignment.verifyPhaseBResponse(
            secondChallenge,
            phaseBResponse(secondChallenge).toString()
        )

        assertEquals(firstPlan.planSha256, secondPlan.planSha256)
        assertEquals(firstReceipt.receiptSha256, secondReceipt.receiptSha256)

        val changedSource = firstAlignment.createTranslationPlan(
            fixtureClauses().copy(promptClauses = listOf("晨雾中的古堡"))
        )
        assertNotEquals(firstPlan.planSha256, changedSource.planSha256)
        val emptyNegative = firstAlignment.createTranslationPlan(
            fixtureClauses().copy(negativePromptClauses = emptyList())
        )
        val absentNegative = firstAlignment.createTranslationPlan(
            fixtureClauses().copy(negativePromptClauses = null)
        )
        assertNotEquals(emptyNegative.planSha256, absentNegative.planSha256)

        val changedTranslations = translationsBySource().toMutableMap().apply {
            put("月光下的古堡", "an old castle under moonlight")
        }
        val changedResult = firstAlignment.parsePhaseAResponse(
            firstPlan,
            phaseAResponse(firstPlan, changedTranslations).toString()
        )
        val changedChallenge = firstAlignment.createVerificationChallenge(changedResult)
        val changedReceipt = firstAlignment.verifyPhaseBResponse(
            changedChallenge,
            phaseBResponse(changedChallenge).toString()
        )
        assertNotEquals(firstReceipt.receiptSha256, changedReceipt.receiptSha256)
    }

    @Test
    fun `duplicate source clauses share one opaque unit and reconstruct all occurrences`() {
        val clauses = fixtureClauses().copy(
            promptClauses = listOf("月光下的古堡", "月光下的古堡")
        )
        val alignment = LocalImagePromptAlignmentV4(CountingEntropy(71))
        val plan = alignment.createTranslationPlan(clauses)
        val duplicateUnit = plan.units.single { unit -> unit.sourceText == "月光下的古堡" }
        val result = alignment.parsePhaseAResponse(plan, phaseAResponse(plan).toString())

        assertEquals(2, duplicateUnit.occurrences.count { occurrence ->
            occurrence.origin == LocalImagePromptAlignmentOrigin.USER_PROMPT
        })
        assertEquals(
            listOf("a castle under moonlight", "a castle under moonlight"),
            result.translatedPromptClauses()
        )
        val challenge = alignment.createVerificationChallenge(result)
        alignment.verifyPhaseBResponse(challenge, phaseBResponse(challenge).toString())
    }

    @Test
    fun `system prompt fingerprints are phase separated and content bound`() {
        val translation = localImagePromptAlignmentSystemPromptSha256V4(
            LocalImagePromptAlignmentSystemPhase.TRANSLATION,
            "translate exactly"
        )
        assertEquals(
            translation,
            localImagePromptAlignmentSystemPromptSha256V4(
                LocalImagePromptAlignmentSystemPhase.TRANSLATION,
                "translate exactly"
            )
        )
        assertNotEquals(
            translation,
            localImagePromptAlignmentSystemPromptSha256V4(
                LocalImagePromptAlignmentSystemPhase.VERIFICATION,
                "translate exactly"
            )
        )
        assertNotEquals(
            translation,
            localImagePromptAlignmentSystemPromptSha256V4(
                LocalImagePromptAlignmentSystemPhase.TRANSLATION,
                "translate faithfully"
            )
        )
    }

    private fun fixtureClauses(): LocalImagePromptAlignmentClauseSet =
        LocalImagePromptAlignmentClauseSet(
            promptClauses = listOf("月光下的古堡", "城堡上空有三只白色飞鸟"),
            negativePromptClauses = listOf("不要水印"),
            canaryPromptClauses = listOf(
                "一只红色杯子放在蓝色桌子上",
                "杯子左侧有两个绿色苹果"
            ),
            canaryNegativePromptClauses = listOf("不要人物", "不要文字", "不要多余水果")
        )

    private fun fixtureSourceTexts(): Set<String> = buildSet {
        val clauses = fixtureClauses()
        addAll(clauses.promptClauses)
        addAll(clauses.negativePromptClauses.orEmpty())
        addAll(clauses.canaryPromptClauses)
        addAll(clauses.canaryNegativePromptClauses)
    }

    private fun translationsBySource(): Map<String, String> = mapOf(
        "月光下的古堡" to "a castle under moonlight",
        "城堡上空有三只白色飞鸟" to "three white birds above the castle",
        "不要水印" to "watermark",
        "一只红色杯子放在蓝色桌子上" to "one red cup on a blue table",
        "杯子左侧有两个绿色苹果" to "two green apples to the left of the cup",
        "不要人物" to "people",
        "不要文字" to "text",
        "不要多余水果" to "extra fruit"
    )

    private fun phaseAResponse(
        plan: LocalImagePromptTranslationPlanV4,
        overrides: Map<String, String> = emptyMap()
    ): JSONObject {
        val translations = translationsBySource() + overrides
        return JSONObject()
            .put("contract_version", LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION)
            .put("translations", JSONArray().apply {
                plan.units.asReversed().forEach { unit ->
                    put(JSONObject()
                        .put("slot_id", unit.phaseASlotId)
                        .put(
                            "translation",
                            requireNotNull(translations[unit.sourceText]) {
                                "Missing fixture translation for ${unit.sourceText}"
                            }
                        )
                    )
                }
            })
    }

    private fun phaseBResponse(
        challenge: LocalImagePromptVerificationChallengeV4,
        candidateOrdinalOverrides: Map<Int, Int> = emptyMap()
    ): JSONObject {
        val candidateByOrdinal = challenge.candidates.associateBy { candidate ->
            candidate.unitOrdinal
        }
        return JSONObject()
            .put("contract_version", LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION)
            .put("matches", JSONArray().apply {
                challenge.sources.asReversed().forEach { source ->
                    val candidateOrdinal = candidateOrdinalOverrides[source.unitOrdinal]
                        ?: source.unitOrdinal
                    put(JSONObject()
                        .put("source_id", source.sourceId)
                        .put(
                            "candidate_id",
                            requireNotNull(candidateByOrdinal[candidateOrdinal]).candidateId
                        )
                        .put("verdict", "equivalent")
                    )
                }
            })
    }

    private fun JSONArray.strings(field: String): List<String> = buildList {
        for (index in 0 until length()) add(getJSONObject(index).getString(field))
    }

    private class CountingEntropy(seed: Int) : LocalImagePromptAlignmentEntropy {
        private var invocation = seed

        override fun nextBytes(destination: ByteArray) {
            val current = invocation++
            destination.indices.forEach { index ->
                destination[index] = if (index < Int.SIZE_BYTES) {
                    (current ushr (index * Byte.SIZE_BITS)).toByte()
                } else {
                    (current + index * 31).toByte()
                }
            }
        }
    }
}
