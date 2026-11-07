# 文本输入框：共享控件与迁移记录

## 为什么有这个文件

项目曾有 **95 个文本输入框，只有 3 个能一键清空**。用户在温度、Top-P、API Key、预设名、角色描述这些地方输错一个字，只能长按、全选、再删。这个问题的根因不是各页面偷懒，而是 `core:designsystem` 里**没有统一的文本输入框组件**，89 处都是各自直接调 `OutlinedTextField`，清空按钮因此无从统一。

## 现在的规则

**新增或修改输入框时使用 `McaTextField`（`core:designsystem`）。**

```kotlin
McaTextField(
    value = text,
    onValueChange = { text = it },
    label = { Text("温度") },
    singleLine = true,
)
```

清空按钮由组件自己提供，调用方不需要写 `trailingIcon`，也不需要写清空逻辑。

## 行为契约

| 条件 | 清空按钮 |
|---|---|
| `value` 为空 | 不显示 |
| `enabled = false` | 不显示 |
| `readOnly = true` | 不显示 |
| 调用方传了 `trailingIcon` | 不显示（调用方的图标优先） |
| 其余情况 | 显示 |

- 点击清空调用 `onValueChange("")`，**与键盘输入同一通道** —— 调用方的校验、脏标记、状态恢复都不会被绕过。
- 无障碍名是 `McaActionLabels.CLEAR`（「清空输入」），并继承 `McaIconButton` 的触感反馈。
- 参数名与 `OutlinedTextField` 一致，迁移就是改一个符号。

## 为什么只读字段单独一个组件

`McaReadOnlyTextField` 存在的原因是：**清掉用户无法重新输入的内容是破坏性操作**。所以只读字段按契约不带清空按钮，但需要与周围输入框一致的排版和描边。

## 迁移结果

```
McaTextField 使用文件：34
迁移调用点：91
剩余原生 OutlinedTextField：2（均为契约内声明保留）
剩余原生 TextField：0
```

## 保留原生控件的 5 处 `BasicTextField` 与 2 处 `OutlinedTextField`

这些不是遗漏，是**共享封装覆盖不了的场景**。改动它们会牺牲已有能力：

| 文件:行 | 为什么不能替换 |
|---|---|
| `McaTextField.kt` | 封装自身的实现 |
| `ImagePromptBarScreens.kt`（负面提示词） | 使用 `TextFieldValue`，需要保留选区与输入法组合状态；`McaTextField` 只接收 `String` |
| `AgentHomeComposer.kt` | 无边框的内联输入区，样式由调用方完全定制 |
| `AgentQuestionCard.kt` | 同上，问题卡片里的无边框输入 |
| `ChatComposerScreens.kt` | 聊天输入框，承载附件芯片与富交互，非普通表单字段 |
| `ImagePromptTranslationPanel.kt` | 无边框、带主题色强调的翻译草稿区 |
| `ImagePromptBarScreens.kt` | 生图提示词的多行自绘输入区 |

判断标准：**如果替换后需要给共享组件加一堆只为这一处服务的参数，那这一处就该继续用原生控件。**

## 迁移脚本

`scripts/ui/migrate-text-fields.py` 保留在仓库里，供以后批量调整样式使用。它解析每个调用点的参数列表：

- 只接受白名单内的命名参数；遇到 `prefix`/`suffix` 等不支持的参数**跳过并报告**，绝不静默丢参数
- 位置参数最多 2 个（`value`、`onValueChange`），与共享签名一致
- 文件要么整体重写，要么完全不动，不留半迁移状态

```powershell
python scripts/ui/migrate-text-fields.py --dry-run
python scripts/ui/migrate-text-fields.py --apply
```

## 已知缺口

清空按钮的**实际手感没有真机验证过**（点击热区 40dp、与输入框内边距的配合）。JVM 单测只能证明结构与契约（`McaTextFieldContractTest`），实际观感需要在真机上逐个页面确认。
