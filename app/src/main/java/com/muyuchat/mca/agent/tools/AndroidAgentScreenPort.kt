package com.muyuchat.mca.agent.tools

import com.muyuchat.core.dsh.a11y.android.DshA11yController
import com.muyuchat.core.dsh.a11y.policy.A11yCommand
import com.muyuchat.core.dsh.a11y.policy.A11yCoord
import com.muyuchat.core.dsh.a11y.policy.A11yInputResult
import com.muyuchat.core.dsh.a11y.policy.A11yLimits
import com.muyuchat.core.dsh.a11y.policy.A11yOutcome
import com.muyuchat.core.dsh.a11y.policy.A11yRejection
import com.muyuchat.core.dsh.a11y.policy.A11yScrollDirection
import com.muyuchat.core.dsh.a11y.policy.A11yScrollResult
import com.muyuchat.core.dsh.a11y.policy.A11yTapResult
import com.muyuchat.core.dsh.a11y.policy.A11yTreeSnapshot
import com.muyuchat.core.dsh.a11y.policy.A11yTreePolicy
import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production [AgentScreenPort]: the agent's `device.screen` tool over the *existing* accessibility
 * engine.
 *
 * The port is deliberately thin. `DshA11yController` already owns every hard part — the enabled
 * service, main-looper dispatch, per-command timeouts, node-ref staleness, gesture bookkeeping and
 * the caps — and the DSH capability lane calls exactly the same object. Re-implementing any of that
 * here would create a second tapper that could disagree with the first, so each action is mapped to
 * one [A11yCommand] and the typed [A11yOutcome] is translated into the tool's three outcomes.
 *
 * Command mapping:
 * | tool action            | a11y command                                  |
 * |------------------------|-----------------------------------------------|
 * | `read`                 | [A11yCommand.Dump]                            |
 * | `tap` with text        | [A11yCommand.Tap] with `text` (matched+clicked)|
 * | `tap` with x/y         | [A11yCommand.Tap] with pixel [A11yCoord]      |
 * | `input`                | [A11yCommand.Input] (no clipboard paste)       |
 * | `scroll`               | [A11yCommand.Scroll] up/down by `forward`     |
 * | `back`                 | [A11yCommand.Back]                            |
 * | `home`                 | [A11yCommand.Home]                            |
 *
 * Honesty rules kept from the controller's own contract:
 *  - a disabled service is [AgentScreenOutcome.ServiceDisabled], never a fake success;
 *  - a tap that found no clickable node is a failure **unless** a nearby candidate can be described:
 *    then it is an [AgentScreenOutcome.Observed] that reports "nothing was tapped" plus the ranked
 *    candidates, so the model can choose instead of guessing coordinates. The fallback never taps on
 *    its own — picking a "close enough" control would be an unapproved side effect on a real phone;
 *  - nothing here invents a result when the engine refuses.
 */
class AndroidAgentScreenPort internal constructor(
    /**
     * The accessibility engine, expressed as one command function.
     *
     * A seam rather than a direct `DshA11yController` reference: the controller is a final
     * Android-service singleton, so without this the whole mapping — including the screenshot
     * fallback — could only be tested on a device. Production passes the process singleton.
     */
    private val engineProvider: () -> AgentScreenEngine = {
        agentScreenEngine(DshA11yController.instance)
    },
    /** Renders the captured tree as model-facing text; kept as a seam for the same reason. */
    private val snapshotParser: (List<com.muyuchat.core.dsh.a11y.policy.A11yNode>) -> String =
        ::renderSnapshotText,
    /**
     * Encodes screenshot bytes into a `data:` URL. Production is [encodeScreenshotForModel]; a test
     * injects the same shape with a fake so no `Bitmap` decode is needed.
     */
    private val encodeImage: (ByteArray) -> String? = { bytes -> encodeScreenshotForModel(bytes) },
    /** Reads the PNG the capture wrote. Returns null when the file is gone or unreadable. */
    private val readScreenshot: (String) -> ByteArray? = ::readScreenshotBytes
) : AgentScreenPort {

    override suspend fun act(action: AgentScreenAction): AgentScreenOutcome {
        val engine = engineProvider()
        return when (action) {
            AgentScreenAction.ReadScreen -> readScreen(engine, withImage = false)
            AgentScreenAction.ReadScreenshot -> readScreen(engine, withImage = true)
            is AgentScreenAction.Tap -> tap(engine, action)
            is AgentScreenAction.InputText -> inputText(engine, action.text)
            is AgentScreenAction.Scroll -> scroll(engine, action)
            AgentScreenAction.GlobalBack -> globalAction(engine, A11yCommand.Back, "back")
            AgentScreenAction.GlobalHome -> globalAction(engine, A11yCommand.Home, "home")
            is AgentScreenAction.Rejected -> AgentScreenOutcome.Failed(action.reason)
        }
    }

    /**
     * Observe the screen, optionally with pixels.
     *
     * Degradation is intentional and ordered, because "cannot see" must never be reported as a
     * failed observation when *some* observation succeeded:
     *  1. the tree is always read (text is the cheap, reliable half);
     *  2. when [withImage] is set (the `screenshot` action), one capture is attempted — no image,
     *     an unreadable file, a codec `null`, or an image too large for the tool-result budget all
     *     keep the text and add an honest note instead of failing;
     *  3. only when the tree *and* the image are both unavailable does the call fail.
     */
    private suspend fun readScreen(
        engine: AgentScreenEngine,
        withImage: Boolean
    ): AgentScreenOutcome {
        var text: String? = null
        var treeFailure: String? = null
        when (val outcome = engine.execute(A11yCommand.Dump)) {
            is A11yOutcome.Dump -> text = snapshotParser(outcome.snapshot.nodes)
            is A11yOutcome.Rejected -> {
                // A disabled service cannot capture either, so it is reported once, unchanged,
                // instead of spending a second refused command.
                if (outcome.rejection.code == DshCapabilityErrorCode.NOT_ENABLED) {
                    return AgentScreenOutcome.ServiceDisabled
                }
                treeFailure = outcome.rejection.message
            }

            else -> treeFailure = "the accessibility engine returned no screen"
        }
        if (!withImage) {
            return text?.let { AgentScreenOutcome.Observed(it) }
                ?: AgentScreenOutcome.Failed(treeFailure ?: "the accessibility engine returned no screen")
        }
        val image = captureImageDataUrl(engine)
        val textValue = text
        return when {
            textValue != null && image.dataUrl != null ->
                AgentScreenOutcome.Observed(textValue, image.dataUrl)

            textValue != null -> AgentScreenOutcome.Observed(
                textValue + image.fallbackNote(),
                null
            )

            image.dataUrl != null -> AgentScreenOutcome.Observed(
                "The accessibility tree is not readable, so this observation is the image only." +
                    image.fallbackNote(includeText = false),
                image.dataUrl
            )

            // Neither half produced anything: this is the only genuinely failed observation.
            else -> AgentScreenOutcome.Failed(
                listOfNotNull(treeFailure, image.reason).distinct().joinToString("; ")
                    .ifBlank { "the screen could not be read" }
            )
        }
    }

    /** One screenshot attempt, reduced to "a usable data URL or why not". */
    private suspend fun captureImageDataUrl(engine: AgentScreenEngine): ImageAttempt {
        val outcome = engine.execute(A11yCommand.Screenshot(grid = 0, displayId = 0))
        val screenshot = when (outcome) {
            is A11yOutcome.Screenshot -> outcome.result
            is A11yOutcome.Rejected -> return ImageAttempt(null, outcome.rejection.message)
            else -> return ImageAttempt(null, "the screenshot did not complete")
        }
        val bytes = readScreenshot(screenshot.path)
            ?: return ImageAttempt(null, "the screenshot file could not be read")
        val dataUrl = encodeImage(bytes)
            ?: return ImageAttempt(null, "the screenshot could not be encoded for the model")
        if (dataUrl.length > MAX_IMAGE_DATA_URL_CHARS) {
            // The tool-result envelope has a 16 KiB model budget; an image that does not fit would
            // replace the whole observation with a truncation note, so the image is dropped here
            // while the text half still reaches the model.
            return ImageAttempt(null, "the screenshot is too large to send with this result")
        }
        return ImageAttempt(dataUrl, null)
    }

    private data class ImageAttempt(val dataUrl: String?, val reason: String?) {
        fun fallbackNote(includeText: Boolean = true): String = when {
            reason == null -> ""
            includeText -> "\n(screenshot unavailable: $reason)"
            else -> " (screenshot unavailable: $reason)"
        }
    }

    /**
     * A tap carries either a control's text or a point. Both go through one [A11yCommand.Tap]:
     * the controller matches text against the live tree (falling back to a gesture at the matched
     * node's centre) and dispatches a gesture at a pixel target.
     */
    private suspend fun tap(
        engine: AgentScreenEngine,
        action: AgentScreenAction.Tap
    ): AgentScreenOutcome {
        val hasText = action.text.isNotBlank()
        val hasPoint = action.x >= 0 && action.y >= 0
        if (!hasText && !hasPoint) {
            return AgentScreenOutcome.Failed("tap needs x/y or the text of a control")
        }
        val command = A11yCommand.Tap(
            text = action.text.takeIf { hasText },
            desc = null,
            target = if (hasPoint) A11yCoord.Pixels(action.x, action.y) else null,
            ref = null,
            expectedPackage = null
        )
        return when (val outcome = engine.execute(command)) {
            is A11yOutcome.Tap -> {
                val result = outcome.result
                // A text tap that matched nothing is the case this self-rescue exists for: the
                // model named a control the tree could not match verbatim (a partial label, a
                // differently-worded goal, a self-drawn row). Instead of a bare failure it gets the
                // ranked candidate list, so it can pick an index or tap a coordinate.
                if (result.found || !hasText) {
                    tapResult(result)
                } else {
                    tapMissWithCandidates(engine, action.text, result)
                }
            }

            is A11yOutcome.Rejected -> rejected(outcome.rejection)
            else -> AgentScreenOutcome.Failed("the tap did not complete")
        }
    }

    /**
     * What to report when a text tap found no control.
     *
     * The fallback is deliberately *observation-only*: nothing is tapped here. The tool may not
     * decide that a "close enough" control was the intended one — a wrong tap on a real phone is a
     * side effect the user never approved — so the candidates go back to the model, which picks.
     *
     * A second tree capture is required rather than assumed: the tap outcome carries no nodes, and
     * a screen the engine cannot dump leaves nothing to rank, so the original failure stands.
     */
    private suspend fun tapMissWithCandidates(
        engine: AgentScreenEngine,
        requestedText: String,
        result: A11yTapResult
    ): AgentScreenOutcome {
        val failure = result.error?.takeIf(String::isNotBlank)
            ?: "no clickable control matched the request"
        val nodes = when (val dump = engine.execute(A11yCommand.Dump)) {
            is A11yOutcome.Dump -> dump.snapshot.nodes
            else -> return AgentScreenOutcome.Failed(failure)
        }
        val candidates = AgentScreenTargetResolver.candidates(
            ScreenTarget(byText = listOf(requestedText), byDescription = listOf(requestedText)),
            nodes
        )
        if (candidates.isEmpty()) return AgentScreenOutcome.Failed(failure)
        return AgentScreenOutcome.Observed(
            buildString {
                append("No exact control matched \"").append(requestedText).append("\" — ")
                append("nothing was tapped. Choose one candidate index or tap its bounds.\n")
                append(AgentScreenTargetResolver.describeCandidates(candidates))
            }
        )
    }

    private fun tapResult(result: A11yTapResult): AgentScreenOutcome = when {
        result.found -> AgentScreenOutcome.Observed(tappedLabel(result.method))
        else -> AgentScreenOutcome.Failed(
            result.error?.takeIf(String::isNotBlank) ?: "no clickable control matched the request"
        )
    }

    private suspend fun inputText(engine: AgentScreenEngine, text: String): AgentScreenOutcome {
        if (text.isEmpty()) return AgentScreenOutcome.Failed("input needs the text to type")
        if (text.length > A11yLimits.MAX_INPUT_TEXT_CHARS) {
            return AgentScreenOutcome.Failed(
                "input is longer than ${A11yLimits.MAX_INPUT_TEXT_CHARS} characters"
            )
        }
        val command = A11yCommand.Input(text = text, paste = false)
        return when (val outcome = engine.execute(command)) {
            is A11yOutcome.Input -> inputResult(outcome.result)
            is A11yOutcome.Rejected -> rejected(outcome.rejection)
            else -> AgentScreenOutcome.Failed("the text input did not complete")
        }
    }

    private fun inputResult(result: A11yInputResult): AgentScreenOutcome = when {
        result.error == null && result.focused ->
            AgentScreenOutcome.Observed("Text entered into the focused field (${result.method}).")

        result.error == null -> AgentScreenOutcome.Failed("no focused text field received the input")
        else -> AgentScreenOutcome.Failed(result.error.orEmpty())
    }

    private suspend fun scroll(
        engine: AgentScreenEngine,
        action: AgentScreenAction.Scroll
    ): AgentScreenOutcome {
        // The tool's `forward` flag is "towards the end of the content", which is a downward scroll;
        // `distance` has no a11y equivalent (the engine pages a scrollable node), so it is validated
        // but never silently reinterpreted as a gesture length.
        val command = A11yCommand.Scroll(
            direction = if (action.forward) A11yScrollDirection.DOWN else A11yScrollDirection.UP
        )
        return when (val outcome = engine.execute(command)) {
            is A11yOutcome.Scroll -> scrollResult(outcome.result, action.forward)
            is A11yOutcome.Rejected -> rejected(outcome.rejection)
            else -> AgentScreenOutcome.Failed("the scroll did not complete")
        }
    }

    private fun scrollResult(result: A11yScrollResult, forward: Boolean): AgentScreenOutcome = when {
        result.error == null && result.method != "none" ->
            AgentScreenOutcome.Observed(
                "Scrolled ${if (forward) "down" else "up"} via ${result.method}."
            )

        result.error == null -> AgentScreenOutcome.Failed("nothing on this screen can scroll")
        else -> AgentScreenOutcome.Failed(result.error.orEmpty())
    }

    private suspend fun globalAction(
        engine: AgentScreenEngine,
        command: A11yCommand,
        label: String
    ): AgentScreenOutcome = when (val outcome = engine.execute(command)) {
        is A11yOutcome.GlobalAction ->
            if (outcome.result.ok) {
                AgentScreenOutcome.Observed("Pressed the system ${label} action.")
            } else {
                AgentScreenOutcome.Failed("the system rejected the ${label} action")
            }

        is A11yOutcome.Rejected -> rejected(outcome.rejection)
        else -> AgentScreenOutcome.Failed("the ${label} action did not complete")
    }

    /**
     * One refusal shape for every command. A disabled/unusable service is the only outcome the tool
     * must report as [AgentScreenOutcome.ServiceDisabled]; everything else keeps the engine's own
     * single-line, secret-free message.
     */
    private fun rejected(rejection: A11yRejection): AgentScreenOutcome =
        if (rejection.code == DshCapabilityErrorCode.NOT_ENABLED) {
            AgentScreenOutcome.ServiceDisabled
        } else {
            AgentScreenOutcome.Failed(rejection.message)
        }

    private fun tappedLabel(method: String): String =
        "Tapped a control on the screen ($method)."
}

/**
 * Renders one captured screen as the compact, model-facing text `read` returns.
 *
 * The tree snapshot is structured; a model needs the *content* plus enough geometry to decide a
 * coordinate tap. Nodes without text or description are dropped (they are layout containers), the
 * `[x,y wxh]` prefix is the node rectangle in physical pixels, and flags are one compact suffix so a
 * long screen stays readable. Output is bounded by the same node cap the controller already applies
 * ([A11yLimits.MAX_NODES]) plus a hard character ceiling.
 */
internal fun renderSnapshotText(
    nodes: List<com.muyuchat.core.dsh.a11y.policy.A11yNode>
): String {
    if (nodes.isEmpty()) return A11yTreePolicy.EMPTY_SCREEN_HINT
    val builder = StringBuilder()
    var lastPackageDepth = -1
    for (node in nodes) {
        val label = node.text.ifBlank { node.desc }
        if (label.isBlank()) continue
        val text = A11yTreePolicy.truncate(label.replace('\n', ' ').replace('\r', ' '))
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append('[')
            .append(node.rect.x).append(',').append(node.rect.y)
            .append(' ').append(node.rect.w).append('x').append(node.rect.h)
            .append("] ")
            .append(if (node.depth > 0) "  ".repeat(minOf(node.depth, 6)) else "")
            .append(text)
        val flags = buildList {
            if (node.clickable) add("tap")
            if (node.input) add("input")
            if (node.scrollable) add("scroll")
            if (node.checked) add("checked")
            if (node.password) add("password")
        }
        if (flags.isNotEmpty()) builder.append(" (").append(flags.joinToString(",")).append(')')
        lastPackageDepth = node.depth
        if (builder.length >= MAX_SCREEN_TEXT_CHARS) break
    }
    if (lastPackageDepth < 0) return A11yTreePolicy.EMPTY_SCREEN_HINT
    return if (builder.length <= MAX_SCREEN_TEXT_CHARS) {
        builder.toString()
    } else {
        builder.substring(0, MAX_SCREEN_TEXT_CHARS)
    }
}

/** One screen's worth of text; well under the tool result budget. */
internal const val MAX_SCREEN_TEXT_CHARS: Int = 6_000

/**
 * The one engine operation the port needs, as a function type.
 *
 * `DshA11yController.execute(A11yCommand)` is the production implementation; a JVM test supplies an
 * in-memory script instead, which is what makes the tap/input/scroll mapping and the screenshot
 * fallback testable without an Android service.
 */
internal fun interface AgentScreenEngine {
    suspend fun execute(command: A11yCommand): A11yOutcome
}

/** Production adapter: the process-wide accessibility controller. */
internal fun agentScreenEngine(controller: DshA11yController): AgentScreenEngine =
    AgentScreenEngine { command -> controller.execute(command) }

/** Reads the PNG the capture wrote, or null when it is gone/unreadable/oversized. */
internal fun readScreenshotBytes(path: String): ByteArray? {
    if (path.isBlank()) return null
    return runCatching {
        val file = java.io.File(path)
        if (!file.isFile) return null
        if (file.length() <= 0L || file.length() > MAX_SCREENSHOT_BYTES) return null
        file.readBytes()
    }.getOrNull()
}

/**
 * Longest `data:` URL kept in one observation.
 *
 * The tool-result envelope has a 16 KiB model budget ([AgentToolResults.MODEL_RESULT_MAX_BYTES]); an
 * image near that would push the whole observation into "truncated", losing both the pixels and the
 * text, so the image half is dropped first and the text still reaches the model.
 */
internal const val MAX_IMAGE_DATA_URL_CHARS: Int = 8_000

/** Same source cap the capture enforces before it writes the PNG. */
private const val MAX_SCREENSHOT_BYTES: Long = 8L * 1024 * 1024