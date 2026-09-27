# 每条回复的生成统计

新生成的助手回复下方显示上行 tokens、下行 tokens、tok/s 和总用时，例如：

`上行 1,245 tokens · 下行 86 tokens · 12.4 tok/s · 用时 8秒`

统计随当前回复保存到本地聊天记录，重新打开会话仍可查看。重新生成会生成新的统计；旧记录没有采集过统计时不显示这一行。停止生成或中途失败时，有已接收的生成统计则保留本轮快照。

## 统计口径

- **上行**：本轮模型输入的 token 数，通常包含系统设定和对话历史。它不是最后一条用户消息的字数，也不是网络上传字节数。缓存复用不应从完整输入量中扣除。
- **下行**：本轮模型输出的 token 数。服务商的 usage 可能包含推理 token，因此不一定等于屏幕上正文的 token 数。
- **速度**：本地优先使用运行时报告的解码速度；未提供时回退到整体速度。云端使用请求整体平均速度，包含服务端等待与网络时间，避免将一次性返回的正文误算为极高的解码速度。
- **总用时**：使用单调时钟记录本轮生成任务开始到完成或点击停止的时间，包含上下文准备、等待首 token 与生成阶段；不包含模型此前单独加载的时间。显示秒、分秒或小时分秒。

OpenAI Chat Completions 读取 `prompt_tokens` / `completion_tokens`，Responses 读取 `input_tokens` / `output_tokens`；Anthropic 合并起始输入 usage 与最终输出 usage，并计入单独报告的缓存读取/写入 token。OpenAI 兼容服务明确拒绝 `stream_options.include_usage` 时，仅对该参数拒绝重试一次，并继续支持无 usage 的服务。

运行时或服务商没有提供真实 token 数时，使用已有文本估算并显示“约”和“含估算”；未知速率显示 `—`。估算不作为服务商计费依据，图片输入或隐藏推理可能使估算误差增大。

## 存储与验证范围

Room 数据库从 21 升到 22，只新增可空的 `generationMetricsJson` 列，保留原有消息。统计元数据不作为聊天正文传回模型；损坏的可选统计字段不会阻止读取消息。

回归覆盖统计格式、估算标记、历史往返、SQLite 升级、当前回复归属、停止计时、三种云端协议 usage、无 usage 的兼容响应及本地运行时。单元测试使用模拟响应与本机 SQLite，不等同于新 APK 或真机验收。

2026-09-21 主机验证：相关测试 **90/90 通过**（引擎 30、聊天界面格式 4、App 56），App Kotlin 编译及 `git diff --check` 通过。最终日志为 `artifacts/chat-reply-metrics-validation-final.log`，逐项结果为 `artifacts/chat-reply-metrics-test-summary.json`。本轮未构建或安装 APK。
