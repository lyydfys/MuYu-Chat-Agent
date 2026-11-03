package com.muyuchat.feature.dsh.workspace.device

/**
 * Quote- and escape-aware tokeniser used by [DshDeviceCommandPolicy].
 *
 * Split from the policy tables so the allow/deny rules stay readable; it is internal because the
 * policy and the tests share it, and it is never part of a public API.
 */
internal class DshDeviceCommandScanner(private val source: String) {

    var failure: DshDeviceCommandDecision.Rejected? = null
        private set

    private var position = 0
    private var argument: StringBuilder? = null
    private var argv: MutableList<String> = mutableListOf()
    private val segments: MutableList<DshDeviceCommandSegment> = mutableListOf()
    private var nextSeparator: DshDeviceSegmentSeparator = DshDeviceSegmentSeparator.FIRST

    fun scan(): List<DshDeviceCommandSegment>? {
        while (position < source.length) {
            if (failure != null) return null
            val current = source[position]
            when {
                current == ' ' || current == '\t' -> {
                    endArgument()
                    position++
                }

                current == '\n' || current == ';' -> {
                    commit(DshDeviceSegmentSeparator.SEQUENCE)
                    position++
                }

                current == '&' -> {
                    if (position + 1 < source.length && source[position + 1] == '&') {
                        commit(DshDeviceSegmentSeparator.AND)
                        position += 2
                    } else {
                        fail("background execution")
                    }
                }

                current == '|' -> {
                    if (position + 1 < source.length && source[position + 1] == '|') {
                        commit(DshDeviceSegmentSeparator.OR)
                        position += 2
                    } else {
                        commit(DshDeviceSegmentSeparator.PIPE)
                        position++
                    }
                }

                current == '>' || current == '<' -> fail("redirection")
                current == '`' -> fail("command substitution")
                current == '$' -> fail("variable expansion")
                current == '(' || current == ')' -> fail("subshell or grouping")
                current == '{' || current == '}' -> fail("brace expansion")
                current == '"' -> readDoubleQuoted()
                current == '\'' -> readSingleQuoted()
                current == '\\' -> readEscaped()
                current == '#' -> if (argument == null) fail("comment") else append(current)
                current.code < 0x20 -> fail("control character")
                else -> append(current)
            }
        }
        if (failure != null) return null
        commit(separatorAfter = null)
        return if (failure != null) null else segments.toList()
    }

    private fun commit(separatorAfter: DshDeviceSegmentSeparator?) {
        endArgument()
        val words = argv
        argv = mutableListOf()
        if (words.isEmpty()) {
            if (separatorAfter != null) {
                // A separator with nothing before it (leading, doubled or trailing-in-the-middle).
                fail("empty pipeline segment", DshDevicePolicyRejection.EMPTY_SEGMENT)
            } else if (nextSeparator == DshDeviceSegmentSeparator.AND ||
                nextSeparator == DshDeviceSegmentSeparator.OR ||
                nextSeparator == DshDeviceSegmentSeparator.PIPE
            ) {
                // "id &&" / "id |" end with no right-hand side: a syntax error, not a shorter command.
                fail("dangling pipeline operator", DshDevicePolicyRejection.EMPTY_SEGMENT)
            }
            return
        }
        segments.add(
            DshDeviceCommandSegment(
                index = segments.size,
                separator = nextSeparator,
                argv = words.toList()
            )
        )
        if (separatorAfter != null) nextSeparator = separatorAfter
    }

    private fun endArgument() {
        val pending = argument ?: return
        argv.add(pending.toString())
        argument = null
    }

    /**
     * Appends one ordinary character and advances past it.
     *
     * The advance lives here on purpose: the scan loop's default branch is `else -> append(current)`,
     * and an append that did not move [position] made the loop re-read the same character forever
     * (every ordinary command exhausted the heap instead of parsing).
     */
    private fun append(value: Char) {
        val builder = argument ?: StringBuilder().also { argument = it }
        builder.append(value)
        position++
    }

    private fun readSingleQuoted() {
        position++
        val start = position
        while (position < source.length && source[position] != '\'') position++
        if (position >= source.length) {
            fail("unterminated single quote")
            return
        }
        val literal = source.substring(start, position)
        position++
        val builder = argument ?: StringBuilder().also { argument = it }
        builder.append(literal)
    }

    private fun readDoubleQuoted() {
        position++
        val builder = argument ?: StringBuilder().also { argument = it }
        while (position < source.length) {
            when (val current = source[position]) {
                '"' -> {
                    position++
                    return
                }

                '\\' -> {
                    if (position + 1 >= source.length) {
                        fail("dangling escape")
                        return
                    }
                    val escaped = source[position + 1]
                    when (escaped) {
                        '"', '\\', '$', '`' -> {
                            builder.append(escaped)
                            position += 2
                        }

                        else -> {
                            builder.append('\\')
                            position++
                        }
                    }
                }

                '$', '`' -> {
                    fail("expansion inside double quotes")
                    return
                }

                else -> {
                    builder.append(current)
                    position++
                }
            }
        }
        fail("unterminated double quote")
    }

    private fun readEscaped() {
        position++
        if (position >= source.length) {
            fail("dangling escape")
            return
        }
        // append() advances past the escaped character, so there is no second increment here.
        append(source[position])
    }

    private fun fail(
        rule: String,
        rejection: DshDevicePolicyRejection = DshDevicePolicyRejection.SHELL_COMPOSITION
    ) {
        if (failure == null) {
            failure = DshDeviceCommandDecision.Rejected(rejection = rejection, rule = rule)
        }
    }
}
