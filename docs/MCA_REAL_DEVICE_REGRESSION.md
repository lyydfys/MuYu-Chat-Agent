# MCA 真机回归测试表

本表用于每次生成 Debug/Release APK 后的固定验收。目标是确认霂榆Chat Agent 在真实 Android 手机上能稳定完成本地 GGUF 推理、模型管理、Agent 调参和本地 API 调用。

## 测试环境记录

| 项目 | 记录 |
|---|---|
| 测试日期 |  |
| APK 版本/Commit |  |
| 手机品牌与型号 |  |
| SoC | 骁龙 / 天玑 / 其他： |
| Android 版本 |  |
| RAM / 可用 RAM |  |
| 测试模型 |  |
| 模型大小/量化 |  |
| 网络环境 | 离线 / Wi-Fi / 手机热点 / 电脑同网段 |

## 核心流程

| 编号 | 测试项 | 操作 | 预期结果 | 结果 |
|---|---|---|---|---|
| 1 | 冷启动 | 安装后首次打开 App | App 正常进入聊天页，无崩溃 |  |
| 2 | 本地模型导入 | 模型页点击“导入 GGUF”，选择本地 `.gguf` | 模型出现在本地列表，显示大小/量化/来源 |  |
| 3 | 模型校验 | 点击本地模型“校验” | 校验通过或给出明确失败原因 |  |
| 4 | 模型加载 | 点击“加载模型” | 加载成功；失败时给出内存/文件/Native 建议 |  |
| 5 | 首轮聊天 | 输入中文问题并发送 | 流式输出正常，无中文乱码 |  |
| 6 | 停止生成 | 生成过程中点击停止 | 1 秒内停止，按钮状态恢复 |  |
| 7 | 重新生成 | 对最后一条回答点击重新生成 | 旧回答被替换，新回答开始流式输出 |  |
| 8 | 清空当前对话 | 新建对话或清空当前上下文 | 聊天区清空，模型不卸载 |  |
| 9 | 上传文本文件 | 点击输入框左侧加号，选择 `.txt/.md/.json` | 文件文本被加入输入框，大文件截断提示正常 |  |
| 10 | 历史侧边栏 | 打开左上角历史，选择旧会话 | 能切换到旧会话 |  |
| 11 | 历史持久化 | 关闭 App 后重新打开 | 历史记录仍存在，最近会话恢复 |  |
| 12 | 历史管理 | 重命名、置顶、导出、删除单条记录 | 操作生效；删除/清空有确认弹窗 |  |
| 13 | Agent 体检 | Agent 页点击重新体检 | 显示移动平台、核心、内存、温控、推荐说明 |  |
| 14 | 短基准 | 加载模型后运行短基准 | 产出 TTFT、decode token/s、内存指标 |  |
| 15 | 应用推荐参数 | Agent 页应用推荐参数 | 参数页同步变化，下一轮生成生效 |  |
| 16 | 前后台切换 | 生成时切到后台，再回到前台 | App 不崩溃；后台时生成被安全停止 |  |
| 17 | 低内存兜底 | 后台开多个 App 后加载较大模型 | 内存不足时提前提示，不应直接崩溃 |  |

## ModelScope 下载

| 编号 | 测试项 | 操作 | 预期结果 | 结果 |
|---|---|---|---|---|
| 18 | 推荐模型下载 | 模型页选择推荐模型下载 | 显示百分比、已下载/总大小、状态文字 |  |
| 19 | 下载中断续传 | 下载过程中断网，再恢复网络重试 | 从 `.part` 文件续传，不从 0 开始 |  |
| 20 | 空间不足 | 设备剩余空间不足时下载大模型 | 明确提示空间不足，不写入损坏最终文件 |  |
| 21 | 校验失败 | 模拟损坏 `.part` 或错误长度 | 提示重新下载，失败文件不进入可加载列表 |  |

## 本地 API

手机端在日志/API 页开启“本机调用”与“开放端口”，电脑与手机连接同一 Wi-Fi 或热点。

| 编号 | 测试项 | 操作 | 预期结果 | 结果 |
|---|---|---|---|---|
| 22 | 网页聊天页 | 电脑浏览器打开 `http://手机IP:11435/` | 显示 MCA Web Chat 页面 |  |
| 23 | Health | `curl http://手机IP:11435/health` | 返回 `status=ok` |  |
| 24 | 模型列表 | 带 API Key 请求 `/v1/models` | 返回 OpenAI 风格模型列表 |  |
| 25 | OPTIONS/CORS | `curl -i -X OPTIONS http://手机IP:11435/v1/chat/completions` | 返回 204 和 CORS 头 |  |
| 26 | 非流式调用 | `stream=false` 或省略 `stream` | 返回完整 `chat.completion` JSON |  |
| 27 | 流式调用 | `stream=true` | 返回 SSE token 流与 `[DONE]` |  |
| 28 | 停止接口 | 调用 `/v1/generate/stop` | 当前生成停止 |  |
| 29 | 错误 JSON | API Key 错误或模型未加载 | 返回统一 `error.message/type/code` JSON |  |

## 推荐 curl

```bash
curl -i http://PHONE_IP:11435/health
```

```bash
curl -i -X OPTIONS http://PHONE_IP:11435/v1/chat/completions
```

```bash
curl http://PHONE_IP:11435/v1/chat/completions \
  -H "Authorization: Bearer MCA_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"mca-local-model","messages":[{"role":"user","content":"你好，简单介绍一下你自己"}]}'
```

```bash
curl -N http://PHONE_IP:11435/v1/chat/completions \
  -H "Authorization: Bearer MCA_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"stream":true,"messages":[{"role":"user","content":"用三句话解释 GGUF"}]}'
```

## 通过标准

- 核心流程 1-17 必须全部通过。
- API 流程 22-29 至少在电脑浏览器和 curl 中通过。
- 下载流程允许受网络影响重试，但不能产生损坏的最终模型文件。
- 出现失败时，需要保存诊断报告、截图、机型、模型名、最近一次 native stats。
