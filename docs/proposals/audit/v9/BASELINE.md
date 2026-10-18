# MCA V9 前端基线（G00）

- 采集时间：2026-10-18 12:30 +08:00
- 仓库：`E:\MAC`
- HEAD：`5e2e7fe177ec86a0f65d927bd7aa0384c552401c`
- 任务模式：`EXISTING_SYSTEM` + `POLISH_AUDIT`，不是换肤或复制竞品。
- APK：`artifacts/rc/final/MCA-v0.2.4-arm64-v8a.apk`
- APK SHA-256：`6bbf9d5ada4620dca8583cceea952c56ccae45b33ed55ae5d40f42738972e3b7`
- APK bytes：`181368728`

## 设备与渲染条件

- 设备：Xiaomi `25091RP04C`，serial `98a37aa7`，Android API 37，arm64-v8a。
- 物理分辨率：`2136x3200`；density `440`；fontScale `1.0`；浅色模式。
- 当前显示模式：`50Hz`，支持 30/48/50/60/90/120/144Hz；性能对比必须绑定真实刷新率，不能把 60Hz 目标当 120Hz 结论。
- Before 截图/无障碍树：`artifacts/ui-v9/before/main.png`、`main.xml`、`drawer.png`、`drawer2.xml`。

## 当前视觉观察

- 聊天默认状态延续现有蓝色主调、白色顶部胶囊、透明助手正文、右侧非对称用户气泡和底部大圆角输入壳。
- 历史抽屉默认 MCA 标题 + 搜索/品牌双按钮、图片入口、最近会话列表和底部“聊天”入口。
- 当前截图未发现默认状态遮挡、裁切或无法访问的主入口；后续只处理有证据的缺陷。
- 历史抽屉搜索图标当前没有行为：`onClick = {}`，而 query/filter/无结果逻辑已经存在。
- 历史抽屉与聊天头部品牌 logo `IconButton` 缺少可访问名称。
- 主聊天文件存在已确认的 Markdown 重复解析：`content == fullContent` 时 `parseChatMarkdownSource` 被调用两次。

## 性能基线边界

- 当前仓库没有 V9 要求的独立 UI Macrobenchmark 模块，也没有可引用的 `FrameTimingMetric` 原始 trace。
- 本轮先做 deterministic 明确工作消除和真机交互复验；在建立 benchmark 前不宣称帧率、P95、启动或内存的量化提升。
- 后续性能声明必须绑定同设备、variant、APK SHA、模型/参数、主题、fontScale、刷新率和原始 trace。

## 已知工作区状态

- 父仓库生产前端源码在本切片开始前无未提交改动。
- `third_party/llama.cpp`、`third_party/stable-diffusion.cpp`、`third_party/tokenizers-cpp` 有既有内部 diff，属于构建输入，本前端任务不修改。
- `docs/architecture/MCA_PROJECT_HARD_LOCK.md`、本 V9 方案及用户已有 proposals 不可被本切片覆盖。