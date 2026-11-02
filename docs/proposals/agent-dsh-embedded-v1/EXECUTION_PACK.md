# MCA 内嵌原版 DSH V1 执行包

状态：供后续授权实施使用，本轮没有执行。先读 [架构方案](ARCHITECTURE.md)和[项目工程合同](../../architecture/MCA_PROJECT_HARD_LOCK.md)。本文不能被当作已实现记录。

## 1. 执行顺序与权限

当前用户仅授权写方案。接手者不得因看到命令示例或任务卡就开始修改生产代码、安装 runtime、执行上游安装脚本、构建或真机部署；未来获得实现授权后按下面的小切片执行，不反复询问普通实现选择。

R00 → R01 是可行性门；R02–R04 建闭环；R05–R07 做真实功能对齐与既有数据保护；R08 才形成候选交付。探针失败时继续定位可修复问题，但不能绕过 SDK、依赖、功能或资源安全门槛。

所有路径和类型名中，未标记“已有”的均为拟议职责，不表示仓库已有实现。源文件位置以开工时真实结构为准，避免为满足名称硬造无价值模块。

## 2. 工作卡

### R00：锁定发行与基线，产出正式 ADR

Owner：整合者；允许写集：本方案下的基线/清单/ADR 文档，不改生产。

1. 重读最新 HEAD、tracked/untracked 差异、子模块与内部补丁，保留并行前端改动。
2. 固定官方 DSH SHA `687f282e8625c6b8d11ff58211a90524be2e4317` 及其 lockfile；记录更新策略、Node 候选、许可证与来源。
3. 从官方 web profile 及用户需要的可选包建立机器可读能力/依赖清单，列全节点与客户端插件，不以 README 功能名代替实际树。
4. 明确单 APK 可信代码模式与强隔离要求的区别；记录宿主选择待 R01 证实的条件。
5. 形成独立双模式 ADR：Kotlin Agent 与 DSH 并存，图片→Agent→DSH，各有内核/前端/数据/工具，只共用 MCA 模型与必要平台契约。既有 Kotlin 优化留给后续，不用本卡取消原路线。

验收：每个必需功能都有清单行、源码来源和对照用例；新 DSH 任务卡不替换或侵入 Kotlin Agent 任务卡。不得将本文的上游静态事实写成设备 PASS。

### R01：Android 完整宿主探针

Owner：Android runtime 工作者；写集：独立可丢弃探针与平台补丁，不修改现有聊天/生图业务。只允许一个人持构建锁运行 Gradle/native。

1. 对 APK 内可执行宿主和 Node JNI 方案做最小比较，保持 MCA targetSdk 36；锁定实际可行的一种。
2. 检查 Android Bionic/ABI、native 依赖、安装后路径、库/页面尺寸兼容、process.execPath 和子进程行为。
3. 启动固定版本完整官方 Web profile，验证 loader/addon、官方静态与动态资源、ready、Cookie 和 WebSocket。
4. 新建会话，写入/恢复日志，验证真实文件锁、SQLite/压缩所需能力；不以禁用插件绕开必需依赖。
5. 跑一个纯 JS 插件和一个实际 PTY/子进程工具，验证终端 resize、停止与输出；对 PTC fork/spawn 做真实探针。
6. 测旋转、退后台、宿主崩溃/重启与资源回收；本卡不接真实用户资料。

验收：日志绑定源码、APK、签名、依赖校验和与实际设备/系统；官方 Web 能运行且各探针给出真实结果。失败项必须明确是可修复补丁还是尚无可行机制，不接受“Node 已启动所以全部可用”。

回退：删除/停用本卡受控探针接线即可，既有 MCA 数据不发生迁移。未经本卡证实，不继续大规模 DSH 适配；无论本卡是否通过，都不替换现有 Kotlin Agent。

### R02：MCA 模型工具协议闭环

Owner：模型/协议工作者；写集：窄网关、必要推理契约与专属测试；根装配由整合者接线。不得修改 DSH loop。

1. 从原 `ChatExecutionFacade → ChatExecutionOwner → 原 backend/worker` 核对准入、模板、取消、native 停稳与 lease 路径。
2. 实现受认证的内部模型入口，保留原公共 Local API 行为；不因 Agent 入口打开而开启对外监听。
3. 使用 DSH 官方 provider 发 tools/tool_choice/messages，正确保留 tool_calls、tool_call_id 与 tool result，完成“模型调用 DSH 工具 → 工具结果回模型 → 正常回答”。
4. 检查真实模型模板和解码；不能丢工具字段、把 tool 消息转 user、把 reasoning 当动作或由网关直接执行工具。
5. 测流式多工具/交错片段、畸形 JSON、上下文溢出、length、异常 EOF、后端错误和 usage；终态严格保持区别。
6. 目录绑定实际模型能力；切换模型仍走原准入。覆盖一条真实本地链与一条真实云端链；条件不具备则记录 NOT_RUN/BLOCKED，不能宣布两条都可用。

验收：固定请求 fixture 在官方 DSH 与 MCA 网关之间字段/关联/终态无损；原聊天、Local API 的旧 fixture 通过。实际本地/云端工具闭环各有独立证据。若选 provider 插件替代 HTTP 路线，须提交协议原因并删去重复长期路径。

### R03：精确取消、竞争与 runtime 生命周期

Owner：整合者与共享模型 owner 维护者；写集：唯一 owner、网关 request registry、平台 Runtime Owner 和相关行为测试。

1. 每个网关 call 绑定 runtimeEpoch、MCA requestId、原 lease；保持旧回调与新请求隔离。
2. DSH 取消、AbortSignal、断连、Node 退出和 Service 终止只取消各自身份。宿主停止拒绝新请求并按顺序排空。
3. 获得真实 worker/native 停稳证据后才释放推理资源；读取失败不得作“已停止”。现有 AgentStopProofSource 的宽松推断不能照抄。
4. 验证 Kotlin Agent/聊天/图片/API/DSH 的竞争；DSH 子代理排队不启动第二套模型引擎，旧聊天 global stop 不误停 DSH/Kotlin Agent/新请求。
5. 后台切换、旋转不重建 runtime；进程死后恢复原事实，不自动重复未知写操作。通知 action 带模式和代次，不影响另一模式或后来任务。
6. DSH 按需启动；进入/退出某模式、停止/重置 DSH 不启动或终止 Kotlin Agent，不释放仍归其他请求所有的共享资源。

验收：每条取消链都有 UI 反馈/取消送达/真实执行停止/lease 释放证据；超时维持 fence。仅协程取消或 facade 终态不算通过。

### R04：独立入口和官方前端容器

Owner：页面宿主工作者；写集：Agent 容器、窄 WebView 平台交互；侧栏与 MainActivity 由整合者最小接线。

1. 左侧顺序图片→Agent→DSH；已有 `AGENT_WORKSPACE` 继续 Kotlin Agent，`AGENT` 继续调参。新增 `DSH_WORKSPACE` 或经审查的等价稳定键，追加映射不移动旧 ordinal。
2. 加载 ready 的官方认证同源 URL；不启动第二个 runtime，不接通用危险 JS bridge。
3. 处理官方页面上传/下载、SAF、分享、剪贴板、外链、返回和键盘；保留官方页面功能和路由。
4. 覆盖启动失败、断线重连、Cookie、后台与恢复；token 不入普通日志与外部 Intent。
5. 对原版网页与手机窄屏做真实渲染对照；必要补丁限 Android/移动层。分别恢复两模式路由/草稿/滚动，MCA 其他页面及 Kotlin Agent 保持原冻结合同。

验收：官方 UI 的能力清单逐项可操作，至少两轮渲染/交互复核；没有自己重画审批/插件/会话状态。需要开启的 Android 权限只按真实功能申请。

### R05：原工具和插件平台对齐

Owner：Android DSH 插件适配工作者；写集：Android provider/plugin、可复现 upstream patch 与依赖清单；不改 MCA 聊天工具系统。

按 R00 清单逐项验证原文件工具、搜索/编辑、终端/子进程、附件、MCP、Skills、插件管理、前后端插件扩展、PTC、workflow、子代理、计划和定时能力。每项先证明原版语义，再写最小平台适配。

插件包固定版本、来源和 hash；安装/升级/失败回滚不破坏正在运行的官方树。native addon 与动态下载可执行依赖必须遵守实际 Android 运行条件，不能偷偷走可写目录 exec 旧方案。

验收：必需清单每项均有行为证据。普通 pipe 不算真 PTY；仅加载成功不算工具可执行；关闭原功能或忽略权限失败不算移植成功。插件可信/隔离合同和网络承诺必须与实现一致。

### R06：双模式数据隔离、恢复与升级

Owner：数据工作者；写集：DSH 数据目录/备份/导入导出平台接线和兼容 fixture；不猜造历史格式。

1. DSH 原持久化独占写入；MCA 引用可重建，不双写权威会话。
2. 验证 crash/torn tail、锁竞争、fork、压缩、附件与副作用未知恢复。
3. 保留 Kotlin Agent 正常任务执行、审批、历史与恢复；不迁移为 DSH、不只读归档，ID 命名空间区分。
4. 各自清理历史/插件/配置、恢复进程、导入导出时不误改另一模式；测试两个模式有在途任务时的 DSH 重启/重置边界。
5. 验证原用户数据上的兼容签名升级；清数据或改 applicationId 不作为成功。新 DSH 数据的升级/回退单独验证，不借此升级或清理 Kotlin Agent 表。

验收：旧聊天/图片/模型/Agent 数据连续；新会话只有一个 writer；失败升级与恢复有原始日志和数据 fixture。数据格式升级后的回退边界写清楚。

### R07：固定版本功能与插件对照验收

Owner：独立验证者；写集：fixture、报告与能暴露实际问题的测试。不得以改测试定义消除失败。

对 R00 每个必需项使用同一输入 fixture 比较原版 DSH 与 Android 集成版。能用 mock 确定协议的部分做确定性对照；涉及操作系统、native、模型和网络的能力仍需真实执行。文本结果不强求逐字相同，比较调用关联、工具效果、事件次序、数据恢复与终态。

验收报告逐行写 PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE、原始证据、源码和产物身份。不得以“用了官方代码”推导手机行为全部一致，或引用旧 3245 单测统计作为这次运行结果。

### R08：候选整合、可维护性和回退

Owner：唯一整合者；写集：最终装配、构建配置、交付证据与本次 DSH 适配临时路径的清理。

确认无新循环依赖、无大 Host 扩张、无第二个实际模型 owner；Kotlin 与 DSH 各自内核合法并存，但同一个任务不由两内核推进，同一状态无双 writer。DSH feature 不依赖 Kotlin Agent feature 内部实现。依赖闭包、许可证、补丁、构建锁和版本可复现；前端/模型维护者能独立定位其负责部分。

运行适用架构检查、受影响模块与官方回归、真实 APK UI/API、兼容签名升级、后台/长任务/停止/恢复。全部结果绑定候选源码、工作区补丁、submodule 输入和 APK；失败项不发布完整能力声明。

只清理本次 DSH 集成中已验证可退出的临时适配；Kotlin Agent 是保留产品模式，不属于待删除旧实现。发布、远端推送等仍由实际用户授权决定。

## 3. 并行分工

R00 先冻结模型协议和宿主边界。R01 runtime 探针与 R02 模型协议研究可在独立写集并行；R04 可准备容器，最终接线等待 ready 合同。R05 按独立插件分工，native 构建共享一把锁。R03、根构建、公共契约、导航、数据库与最终装配由整合者协调。

不允许不同 Agent 同时修改同一 upstream patch、根 Gradle 配置或 MainRuntimeAssembly 后靠覆盖合并。各自交付文件清单、行为变化、真实测试、未验证项和回退点；整合者审实际 diff。

## 4. 发给接手 Agent 的总提示词

> 你正在为 MCA 新增独立原版 DSH 模式，先读取适用项目指令、docs/architecture/MCA_PROJECT_HARD_LOCK.md、本目录 ARCHITECTURE.md 和 EXECUTION_PACK.md。用户最新明确两个模式并存：左侧图片→Agent→DSH；Agent 保留现有 Kotlin 内核/原生前端/任务/工具/审批，后续单独优化；DSH 新入口使用官方内核+官方前端+原工具/插件。不得把 AGENT_WORKSPACE 指向 DSH，不迁移/归档/删除 Kotlin Agent。MCA 为 DSH 只提供独立入口、Android 必需宿主与模型适配，不用 Kotlin 复刻 DSH 或重绘其前端。先确认当前是否已获实现授权；只有方案授权时只补文档。开工锁定真实工作区、子模块补丁和官方 SHA，保留其他人改动。每次只领取一张卡，声明文件写集/owner/验证/回退；你不是唯一维护者，不撤销他人改动。先证实 targetSdk36 下完整官方 Web 启动、依赖、工具、MCA 模型往返与精确停止，不降低 SDK、不删除功能、不忽略错误。两模式分别独占自己的任务/会话/工具状态，MCA 原 owner 独占实际模型资源；不允许同任务双内核推进或同状态双写，停止/重置不得跨模式。现有 Local API 不支持完整工具调用，必须验证模板、tool关联、流式终态和原native停稳，不能只改baseURL。普通:dsh进程同UID，不宣称恶意插件隔离或全离线。全部结果用绑定源码/产物的证据报告，区分静态、单测、真实模型、设备和发布；无证据保持NOT_RUN/BLOCKED。普通实现选择自主推进，超出授权的行为明确说明影响。交付包含改动、行为、owner、测试、未验证、补丁、回退与下一卡。

## 5. 本轮实际交付记录

只新增本目录两份方案文档；只读检查官方及 Android 参考源码、MCA 相关源码/测试/工作区与子模块状态。未安装依赖、未运行上游脚本、未启动 DSH、未构建或测试、未做设备操作、未修改生产代码/配置/数据，未提交或推送。所有 Android runtime、真实模型、完整功能对齐和升级验收均 NOT_RUN。
