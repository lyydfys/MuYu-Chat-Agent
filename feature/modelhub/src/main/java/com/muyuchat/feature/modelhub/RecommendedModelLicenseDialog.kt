package com.muyuchat.feature.modelhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.download.ImageEngineRemoteBundleSpec
import com.muyuchat.core.download.ImageEngineRemoteBundleVariant
import java.util.Locale

/**
 * Shown before a remotely published Anima derivative bundle is downloaded.
 * The user must pick a package and accept the CircleStone non-commercial
 * license and the NVIDIA derivative notices; the full texts stay one tap away.
 */
@Composable
internal fun RemoteImageBundleLicenseDialog(
    title: String,
    spec: ImageEngineRemoteBundleSpec,
    onOpenPage: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedVariantId by remember(spec.defaultVariantId) {
        mutableStateOf(spec.defaultVariantId)
    }
    val variants = remember(spec) {
        spec.variants.sortedByDescending { it.id == spec.defaultVariantId }
    }
    McaDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择包型并同意非商业许可", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("「$title」是 Anima 的衍生权重（AOT 编译 / 量化），仅限非商业用途。")
                Text(
                    "The CircleStone Model is licensed by CircleStone Labs LLC under the " +
                        "CircleStone Non-Commercial License. Copyright CircleStone Labs LLC."
                )
                Text("本衍生版修改了原模型（NPUG/DLA 编译网络与打包资产），不是 CircleStone 官方产品。")
                Text(
                    "Licensed by NVIDIA Corporation under the NVIDIA Open Model License. " +
                        "Built on NVIDIA Cosmos."
                )
                Text(
                    "固定包：Prompt 已预计算、不能修改；约 43 秒/张；适合快速演示或基准。" +
                        "动态包：支持任意文本 Prompt；约 111 秒/张（其中文本编码约 73 秒）；推荐日常使用。"
                )
                variants.forEach { variant ->
                    VariantOption(
                        variant = variant,
                        selected = variant.id == selectedVariantId,
                        onSelect = { selectedVariantId = variant.id }
                    )
                }
                Text("下载 + 解压可用空间：固定约 4 GB，动态约 7 GB。")
                Text(
                    "模型与衍生权重禁止商用；商用模型/衍生物需向 CircleStone Labs 单独申请授权。" +
                        "输出图片可按模型卡商用。"
                )
                McaButton(style = McaButtonStyle.Text, onClick = { onOpenPage(spec.licenseUrl) }) {
                    Text("查看完整许可")
                }
                McaButton(style = McaButtonStyle.Text, onClick = { onOpenPage(spec.noticeUrl) }) {
                    Text("查看署名与修改声明")
                }
            }
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Primary, onClick = { onConfirm(selectedVariantId) }) {
                Text("同意并下载所选包")
            }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun VariantOption(
    variant: ImageEngineRemoteBundleVariant,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val gb = String.format(Locale.US, "%.1f", variant.sizeBytes / 1_000_000_000.0)
    val (label, detail) = when (variant.id) {
        "dynamic" -> "动态包（推荐）· $gb GB" to "支持任意 Prompt · 约 111 秒/张（文本编码约 73 秒）"
        "fixed" -> "固定包 · $gb GB" to "Prompt 固定不可改 · 约 43 秒/张 · 适合快速演示/基准"
        else -> "${variant.id} · $gb GB" to variant.conditioningMode
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        McaButton(
            style = if (selected) McaButtonStyle.Primary else McaButtonStyle.Outlined,
            onClick = onSelect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (selected) "已选择：$label" else "选择$label")
        }
        Text(detail)
    }
}
