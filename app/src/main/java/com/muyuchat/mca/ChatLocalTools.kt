package com.muyuchat.mca

import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.mca.dsh.DshAdapterLimits
import com.muyuchat.mca.dsh.DshParsedToolCall
import com.muyuchat.mca.dsh.DshPromptedParse
import com.muyuchat.mca.dsh.DshPromptedToolProtocol
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/**
 * The three zero-side-effect tools a role-play turn may use.
 *
 * `get_device_time` is not decoration: the local engine injects
 * [com.muyuchat.core.engine.DeviceClockContextProvider] text, but the cloud transports never sent
 * any clock context at all, so a cloud model asked "现在几点" had no source of truth. `calc` gives
 * arithmetic a real answer instead of a plausible-looking hallucination. `roll_dice` is the one
 * random source a table-top flavoured scene expects.
 *
 * Every tool is local, deterministic where it can be, and purely informational: nothing here
 * writes files, reads user data, reaches the network, or changes app state.
 */
internal enum class ChatLocalTool(val toolName: String) {
    DEVICE_TIME("get_device_time"),
    CALC("calc"),
    ROLL_DICE("roll_dice")
}

/** One executed tool result, already reduced to the short text a model can read. */
internal data class ChatLocalToolResult(
    val name: String,
    val ok: Boolean,
    val text: String
)

/**
 * Catalog and prompt text for the prompted tool protocol shared with DSH.
 *
 * The envelope markers, tolerant framing and strict JSON rules are the ones already proven in
 * [DshPromptedToolProtocol]; chat only supplies a much smaller catalog and a shorter limit budget.
 */
internal object ChatLocalToolCatalog {

    /** Names offered in the instruction; the parser must accept exactly this set. */
    val offeredNames: List<String> = ChatLocalTool.entries.map { it.toolName }

    /** At most one continuation round per user turn; a loop is never unbounded. */
    const val MAX_ROUNDS = 1

    const val MAX_CALLS_PER_TURN = 2

    /** Continuation block ceiling; keeps a local model's context window from growing unbounded. */
    const val MAX_CONTINUATION_CHARS = 900

    private const val MAX_ECHO_CHARS = 180

    val limits: DshAdapterLimits = DshAdapterLimits.Default.copy(
        maxPromptedToolCalls = MAX_CALLS_PER_TURN,
        // A chat envelope only carries a short expression or dice string.
        maxToolCallArgumentsChars = 1024,
        maxPromptedEnvelopeChars = 4096
    )

    /**
     * Stable instruction text. It is appended to the request-scoped system context, which the
     * persisted prefix cache deliberately excludes, so enabling tools cannot invalidate the
     * on-disk role prefix.
     */
    fun instruction(): String = buildString {
        appendLine("【本地工具】需要真实时间、算术结果或掷骰时，在回复最末尾输出一个工具调用块；不需要工具就正常回答，并且不要提到本段说明。")
        appendLine("可用工具：")
        appendLine("- get_device_time：读取设备当前日期与时间。参数 {\"timezone\":\"可选，IANA 时区名，如 Asia/Shanghai\"}")
        appendLine("- calc：计算算术表达式。参数 {\"expression\":\"例如 (12.5*3+7)/2\"}，支持 + - * / % ^ 括号，以及 abs/sqrt/round/floor/ceil/ln/log/min/max/pow")
        appendLine("- roll_dice：掷骰。参数 {\"dice\":\"例如 2d6+1 或 d20\"}")
        appendLine("调用块格式（整段放在回复最后，之后不要再写任何内容）：")
        appendLine(DshPromptedToolProtocol.START_MARKER)
        appendLine("{\"tool_calls\":[{\"name\":\"calc\",\"arguments\":{\"expression\":\"1+1\"}}]}")
        append(DshPromptedToolProtocol.END_MARKER)
        appendLine()
        append("一次最多 $MAX_CALLS_PER_TURN 个调用。工具结果会返回给你，请自然接续上文，不要重复已经说过的话。")
    }

    /**
     * Builds the second-round context. It names the results as device-local facts, repeats the
     * model's own visible tail so the continuation reads as one answer, and states the round
     * budget so a model cannot ask for tools forever.
     */
    fun continuationContext(results: List<ChatLocalToolResult>, alreadySaid: String): String = buildString {
        appendLine("【本地工具结果】以下结果已在本机执行完毕，是真实结果。请直接用角色的口吻继续回答，不要再输出工具调用块。")
        results.forEach { result ->
            append("- ").append(result.name).append('：')
            append(if (result.ok) result.text else "执行失败（${result.text}）")
            appendLine()
        }
        val echo = alreadySaid.trim()
        if (echo.isNotEmpty()) {
            append("你上一条已经说过：「")
            append(if (echo.length <= MAX_ECHO_CHARS) echo else echo.takeLast(MAX_ECHO_CHARS))
            appendLine("」。请紧接着这句话往下写，不要重复。")
        } else {
            appendLine("请直接给出最终回答。")
        }
    }.let { text ->
        if (text.length <= MAX_CONTINUATION_CHARS) text else text.take(MAX_CONTINUATION_CHARS)
    }
}

/** What the collector should do after one streamed answer ends. */
internal sealed interface ChatLocalToolTurn {
    /** The model asked for tools: execute them and run exactly one continuation round. */
    data class Continue(val calls: List<ChatToolCall>) : ChatLocalToolTurn

    /** No tool call: release the text the stream gate was still holding. */
    data class Plain(val tail: String) : ChatLocalToolTurn

    /**
     * The answer looked like a protocol envelope but is not a valid one. Nothing is executed and
     * nothing of the envelope is shown, so a model cannot smuggle `<<<MCA_TOOL_CALLS_V1>>>` prose
     * into the transcript or half-invoke a tool.
     */
    data object Suppressed : ChatLocalToolTurn
}

internal fun resolveChatLocalToolTurn(parsed: DshPromptedParse): ChatLocalToolTurn = when (parsed) {
    is DshPromptedParse.Calls -> ChatLocalToolTurn.Continue(parsed.calls.mapTo(ArrayList()) { it.toChatToolCall() })
    is DshPromptedParse.NoCalls -> ChatLocalToolTurn.Plain(parsed.text)
    is DshPromptedParse.Malformed,
    is DshPromptedParse.UnknownTools,
    is DshPromptedParse.TooManyCalls,
    is DshPromptedParse.ArgumentsTooLarge,
    is DshPromptedParse.ParallelCallsForbidden -> ChatLocalToolTurn.Suppressed
}

private fun DshParsedToolCall.toChatToolCall(): ChatToolCall =
    ChatToolCall(id = id, name = name, argumentsJson = argumentsJson)

/**
 * Executes one validated call. Unknown names are rejected rather than guessed, and every argument
 * object is checked field by field, matching the image tool's contract.
 */
internal object ChatLocalToolExecutor {

    private const val MAX_ROLL_CHARS = 32
    private const val MAX_DICE_COUNT = 20
    private const val MAX_DICE_SIDES = 1000
    private const val MAX_DICE_MODIFIER = 10_000

    private val DICE = Regex("""^(\d{0,2})d(\d{1,4})([+-]\d{1,5})?$""", RegexOption.IGNORE_CASE)

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun execute(
        call: ChatToolCall,
        now: () -> Instant = Instant::now,
        zone: (String?) -> ZoneId? = { name ->
            if (name.isNullOrBlank()) ZoneId.systemDefault() else runCatching { ZoneId.of(name.trim()) }.getOrNull()
        },
        rollDie: (Int) -> Int = { sides -> Random.nextInt(1, sides + 1) }
    ): ChatLocalToolResult {
        val arguments = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return failure(call.name, "参数不是有效 JSON。")
        val keys = arguments.keys().asSequence().toSet()
        return when (call.name) {
            ChatLocalTool.DEVICE_TIME.toolName -> {
                if (!keys.all { it == "timezone" }) {
                    return failure(call.name, "时间工具只接受可选参数 timezone。")
                }
                val requested = runCatching { arguments.opt("timezone") as? String }.getOrNull()
                val resolved = zone(requested) ?: return failure(call.name, "时区名称无法识别：$requested")
                ChatLocalToolResult(call.name, true, describeNow(now(), resolved))
            }
            ChatLocalTool.CALC.toolName -> {
                if (keys != setOf("expression")) {
                    return failure(call.name, "计算工具只接受参数 expression。")
                }
                val expression = arguments.opt("expression") as? String
                    ?: return failure(call.name, "expression 必须是文本。")
                when (val result = ExpressionEvaluator.evaluate(expression)) {
                    is ExpressionEvaluator.Result.Value ->
                        ChatLocalToolResult(call.name, true, "${expression.trim()} = ${result.formatted}")
                    is ExpressionEvaluator.Result.Failure -> failure(call.name, result.reason)
                }
            }
            ChatLocalTool.ROLL_DICE.toolName -> {
                if (keys != setOf("dice")) return failure(call.name, "掷骰工具只接受参数 dice。")
                val spec = (arguments.opt("dice") as? String)?.trim()
                    ?: return failure(call.name, "dice 必须是文本。")
                rollDice(spec, rollDie)
            }
            else -> failure(call.name, "不支持的本地工具。")
        }
    }

    private fun describeNow(at: Instant, zone: ZoneId): String {
        val zoned = ZonedDateTime.ofInstant(at, zone)
        val weekday = zoned.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE)
        // `xxx` keeps UTC readable as +00:00 instead of the ISO `Z` a spoken answer would stumble on.
        val offset = zoned.format(DateTimeFormatter.ofPattern("xxx"))
        return "${zoned.format(TIME_FORMAT)} $weekday（${zone.id}，UTC$offset）"
    }

    private fun rollDice(spec: String, rollDie: (Int) -> Int): ChatLocalToolResult {
        if (spec.isEmpty()) return failure(ChatLocalTool.ROLL_DICE.toolName, "骰子表达式为空。")
        if (spec.length > MAX_ROLL_CHARS) {
            return failure(ChatLocalTool.ROLL_DICE.toolName, "骰子表达式超过 $MAX_ROLL_CHARS 字符限制。")
        }
        val match = DICE.matchEntire(spec)
            ?: return failure(ChatLocalTool.ROLL_DICE.toolName, "无法识别的骰子表达式：$spec")
        val count = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 1
        val sides = match.groupValues[2].toIntOrNull()
            ?: return failure(ChatLocalTool.ROLL_DICE.toolName, "骰子面数无法识别。")
        val modifier = match.groupValues[3].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
        if (count !in 1..MAX_DICE_COUNT) {
            return failure(ChatLocalTool.ROLL_DICE.toolName, "骰子数量必须在 1..$MAX_DICE_COUNT 之间。")
        }
        if (sides !in 2..MAX_DICE_SIDES) {
            return failure(ChatLocalTool.ROLL_DICE.toolName, "骰子面数必须在 2..$MAX_DICE_SIDES 之间。")
        }
        if (modifier !in -MAX_DICE_MODIFIER..MAX_DICE_MODIFIER) {
            return failure(ChatLocalTool.ROLL_DICE.toolName, "修正值超出允许范围。")
        }
        val rolls = (1..count).map { rollDie(sides).coerceIn(1, sides) }
        val total = rolls.sum() + modifier
        val detail = if (count == 1 && modifier == 0) {
            "$sides → $total"
        } else {
            val parts = rolls.joinToString(" + ")
            val suffix = when {
                modifier > 0 -> " + $modifier"
                modifier < 0 -> " - ${-modifier}"
                else -> ""
            }
            "$spec → $parts$suffix = $total"
        }
        return ChatLocalToolResult(ChatLocalTool.ROLL_DICE.toolName, true, detail)
    }

    private fun failure(name: String, reason: String): ChatLocalToolResult =
        ChatLocalToolResult(name, false, reason)
}
