# DSH V2 对标验收与证据合同

本文是后续实施的验收规范，配套[总方案](README.md)、[架构](ARCHITECTURE.md)、[来源审计](SOURCE_MAP.md)、[任务卡](EXECUTION_PACK.md)和[逐项能力账本](PARITY_MATRIX_V2.json)。**本轮仅写方案；下列运行用例全部为 `NOT_RUN`。源码静态检查、旧 APK 的 PASS、工具成功注册、前端展示完整，均不能替代本候选端到端通过。**

## 1. 对标分母与完成定义

### 1.1 三份来源分别证明什么

1. 官方 DSH `687f282e8625c6b8d11ff58211a90524be2e4317`：复用内核、官方前端、账号协议、工具/插件/Skills/MCP/子 Agent/jobs/会话与配置语义。现有参照是 archive + SHA marker，不是可用 Git 重推导 revision 的 checkout；D00 必须保留这一来源局限并补可复现来源。
2. 指定手机版 `woaiys3/deepseek-harness-android-app`，commit `98162a23b39eeeb75b8d4608b3e773bbb13be43d`、v1.20.1：提供 Android 宿主、手机工具、维护与交互参考；不证明它的 target 28 方案可原样在 MCA target 36 下执行，也不证明其替代 PTY/文件锁具有官方语义。
3. MCA 当前源码/工作区/实际 APK：证明本产品的生产调用、模型资源与数据连续性。不能把参照项目能力或旧 APK 结果转记为 MCA 当前 PASS。

固定 payload 是独立构建输入：`payload.zip` SHA-256 `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab`，160,464,248 bytes；kernel `0.2.1-rc.2` / `20261018072502`。参照 APK SHA-256 `c324272bff00e8cce06e0baa2ebb9b88e524a5d246e06f8cb55d39df7c66dafa`。这些字节与后续 Android 补丁/ELF/插件闭包必须分别登记，不能只记录“用了 v1.20.1”。

### 1.2 不删分母、不把名称数当功能数

- 保留旧[官方能力清单](../CAPABILITY_MANIFEST.json)的 **422 个 identity**，包括旧标注 optional/disabled 的条目。422 是 composition、client、preset、工具、命令等多种行的身份数，不是 422 个互不重叠的业务功能。
- 新账本另加手机版 **43 个唯一工具 identity**：Shizuku 5、Android 7、Accessibility 13、VScreen 8、Browser 10；上游身份合计 **465 行**。另加20条明确的MCA目标设计identity（source.kind=`mca-target-design`），总账本 **485 行**，与上游导出/旧源码事实分开。A11y 中 `android_back`/`android_home` 是动态注册，计入；不得从 README 计数或重复 VScreen 副本得到 44。
- 工具 action、枚举、错误分支、权限、生命周期、维护功能与 MCA 模型是**额外子分母**；不能仅验 43 次调用就宣称完整对标。
- 每个官方 identity 要记录 effective profile、生产调用者、正向/失败/取消用例与证据；服务项需验证真实消费者，UI 项需验证实际交互及其落盘/执行效果。`package 存在`、`接口存在`、`已写测试名`均不是完成。
- optional 不是自动豁免。D00 对每行判为 `REQUIRED / ADAPTED_REQUIRED / OPTIONAL_RETAINED / NOT_APPLICABLE`；后者必须记录精确 profile、源条件与理由。替代平台实现需证明等价语义；未决保持 `UNREVIEWED`，不得缩小总账本。
- Windows PowerShell 等不适用于 Android 的原平台入口可在该 profile 标 N/A，但源码能力身份保留；Android 命令能力不能因此被豁免。官方标准、PTC、minimal、cordis 等 preset 分别审查，不把标准 preset 通过推广到所有 preset。

`PARITY_MATRIX_V2.json` 初始化 485 行均为 `profile=UNREVIEWED`、`implementation=NOT_ASSESSED`、`runtime=NOT_RUN`、`evidence=[]`，owner 卡号只是分工。官方/手机版行的 `acceptanceCases=[]` 明示尚未逐行接入测试；20条MCA目标行先关联本文件场景，但仍无实现/运行结论，D00 必须把本文件用例及必要新增用例绑定进去。**本文件的场景集是最低验收集，不能取代 485 行逐项审查。**

### 1.3 对外允许的完成表述

| 表述 | 必须具备的证据 |
| --- | --- |
| 源码已实现 | 当前生产路径接线、owner/状态语义与针对性检查，运行仍可 NOT_RUN |
| 基础链路通过 | 明确 APK、设备、模型配置、入口与一次实际往返；不扩大为任务正确 |
| 某能力完成 | 当前候选正向、失败/拒绝、取消/恢复及真实效果通过；对应行无未决项 |
| 对标指定手机版完成 | 43 工具及 action 子分母、宿主/运维/交互逐项通过；差异被明确解释 |
| 对标官方 DSH 完成 | 全 422 identity 审清，各 profile 所有 required/adapted 项通过，原内核协议语义保留 |
| 完全能力交付 | 前两者 + DeepSeek 官方账号 + MCA 逐后端模型 + 数据升级/长运行/旧功能回归全部过门；无 REQUIRED 的 FAIL/NOT_RUN/BLOCKED/UNREVIEWED |

不能承诺所有第三方插件的 native 包都天然兼容 Android。插件体系对标的含义是官方协议与生命周期完整、兼容包能装能跑、不兼容包准确诊断且不破坏 profile；如某具体插件已列为目标能力，其不兼容必须修复或列明未完成，不能以“第三方”直接结案。

## 2. 当前事实与旧证据边界

- 当前 service stop policy/client snapshot/owner 已有“停止失败不假报 STOPPED”、未知态 fence、同 UID 残留扫描等源码修复；本轮没运行，先复核和验证，禁止重复回退这些修复。
- installer 已有 FileChannel 锁、锁外解压与锁内发布；不等于 Node payload 的 flock/no-replace/session 世代竞态已经修复。两类生产原语分开验。
- 已有平台 overlay 保留 sandbox 服务并接入 bash-local；本地命令自报 workspace-write 不等于真实目录隔离。审批通过也不自动产生 OS 隔离。
- `required` tool choice 已有缺失调用拒绝逻辑，结构化 tool calls 也有部分校验；仍需验证 named、真实最终 wire、终态、历史保全和每个后端。
- main 更新 endpoint holder，而活 Node 在 READY 时直接复用，尚不能据此证明新端口/token/catalog 已应用；必须验证 `desired/applied/ACK` 闭环。
- Android 能力契约和 fake 不等于生产平台桥；WebView 文件上传不是 Agent 可调用手机工具。
- 账号包存在与本地 Web launch token/cookie 都不能证明 DeepSeek 账号登录成功。当前 popup 对 about:blank 的 1 秒回收和所有 popup 5 秒回收，可能提前销毁官方“先开 blank、后 auth_init 跳转”的窗口，属待复现风险。

旧真机原始证据：[20261020-051217](../../../../artifacts/dsh-verify/20261020-051217/FINAL-RELEASE-VERIFY.md)，2026-10-20 05:12–05:18 +08、APK SHA `b610e1d0d29195e966e705a4a5113f46d8601a16349f45bedb98954ace73ae9a`。其入口、官方前端、模型选择/切换、本地一轮与同 Node 再入可引用为历史证据。Qwen3.5 0.8B MNN 当时 `n_ctx=16384`，要求回复 DSH-OK 而实际离题；只能证明回返链，任务完成率和工具正确性均未获证明。不把这一上下文数推广为所有模型的要求，不用“MODEL_QUALITY_ONLY”直接跳过协议/模板联合诊断。

## 3. 验收记录、fixture 与证据格式

### 3.1 每次运行前冻结

- 记录完整构建闭包：HEAD、tracked patch 摘要/hash、untracked 文件清单与内容 hash、三个子模块 revision/内部 diff、Gradle/JDK/SDK/NDK、payload、官方补丁、手机插件、ELF、依赖 lock、APK hash/签名/applicationId/version。
- 记录实际运行环境：OS/API/ABI、WebView provider/version、后台限制、权限、网络、可用空间、热状态。设备信息只用于复现与调优；不能成为入口或能力准入名单。
- 记录模型来源/真实 ID/后端/权重/格式/hash/tokenizer/chat template、有效 context/max output、采样、reasoning、tool transport、视觉能力；保留脱敏最终 wire 摘要与 token 统计。显示标签不是模型身份。
- fixture 限定独立工作区、脱敏会话、测试 APK、受控网页/HTTP/MCP 服务、可恢复设置键与测试通知；所有破坏性测试只操作 fixture，不使用真实用户 App/文件/账号数据。

### 3.2 单条结果 schema

```json
{
  "caseId": "AUTH-01",
  "manifestIds": ["host.deepseek-account"],
  "sourceAction": "startSignIn",
  "ownerDCard": "D03",
  "candidateId": "sha256-of-closed-build-inputs",
  "profile": "android-mca",
  "productionEntry": "real route -> official RPC -> platform hook",
  "preconditions": ["fixture revision", "permission state", "selected model identity"],
  "status": "NOT_RUN",
  "expected": "observable postcondition",
  "actual": null,
  "evidence": [],
  "failureClass": null,
  "cleanupVerified": false
}
```

状态只用 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`。缺账号/网络/设备是 BLOCKED 的具体原因，不是通过；“尚未做”是 NOT_RUN。N/A 要 profile 与理由。证据保存原始时间、命令/退出码、真实 UI/API/OS 效果与清理结果；不得记录 token、PKCE verifier、真实账号资料或完整私人对话。

每条需要至少四种观察中的适用项：①用户能看到的结果；②官方内核/桥/模型 owner 同一关联 ID；③外部副作用或文件/进程/系统状态；④终态、取消和资源清理。仅截图、仅日志 ok、仅模型自述均不足以证明系统动作。

### 3.3 正确性分层

- **L0 确定性合同**：协议 fixture、状态机、路径与锁竞争、幂等、凭据竞态；适合 JVM/Node/native 针对性测试。
- **L1 生产平台效果**：真实 Android 服务/ELF/PTY/文件/权限/WebView/升级；fake 结果不能结算此层。
- **L2 模型协议能力**：真实选中模型产生可解析且合法的 tool call、历史配对和终态；不靠文本正则冒充 native tool call。
- **L3 任务成功**：模型能选对工具、参数、顺序并产出满足 oracle 的结果。L2 通过不等于 L3。

下列每一行都是后续可执行用例，**初始状态统一为 NOT_RUN**。同一行列出多个枚举值时，每个值必须保存独立子记录，不得一次成功替整行全过。

## 4. DeepSeek 账号与浏览器协议

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| AUTH-01 | D02 | 空 MCA 模型目录启动 | 不先启动 MCA gateway 也能进入官方账号页和登录流程；账号入口不被空模型阻断。 |
| AUTH-02 | D03–D05 | 真实 DeepSeek 账号完成一次登录 | 系统浏览器授权→官方 callback→官方凭据落盘→账号模型目录→实际账号推理；用户信息脱敏。 |
| AUTH-03 | D03 | 核对官方 startSignIn 参数 | 使用 client/callbackOrigin/loginSource；来源仅官方允许的 web/desktop，不发明新 issuer/endpoints。 |
| AUTH-04 | D04 | callbackOrigin 合法集合 | 逐项测 http localhost、127.0.0.1、[::1] 与显式非零端口，回调复用官方 ctx.webServer 固定 /oauth/callback。 |
| AUTH-05 | D04 | callbackOrigin 拒绝集合 | 非 loopback、https 伪装、缺端口/端口0、userinfo、非法 origin 被官方规则拒绝，不开放任意本地代理。 |
| AUTH-06 | D03 | PKCE 与 state | 独立32字节随机值、S256、state常量时间比较；日志和证据不泄露 verifier/state。 |
| AUTH-07 | D04 | 单值 callback 参数 | 缺 code/state、空值、重复 code/state、错误 state 各得官方400；不写凭据。 |
| AUTH-08 | D04 | 重复与取消回调 | 同 attempt 二次回调、取消后回调、非 waiting phase 回调得410，既有凭据不被覆盖。 |
| AUTH-09 | D04 | 慢 auth_init 的 blank popup | 人为延迟1.5秒、6秒和接近30秒，预开 about:blank 仍能在合法attempt内路由至系统浏览器，超时只按官方终态结算。 |
| AUTH-10 | D04 | 浏览器失败/无可处理Activity | 明确失败与重试入口；不将授权页面降级至不受信任scheme、不误报已登录。 |
| AUTH-11 | D04 | Deep link/外链隔离 | 真实授权HTTP(S)经系统浏览器；未知scheme、file/content/javascript不获任意native执行或WebView桥。 |
| AUTH-12 | D03 | attempt 总时限 | 默认600000ms、上限3600000ms；auth_init.expires_in仅缩短attempt，包含init/browser/exchange，不当凭据expiry。 |
| AUTH-13 | D03 | exchange/committing 竞态 | code仅交换一次；进入committing清timer，完成提交不被旧超时回调误取消。 |
| AUTH-14 | D05 | 凭据持久化和重启 | 按官方 record 语义存储，重启恢复账号而不显示明文secret；不虚构refresh grant/expiry。 |
| AUTH-15 | D05 | 退出时本地删除失败 | 不发远端logout、不先假报signed-out，保留准确错误与可重试状态。 |
| AUTH-16 | D05 | 正常退出顺序 | 等待committing→await deleteRecord→启动后台logout→signed-out；只取消最新durable provider=deepseek-account的running agent。 |
| AUTH-17 | D05 | 退出不中断 MCA | 同时运行受控MCA任务，账号退出不得删除MCA配置/令牌或取消非账号provider任务。 |
| AUTH-18 | D05 | logout网络失败 | 首发加最多5次额外重试，内存only，停机abort；不把远端失败恢复成本地已删凭据。 |
| AUTH-19 | D05 | 旧 inference 401 竞态 | 旧token/旧detailsLifetime/旧issuer的401不删除刚成功的新凭据；匹配身份才失效。 |
| AUTH-20 | D05 | 目录401特殊语义 | 模型listing401保留旧catalog和credential；不得等价退出，显示目录失败可重试。 |
| AUTH-21 | D05 | 账号失效分型 | 匹配身份的inference401、profile/balance401或官方code40003走官方失效；无关网络错误不清账号。 |
| AUTH-22 | D06 | 三个来源独立 | DeepSeek账号、官方API Key、MCA同时可配置；account失败不自动落到API Key或MCA。 |
| AUTH-23 | D05 | 凭据迁移/备份隔离 | 系统备份、诊断包、导出会话与模型配置中不泄露secret；安全存储迁移中断可恢复。 |
| AUTH-24 | D04 | 前后台/旋转/进程重建 | 授权中离开应用后返回可准确显示attempt结果；进程死后不重放旧code，不伪造续登。 |
| AUTH-25 | D04 | D04a可信pending与官方attempt关联 | 官方EXT将pending handle与safe AccountView/SignInAttemptView关联后只读投影；onCreateWindow本身不能证明登录attempt。 |
| AUTH-26 | D04 | authorizeUrlWithTheme合法转换 | 官方原authorizeUrl仅允许官方theme light/dark变换，合法主题跳转不因整串比较被误拒；host/path/其它query篡改仍拒绝。 |
| AUTH-27 | D04 | pending错配与多窗口 | 两个pending/取消后晚到URL/非账号popup交错，只给匹配attempt打开系统浏览器；不以window时序猜身份。 |

## 5. MCA 模型、路由与工具 wire

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| MODEL-01 | D06 | 同名不同来源模型 | 账号/MCA/cloud同名标签仍显示来源且稳定ID不同；选择命中正确endpoint/backend。 |
| MODEL-02 | D06 | 主模型与子Agent模型 | 默认主模型、子Agent指定/继承分别验证；task开始固定路由，不能中途悄换。 |
| MODEL-03 | D07 | 启动快照与目录更新 | 新增/删除/改名模型后官方目录获得新revision，不要求清profile；旧已选ID不错误映射。 |
| MODEL-04 | D07 | main重建gateway | 新端口/token与catalog具desired/applied/ACK；真实新请求命中新binding，旧Node不能只复用holder。 |
| MODEL-05 | D07 | busy时重绑 | 受控长任务中更新binding，旧任务结算明确；不得自动重放写工具或重复启动宿主。 |
| MODEL-06 | D07 | 热绑不可用fallback | 由官方活动证明准入受控重连，不能按timer猜空闲；用户可看到需要停止的真实状态。 |
| MODEL-07 | D08 | 最终wire完整性 | 捕获脱敏最终engine输入，DSH system/history/tools/tool results不被MCA默认提示二次污染或静默裁掉。 |
| MODEL-08 | D08 | 工具目录完整性 | offered工具名/参数schema/required字段原样到后端；预算不够明确拒绝，不截掉catalog后继续。 |
| MODEL-09 | D08 | tool_choice=auto | 有调用与无调用都合法，结构化call事件和模型文字分开。 |
| MODEL-10 | D08 | tool_choice=none | 模型不能执行工具；意外call按协议拒绝，不落入实际工具dispatcher。 |
| MODEL-11 | D08 | tool_choice=required | 无call时明确TOOL_REQUIRED_MISSING；不能把普通文字当成功。 |
| MODEL-12 | D08 | tool_choice=named | 只接受指定且offered工具；指定不存在、错误工具、参数不合法分别可诊断。 |
| MODEL-13 | D08 | 并行/多个调用 | 同一轮多个call的ID唯一，完整arguments一次结算，结果对应原ID，无乱序串会话。 |
| MODEL-14 | D08 | 流式参数拆片 | 中文、多字节、转义和大JSON跨chunk，最终可验证；中断片段不执行。 |
| MODEL-15 | D08 | 恶意或坏调用 | 未知工具、重ID、非法JSON、超尺寸、缺required均在执行前拒绝；额外字段严格按真实schema的additionalProperties规则，不误拒schema允许的输入。 |
| MODEL-16 | D08 | 历史tool配对 | 多轮assistant call/tool result按官方role顺序重放，历史call id不和新轮冲突。 |
| MODEL-17 | D08 | reasoning策略 | 模型模板支持的思考模式由显式合同确定；reasoning不混入工具参数或最终用户答案。 |
| MODEL-18 | D08 | 真实终态 | 分别触发EOS、max output、用户取消、超时、后端失败；STOP/LENGTH/ERROR不统一伪装STOP。 |
| MODEL-19 | D09 | MNN本地真实工具回合 | 代表模型真实完成受控读写工具任务；记录全部有效配置与任务oracle。 |
| MODEL-20 | D09 | llama及其他本地后端 | 每个本次改动实际支持的后端各用代表模型验工具和取消，某后端PASS不外推。 |
| MODEL-21 | D09 | 云端native tool provider | 真实服务完成结构化工具回合，401/429/5xx/断流保持原错误类别与终态。 |
| MODEL-22 | D09 | 视觉附件逐后端 | 账号/MCA具视觉模型各验真实image输入与答案；无视觉模型准确拒绝，不传空占位。 |
| MODEL-23 | D08 | 上下文容量边界 | 长history+完整tools+附件接近上限时保留官方受保护消息；无法容纳明确报错。 |
| MODEL-24 | D09 | 共享推理owner竞争 | 聊天/Local API/原生Agent/DSH竞争同模型时由既有资源仲裁；无第二套native实例或lease泄漏。 |
| MODEL-25 | D08 | 取消到实际释放 | 取消SSE→取消共享owner→backend真实停→lease释放，用户反馈与实际停止分别记录。 |
| MODEL-26 | D06 | 离线使用本地模型 | 阻断互联网后本地推理、离线工具、既有profile仍工作；需要云服务的功能明确失败。 |

## 6. 生命周期、进程树与恢复

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| HOST-01 | D01 | 一次启动/并发进入 | 双击入口、页面重组、两个客户端并发只存在一个权威Node宿主。 |
| HOST-02 | D01 | 正常停止 | Node及所有owned工具子进程退出、socket/PTY/lease释放后才显示STOPPED并撤通知。 |
| HOST-03 | D01 | 停止超时 | fixture子进程拒绝退出，保留STOP_TIMEOUT与通知/fence，不stopSelf假成功。 |
| HOST-04 | D01 | Binder死亡 | 停止途中Binder死，client缺snapshot不能证明STOPPED；重连查询权威事实。 |
| HOST-05 | D01 | main先死 | Node活而main死，恢复后采用新binding/ACK或受控停止，不能出现两个host。 |
| HOST-06 | D01 | service先死 | main保留旧句柄，晚回调不能结算新世代任务或误释放新lease。 |
| HOST-07 | D01 | Node崩溃 | 准确错误、进程树回收与可恢复入口；官方会话已落盘部分完整。 |
| HOST-08 | D01 | PID复用 | 旧PID重新被无关进程使用时不得误杀；核对代际与process identity。 |
| HOST-09 | D01 | 同UID孤儿 | 真实orphan被识别；无法证明归属时保持unknown/fence，不能粗暴杀全部同UID进程。 |
| HOST-10 | D01 | 连续重复stop | 并发stop只执行一个结算，未知→晚STOPPED收口幂等，通知和状态一致。 |
| HOST-11 | D25 | 前后台和锁屏 | 按既有保活合同执行；页面不可见不等于取消，后台受限时有准确状态。 |
| HOST-12 | D25 | 从通知返回 | 返回同官方会话/任务，不能创建新Node或丢草稿。 |
| HOST-13 | D01 | 系统杀进程恢复 | 强制杀死受控测试进程，恢复任务状态而不自动重复外部副作用。 |
| HOST-14 | D01 | 磁盘/内存不足 | 启动与运行失败可定位且无半发布home、无虚假READY。 |
| HOST-15 | D07 | catalog-only更新 | 仅目录变更不旋转无关账号凭据或中断稳定任务；applied revision可观测。 |
| HOST-16 | D01 | 跨入口取消隔离 | 停止DSH任务不误取消新聊天/原生Agent request identity，旧取消不命中新请求。 |

## 7. Android命令、文件原语与真PTY

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| EXEC-01 | D10 | Node实际文件锁互斥 | 两个真实进程竞争payload生产flock，第二个阻塞/失败符合合同；不得no-op。 |
| EXEC-02 | D10 | 锁持有者崩溃 | 持锁进程SIGKILL后锁自然释放，下一世代可获取且无永久假锁。 |
| EXEC-03 | D10 | installer发布互斥 | 两个真实安装者同一版本竞争，只产生一个完整原子published目录与正确marker。 |
| EXEC-04 | D10 | no-replace冲突 | 对既有目标发布失败且不覆盖原内容；测试Node文件与session JSONL世代原语。 |
| EXEC-05 | D10 | 跨文件系统/链接失败 | 失败不能退为stat→rename覆盖竞态；无可靠原语时准确拒绝。 |
| EXEC-06 | D11 | target36生产执行 | 在正式target/ABI下运行nativeLibraryDir内ELF与显式解释器脚本，不能依靠shell提权上下文。 |
| EXEC-07 | D11 | shell语义 | 需要GNU Bash的fixture真实执行；Android sh路径不得伪称完整bash；rc/env/cwd按合同。 |
| EXEC-08 | D11 | Node子命令闭包 | node/rg/curl/git/python逐项真实执行，PATH/HOME/TMP/CA/退出码和中文路径正确。 |
| EXEC-09 | D11 | npm/npx dispatcher | 真实安装并执行锁定纯JS fixture包，shebang/native-helper路径可达，不只--version。 |
| EXEC-10 | D11 | pnpm dispatcher | 含生命周期脚本、依赖解析、离线缓存和失败回滚的受控包完整安装运行。 |
| EXEC-11 | D11 | pip/python闭包 | 受控纯Python包安装后import/运行；无Android wheel时正确错误，不跑错误ABI二进制。 |
| EXEC-12 | D11 | 网络/证书/代理 | curl/git/npm/pnpm/pip各验受控TLS、失败证书、代理与超时，不能关闭校验掩盖失败。 |
| EXEC-13 | D11 | 路径与权限 | 空格/中文/长路径/无写权/路径穿越分别处理，不执行工作区外注入路径。 |
| EXEC-14 | D11 | 实际隔离能力 | 分别验证read-only/workspace-write/danger preset实际文件边界；无OS隔离时不能显示“已沙箱化”。 |
| EXEC-15 | D12 | isatty及controlling tty | 真实程序isatty为true并具controlling terminal，pipe fallback标降级不能PASS真PTY。 |
| EXEC-16 | D12 | 交互程序输入 | 运行受控REPL与全屏TUI，读键盘/输出/回显按终端语义，非单次echo。 |
| EXEC-17 | D12 | resize/SIGWINCH | 改变行列后stty size与TUI布局更新，不能空resize。 |
| EXEC-18 | D12 | job control | Ctrl-C/Ctrl-Z、前台进程组、后台job再fg，信号命中正确group。 |
| EXEC-19 | D12 | attach/detach | 离开再附着同terminal/job，滚动历史和状态连续，不起重复子进程。 |
| EXEC-20 | D12 | PTY取消与孤儿 | 停止任务/主进程死/PTY断开后进程组、fd、master/slave均清理。 |

## 8. 官方完整内核、插件、Skills、MCP与jobs

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| CORE-01 | D13 | 官方插件纯JS完整生命周期 | 通过官方manager安装→启用→工具调用/前端资源→禁用→删除，检查锁文件与profile一致。 |
| CORE-02 | D13 | 插件带client资源 | 真实设置页/工具渲染器加载并HMR或受控重载，非只backend require成功。 |
| CORE-03 | D13 | 依赖冲突/安装失败 | 下载断流、校验错、生命周期失败、磁盘不足均回滚到上一有效profile。 |
| CORE-04 | D13 | native addon分类 | Android可重建fixture实际加载；错误ABI/无binding插件明确诊断，不以no-op冒充能力。 |
| CORE-05 | D13 | 插件更新/回滚 | 升级后工具和UI可用，失败回退依赖闭包和配置；不覆盖用户编辑。 |
| CORE-06 | D13 | 重复安装与并发卸载 | 锁与维护屏障阻止破坏running工具；同操作重复幂等。 |
| CORE-07 | D14 | Skills发现和使用 | 官方SKILL.md索引/相对资源/工具约束由原内核消费，真实任务产生预期artifact。 |
| CORE-08 | D14 | 坏Skill/注入隔离 | 无效元数据、越界资源、附件内伪指令不获得额外平台权限；错误可定位。 |
| CORE-09 | D14 | MCP stdio真实服务 | 受控服务发现tools/resources/prompts、执行与取消；进程树/环境/secret不泄漏。 |
| CORE-10 | D14 | MCP streamable-http | 真实受控HTTP transport认证/重连/断流/取消，协议和错误原样映射。 |
| CORE-11 | D14 | 子Agent委派 | 父子模型路由、隔离上下文、结果回收、失败与取消树由官方内核管理。 |
| CORE-12 | D14 | jobs长任务 | 后台job可查询/等待/停止，应用前后台与attach恢复不重复执行。 |
| CORE-13 | D14 | workflow/PTC | 使用官方PTC路径运行受控多步骤计算+工具任务，检查依赖、结果、取消和恢复。 |
| CORE-14 | D14 | 权限审批闭环 | 读/写/外发按选定策略批准或拒绝，UI审批命中原request；取消与过期审批不能执行。 |
| CORE-15 | D14 | 检查点与上下文压缩 | 官方checkpoint/summary后关键目标、文件引用、工具结果可用，不重复写副作用。 |
| CORE-16 | D14 | preset逐一create/resume | standard/PTC/minimal/cordis所有目标preset分别启动与续会话；缺服务明确失败。 |
| CORE-17 | D14 | 官方工具/命令逐行展开 | 将422所有有效identity绑定正向/失败用例，含web/search/fetch/edit/read/commands等实际导出；不得用本行一次PASS替422行。 |
| CORE-18 | D14 | 官方服务项消费者 | timer/sandbox/authorization等不是只查mounted；真实依赖消费者执行并结算。 |
| CORE-19 | D14 | 官方前后端版本一致 | RPC schema/客户端资源/插件版本不混代；版本错准确阻断且可恢复。 |
| CORE-20 | D13 | 手机插件协议适配 | 保留43工具名、参数schema与结果/附件语义，只替平台传输；官方执行context、审批和取消不旁路。 |

## 9. 手机版43工具逐identity验收

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| PHONE-01 | D16 | shizuku_shell | 受控shell命令返回stdout/stderr/exitCode；超时/取消终止owned进程，不降级为无提示root。 |
| PHONE-02 | D16 | shizuku_status | 未安装/未运行/未授权/可用/Binder死亡逐态准确，不以安装包存在表示可用。 |
| PHONE-03 | D16 | android_notify | 测试channel真实出现通知、动作可用，拒通知权限准确失败不伪报已发送。 |
| PHONE-04 | D16 | android_setting_app | get/put受控应用设置值并读回；无写权明确失败且旧值保留。 |
| PHONE-05 | D16 | android_clipboard | read/write受控文本，遵守OS前后台限制，写后读回；拒绝不返回伪空成功。 |
| PHONE-06 | D16 | android_usage | 授予/撤销usage access下读取受控测试App活动；无授权返回可操作原因。 |
| PHONE-07 | D16 | android_overlay | show/hide/status对应真实悬浮层与权限状态；Node停止后owned窗口回收。 |
| PHONE-08 | D16 | android_package | list/install/uninstall/clear/grant/revoke仅操作受控测试APK；每步查询OS真实结果。 |
| PHONE-09 | D16 | android_app | launch/force_stop/current仅操作fixture，前台包和进程状态证明效果。 |
| PHONE-10 | D16 | android_setting | get/put/list在global/system/secure逐权限验证；测试改值后恢复。 |
| PHONE-11 | D16 | android_screenshot | 实际截图尺寸/方向/内容正确，附件可被DSH模型读取；安全窗口按OS真实限制处理。 |
| PHONE-12 | D16 | android_input | tap/swipe/text/keyevent改变fixture UI，坐标/中文/转义无shell注入。 |
| PHONE-13 | D17 | android_a11y_status | 服务关闭/开启/已连接/撤销/重连状态准确，不用settings enabled替实际连接。 |
| PHONE-14 | D17 | android_screen | fixture可访问节点、文本、bounds/层级正确，不把旧树当当前屏。 |
| PHONE-15 | D17 | android_tap | 节点及坐标目标按真实schema分别点击，fixture计数变化；失效节点准确拒绝。 |
| PHONE-16 | D17 | android_type | 中文/emoji/换行/空字符串清空、setText与paste选项逐测，输入内容可读回。 |
| PHONE-17 | D17 | android_back | 真实返回fixture上一页面，返回失败或服务失联准确可见。 |
| PHONE-18 | D17 | android_home | 真实回到系统桌面，不能仅返回ok。 |
| PHONE-19 | D17 | android_scroll | up/down/left/right各测内容移动与到边界changed/atEdge；边界不误判注入失败。 |
| PHONE-20 | D17 | android_see | 真实屏幕图像与节点语义对应，图片走官方附件管线，具视觉模型可消费。 |
| PHONE-21 | D17 | android_swipe | 不同方向/持续时间/坐标边界，fixture滑动效果可观察，非法坐标拒绝。 |
| PHONE-22 | D17 | android_hold | fixture长按触发真实上下文动作，时长/取消正确，无永久按下。 |
| PHONE-23 | D17 | android_touch | down/move/up及多finger状态，最终抬起且无残留；断开时安全清理。 |
| PHONE-24 | D17 | android_gesture | down/move/up/tap/swipe/hold/wait步骤组合按顺序生效，坏序列执行前拒绝。 |
| PHONE-25 | D17 | android_touch_status | 真实活动finger与最后状态一致，服务死/取消后回到无按压。 |
| PHONE-26 | D18 | android_vscreen_create | 真实建立virtual display并得到独立ID/尺寸；失败无残留display。 |
| PHONE-27 | D18 | android_vscreen_status | reported display/app/帧状态与系统一致，旧ID不映射新display。 |
| PHONE-28 | D18 | android_vscreen_launch | fixture只在指定display启动，主屏不被意外劫持；不支持多屏App明确报错。 |
| PHONE-29 | D18 | android_vscreen_see | ImageReader/JPEG帧链真实更新、方向/大小正确，附件给模型；不要求上游未实现的H264。 |
| PHONE-30 | D18 | android_vscreen_tap | 指定display坐标点击只改变该屏fixture。 |
| PHONE-31 | D18 | android_vscreen_swipe | 指定display滑动真实生效，触摸坐标按尺寸映射。 |
| PHONE-32 | D18 | android_vscreen_key | 指定display按键事件效果正确，不能误发主屏。 |
| PHONE-33 | D18 | android_vscreen_close | 关闭显示并释放Surface/ImageReader/帧泵/相关进程；重复close幂等。 |
| PHONE-34 | D19 | browser_caps | 反映真实Browser服务能力/版本/权限，未启用状态明确。 |
| PHONE-35 | D19 | browser_open | 受控网页真实加载，返回可用tab身份，拒绝危险scheme及私网越权策略之外输入。 |
| PHONE-36 | D19 | browser_tabs | list/new/switch/close操作对应真实tab且identity稳定，不泄露账号浏览器会话。 |
| PHONE-37 | D19 | browser_snapshot | 真实DOM/可见语义与页面revision一致，元素ID在规定生命周期可用。 |
| PHONE-38 | D19 | browser_find | 按真实schema搜索fixture节点，多个/无匹配行为与结果可解释。 |
| PHONE-39 | D19 | browser_click | 真实元素点击触发fixture状态；旧元素ID不得点击新页面随机目标。 |
| PHONE-40 | D19 | browser_type | 文本/Unicode/清空/表单字段真实输入，不能把网页文本当系统指令。 |
| PHONE-41 | D19 | browser_read | 读取fixture可见内容及受支持范围，长内容限额显式，不偷读其它tab凭据。 |
| PHONE-42 | D19 | browser_nav | back/forward/reload/close分别生效，等待与页面失效语义正确。 |
| PHONE-43 | D19 | browser_scroll | down/up/top/bottom逐项滚动并以真实页面位置证明。 |

## 10. action、参数与权限的额外子分母

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| ACTION-01 | D16 | android_setting_app: get/put | 每个action单独记录；验证缺key/坏值、读取旧值、put读回和恢复。 |
| ACTION-02 | D16 | android_clipboard: read/write | 每action正常及OS拒绝各一次，write Unicode与敏感内容不进日志。 |
| ACTION-03 | D16 | android_overlay: show/hide/status | 三个action逐项，未授权show不伪成功，hide后status与窗口一致。 |
| ACTION-04 | D16 | android_package: list | 受控App已装/未装与过滤；不根据包名字符串推断真实安装。 |
| ACTION-05 | D16 | android_package: install | 有效fixture APK、坏APK、版本冲突、用户拒绝各记录，签名不匹配不清数据重装。 |
| ACTION-06 | D16 | android_package: uninstall | 仅fixture，批准/拒绝/不存在三态；实际包查询证明。 |
| ACTION-07 | D16 | android_package: clear | 仅fixture预置数据，审批拒绝保留数据，批准后真实清除。 |
| ACTION-08 | D16 | android_package: grant/revoke | 两个action各验可授予权限与不能授予权限，不把shell退出0当权限变更。 |
| ACTION-09 | D16 | android_app: launch/force_stop/current | 每action子记录；不存在包、不可启动Activity、受限停止分别报真实原因。 |
| ACTION-10 | D16 | android_setting: get/put/list × namespace | global/system/secure逐项组合，读写能力不同分别标注；无权限不得假成功。 |
| ACTION-11 | D16 | android_input: tap/swipe/text/keyevent | 四action逐项，超范围坐标、中文/引号/$文本与非法keycode各负例。 |
| ACTION-12 | D17 | android_scroll: up/down/left/right | 四方向各正常与边界；changed/compared/atEdge根据真实比较。 |
| ACTION-13 | D17 | android_type: empty/paste | 空字符串合法清空；paste=true按真实OS剪贴板限制执行或准确失败。 |
| ACTION-14 | D17 | android_touch: down/move/up | 每action与finger组合记录；move/up无down、重复down、取消均无悬挂触点。 |
| ACTION-15 | D17 | android_gesture: 7种kind | down/move/up/tap/swipe/hold/wait每kind有效一次，混合多finger与等待时取消。 |
| ACTION-16 | D17 | 旋转/缩放/窗口变化 | tap/swipe/hold/touch/gesture各验证坐标版本，不在旧图上静默操作新屏。 |
| ACTION-17 | D18 | VScreen尺寸与生命周期 | create的有效尺寸/非法尺寸、重复create、launch失败、帧泵中断、close后所有旧ID操作。 |
| ACTION-18 | D18 | VScreen权限路线分别验收 | Shizuku与root-only独立验create/launch/input/frame/close及失联；参照root-only无完整bridge实现，ROOT_AVAILABLE不证明可用。权限撤销/MediaProjection拒绝按实际路线测，释放资源不误伤主屏。 |
| ACTION-19 | D19 | browser_tabs: list/new/switch/close | 四op逐项；缺tab、已关闭、切换后旧元素ID、并发close均可诊断。 |
| ACTION-20 | D19 | browser_nav: back/forward/reload/close | 四op分别验证history、加载失败、最后tab关闭和取消等待。 |
| ACTION-21 | D19 | browser_scroll: down/up/top/bottom | 四direction分别验证真实位置，页面无滚动区域不伪称移动。 |
| ACTION-22 | D19 | Browser导航与iframe | 跨源导航、iframe、popup、下载和未完成加载中snapshot，按真实schema边界处理。 |
| ACTION-23 | D15 | 桥认证及反重放 | 错误token、旧hostInstance、过期capability/attempt不得执行手机动作；日志只含脱敏ID。 |
| ACTION-24 | D15 | 动作幂等账本 | 响应丢失后相同operationId不重复通知/安装/点击，UNKNOWN不能自动当失败重放。 |
| ACTION-25 | D15 | SAF读取与写入 | 用户授权tree/document真实读取写入；撤销授权、provider断开、URI遍历与取消准确处理。 |
| ACTION-26 | D16 | Shizuku/root选择 | 未授权Shizuku不静默改root；明确用户选定执行器、权限与真实失败，不按设备品牌屏蔽入口。 |
| ACTION-27 | D17 | 手机控制取消与审批 | 拒绝审批零动作；批准后取消停止后续gesture，已发生动作不虚构回滚。 |
| ACTION-28 | D19 | 页面提示注入隔离 | fixture网页要求外发secret或越权操作，网页内容不改变系统权限/审批策略。 |

## 11. 数据、维护、备份、补丁与完整闭包升级

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| OPS-01 | D21 | 官方会话回收 | 受控会话回收后列表/关联准确，活任务不被回收；取消不出现半状态。 |
| OPS-02 | D21 | 会话恢复 | 恢复后原ID/消息/附件/工具call关联保留，官方create/resume正常。 |
| OPS-03 | D21 | 附件缺失精确修复 | 只修fixture已知损坏引用，不删除正常附件或整会话；生成修复预览和journal。 |
| OPS-04 | D21 | JSONL部分写入 | 截断末行/坏记录/重复世代分别按官方规则恢复，完整旧记录不被覆盖。 |
| OPS-05 | D22 | 维护准入屏障 | 运行推理、工具、MCP、PTY/job时维护被明确等待或拒绝；不能仅看UI空闲。 |
| OPS-06 | D22 | 维护中断恢复 | 在journal每个持久化边界注入进程死，重启恢复为旧版/新版明确状态，不半混。 |
| OPS-07 | D22 | 备份内容闭包 | 配置/会话/附件/用户插件/Skills/补丁版本齐全且hash可验，secret按显式策略排除或独立保护。 |
| OPS-08 | D22 | 备份恢复 | 恢复到受控新home后真实resume、附件读取、插件工具与模型路由可用；旧数据仍可恢复。 |
| OPS-09 | D22 | 损坏/恶意备份 | hash错、缺文件、zip slip、超体积、symlink越界均拒绝，未修改原home。 |
| OPS-10 | D22 | 安全模式 | 坏插件/用户补丁造成启动失败时可进入救援，保留用户数据并能禁用具体坏项。 |
| OPS-11 | D22 | 自检真实生产链 | 检查Node/ELF/锁/依赖/桥/模型绑定实际结果，不只文件存在；失败给出定位信息。 |
| OPS-12 | D23 | 用户补丁安装与撤销 | 声明目标版本/hash，冲突预览、备份、原子应用、失败还原、撤销后原版本可用。 |
| OPS-13 | D23 | 主题导入与切换 | 主题是受限样式数据，不授予native脚本；浅深色、键盘/代码/审批可读。 |
| OPS-14 | D23 | 悬浮动作 | 开启/关闭/拖拽/权限撤销/任务取消与真owner一致，不凭浮层另起Agent循环。 |
| OPS-15 | D24 | 候选闭包校验 | Node、payload、Android patches、ELF、插件、profile schema/toolchain一并核对，拒绝混版本。 |
| OPS-16 | D24 | 候选真实trial | 停止旧宿主并确认资源释放后，隔离候选数据快照真实启动，账号模块只用脱敏fixture及受控stub，UI/模型/工具仅限无外部副作用smoke；不登录真实账号、不加载用户插件、不发通知/点击/外发。trial通过并确认停止后，真实home预检/备份→production health pending核验账号/provider只读状态及既有配置/会话reader→健康证据落盘→最终COMMITTED；不能先提交再补健康结论。真实账号推理/动作由D26受控验收或用户正常操作证明，不由升级探针自动登录、收费生成或重放旧turn。 |
| OPS-17 | D24 | trial失败回退 | 任一smoke失败回到已知旧闭包与匹配数据，journal可解释；不让两host同时写home。 |
| OPS-18 | D24 | 提交瞬间崩溃 | commit前/后故障各恢复一致active版本，不能读取半更新selector。 |
| OPS-19 | D24 | 不可逆数据升级 | 有forward repair或明确恢复点，旧二进制不能直接读新格式；未设计前不得宣称可任意降级。 |
| OPS-20 | D24 | 真实同签名APK升级 | 在已有真实fixture数据上原applicationId同兼容签名覆盖安装，官方/聊天/图片/原生Agent数据保留。 |
| OPS-21 | D24 | 升级无网络/空间不足 | 保留旧可运行版本，临时文件受控清理，不误删旧payload/模型。 |
| OPS-22 | D22 | 诊断导出 | 包含脱敏状态、版本/hash、失败链，排除账号secret/消息原文/私人路径，导出失败不破坏home。 |
| OPS-23 | D21 | 导入导出兼容 | 官方支持的会话格式含工具结果与附件关联，旧fixture与往返读取均验证。 |
| OPS-24 | D22 | 并发维护命令 | 备份/修复/升级/插件管理争同一维护lease，仅一个writer且取消释放正确。 |
| OPS-25 | D07 | 维护与账号exchange/committing竞争 | 登录到exchange/committing时发起备份/升级，排他屏障等待或拒绝；快照不能含半提交凭据。 |
| OPS-26 | D07 | 维护与logout/401删除竞争 | logout及匹配401触发credential删除与备份/恢复交错，保持同一writer事务顺序，不复活已删token。 |
| OPS-27 | D07 | 维护与settings/profile及watch竞争 | 官方settings/profile写入和watch回调同时触发，维护期所有mutator被屏障纳入，不只是阻止新turn。 |
| OPS-28 | D07 | 维护与插件/会话mutator竞争 | 安装/卸载/更新plugin、写session/checkpoint与备份/升级交错，锁顺序明确，无半profile或死锁。 |
| OPS-29 | D07 | 无在线全屏障时离线维护 | 不能证明覆盖全部writer时先按D01确认Node及全部可写子进程树停止，再维护；任一UNKNOWN保持拒绝。 |

## 12. UI、旧功能与性能回归

| 用例 | 责任卡 | 操作 / 场景 | 必须观察到的通过结果 |
| --- | --- | --- | --- |
| REG-01 | D25 | 侧栏独立入口 | 保留聊天/生图/原生Agent，DSH入口在Agent之后；正确进入官方前端，不改既有冻结布局。 |
| REG-02 | D25 | WebView加载恢复 | 冷启动/离页再入/旋转/资源重载保持正确origin与会话，错误页能重试。 |
| REG-03 | D25 | 文件上传下载 | input与下载真实选择/保存、取消/权限拒绝、大小限制和中文文件名正确；不能替代Agent工具桥。 |
| REG-04 | D25 | 键盘/返回/焦点/滚动 | 长对话、代码块、表格、模型菜单与审批弹层下逐项验证，无嵌套返回丢任务。 |
| REG-05 | D25 | 浅深色/大字体/旋转 | 真实渲染检查官方页+宿主控制台，不用Preview/编译替真实交互。 |
| REG-06 | D20 | 宿主控制台 | 账号/MCA连接/权限/执行器/VScreen/Browser/维护显示真实owner投影，按钮触发真实路径。 |
| REG-07 | D25 | 普通聊天回归 | 本地/云端、流式、多轮、附件、取消、编辑重试和历史fixture按受影响清单验证。 |
| REG-08 | D25 | 生图回归 | 受影响真实后端代表模型、参数、任务、图库、保存/分享和失败恢复，不能仅页面打开。 |
| REG-09 | D25 | 原生Agent回归 | 工具/审批/记忆/工作流已有能力与任务数据保持；DSH不依赖原生Agent循环。 |
| REG-10 | D25 | Local API回归 | 既有认证/HTTP/SSE/取消/终态与共享推理owner一致，DSH token不替换用户API凭据。 |
| REG-11 | D26 | 无网络本地任务 | 关闭WAN完成本地模型+文件/命令任务；云/账号相关失败准确而本地入口可用。 |
| REG-12 | D27 | 重复启动与长运行 | 预注册次数内启动/stop/重入/模型切换/插件生命周期循环，资源无单调增长/孤儿。 |
| REG-13 | D27 | 性能基线比较 | 同模型/有效参数/设备/热状态测启动、TTFT、吞吐、停止/释放、总内存/磁盘；不通过缩上下文降质量改善。 |
| REG-14 | D27 | 72小时受控耐久 | 先完成2小时整合预检，再做72小时最终耐久；持续任务、后台/锁屏、取消、网络切换、恢复与维护穿插；关联ID可定位，无数据坏/资源泄漏。 |

## 13. 模型任务成功率、对照与性能判据

D26 先提交并冻结任务集、oracle、重复次数、采样参数与失败分类，再开始收结果，不得看到成绩后删失败任务。建议最低可执行设置：

| 层次 | 场景与样本 | 过门 |
| --- | --- | --- |
| 确定性合同 | 本文L0及每行绑定的协议/锁/幂等/状态/错误分支 | 必须全部通过；0执行/跳过不可当通过 |
| 手机工具效果 | 43 identity + 所有列明action/关键参数；每项至少正常与权限/失效负例 | 全部required项真实OS效果、拒绝无副作用、取消清理通过 |
| 模型基础工具基准 | 冻结60个任务：文件10、命令10、检索/浏览器10、手机UI10、委派/Skills/MCP10、多步/恢复10；其中20个仅整合者保留 | 每个目标“模型+后端+工具协议”组合分别统计；60题×3次，不合并不同模型分母 |
| 控制与安全不变量 | 重复写入、越权、取消后继续动作、secret泄露、错会话、假STOPPED、数据损坏 | 零容忍，任一失败阻断该范围完成，不被平均任务分抵消 |
| 任务成功率 | 第一次完成、重试后完成、工具选择/参数/结果正确率、总耗时、人工介入均记录 | 对受控同模型同配置的上游桌面DSH作基准；上线前预锁可接受绝对目标与非劣效界限 |
| 相对比较 | 同模型/任务/参数在官方桌面DSH、指定手机版/MCA运行可比子集；平台动作只比同语义fixture | 报Wilson区间与失败分类；模型/服务不可比的部分明确不做归因 |
| 耐久 | REG-12循环与REG-14 72小时；循环次数/负载在运行前锁定 | 零孤儿/数据坏/错误终态；资源趋势无持续泄漏，有原始时间序列 |

不预先编造“任何0.8B均可达到某成功率”。工程门槛可定为：确定性、协议与平台操作100%过门；模型任务建议首轮≥90%、受控一次重试后≥95%作为发布目标，但D26须结合实际选定模型先注册且不得事后降低。**如果模型达不到，报告该组合不满足任务能力目标；不能写成内核全完成，也不能通过全局隐藏模型入口处理。** 支持模型被调用与推荐它执行复杂任务是两件事。

复杂任务失败按 `MODEL_SELECTION / PARAMETER / TOOL_SCHEMA / WIRE_TEMPLATE / GATEWAY / PERMISSION / PLATFORM / TIMEOUT / DATA / USER_REJECTED` 等根因分类；先核真实wire、chat template、能力与结果，不凭离题回答直接判“小模型问题”。拒绝审批是正确控制结果，不能混入“模型失败”或当任务完成。官方在线搜索等使用专属服务时，MCA来源需分别明确服务依赖和降级；本地模型可运行不等于全部外部工具离线。

性能至少记录 p50/p95 与样本量；总资源含main/Node/工具/native/GPU/NPU适用项。对冷启动、READY、官方前端可交互、TTFT、解码、用户取消反馈、实际停止、lease释放分别计时。百分比目标由同条件基线和产品预算锁定；缺基线保持NOT_RUN，不能用单次最快值宣称提升。

## 14. 422官方行逐项验收的展开规则

D00 为每行建立或链接具体的 case，不要求机械新增422个同构单测。账本的family/owner初始映射是：账号D03（生命周期D05、popup D04）、model-routing D06（wire D08、后端D09）、terminal D12、extensions D13、session D21、client-ui D25、host-runtime D01、storage/workspace D10、native/subprocess D11、default-config D02、其余官方loop/工具/Skills/MCP/preset/commands由D14负责；真实读源后可调整owner并记录理由，不能以分类代替逐行判断。

`UNREVIEWED`表示尚未审清profile；外部依赖暂缺不能直接变N/A；旧optional/disabled必须核实实际composition与目标profile。来源不明、有效disabled条件不能判定或生产消费者不明时继续UNREVIEWED并形成D00阻塞项；运行操作的UNKNOWN只属于动作/停止事实，不是通过或profile豁免。

逐行步骤：

1. 固定真实composition/客户端/preset来源；确认动态disabled条件与有效服务图。不改旧manifest的身份或requiredForV1字段，新判断写新账本。
2. 明确生产调用者、被测输入和真实效果。tool 行要展开实际工具名及schema/action；command 行要执行官方命令；service 行要驱动真实消费者；client 行要操作UI并观察owner或持久化结果。
3. 检查正向、关键负向、取消/恢复风险；多个 identity 可以共享一个可证明覆盖的端到端case，但记录具体断言，不允许“同属插件所以算通过”。
4. Android需要适配的条目保留原语义与错误，而不是return success/no-op。Windows/桌面特有条目按profile列N/A，有依据、有替代说明，身份仍在485总账本。
5. 每行最终包含 `profile.reason`、`implementation.productionEntry`、`acceptanceCases`、当前candidate证据；未绑定用例或无消费者的行不能标 VERIFIED。

手机版的宿主控制台、备份/恢复、自检、安全模式、会话回收、补丁/主题/悬浮功能不只计43工具，分别对应D20–D25与OPS/REG测试。SOURCE_MAP查出的新入口还须追加身份/用例，不用“未在43中”剔除。

## 15. 最终门与接手者执行顺序

1. **G0 基线与可靠宿主（D00–D02、D10基础）**：422旧identity、43唯一tool、20 MCA目标完整保留；来源/闭包/dirty输入与owner明确。配置、正确停止/未知态fence、锁和数据原语经过对应验证。
2. **G1 账号与MCA双模型（D03–D09）**：真实账号登录/退出/401全生命周期；账号/API Key/MCA独立；binding应用ACK、最终wire、逐后端工具与视觉验证。
3. **G2 DSH执行生态（D11–D14）**：target36真实命令、真PTY与准确降级标签、官方插件/Skills/MCP/jobs/子Agent/PTC及422行对应执行语义通过。
4. **G3 手机版五能力（D15–D19）**：平台桥认证/幂等及43工具/actions在各实际权限路线有真实OS效果与失败/取消证据，不把root可见当bridge可用。
5. **G4 数据与运维（D20–D25）**：维护屏障、会话/备份/补丁、完整闭包无副作用trial、最终提交前production health pending、前后台/WebView、真实同签名升级及旧数据连续。
6. **G5 对标交付（D26–D27）**：当前单一候选完整覆盖、模型成绩/对照、2小时整合预检及72小时最终耐久、总资源、架构/视觉和旧功能回归。所有required行无FAIL/NOT_RUN/BLOCKED/UNREVIEWED，未修新回归为零。

失败只回退到上个已验证的源码/闭包/数据恢复点，保留原始证据。回退二进制前先判断数据schema兼容；不清用户数据、不重置账号、不删测试“复绿”。无需为文档运行全量构建；本轮不执行以上门禁、不登录用户账号、不操作设备。

## 16. 账本静态检查与本轮实际范围

接手者可在仓库根运行以下只读检查（文档方案检查，不是能力验收）：

```python
import json
from pathlib import Path
base = Path('docs/proposals/agent-dsh-embedded-v1')
old = json.loads((base/'CAPABILITY_MANIFEST.json').read_text(encoding='utf-8-sig'))
new = json.loads((base/'completion-v2/PARITY_MATRIX_V2.json').read_text(encoding='utf-8-sig'))
rows = new['rows']
assert len(rows) == len({r['id'] for r in rows}) == 485
assert {r['id'] for r in old['rows']} == {
    r['id'] for r in rows if r['source']['kind'] == 'official-manifest-row'
}
mobile = [r for r in rows if r['source']['kind'] == 'mobile-tool-export']
assert len(mobile) == len({r['source']['exportedToolName'] for r in mobile}) == 43
assert all(r['ownerDCard'] in {f'D{i:02d}' for i in range(28)} for r in rows)
assert len([r for r in rows if r['source']['kind'] == 'mca-target-design']) == 20
assert new['preservedLegacySourceMetadata'] == old['source']
```

本轮仅新增本验收规范和新矩阵；未修改旧manifest或生产代码。实际检查范围：JSON解析、422身份集合/来源元数据保留、43名称唯一和分组数、20条MCA目标来源分离、卡号合法、用例ID唯一、Markdown链接/尾空白、内容一致性。**所有运行用例NOT_RUN，无APK构建、无设备验收、无真实账号登录。**
