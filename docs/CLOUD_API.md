# 云端聊天协议

MCA 的云端聊天可选择三种并列协议：OpenAI-compatible（Chat Completions）、Anthropic Messages、OpenAI Responses。云端生图的协议配置独立，不会因切换聊天协议而自动变更。

## 接入 Responses

1. 打开模型页的云端模型列表，新增聊天模型，协议选择 **OpenAI Responses**。
2. 填写服务商的 Base URL、准确模型名和 API Key。地址可为 `https://example.com/v1`、带网关前缀的 API 根路径，或完整的 `/responses` 地址。官方接口的 Base URL 为 `https://api.openai.com/v1`。
3. 模型支持看图时开启“支持图片输入”。MCA 发送 Responses 的 `input_image` 数据；未上传的 Android 私有文件 URI 不会直接发送给服务商。
4. 仅在模型支持 `reasoning` 时开启“推理模型”。此模式发送推理强度并请求摘要，省略温度和 Top P。普通模型关闭该项，使用温度和 Top P。不要仅凭服务商名称判断模型是否支持推理。
5. 执行快速测试，保存后在聊天页选择该模型。协议、图片能力和推理选项按模型保存，重启或重新选择仍保留。保存聊天配置会保留聊天协议名称，不会改成生图协议标签。

官方服务需要有效的 API Key；允许留空仅用于不要求鉴权的用户自建兼容服务。

## 已对齐的聊天功能

- 使用现有 `ChatRequest` 的完整系统设定与会话历史，因此角色卡、世界书、知识库检索、联网搜索结果和临时上下文沿用现有组装流程；不会只发送最后一句话。
- 文字、图片输入、连续多轮聊天、流式正文及推理摘要展示、非流式 JSON 返回兼容、连接测试和错误提示。
- SSE 支持事件名称、多行 `data`、心跳、正文/拒绝/推理摘要增量；完成事件中的完整正文不会重复追加。独立空格、换行和字面 `null` 不被丢弃。
- 停止生成会关闭该请求的 HTTP 连接，覆盖等待响应头和等待流式正文。停止词由客户端匹配，跨事件的半个标记不会泄漏到正文。
- 请求状态互不共享；进入后台沿用聊天的前台任务保护。Android 强制停止或系统回收后的恢复能力不因新增协议而扩大。
- 终态提供 usage 时使用真实的 `input_tokens`、`output_tokens` 和 `cached_tokens`；流式过程中使用估计值，不伪造服务端统计。

## 协议差异与边界

Responses 使用 `input`、`max_output_tokens` 和专用事件，不能直接转发 Chat Completions 的全部字段。`max_output_tokens` 同时包含正文和推理 token，官方最小值为 16。预算沿用 MCA 现有生成规则：标准/进阶思考模式的最低有效预算为 2048/8192；预算是上限，不是要求模型必须输出这么多。推理耗尽额度而没有正文时，应增加输出上限。

`top_k`、`min_p`、`seed`、重复/存在/频率惩罚不会作为无效字段发送。停止词在客户端处理。推理模式关闭展示不意味着所有服务端推理模型都能禁止内部推理；能力取决于模型。

流缺少最终完成事件、接口返回 `failed`、`cancelled`、`incomplete`、错误 JSON、HTML 网关页面或空正文时不会伪报为成功。`incomplete` 的已收到正文保留，并提示具体原因。快速测试使用小额输出预算，因此有效的 `max_output_tokens` 截断可证明连通性，但不代表完整聊天质量通过。

每次请求发送当前完整历史，设置 `store=false`，不依赖 `previous_response_id` 或服务端 Conversations。该字段关闭 Responses 对象存储，不代表服务商没有安全日志或其他数据保留。云端请求的提示词和图片仍会发往所配置的服务商。

此次新增的是与既有协议同级的 **聊天接入**，未扩展 Responses 专属工具执行、远程 MCP、托管文件搜索、视频/音频或服务端后台任务，也不把这些能力伪装成已支持。

## 验证

2026-09-21 增加请求结构、系统上下文与图片、流式去重、推理显示、失败/截断、真实 usage、分块停止词、端点/认证、非流式返回、快速测试、并发请求、真实本机 HTTP socket 取消、设置保存重载和旧两协议回归测试。

本机测试使用构造响应及 loopback HTTP 服务，不调用付费远端接口。最终结果见 `artifacts/responses-protocol-20260921/validation-final.log` 和 `test-summary.json`。未据此宣称真实服务商、真机 UI 或新 APK 已验收。

最终 Gradle 校验 **BUILD SUCCESSFUL（5m20s）**。Responses 新增 **24/24** 项通过；App Debug 1300 项中 1292 通过、8 跳过，模型页 Debug 22 项通过，合计 **1314 通过、0 失败/错误、8 跳过**。跳过项为既有 Windows 符号链接权限用例及未配置的外部联网 smoke；未变化的 Gradle 任务可复用有效结果。`git diff --check` 通过。磁盘 APK 仍为 2026-09-18 旧包，本轮未打包、安装或发布。

## 官方依据

- [Migrating to Responses](https://developers.openai.com/api/docs/guides/migrate-to-responses)
- [Streaming API responses](https://developers.openai.com/api/docs/guides/streaming-responses)
- [Create a response](https://developers.openai.com/api/reference/resources/responses/methods/create)

实现时核对日期：2026-09-21。服务商的兼容实现及具体模型支持字段可能不同，应以其实际错误和能力说明为准。
