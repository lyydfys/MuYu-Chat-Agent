package com.muyuchat.mca.agent.device.sms

import android.content.Context
import android.provider.Telephony
import com.muyuchat.mca.agent.device.AgentSmsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only platform query of the SMS inbox.
 *
 * Bounded and honest:
 *  - the cursor is newest-first and never scans more than [AgentSmsPolicy.MAX_SCAN_ROWS] rows;
 *  - the projection is exactly address/body/date — no thread ids, no read flags, no attachments;
 *  - a platform failure or a permission race returns [AgentSmsRawQueryResult.Failed]; an empty inbox
 *    returns an empty [AgentSmsRawQueryResult.Rows], so "no access" and "no messages" stay
 *    distinguishable;
 *  - nothing is written, logged or cached: rows leave this function only in the returned value.
 */
class AndroidAgentSmsReader(context: Context) : AgentSmsSource {

    private val appContext = context.applicationContext

    override suspend fun query(limit: Int, keyword: String?): AgentSmsRawQueryResult =
        withContext(Dispatchers.IO) {
            val cap = if (keyword.isNullOrBlank()) limit else AgentSmsPolicy.MAX_SCAN_ROWS
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            try {
                val cursor = appContext.contentResolver.query(
                    INBOX_URI,
                    projection,
                    null,
                    null,
                    "${Telephony.Sms.DATE} DESC"
                ) ?: return@withContext AgentSmsRawQueryResult.Failed(
                    AgentSmsReasonCode.QUERY_FAILED,
                    "系统短信库不可用（可能被系统策略禁用）。"
                )
                cursor.use {
                    val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                    val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                    val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
                    val rows = ArrayList<AgentSmsRawMessage>(cap.coerceAtMost(AgentSmsPolicy.MAX_SCAN_ROWS))
                    while (it.moveToNext() && rows.size < cap) {
                        rows += AgentSmsRawMessage(
                            address = if (addressIndex >= 0) it.getString(addressIndex) else null,
                            body = if (bodyIndex >= 0) it.getString(bodyIndex) else null,
                            receivedAtMillis = if (dateIndex >= 0) it.getLong(dateIndex) else 0L
                        )
                    }
                    AgentSmsRawQueryResult.Rows(rows)
                }
            } catch (security: SecurityException) {
                AgentSmsRawQueryResult.Failed(
                    AgentSmsReasonCode.PERMISSION_MISSING,
                    "读取短信需要「短信」权限（READ_SMS）。"
                )
            } catch (t: Throwable) {
                AgentSmsRawQueryResult.Failed(
                    AgentSmsReasonCode.QUERY_FAILED,
                    "读取短信失败：${t.javaClass.simpleName}"
                )
            }
        }

    private companion object {
        val INBOX_URI = android.net.Uri.parse("content://sms/inbox")
    }
}
