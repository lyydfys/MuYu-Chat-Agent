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
 *  - a tap that found no clickable node is [AgentScreenOutcome.Failed] with the controller's reason;
 *  - nothing here invents a result when the engine refuses.
 */
class AndroidAgentScreenPort(
    private val controller: () -> DshA11yController = { DshA11yController.instance },
    /** Text a tap may match, in physical pixels; kept as a seam so the mapping is JVM-testable. */
    private val snapshotParser: (List<com.muyuchat.core.dsh.a11y.policy.A11yNode>) -> String =
        ::renderSnapshotText
) : AgentScreenPort {

    override suspend fun act(action: AgentScreenAction): AgentScreenOutcome {
        val engine = controller()
        return when (action) {
            AgentScreenAction.ReadScreen -> readScreen(engine)
            is AgentScreenAction.Tap -> tap(engine, action)
            is AgentScreenAction.InputText -> inputText(engine, action.text)
            is AgentScreenAction.Scroll -> scroll(engine, action)
            AgentScreenAction.GlobalBack -> globalAction(engine, A11yCommand.Back, "back")
            AgentScreenAction.GlobalHome -> globalAction(engine, A11yCommand.Home, "home")
            is AgentScreenAction.Rejected -> AgentScreenOutcome.Failed(action.reason)
        }
    }

    private suspend fun readScreen(engine: DshA11yController): AgentScreenOutcome =
        when (val outcome = engine.execute(A11yCommand.Dump)) {
            is A11yOutcome.Dump -> AgentScreenOutcome.Observed(snapshotParser(outcome.snapshot.nodes))
            is A11yOutcome.Rejected -> rejected(outcome.rejection)
            else -> AgentScreenOutcome.Failed("the accessibility engine returned no screen")
        }

    /**
     * A tap carries either a control's text or a point. Both go through one [A11yCommand.Tap]:
     * the controller matches text against the live tree (falling back to a gesture at the matched
     * node's centre) and dispatches a gesture at a pixel target.
     */
    private suspend fun tap(
        engine: DshA11yController,
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
            is A11yOutcome.Tap -> tapResult(outcome.result)
            is A11yOutcome.Rejected -> rejected(outcome.rejection)
            else -> AgentScreenOutcome.Failed("the tap did not complete")
        }
    }

    private fun tapResult(result: A11yTapResult): AgentScreenOutcome = when {
        result.found -> AgentScreenOutcome.Observed(tappedLabel(result.method))
        else -> AgentScreenOutcome.Failed(
            result.error?.takeIf(String::isNotBlank) ?: "no clickable control matched the request"
        )
    }

    private suspend fun inputText(engine: DshA11yController, text: String): AgentScreenOutcome {
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
        engine: DshA11yController,
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
        engine: DshA11yController,
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
