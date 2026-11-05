package com.muyuchat.feature.dsh.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.os.Build
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.dsh.workspace.DshConsoleRow
import com.muyuchat.feature.dsh.workspace.DshConsoleTone
import com.muyuchat.feature.dsh.workspace.web.DshWebKernel
import com.muyuchat.feature.dsh.workspace.web.DshWebKernelPolicy
import com.muyuchat.feature.dsh.workspace.web.DshWebKernelStore

/**
 * "DSH 网页内核" block of the console.
 *
 * Two kernels render the same authenticated URL; this row is the only place the choice is made.
 * The selected kernel is persisted, but the console always shows the *effective* one: if GeckoView
 * cannot run on this install (ABI or a build without the artifact) the block says so and the
 * workspace keeps rendering with the system WebView instead of failing.
 */
@Composable
fun DshWebKernelSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { DshWebKernelStore(context) }
    var selected by remember(store) { mutableStateOf(store.read()) }
    val abis = remember { Build.SUPPORTED_ABIS.toList() }
    val unavailable = remember(selected, abis) {
        DshWebKernelPolicy.unavailableReason(selected, abis, geckoViewPresent = true)
    }
    val effective = remember(selected, abis) {
        DshWebKernelPolicy.effectiveKernel(selected, abis, geckoViewPresent = true)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "DSH 网页内核",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "两个内核加载同一个已签名 URL，互不影响 DSH 运行时；切换只改变渲染引擎，" +
                "GeckoView 有独立 Cookie 罐，切换后需要重新登录一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DshConsoleRowView(
            DshConsoleRow(
                label = "当前生效",
                value = effective.label,
                tone = if (unavailable == null) DshConsoleTone.POSITIVE else DshConsoleTone.WARNING
            )
        )
        unavailable?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        DshWebKernel.entries.forEach { kernel ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                McaButton(
                    style = if (selected == kernel) McaButtonStyle.Primary else McaButtonStyle.Outlined,
                    enabled = selected != kernel,
                    onClick = {
                        store.write(kernel)
                        selected = kernel
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (selected == kernel) "${kernel.label}（已选）" else kernel.label)
                }
            }
            Text(
                text = kernel.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "切换在下一次进入 DSH 页时生效；已在网页里登录的会话不会跨内核迁移。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
