package com.muyuchat.mca

import java.math.BigDecimal
import org.json.JSONObject

/** T2B22-T4: native-execution JSON primitives and prompt-weighting validation; moved verbatim from ImageExecutionProfileNativeContract.kt. */

internal fun ImageExecutionProfileNativeContract.validatePromptWeightingEvidence(
        native: ImageNativeEffectiveExecution
    ): ImageNativeEffectiveExecution {
        if (native.positiveWeightedTokenCount !in 0..native.tokenCount) {
            invalid(
                "positiveWeightedTokenCount",
                "Native positive weighted-token count must fit the executed token capacity."
            )
        }
        if (native.negativeWeightedTokenCount !in 0..native.tokenCount) {
            invalid(
                "negativeWeightedTokenCount",
                "Native negative weighted-token count must fit the executed token capacity."
            )
        }
        if (
            native.positiveWeightedTokenCount + native.negativeWeightedTokenCount >
            native.tokenCount
        ) {
            invalid(
                "positiveWeightedTokenCount,negativeWeightedTokenCount",
                "Native weighted-token counts exceed the executed positive/negative capacity."
            )
        }
        if (!IMAGE_NATIVE_CONTRACT_SHA256.matches(native.promptWeightFingerprint)) {
            invalid(
                "promptWeightFingerprint",
                "Native prompt-weight fingerprint must be a 64-character SHA-256 value."
            )
        }
        val weightedTokenCount =
            native.positiveWeightedTokenCount + native.negativeWeightedTokenCount
        if (native.promptWeightingApplied && weightedTokenCount == 0) {
            invalid(
                "promptWeightingApplied",
                "Applied prompt weighting requires at least one weighted prompt token."
            )
        }
        if (!native.promptWeightingApplied && weightedTokenCount != 0) {
            invalid(
                "promptWeightingApplied",
                "Weighted-token counts must be zero when prompt weighting was not applied."
            )
        }
        return native
    }

internal fun JSONObject.requiredValue(field: String): Any {
        if (!has(field) || isNull(field)) invalid(field, "Required native execution field is missing.")
        return get(field)
    }

internal fun JSONObject.nativeContractRequiredString(field: String): String {
        val value = requiredValue(field)
        if (value !is String || value.isBlank()) {
            invalid(field, "Native execution field must be a non-blank string.")
        }
        return value
    }

internal fun JSONObject.requiredBoolean(field: String): Boolean {
        val value = requiredValue(field)
        if (value !is Boolean) invalid(field, "Native execution field must be a boolean.")
        return value
    }

internal fun JSONObject.nativeContractRequiredInt(field: String): Int {
        val value = requiredNumber(field)
        return try {
            BigDecimal(value.toString()).intValueExact()
        } catch (error: ArithmeticException) {
            invalid(field, "Native execution field must be an exact 32-bit integer.", error)
        } catch (error: NumberFormatException) {
            invalid(field, "Native execution field must be an exact 32-bit integer.", error)
        }
    }

internal fun JSONObject.requiredLong(field: String): Long {
        val value = requiredNumber(field)
        return try {
            BigDecimal(value.toString()).longValueExact()
        } catch (error: ArithmeticException) {
            invalid(field, "Native execution field must be an exact 64-bit integer.", error)
        } catch (error: NumberFormatException) {
            invalid(field, "Native execution field must be an exact 64-bit integer.", error)
        }
    }

internal fun JSONObject.requiredDouble(field: String): Double {
        val value = requiredNumber(field).toDouble()
        if (!value.isFinite()) invalid(field, "Native execution field must be finite.")
        return value
    }

internal fun JSONObject.requiredNumber(field: String): Number {
        val value = requiredValue(field)
        if (value !is Number) invalid(field, "Native execution field must be numeric.")
        return value
    }

internal fun <T : Enum<T>> JSONObject.requiredEnum(field: String, values: List<T>): T {
        val wireValue = nativeContractRequiredString(field)
        // Native bridges historically emitted product names in lower-case
        // (`flow_match`) while the shared contract uses enum names
        // (`FLOW_MATCH`). Accept the canonical spelling and a normalized
        // case/spacing variant, but keep unknown values strict.
        val normalized = wireValue.trim()
            .uppercase()
            .replace('-', '_')
            .replace(' ', '_')
        return values.firstOrNull { it.name == wireValue || it.name == normalized }
            ?: invalid(field, "Unknown native execution enum value: $wireValue")
    }

internal fun invalid(field: String, message: String, cause: Throwable? = null): Nothing =
        throw ImageNativeExecutionContractException(
            code = IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID,
            field = field,
            message = message,
            cause = cause
        )

internal val IMAGE_NATIVE_CONTRACT_SHA256 = Regex("^[0-9a-f]{64}$")
