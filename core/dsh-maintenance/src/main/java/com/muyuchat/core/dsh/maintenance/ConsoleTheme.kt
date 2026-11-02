package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DSH console theme schema (D23).
 *
 * A theme is restricted style data: known color/scalar tokens, bounded UI copy, a closed card
 * order vocabulary and gesture bindings that must name an action from the typed registry. Script
 * fields, HTML, `url(...)`, unknown top-level keys and unknown token names are rejected, so
 * importing a theme can never grant code execution. Bad stored config falls back to the built-in
 * default instead of failing the console.
 */

enum class DshConsoleActionId {
    OPEN_RESCUE,
    OPEN_DIAGNOSTICS,
    RUN_SELF_CHECK,
    OPEN_SESSION_TRASH,
    START_BACKUP,
    OPEN_RUNTIME
}

data class DshConsoleActionSpec(
    val id: DshConsoleActionId,
    val description: String,
    /** Wide-permission action: a binding must always require an explicit confirmation. */
    val privileged: Boolean = false
)

object DshConsoleActions {
    private val specs = listOf(
        DshConsoleActionSpec(DshConsoleActionId.OPEN_RESCUE, "open the rescue surface", privileged = true),
        DshConsoleActionSpec(DshConsoleActionId.OPEN_DIAGNOSTICS, "export a redacted diagnostic bundle"),
        DshConsoleActionSpec(DshConsoleActionId.RUN_SELF_CHECK, "run the self-check"),
        DshConsoleActionSpec(DshConsoleActionId.OPEN_SESSION_TRASH, "open the session recycle bin"),
        DshConsoleActionSpec(DshConsoleActionId.START_BACKUP, "start a user-data backup"),
        DshConsoleActionSpec(DshConsoleActionId.OPEN_RUNTIME, "open the runtime panel")
    )

    fun all(): List<DshConsoleActionSpec> = specs

    fun find(id: DshConsoleActionId): DshConsoleActionSpec = specs.first { it.id == id }

    fun parse(raw: String): DshConsoleActionId? = DshConsoleActionId.entries.firstOrNull { it.name == raw }
}

enum class DshConsoleGesture { CLICK, LONG_PRESS, DOUBLE_CLICK }

data class DshThemeActionBinding(
    val gesture: DshConsoleGesture,
    val actionId: DshConsoleActionId,
    val requireConfirmation: Boolean = false
)

data class DshConsoleTheme(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val tokens: Map<String, String>,
    val text: Map<String, String> = emptyMap(),
    val layout: List<String> = emptyList(),
    val actions: List<DshThemeActionBinding> = emptyList()
) {
    fun token(name: String): String? = tokens[name]

    companion object {
        const val SCHEMA_VERSION = 1

        val COLOR_TOKENS = setOf(
            "background", "surface", "textPrimary", "textSecondary", "accent", "danger",
            "border", "codeBackground", "codeText", "approvalBackground", "approvalText"
        )
        val SCALAR_TOKENS = setOf("fontScale", "radius", "spacing")

        /** Required contrast pairs (foreground, background, minimum WCAG ratio). */
        val REQUIRED_CONTRAST = listOf(
            Triple("textPrimary", "background", 4.5),
            Triple("textSecondary", "background", 4.5),
            Triple("accent", "background", 3.0),
            Triple("codeText", "codeBackground", 4.5),
            Triple("approvalText", "approvalBackground", 4.5)
        )

        /** Closed vocabulary for console card order; a theme cannot invent a surface. */
        val LAYOUT_IDS = listOf(
            "runtime", "account", "models", "plugins", "browser",
            "vscreen", "diagnostics", "rescue", "maintenance"
        )

        val DEFAULT = DshConsoleTheme(
            schemaVersion = SCHEMA_VERSION,
            id = "mca-dsh-default",
            name = "MCA DSH default",
            tokens = mapOf(
                "background" to "#0B0F14",
                "surface" to "#131A22",
                "textPrimary" to "#F2F5F8",
                "textSecondary" to "#B9C4CE",
                "accent" to "#6EA8FE",
                "danger" to "#FF6B6B",
                "border" to "#2A3542",
                "codeBackground" to "#0F151C",
                "codeText" to "#E6EDF3",
                "approvalBackground" to "#1B2430",
                "approvalText" to "#F2F5F8",
                "fontScale" to "1.0",
                "radius" to "12",
                "spacing" to "8"
            ),
            layout = LAYOUT_IDS,
            actions = emptyList()
        )
    }
}

sealed interface DshThemeValidation {
    data object Valid : DshThemeValidation
    data class Invalid(val failure: DshMaintenanceFailure) : DshThemeValidation
}

object DshConsoleThemeValidator {
    private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")
    private val TOKEN_NAME = Regex("^[a-z][a-zA-Z0-9]{0,31}$")
    private val COPY_KEY = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val FORBIDDEN_TEXT = listOf("javascript:", "<script", "url(", "expression(", "eval(", "onload", "onclick")
    private val CONTRAST_REQUIRED_TOKENS = DshConsoleTheme.COLOR_TOKENS

    fun validate(theme: DshConsoleTheme): DshThemeValidation {
        if (theme.schemaVersion != DshConsoleTheme.SCHEMA_VERSION) {
            return invalid(DshMaintenanceError.VERSION_UNSUPPORTED, "theme schema version is not supported")
        }
        if (!ID_PATTERN.matches(theme.id)) return invalid("theme id is invalid")
        if (theme.name.isBlank() || theme.name.length > 80 || theme.name.any { it.code < 0x20 }) {
            return invalid("theme name is invalid")
        }
        if (theme.tokens.isEmpty() || theme.tokens.size > 200) return invalid("theme token count is outside the budget")
        for ((name, value) in theme.tokens) {
            if (!TOKEN_NAME.matches(name)) return invalid("theme token name is invalid")
            if (name in DshConsoleTheme.COLOR_TOKENS) {
                if (!HEX_COLOR.matches(value)) {
                    return invalid("theme color token $name is not an opaque #RRGGBB value")
                }
            } else if (name in DshConsoleTheme.SCALAR_TOKENS) {
                val number = value.toDoubleOrNull() ?: return invalid("theme scalar token $name is not a number")
                if (number !in 0.0..4.0 && name == "fontScale") return invalid("fontScale is outside 0.5..4.0")
                if (number !in 0.0..64.0 && name != "fontScale") return invalid("theme scalar token $name is out of range")
            } else {
                return invalid("theme token $name is not part of the console schema")
            }
        }
        for (required in CONTRAST_REQUIRED_TOKENS) {
            if (required !in theme.tokens) return invalid("theme is missing required color token $required")
        }
        for ((foreground, background, minimum) in DshConsoleTheme.REQUIRED_CONTRAST) {
            val ratio = contrastRatio(theme.tokens.getValue(foreground), theme.tokens.getValue(background))
            if (ratio < minimum) {
                return invalid(
                    "theme contrast $foreground/$background is ${"%.2f".format(ratio)}, below $minimum"
                )
            }
        }
        if (theme.text.size > 100) return invalid("theme copy is outside the budget")
        for ((key, value) in theme.text) {
            if (!COPY_KEY.matches(key)) return invalid("theme copy key is invalid")
            if (value.length > 200) return invalid("theme copy value is too long")
            if (FORBIDDEN_TEXT.any { value.contains(it, ignoreCase = true) }) {
                return invalid("theme copy contains executable content")
            }
        }
        if (theme.layout.size > 50) return invalid("theme layout is outside the budget")
        for (id in theme.layout) {
            if (id !in DshConsoleTheme.LAYOUT_IDS) return invalid("theme layout references an unknown console surface")
        }
        if (theme.actions.size > 6) return invalid("theme action count is outside the budget")
        val gestures = HashSet<DshConsoleGesture>()
        for (binding in theme.actions) {
            if (!gestures.add(binding.gesture)) return invalid("theme binds a gesture twice")
            val spec = DshConsoleActions.find(binding.actionId)
            if (spec.privileged && !binding.requireConfirmation) {
                return invalid("theme binding for a privileged action must require confirmation")
            }
        }
        return DshThemeValidation.Valid
    }

    private fun invalid(message: String): DshThemeValidation =
        invalid(DshMaintenanceError.THEME_INVALID, message)

    private fun invalid(code: DshMaintenanceError, message: String): DshThemeValidation =
        DshThemeValidation.Invalid(DshMaintenanceFailure(code, message))

    /** WCAG 2.x relative-luminance contrast for two opaque `#RRGGBB` values. */
    fun contrastRatio(foreground: String, background: String): Double {
        val front = luminance(foreground)
        val back = luminance(background)
        val lighter = maxOf(front, back)
        val darker = minOf(front, back)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: String): Double {
        val red = color.substring(1, 3).toInt(16) / 255.0
        val green = color.substring(3, 5).toInt(16) / 255.0
        val blue = color.substring(5, 7).toInt(16) / 255.0
        fun channel(value: Double): Double =
            if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }
}

sealed interface DshThemeDecodeResult {
    data class Valid(val theme: DshConsoleTheme) : DshThemeDecodeResult
    data class Invalid(val failure: DshMaintenanceFailure) : DshThemeDecodeResult
}

/** Strict JSON codec; export/import is a byte-exact round trip for accepted themes. */
object DshConsoleThemeCodec {
    fun encode(theme: DshConsoleTheme): String {
        val json = JSONObject()
        json.put("schemaVersion", theme.schemaVersion)
        json.put("id", theme.id)
        json.put("name", theme.name)
        json.put("tokens", JSONObject(theme.tokens.toSortedMap()))
        json.put("text", JSONObject(theme.text.toSortedMap()))
        json.put("layout", JSONArray(theme.layout))
        val actions = JSONArray()
        for (binding in theme.actions) {
            actions.put(
                JSONObject().apply {
                    put("gesture", binding.gesture.name)
                    put("action", binding.actionId.name)
                    put("requireConfirmation", binding.requireConfirmation)
                }
            )
        }
        json.put("actions", actions)
        return json.toString()
    }

    fun decode(text: String): DshThemeDecodeResult {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return invalid("theme is not a JSON object")
        val allowedTopLevel = setOf("schemaVersion", "id", "name", "tokens", "text", "layout", "actions")
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowedTopLevel) return invalid("theme has an unknown top-level field")
        }
        val tokensJson = json.optJSONObject("tokens") ?: return invalid("theme has no token object")
        val tokens = LinkedHashMap<String, String>()
        val tokenKeys = tokensJson.keys()
        while (tokenKeys.hasNext()) {
            val key = tokenKeys.next()
            val value = tokensJson.opt(key)
            if (value !is String) return invalid("theme token values must be strings")
            tokens[key] = value
        }
        val textJson = json.optJSONObject("text") ?: JSONObject()
        val copy = LinkedHashMap<String, String>()
        val copyKeys = textJson.keys()
        while (copyKeys.hasNext()) {
            val key = copyKeys.next()
            val value = textJson.opt(key)
            if (value !is String) return invalid("theme copy values must be strings")
            copy[key] = value
        }
        val layoutJson = json.optJSONArray("layout") ?: JSONArray()
        val layout = ArrayList<String>(layoutJson.length())
        for (index in 0 until layoutJson.length()) {
            val value = layoutJson.opt(index)
            if (value !is String) return invalid("theme layout entries must be strings")
            layout.add(value)
        }
        val actionsJson = json.optJSONArray("actions") ?: JSONArray()
        val actions = ArrayList<DshThemeActionBinding>(actionsJson.length())
        for (index in 0 until actionsJson.length()) {
            val binding = actionsJson.optJSONObject(index) ?: return invalid("theme action binding is malformed")
            val gesture = DshConsoleGesture.entries.firstOrNull { it.name == binding.optString("gesture") }
                ?: return invalid("theme gesture is unknown")
            val action = DshConsoleActions.parse(binding.optString("action"))
                ?: return invalid("theme action is not in the typed registry")
            actions.add(
                DshThemeActionBinding(
                    gesture = gesture,
                    actionId = action,
                    requireConfirmation = binding.optBoolean("requireConfirmation")
                )
            )
        }
        val theme = DshConsoleTheme(
            schemaVersion = json.optInt("schemaVersion", -1),
            id = json.optString("id"),
            name = json.optString("name"),
            tokens = tokens,
            text = copy,
            layout = layout,
            actions = actions
        )
        return when (val validation = DshConsoleThemeValidator.validate(theme)) {
            is DshThemeValidation.Invalid -> DshThemeDecodeResult.Invalid(validation.failure)
            else -> DshThemeDecodeResult.Valid(theme)
        }
    }

    private fun invalid(message: String) = DshThemeDecodeResult.Invalid(
        DshMaintenanceFailure(DshMaintenanceError.THEME_INVALID, message)
    )
}

sealed interface DshThemeSaveResult {
    data class Saved(val transactionId: String) : DshThemeSaveResult
    data class Rejected(val failure: DshMaintenanceFailure) : DshThemeSaveResult
}

data class DshThemeLoad(val theme: DshConsoleTheme, val usedDefault: Boolean, val failure: DshMaintenanceFailure? = null)

/**
 * Stores the active console theme. A stored document that no longer validates is projected as the
 * built-in default with a bounded failure note; it is never executed or partially applied.
 */
class DshConsoleThemeManager(
    private val engine: DshMaintenanceEngine,
    private val themeFile: File = File(engine.dataRootDirectory, "console/theme.json")
) {
    fun load(): DshThemeLoad {
        if (!themeFile.isFile) return DshThemeLoad(DshConsoleTheme.DEFAULT, usedDefault = true)
        return when (val decoded = DshConsoleThemeCodec.decode(DshMaintenanceIo.readText(themeFile))) {
            is DshThemeDecodeResult.Valid -> DshThemeLoad(decoded.theme, usedDefault = false)
            is DshThemeDecodeResult.Invalid -> DshThemeLoad(
                DshConsoleTheme.DEFAULT,
                usedDefault = true,
                failure = decoded.failure
            )
        }
    }

    fun importTheme(text: String): DshThemeDecodeResult = DshConsoleThemeCodec.decode(text)

    fun exportTheme(theme: DshConsoleTheme): String = DshConsoleThemeCodec.encode(theme)

    fun save(
        theme: DshConsoleTheme,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshThemeSaveResult {
        val validation = DshConsoleThemeValidator.validate(theme)
        if (validation is DshThemeValidation.Invalid) return DshThemeSaveResult.Rejected(validation.failure)
        val encoded = DshConsoleThemeCodec.encode(theme)
        val workRoot = try {
            engine.newWorkDirectory("console-theme")
        } catch (io: Exception) {
            return DshThemeSaveResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the theme staging directory")
            )
        }
        val staged = File(workRoot, "theme.json")
        DshMaintenanceIo.writeDurably(staged, encoded)
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.THEME_APPLY,
                requester = requester,
                quiescence = quiescence,
                targetRoot = engine.dataRootDirectory,
                steps = listOf(
                    DshMaintenanceStep(
                        action = DshStepAction.INSTALL_FILE,
                        target = themeFile,
                        staging = staged,
                        // Hash the exact bytes that writeDurably stores: the UTF-8 encoding of the
                        // same String, using the same charset, so the install step verifies the
                        // staged file rather than a re-encoded copy of it.
                        expectedSha256 = DshMaintenanceIo.sha256(encoded.toByteArray(Charsets.UTF_8))
                    )
                ),
                healthCheckRequired = false,
                detail = "theme=${theme.id}"
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshThemeSaveResult.Saved(outcome.transactionId)
            is DshMaintenanceOutcome.RolledBack -> DshThemeSaveResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.Rejected -> DshThemeSaveResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshThemeSaveResult.Rejected(outcome.failure)
        }
    }

    fun resetToDefault(
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshThemeSaveResult = save(DshConsoleTheme.DEFAULT, requester, quiescence)
}
