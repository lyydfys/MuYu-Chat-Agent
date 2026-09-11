package com.muyuchat.mca

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun AppStartupScreen(state: AppStartupState, onRetry: () -> Unit, onSkipScan: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("MCA", style = MaterialTheme.typography.headlineMedium)
        if (state.loading) {
            CircularProgressIndicator()
            Text("正在读取本地记录…")
            Text("大型模型的扫描将在后台继续。", style = MaterialTheme.typography.bodyMedium)
            if (state.slow) {
                Text("启动时间较长，可能正在整理聊天记录、知识库或模型目录。")
                TextButton(onClick = onSkipScan) { Text("暂缓模型扫描，先进入应用") }
            }
        } else {
            Text(state.problem.orEmpty())
            TextButton(onClick = onSkipScan) { Text("暂缓模型扫描，继续启动") }
            TextButton(onClick = onRetry) { Text("重新启动") }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("MCA 启动诊断", state.diagnostic))
            }) { Text("复制诊断信息") }
            Text("无需卸载或清除应用数据。进入应用后可在模型页刷新本地模型。")
        }
    }
}
