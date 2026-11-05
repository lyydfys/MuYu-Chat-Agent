package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.console.DshTerminalControlKey
import com.muyuchat.core.dsh.contract.console.DshTerminalOpenResult
import com.muyuchat.core.dsh.contract.console.DshTerminalRead
import com.muyuchat.core.dsh.contract.console.DshTerminalSessionInfo
import com.muyuchat.core.dsh.contract.console.DshTerminalUiPort
import com.muyuchat.core.dsh.contract.console.DshTerminalWrite
import com.muyuchat.core.dsh.runtime.process.DshTerminalControl
import com.muyuchat.core.dsh.runtime.process.DshTerminalOpenOutcome
import com.muyuchat.core.dsh.runtime.process.DshTerminalReadOutcome
import com.muyuchat.core.dsh.runtime.process.DshTerminalResizeOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.runtime.process.DshTerminalStatus
import com.muyuchat.core.dsh.runtime.process.DshTerminalWriteOutcome
import com.muyuchat.core.dsh.runtime.process.DshTerminalToolService

/**
 * App-side adapter from the native terminal page to the one terminal owner of the current
 * capability generation.
 *
 * The page never holds a session across generations: [service] re-reads the live assembly on every
 * call, so a generation stop closes the terminal instead of leaving the page writing into a dead
 * owner. Every refusal is translated into the page's own typed result; nothing here can turn a
 * refusal into a success.
 */
internal class DshTerminalUiAdapter(
    /** The live terminal service, or null when no generation owns one right now. */
    private val service: () -> DshTerminalToolService?
) : DshTerminalUiPort {

    override fun list(): List<DshTerminalSessionInfo> {
        val current = service() ?: return emptyList()
        return runCatching {
            current.list().map { snapshot ->
                DshTerminalSessionInfo(
                    sessionId = snapshot.sessionId,
                    name = snapshot.name,
                    columns = snapshot.size?.columns ?: DEFAULT_COLUMNS,
                    rows = snapshot.size?.rows ?: DEFAULT_ROWS,
                    running = snapshot.status == DshTerminalStatus.RUNNING
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun open(
        name: String?,
        columns: Int,
        rows: Int,
        requireRealPty: Boolean
    ): DshTerminalOpenResult {
        val current = service()
            ?: return DshTerminalOpenResult.Refused("DSH 运行时未启动：PTY 由运行时提供，请先启动运行时。")
        val size = clampSize(columns, rows)
        return when (val outcome = runCatching {
            current.open(name = name, size = size, requireRealPty = requireRealPty)
        }.getOrElse { error ->
            return DshTerminalOpenResult.Refused(error.message ?: "打开终端失败")
        }) {
            is DshTerminalOpenOutcome.Opened -> DshTerminalOpenResult.Opened(
                sessionId = outcome.snapshot.sessionId,
                columns = outcome.snapshot.size?.columns ?: size.columns,
                rows = outcome.snapshot.size?.rows ?: size.rows,
                realPty = outcome.snapshot.realPty
            )
            is DshTerminalOpenOutcome.Refused ->
                DshTerminalOpenResult.Refused("${outcome.reason.name}: ${outcome.detail}")
        }
    }

    override fun read(sessionId: String, offset: Long, maxBytes: Int): DshTerminalRead {
        val current = service() ?: return DshTerminalRead.Failed("DSH 运行时已停止")
        return when (
            val outcome = runCatching { current.read(sessionId, offset, maxBytes) }.getOrElse { error ->
                return DshTerminalRead.Failed(error.message ?: "读取失败")
            }
        ) {
            is DshTerminalReadOutcome.Ok -> {
                val page = outcome.page
                // An empty page with the same offset is "nothing yet", not an error.
                if (page.text.isEmpty() && page.nextOffset <= offset) {
                    DshTerminalRead.Idle(page.nextOffset)
                } else {
                    DshTerminalRead.Data(page.text, page.nextOffset)
                }
            }
            is DshTerminalReadOutcome.Refused ->
                DshTerminalRead.Failed("${outcome.reason.name}: ${outcome.detail}")
        }
    }

    override fun write(sessionId: String, text: String, submit: Boolean): DshTerminalWrite {
        val current = service() ?: return DshTerminalWrite(false, "DSH 运行时已停止")
        return when (
            val outcome = runCatching { current.write(sessionId, text, submit) }.getOrElse { error ->
                return DshTerminalWrite(false, error.message ?: "写入失败")
            }
        ) {
            is DshTerminalWriteOutcome.Written -> DshTerminalWrite(true)
            DshTerminalWriteOutcome.PeerClosed -> DshTerminalWrite(false, "会话已结束")
            is DshTerminalWriteOutcome.Refused ->
                DshTerminalWrite(false, "${outcome.reason.name}: ${outcome.detail}")
        }
    }

    override fun control(sessionId: String, key: DshTerminalControlKey): DshTerminalWrite {
        val current = service() ?: return DshTerminalWrite(false, "DSH 运行时已停止")
        val control = when (key) {
            DshTerminalControlKey.INTERRUPT -> DshTerminalControl.CTRL_C
            DshTerminalControlKey.EOF -> DshTerminalControl.CTRL_D
            DshTerminalControlKey.SUSPEND -> DshTerminalControl.CTRL_Z
            DshTerminalControlKey.QUIT -> DshTerminalControl.CTRL_L
            // The owner exposes only the four line-discipline keys; KILL is not one of them.
            DshTerminalControlKey.KILL -> return DshTerminalWrite(false, "运行时不支持该控制键")
        }
        return when (
            val outcome = runCatching { current.control(sessionId, control) }.getOrElse { error ->
                return DshTerminalWrite(false, error.message ?: "控制键失败")
            }
        ) {
            is DshTerminalWriteOutcome.Written -> DshTerminalWrite(true)
            DshTerminalWriteOutcome.PeerClosed -> DshTerminalWrite(false, "会话已结束")
            is DshTerminalWriteOutcome.Refused ->
                DshTerminalWrite(false, "${outcome.reason.name}: ${outcome.detail}")
        }
    }

    override fun resize(sessionId: String, columns: Int, rows: Int): DshTerminalWrite {
        val current = service() ?: return DshTerminalWrite(false, "DSH 运行时已停止")
        return when (
            val outcome = runCatching { current.resize(sessionId, clampSize(columns, rows)) }
                .getOrElse { error -> return DshTerminalWrite(false, error.message ?: "调整大小失败") }
        ) {
            is DshTerminalResizeOutcome.Applied -> DshTerminalWrite(true)
            is DshTerminalResizeOutcome.Coalesced -> DshTerminalWrite(true)
            is DshTerminalResizeOutcome.Unsupported -> DshTerminalWrite(false, outcome.detail)
            is DshTerminalResizeOutcome.Refused -> DshTerminalWrite(false, outcome.detail)
        }
    }

    override fun close(sessionId: String): Boolean {
        val current = service() ?: return false
        return runCatching { current.close(sessionId) != null }.getOrDefault(false)
    }

    private fun clampSize(columns: Int, rows: Int): DshTerminalSize = DshTerminalSize(
        columns = columns.coerceIn(DshTerminalSize.MIN, DshTerminalSize.MAX),
        rows = rows.coerceIn(DshTerminalSize.MIN, DshTerminalSize.MAX)
    )

    private companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
    }
}
