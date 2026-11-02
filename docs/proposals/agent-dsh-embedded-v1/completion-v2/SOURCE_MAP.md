# DSH V2 源码依据、逐工具清单与复用边界

日期：2026-10-20。性质：只读源码研究与实施依据；本文不代表手机功能已验收。配套 [总方案](README.md)、[架构](ARCHITECTURE.md)、[执行卡](EXECUTION_PACK.md)、[逐项验收](PARITY_ACCEPTANCE.md)。

## 1. 固定三份基线，避免把源码、补丁与产物混为一体

| 对象 | 固定身份 / 读取证据 | 证据边界 |
| --- | --- | --- |
| MCA 当前源码 | `68b6eef1e67870b02bfef6afda2e625d36663da9`；`codex/enterprise-refactor`；DSH、原生 Agent、前端等存在大量 tracked/untracked 改动 | HEAD 不是完整构建输入；原有改动不得覆盖。三份 native 子模块的工作区 diff 也必须纳入 D00 快照 |
| 官方 DSH 源码 | `deepseek-ai/deepseek-harness`，审计标记 `687f282e8625c6b8d11ff58211a90524be2e4317`，包版本 `0.2.1-rc.2` | 既有审计副本来自源码归档，依靠 `.mca-review-sha` 标记，不能声称已用该副本 Git 对象重新推导来源；补齐固定 commit 的可复核获取证据 |
| 本次指定手机版源码 | `woaiys3/deepseek-harness-android-app`，Git HEAD **`98162a23b39eeeb75b8d4608b3e773bbb13be43d`**；commit 时间 `2026-10-18T09:28:36+08:00`；研究副本工作区 clean | 本轮所有手机版源码结论均针对这份 revision；不是 `DSH-APP/DSHA`，不是官方 Android 支持，也不是本轮真机通过证据 |
| MCA 既有参考 APK / payload | [payload-lock.json](../../../../scripts/dsh/payload-lock.json)：APK SHA256 `c324272bff00e8cce06e0baa2ebb9b88e524a5d246e06f8cb55d39df7c66dafa`；payload SHA256 `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab`；kernel `0.2.1-rc.2` / revision `20261018072502` | 下载产物身份、源码 commit、最终解包树、Android patch 集分别记录。整包 hash 不能替代最终 composed tree 的逐文件 hash；产物内的补丁不自动等于官方源码 |

既有完整基线见 [BASELINE.md](../BASELINE.md)。旧表中“payload manifest 未最终”等描述属于当时状态，本版以当前 lock、最终解包树和本次候选实际证据继续核对，不改写旧记录。

本轮只读取源码、配置、构建脚本和既有记录，没有启动 DSH、执行仓库脚本、安装插件、请求模型、打开授权页或登录账号。下列“存在/实现”表示静态生产路径存在，不表示运行验证 PASS。

## 2. 官方 DSH 必须保留的事实

以下链接固定官方审计标记；归档来源局限按上一节保留。

| 能力 | 源码定位 | MCA 的处理 |
| --- | --- | --- |
| Web profile 组合 | [boot/app-boot/src/profile.ts](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/boot/app-boot/src/profile.ts)；[bundle/base/cordis.patch.yml](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/bundle/base/cordis.patch.yml)；[bundle/web-app/cordis.patch.yml](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/bundle/web-app/cordis.patch.yml) | 审查最终 profile/preset/service injection 闭包。包存在、配置行存在、已注册与 Agent 实际可见是不同状态 |
| 官方功能逐项清单 | [CAPABILITY_MANIFEST.json](../CAPABILITY_MANIFEST.json) 的既有 422 行 identity | 保留 identity；V2 逐行重新核定适用范围和证据，不以旧 `requiredForV1` 排除本次完整目标 |
| 官方启动与 Web 认证 | [bundle/web-app/src/startup.ts](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/bundle/web-app/src/startup.ts) | 使用 loopback。等 required-startup audit 与认证 ready；监听端口打开不等于 ready。一次性 Web URL token 只用于本地 bootstrap，不能当 DeepSeek 账号凭据 |
| 账号抽象 | [credentials/deepseek-account/src/index.ts#L75](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account/src/index.ts#L75) | `startSignIn(client, callbackOrigin, loginSource)`；不重新发明 Android 账号协议 |
| 账号平台实现 | [credentials/deepseek-account-platform/src/index.ts#L402](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/src/index.ts#L402)；[协议结构](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/src/protocol.ts) | Android 负责外部浏览器、回到页面与生命周期；attempt/交换/凭据状态仍由官方模块负责 |
| 账号模型 provider | [llm/llm-deepseek-account/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/llm/llm-deepseek-account/src/index.ts) | 保留账号 provider。MCA 模型是额外来源；不把登录入口替换成 API Key 表单 |
| 官方生态与配置 | [V1清单](../CAPABILITY_MANIFEST.json)、当前 payload profile 与 lock | 工具、审批、工作流/PTC、压缩、Skills、MCP、jobs、子 Agent、会话与插件保留官方业务实现；Kotlin 不再写一套循环 |

账号源码的精确约束：固定复用 `ctx.webServer` 的 **`GET /oauth/callback`**；`callbackOrigin` 只接受带显式非零端口、无额外路径的 HTTP loopback origin；`loginSource` 只有 `web` / `desktop`。state/verifier 各 32 字节，S256；默认 attempt 600,000 ms、最大 3,600,000 ms；初始化响应 `expires_in` 只缩短本地 deadline。不能凭通用 OAuth 经验添加该版本未定义的 refresh token、grant expiry、每 attempt 随机回调路径或另起临时端口。完整状态机和 Android 处理以 [架构账号章节](ARCHITECTURE.md) 为准。

| 账号细节 | 固定官方定位 | 接手者必须保留的语义 |
| --- | --- | --- |
| 前端预开窗口 | [browser-login.ts#L31](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/client/ui-settings-account/src/client/browser-login.ts#L31)；[#L66](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/client/ui-settings-account/src/client/browser-login.ts#L66) | 用户点击时先预开`about:blank`，RPC返回后再`location.replace(authorizeUrl)`；不是onCreateWindow立即已有授权URL。不能用1秒/5秒固定空白窗口寿命截断官方流程 |
| 只读attempt投影 | [types.ts#L25](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account/src/types.ts#L25)；[account-controller#L66](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/api/account-controller/src/index.ts#L66) | safe view含attempt身份、phase、authorizeUrl、expiresAt而无token；宿主可信seam关联pending窗口，不接受任意网页自报attempt |
| callback校验与交换 | [platform/index.ts#L522](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/src/index.ts#L522)；[protocol.ts#L248](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/src/protocol.ts#L248) | 单值state/code、GET、固定path、origin与phase；错误参数400，取消/重复/非waiting-browser为410；旧callback不覆盖新账号 |
| 取消/退出/失效 | [platform/index.ts#L321](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/src/index.ts#L321)；[#L450](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/src/index.ts#L450)；[#L462](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/src/index.ts#L462) | committing阶段等待done；本地删除成功才后台revoke；失效按lifetime/token/issuer匹配，不让旧401删新登录 |
| 凭据唯一writer | [credentials-local/index.ts#L687](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/credentials-local/src/index.ts#L687) | 保留真实文件锁、0700目录与0600原子文件写；不建立Kotlin平行token store |
| 退出影响的任务 | [account-tasks.ts#L12](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account/src/account-tasks.ts#L12) | signed-out只取消官方定义的deepseek-account运行Agent并keepInbox；不全局取消MCA来源任务 |
| 无expiry/refresh、远端退出重试 | [platform/README.md#L95](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/README.md#L95)；[logout.ts#L25](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/credentials/deepseek-account-platform/src/logout.ts#L25) | 最多初次+5次额外重试，仅内存且shutdown可abort；不持久新增待撤销token队列 |

本轮在指定Android壳Java路径未发现另一套独立DeepSeek账号协议；它的官方UI/模型选择包保留账号provider集成。Android代码中Web token、sessionRPC与cookie换票证据只支持本地Web认证，不能当作DeepSeek账号真机登录成功证据。

## 3. 指定手机版的真实装配方式

```text
Android MainActivity / 控制台
    ├─ 准备 runtime、dshroot、dshhome、wrapper、移动端补丁
    ├─ ProcessBuilder → Node → DSH 官方内核 + Android overlay + 五插件
    │                  ├─ 官方 Web UI / Web token / cookie
    │                  ├─ 官方账号与普通 provider / 模型选择
    │                  └─ 插件 HTTP → Android / Shizuku / A11y / VScreen / Browser
    ├─ EngineService：前台通知（不等于独立、完整的 Node owner）
    ├─ BrowserService：:browser 进程中的 WebView + DOM 自动化
    └─ VScreen bridge → Shizuku app_process 特权服务端
```

| 路径 | 读取到的实现 | 借鉴重点 |
| --- | --- | --- |
| [android-app/build.sh#L39](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/build.sh#L39) | 注入移动端补丁、从预备目录复制 Node/DSH/依赖、生成部分 manifest、制作命令 wrapper、打包配置/UI assets | 采用固定来源与 patch manifest；不要直接运行脚本或把其机器绝对路径变成 MCA 构建依赖 |
| [MainActivity.java#L2211](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/MainActivity.java#L2211) | 启动、安装/同步、readiness、认证URL提取、控制台、权限、维护全部集中 Activity | 借鉴行为与错误分支，拆入 MCA 既有 owner/窄能力实现，禁止移植整个 Activity |
| [MainActivity.java#L2453](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/MainActivity.java#L2453) | readiness 避免探测消耗一次性 token；识别 DSH 特有认证响应 | 探测 liveness、required-ready、认证可用要分别记录，不能仅看到 HTTP401/303 就宣称全部 ready |
| [MainActivity.java#L3992](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/MainActivity.java#L3992) | ProcessBuilder 设置 HOME、DSH_HOME、PATH、CA、Git、APP_LOCAL_TOKEN，处理 stdout/stderr 和 web URL | MCA 保留现有 target36 可执行布局、进程身份与统一 DshRuntimeOwner；不要另起 Activity 管理的 host |
| [EngineService.java#L13](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/EngineService.java#L13) | 前台服务/START_STICKY，实际 Node 起停主要由 Activity 管理 | 不等于 Android 杀进程后可恢复全部状态，不能复制“后台永不死”的结论 |
| [config/cordis.patch.yml](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/config/cordis.patch.yml) | 禁用 bash-sandbox/pwsh-sandbox，插入 bash-local 和五手机插件；保留 sandbox/PTC 注入；禁会话日志下载/遥测；改默认权限 | 逐条记录 Android 适配原因；不能顺便删官方功能或无条件复用危险默认值 |
| [LocalAuth.java#L14](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/LocalAuth.java#L14) | SharedPreferences 稳定本地能力 token，可解析 query token | loopback 不等于可信。MCA 能力 token、模型网关 token、Web bootstrap、账号凭据四者分离；能力 endpoint 优先请求头认证 |

`config/cordis.patch.yml` 的依赖说明有实际参考价值：禁掉整个 sandbox 服务会使 PTC/workflow 所依赖服务永久 pending；禁掉 llm-pi-ai 会导致自定义 provider 协议入口缺失。应先证明服务注入与启动闭包，再做平台替换；不要按包名或 native 猜测删掉整个服务。

## 4. 五插件逐项清点：43 个唯一工具

**固定 revision 源码清点为 `7 + 5 + 13 + 8 + 10 = 43`。** 工具数以 `tools.register(defineTool(...))` 的名字为准，并展开动态 `globalAction` 的 `android_back` / `android_home`。`output.schema` 内普通 `name` 字段不计；一个工具的多个 action 也不伪装成多个工具。条件注册的工具仍属于对标范围；运行 roster 必须标注其依赖条件。

每行的动作细节、参数、输出 schema、审批和取消还需 D00/D15–D19 导出为机器清单并用 fixture 锁定。本文提供可定位源，不允许开发者凭表重新发明 schema 或将 DSH property-map 参数直接误当标准 JSON Schema envelope。

### 4.1 `dsh-tool-android`：7 个

| # | 工具 / 固定源码 | action / 核心行为 | 验收关注 |
| --- | --- | --- | --- |
| A01 | [android_usage#L424](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-android/lib/index.js#L424) | 使用统计查询 | 使用统计授权、时间范围、结构化返回 |
| A02 | [android_overlay#L466](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-android/lib/index.js#L466) | `show / hide / status` | 悬浮权限；收到派发与实际显示分开 |
| A03 | [android_package#L520](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-android/lib/index.js#L520) | `list / install / uninstall / clear / grant / revoke` | 按 action 审批/权限；实际包状态；系统确认结果 |
| A04 | [android_app#L584](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-android/lib/index.js#L584) | `launch / force_stop / current` | 目标包、前台状态、派发未知，不能自动重放写动作 |
| A05 | [android_setting#L617](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-android/lib/index.js#L617) | `get / put / list`；`global / system / secure` | 命名空间/键与读写分级；读回确认 |
| A06 | [android_screenshot#L670](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-android/lib/index.js#L670) | 系统截图 | 图像实际可读、保存/附件引用、敏感页限制 |
| A07 | [android_input#L695](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-android/lib/index.js#L695) | `tap / swipe / text / keyevent` | 坐标/屏幕/前台目标、输入结果，不仅 shell exit0 |

### 4.2 `dsh-tool-shizuku`：5 个

| # | 工具 / 固定源码 | action / 核心行为 | 验收关注 |
| --- | --- | --- | --- |
| S01 | [shizuku_shell#L247](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-shizuku/lib/index.js#L247) | root 或 Shizuku 执行 shell；无授权时条件注册 | root/Shizuku 状态与撤权；显式审批、超时/取消、子进程终止 |
| S02 | [shizuku_status#L309](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-shizuku/lib/index.js#L309) | 通道/授权/原因查询 | 缺权限状态仍可查询，不能假装通道可用 |
| S03 | [android_notify#L412](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-shizuku/lib/index.js#L412) | Android 通知 | 普通通知权限，不能强绑 root；posted/visible 与证据 |
| S04 | [android_setting_app#L500](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-shizuku/lib/index.js#L500) | `get / put` 应用设置 | old/new、错误语义、必要系统授权 |
| S05 | [android_clipboard#L621](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-shizuku/lib/index.js#L621) | `read / write` | Android前台/剪贴板限制、审批、实际读写 |

该源码默认特权 shell 自动执行，仅 `SHIZUKU_APPROVE=ask` 逐次审批；这是参照实现的策略，不是 MCA 应继承的默认授权。MCA 保留用户明确选择的权限 preset 和 Android 能力审批，不能由插件任意绕过。

### 4.3 `dsh-tool-accessibility`：13 个

| # | 工具 / 固定源码 | action / 核心行为 | 验收关注 |
| --- | --- | --- | --- |
| X01 | [android_a11y_status#L389](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L389) | 服务启用/连接状态 | 系统设置启用、进程重连、撤权 |
| X02 | [android_screen#L444](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L444) | 结构化可访问性树 | 当前窗口、节点、屏幕几何、无内容/安全窗口 |
| X03 | [android_tap#L519](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L519) | 节点/坐标点击 | 执行前复核窗口/目标/坐标；完成 callback |
| X04 | [android_type#L645](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L645) | 文本输入 | 可编辑节点、焦点、输入结果与敏感文本日志 |
| X05 | [android_back#L712](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L712) | 动态 globalAction：返回 | 系统操作结果与页面变化 |
| X06 | [android_home#L713](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L713) | 动态 globalAction：Home | 系统操作结果 |
| X07 | [android_scroll#L717](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L717) | `up / down / left / right` | 节点可滚动/方向/实际位移 |
| X08 | [android_see#L776](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L776) | 截图并产生视觉附件；attachments 服务条件注册 | 真图片、附件引用/大小、视觉路由能力 |
| X09 | [android_swipe#L957](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L957) | 滑动 | 时长/边界/dispatchGesture完成 |
| X10 | [android_hold#L1018](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L1018) | 长按 | 手势实际完成、取消 |
| X11 | [android_touch#L1051](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L1051) | `down / move / up` | 连续触摸状态与异常释放 |
| X12 | [android_gesture#L1084](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L1084) | strokes：`down / move / up / tap / swipe / hold / wait` | 序列/多触点/取消/超时后的触摸释放 |
| X13 | [android_touch_status#L1161](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-accessibility/lib/index.js#L1161) | 当前触摸状态 | 与实际未结束触摸一致 |

宿主核心定位：[AccessibilityService.java#L98](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/AccessibilityService.java#L98) 连接；[#L253](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/AccessibilityService.java#L253) 本地认证；[#L669](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/AccessibilityService.java#L669) gesture callback；[#L1285](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/AccessibilityService.java#L1285) 截图。不能把“不需要 root”写成“不需要用户系统授权”。

### 4.4 `dsh-tool-vscreen`：8 个

| # | 工具 / 固定源码 | action / 核心行为 | 验收关注 |
| --- | --- | --- | --- |
| V01 | [android_vscreen_create#L292](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L292) | 创建指定尺寸/方向/dpi独立虚拟屏 | Shizuku/root真实条件、display身份与资源所有权 |
| V02 | [android_vscreen_status#L372](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L372) | 虚拟屏状态 | 未创建/已关闭/进程死/权限失效区分 |
| V03 | [android_vscreen_launch#L418](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L418) | 将应用启动到指定display | 实际display与目标包；不支持多显示的应用错误 |
| V04 | [android_vscreen_see#L498](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L498) | 截图/视觉附件；attachments条件注册 | `no_display / not_rendered / no_frame`、waitMs与图像引用 |
| V05 | [android_vscreen_tap#L667](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L667) | display内点击 | 定向注入，不能误操作主屏 |
| V06 | [android_vscreen_swipe#L695](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L695) | display内滑动 | 坐标换算/尺寸版本/中断释放 |
| V07 | [android_vscreen_key#L725](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L725) | key与别名 | 目标display与实际效果 |
| V08 | [android_vscreen_close#L775](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L775) | 关闭虚拟屏 | Display/reader/线程/特权进程按scope释放 |

宿主由 [VsreenBridgeService.java#L48](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/VsreenBridgeService.java#L48) 桥接到 shell `app_process` 核心；[#L743](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/VsreenBridgeService.java#L743) 附近有加载前 jar SHA256 检查和重提取；这类特权加载完整性是应保留的设计。但 MCA 仍须绑定最终加载文件身份、所有权和启动参数，避免校验与复制/加载之间替换。

插件在 [index.js#L282](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-vscreen/lib/index.js#L282) 按root/Shizuku可用性整族条件注册，而宿主 [bridge#L735](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/VsreenBridgeService.java#L735) 实际等待Shizuku、[#L779](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/VsreenBridgeService.java#L779) 直接通过其binder拉起core，未见root-only宿主launcher分支。因此README“root或Shizuku”不能证明root-only已跑通；D18单独验证/适配两条真实transport。授权变化后工具roster是否更新也需验证，不能仅改UI权限状态。参照 [bridge#L776](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/VsreenBridgeService.java#L776) 还把token拼进命令并记录整个cmd；MCA必须避免该凭据暴露路径。

实际图像路径为 [vscreen/Main.java#L169](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/vscreen/Main.java#L169) 的 `ImageReader` + frame pump，[#L396](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/vscreen/Main.java#L396) 创建 RGBA reader，[#L590](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/vscreen/Main.java#L590) 编码 JPEG 预览；[bridge#L575](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/VsreenBridgeService.java#L575) 是 JPEG 预览轮询。README/许可说明中的 MediaCodec/H264 描述不能当这份源码已实现的视频链。H264可作为后续性能优化，不作为本次复用已完成项。

### 4.5 `dsh-tool-browser`：10 个

| # | 工具 / 固定源码 | action / 核心行为 | 验收关注 |
| --- | --- | --- | --- |
| B01 | [browser_caps#L113](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L113) | 能力/状态查询 | browser进程/协议版本/依赖状态 |
| B02 | [browser_open#L144](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L144) | 打开URL | scheme、重定向、导航成功/失败 |
| B03 | [browser_tabs#L179](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L179) | `list / new / switch / close` | 独立tab身份、stale tab、关闭资源 |
| B04 | [browser_snapshot#L244](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L244) | DOM快照；since/includeText；added/removed/changed | 增量语义与页面generation、正文可读性 |
| B05 | [browser_find#L325](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L325) | 查找节点/ref | 唯一性/不可见节点/跨导航失效 |
| B06 | [browser_click#L374](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L374) | ref点击 | preflight、tab/navGeneration、实际导航/效果 |
| B07 | [browser_type#L421](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L421) | ref输入 | 可编辑目标、受控输入事件与提交审批 |
| B08 | [browser_read#L463](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L463) | 读取节点/页面内容 | 限量/截断明确、文本视为非可信数据 |
| B09 | [browser_nav#L514](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L514) | `back / forward / reload / close` | history、generation更新、资源退出 |
| B10 | [browser_scroll#L543](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/plugins/dsh-tool-browser/lib/index.js#L543) | `down / up / top / bottom` | 实际位移、不可滚动/边界结果 |

浏览器重点源：[BrowserHost.java#L93](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/BrowserHost.java#L93) 区分 tab 与导航generation；[#L482](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/BrowserHost.java#L482) ref指纹；[#L579](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/BrowserHost.java#L579) DOM变更；[#L937](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/BrowserHost.java#L937) generation校验；[BrowserService.java#L80](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/BrowserService.java#L80) 专属WebView data suffix。

`:browser` 进程有崩溃隔离收益，但仍同 UID，不是恶意插件隔离。源码 URL 检查拒绝一部分 literal loopback，只接受http/https/aboutblank；不能据此宣称 DNS、重定向、子资源和私网访问已完整隔离。MCA 需按实际 bridge/网关威胁面补齐策略及测试。

## 5. 执行环境：借鉴 wrapper，拒绝假冒等价

| 源码 | 已有机制 | MCA 必须补上的验证/适配 |
| --- | --- | --- |
| [build.sh#L200](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/build.sh#L200) | `bash` wrapper处理参数后最终执行 `/system/bin/sh` | 这不是 GNU Bash。若暴露 `bash` 能力，需真实匹配所需语义；否则保留明确降级与缺口，不能隐藏官方脚本失败 |
| [build.sh#L226](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/build.sh#L226) | pnpm通过Node运行；package-import-method=copy减少FUSE/private路径硬链接问题 | 包安装、脚本钩子、执行入口、原生扩展、offline/cache、失败回滚逐项验证，不能仅 `--version` |
| [build.sh#L282](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/build.sh#L282) | Python wrapper、`python -m pip` | Python库加载与pip安装后实际import/执行；Android ABI native wheel具体判断 |
| [build.sh#L348](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/build.sh#L348) | npm/npx通过Node CLI；全局prefix在HOME；PATH加入全局bin | target36 可写目录脚本/bin wrapper 执行闭包；npm/pnpm/pip子进程必须实际成功 |
| [subprocess-local Android overlay#L915](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/dsh-patches/overlay-021/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/index.js#L915) | 尝试`/dev/ptmx`后扫描`/dev/pts/0..63`，失败改pipe/JS行处理；resize无操作 | 不得复制任意pts扫描：未证明slave属于master，缺unlock/ptsname/ioctl。实现成对真实PTY、controlling tty、winsize/session/process-group与resize；明确独立pipe fallback |
| [config/cordis.patch.yml#L8](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/config/cordis.patch.yml#L8) | sandbox服务注入保留，native隔离不可用时probe失败 | 保持workflow依赖可启动，不等于受限shell隔离已提供。用户选择受限模式时不得悄悄提升为full-access |

真正的终端对标必须区分 stdout/stderr、交互 stdin、`isatty`、信号、控制字符、resize、取消、会话/作业、进程树退出和资源释放；不能把“屏幕上出现终端文本”算真PTY。参考仓库 [README#L38](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/README.md#L38) 自身也保留终端/PTY限制说明。

## 6. 运维与数据：真正有参考价值的实现

| 能力 / 源码 | 可复用的机制 | 需要改进的边界 |
| --- | --- | --- |
| [KernelSelfCheck.java#L1](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/KernelSelfCheck.java#L1) | managed manifest、先size后hash、quick/full、用户数据/第三方包只报告 | manifest缺失明确SKIPPED/失败；准确锁闭包；只修受管路径，禁止全目录清空 |
| [MainActivity.java#L3431](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/MainActivity.java#L3431) | 清理旧版`@deepseek-ai/dsh`受管命名空间，避免混合树 | 必须与MCA payload manifest、用户插件分离、候选trial/commit配合；不原位更新运行树 |
| [session-admin.mjs#L91](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/tools/session-admin.mjs#L91) | session.v4.jsonl.zstd及旧格式、回收/恢复/清空、恢复路径包含性检查 | 使用维护独占准入、事务journal、同盘原子发布；跨分区copy/delete不是全事务证明 |
| [session-heal.mjs#L13](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/tools/session-heal.mjs#L13) | 默认dry-run；仅修坏attachment part；备份+temp+rename；保持无关zstd帧 | 真实历史fixture、多帧、损坏帧、写中断和修后校验；不可解码时不能“修复”为丢整个会话 |
| [UserPatches.java#L22](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/UserPatches.java#L22) | 安装后baseline、导出/重放用户改动、路径/大小/后缀约束 | JS/MJS/CJS仍是可执行代码。绑定from/to hash与版本，冲突保留并询问，不把扩展名表当信任沙箱 |
| [MainActivity.java#L10943](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/MainActivity.java#L10943) | safe mode stash用户profile/依赖，临时使用出厂profile；保留账号/session等数据 | 源实现多个move/delete不等于durable all-or-nothing；MCA需要journal、manifest与中断恢复，避免恢复时删掉唯一副本 |
| [MainActivity.java#L11024](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/MainActivity.java#L11024) | backup包含sessions/storages/attachments/settings/profile/用户patch | **当前源会把`.credentials.yaml`写入公共Download的普通ZIP**；MCA不可照搬。默认不导出凭据，用户选择敏感备份时加密与密钥/恢复语义明确 |
| [MainActivity.java#L11223](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/MainActivity.java#L11223) | backup导入逐entry处理 | staging、大小/条目/路径/链接校验、版本/hash校验、停写与预检、事务提交；原地逐entry导入不能算完整恢复事务 |
| [ConsoleTheme.java#L51](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/src/com/deepseek/harness/ConsoleTheme.java#L51) | 主题schema、卡片/动作、导入导出；救援/主题/自检等入口补回 | 仅限DSH专属console。shell/http/intent/prompt动作有执行能力，必须按能力审批，不能当纯皮肤信任 |

会话管理应优先调用当前官方管理能力；必须做底层格式维护时，使用固定格式adapter与维护排他锁，不能让控制台与运行中的官方session writer同时改文件。DSH凭据仍归官方模块，MCA控制台不解析任意配置抓取API Key供复制。

## 7. README、源码与目标差异登记

| 项 | 指定项目读取事实 | MCA 决策 / 对应卡 |
| --- | --- | --- |
| 版本 | README/v1.20.1标签；Manifest versionName仍`1.20.0`、versionCode57；历史说明还有0.2.0-rc.2残留 | D00分别记录源码commit、APK manifest、payload kernel，不从README猜产物身份 |
| Android版本 | [主Manifest#L8](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/AndroidManifest.xml#L8) min24/target28；compat min26/target28；MCA现target36 | D11不得降targetSdk。采用MCA已存在的受控nativeLibraryDir路径并补命令闭包 |
| 内核原样程度 | Android overlay会替换subprocess、附件、持久化原子操作和UI；不是完全原版未改 | D00/D02列每补丁的来源/目的/差异/退出条件；官方行为变化有明确fixture与验收 |
| PTY | native尝试和pipe fallback，resize no-op；不具备完整配对证明 | D12补真PTY；不能把参考app终端当等价性金标准 |
| 虚拟屏预览 | 当前源码ImageReader/JPEG；README/notice H264说法落后 | D18按实际功能验收；不把未来优化写成已实现 |
| 虚拟屏root-only | 插件接受root可用条件，但宿主core启动实际依赖Shizuku binder | D18单独验证root-only/仅Shizuku两条transport；不将README并列声明当真机证据 |
| 账号登录 | Java里的web token/cookie是本地认证，未发现独立Android账号协议；官方包/模型UI保留账号集成 | D03–D05跟官方账号源码做外部浏览器完整闭环，不把本地Web认证算登录完成 |
| 默认权限 | `defaultPreset: danger-full-access` + approval never；特权shell默认自动执行 | D02保留MCA受控策略，运行不可静默提升；对标能力无需复制该默认值 |
| 官方功能删减 | patch禁session-log-download与telemetry等 | session导出仍须在本次完整对标中有等效入口；遥测需独立明确策略，不能无记录删能力 |
| 浏览器隔离 | 单独进程/data suffix/DOM稳定ref；URL阻挡只覆盖部分literal地址 | D19复用状态/refs；完整来源/子资源/桥接隔离另补，不夸大为跨UID沙箱 |
| 二进制与可复现 | repo排除完整runtime/dshroot；build从预备目录取外部包 | D00/D11固化来源、版本、hash、ABI与许可证；README版本号不等于可复现闭包 |
| 前台运行 | EngineService存在，Node多处由Activity管理 | D01/D25保持MCA单runtime owner，验证通知与实际退出、进程死亡/重建 |
| 备份 | 普通ZIP含凭据，写公共Download；逐entry恢复 | D22补默认不导出凭据、可选加密、事务恢复与中断安全 |
| 规模 | `MainActivity.java`本revision **11,374行** | 借鉴功能，不拷贝巨型宿主；MCA维持职责预算、窄接口、页面只读投影 |

## 8. 分组件复用策略

| 分类 | 可以复用 | MCA处理方式 |
| --- | --- | --- |
| 官方第三方代码 | DSH kernel、官方Web UI、账号provider、工具/插件/配置/session/PTC体系 | 固定版本、保留原样层，Android patch独立且最小；升级做候选闭包校验 |
| 指定手机版MIT插件 | 五插件的工具定义、结果render、服务调用与部分参数处理 | 按第三方边界保留来源；schema/ID保持兼容；HTTP能力契约接MCA生产平台owner。调整认证/审批/取消时记录补丁 |
| 手机版平台行为 | DOM ref generation、WebView独立data目录、组件hash自检、精准session附件修复、回收站、console配置 | 复用可测试算法/fixture或移植机制；从巨型Activity提取职责，不将Activity本身引入 |
| 运维事务 | baseline、manifest、safe mode stash概念、backup范围清单 | 参考业务目标；由MCA各维护owner实现事务与恢复，不直接复制原地多步删除/覆盖 |
| 特权vscreen | 虚拟屏创建/定向input/launch、FakeContext/Workarounds | 先按实际衍生代码许可审查；独立模块/进程、可对应源码、校验加载、权限/进程scope明确 |
| 禁止直接照搬 | target28可写ELF执行、伪bash、任意pts扫描、默认全权限自动执行、明文凭据公共ZIP、无限复制巨型MainActivity | 都必须在对应D卡被替换为明确语义及测试；缺口仍列入未完成清单 |

第三方原样层可以保留其既有代码结构，但不得把这点变成MCA自有owner、页面、装配文件无限增长的理由。对第三方源的必要修改使用独立patch集，记录适用版本、上下游hash、行为目的、测试与冲突处理，不允许安装后悄悄覆盖而无证据。

## 9. 许可证与供应链边界

参照项目 [LICENSE](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/LICENSE) 为MIT；[THIRD_PARTY_NOTICES.md](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/THIRD_PARTY_NOTICES.md) 明确存在多许可证，不能用根MIT覆盖所有依赖。

| 组件 | 源声明 / 已知来源 | 方案要求 |
| --- | --- | --- |
| App壳、五自研插件、补丁/脚本 | 根MIT，notice摘要仍残留“四插件”的旧说明 | 固定实际文件清单，保留notice/作者；不按旧摘要漏掉browser |
| `android-app/src/com/deepseek/harness/vscreen/` | notice按保守口径整目录视为LGPL-3.0；FakeContext/Workarounds直接源自第三方 | 拷贝或衍生前确认对应源码/修改/编译重链接等具体分发义务；不得以自研wrapper是MIT推导整个vscreen是MIT |
| Shizuku SDK/rish | Apache-2.0 | 固定版本、原LICENSE/NOTICE与对应二进制来源/hash |
| 官方DSH与依赖 | 官方根MIT；逐npm/native/vendor条款分别适用 | 最终实际组合闭包SBOM、原许可及notice；存在未声明字段的包需单独查明 |
| Node/Python/OpenSSL/curl/git等runtime | 各组件及其依赖许可 | 固定来源/编译输入/patch/ABI/API/散列；不能以Node的MIT覆盖Git/GNU等独立组件 |
| GeckoView兼容变体 | 兼容构建另有GeckoView依赖及MPL等组件条款 | 只有采用时纳入最终闭包，不按WebView主变体泛称已适配/已完成分发审查 |

以上是工程来源清单，**没有完成分发法律合规结论**。执行阶段以实际APK、source/patch bundle和对应源码交付内容核对，不能复制上游“已经履行”一句代替MCA自己的证明。

## 10. 可直接交给执行Agent的使用规则

1. D00重新锁本仓库工作区与三份基线，导出43工具各自参数/action/输出/条件注册；不要重新猜工具数。以官方manifest与本表取并集，保留未满足的能力。
2. D02检查最终composed profile，不按npm包存在判断服务启动；先复用已有Android seeder修复，保证官方账号与MCA provider并存。
3. D03–D05从官方账号源码完成流程；禁止抓网页登录cookie、把本地cookie当账号、手写未定义refresh协议或让MCA保管账号密码。
4. D11/D12采用target36真实执行路径，跑安装后实际运行与真PTY验收；`--version`、fake、pipe输出不能代替。
5. D15–D19按43行工具和每action逐项接生产bridge；审批/取消/派发未知/权限撤销/真实效果都留证据。shell exit0不能证明UI动作成功。
6. D20–D24使用维护事务与恢复证据；禁止运行中原位替换整runtime、直接覆盖session或公开导出凭据。
7. 文档中的目标接口/文件并非现有生产实现；执行前查真实调用者、owner与写集，与前端/原生Agent工作交接，不能覆盖他人改动。

本轮检查结论：源码位置与固定引用已人工复核，43工具名称按唯一注册展开；没有执行功能验收。构建、真机、账号、MCA推理、插件安装、PTY、权限与升级/恢复均为本轮 `NOT_RUN`。最终状态与证据以 [验收文件](PARITY_ACCEPTANCE.md) 的候选产物记录为准。
