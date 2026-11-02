# DSH 补齐 V1：实施任务包与执行提示词

日期：2026-10-20。当前状态：方案，所有 C 任务均未在本轮执行。本次修订按 2026-10-20 配置生效层复核事实重构 C02c/C07/C08，并补 §1.3 总览；仍为方案，不新增 PASS。

入口：[总体方案](DSH_COMPLETION_PLAN_V1_20261020.md)；标准：[验收规范](DSH_COMPLETION_ACCEPTANCE_V1.md)；约束：[项目架构合同](../../architecture/MCA_PROJECT_HARD_LOCK.md)；C07/C08 背景与探针设计：[命令与 PTY 可行性决议备忘](DSH_COMMAND_AND_PTY_FEASIBILITY.md)。发生冲突时先核对当前源码与用户授权，不照抄旧路径实施。备忘 F1/F2 关于 home 级 overlay 的旧结论已被 2026-10-20 复核更正，本包以 C02c 的已核实事实为准，不把旧结论当事实。

## 1. 执行规则与文件路径约定

每次只领取一张任务卡或其明确子片。先记录实际基线与写集，再补能够暴露风险的测试，最小实现，整合者审 diff，最后运行本片所需验证。不能先做所有实现再统一寻找问题。

为缩短任务卡，以下前缀均指仓库真实路径：

| 缩写 | 根路径 |
| --- | --- |
| CONTRACT | `core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/` |
| RUNTIME | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/` |
| GATEWAY | `api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/` |
| ADAPTER | `app/src/main/java/com/muyuchat/mca/dsh/` |
| COMPOSE | `app/src/main/java/com/muyuchat/mca/composition/` |
| WEB | `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/` |
| INFERENCE | `core/inference-contract/src/main/java/com/muyuchat/core/engine/` |
| ENGINE | `core/engine/src/main/java/com/muyuchat/core/engine/` |
| PAYLOAD | `core/dsh-runtime/src/main/assets/dsh/payload.zip` 内部内容，不是可直接编辑的源码目录 |

测试放对应模块已有 test/androidTest source set，不把全部测试放 app。修改二进制归档前先获得可审查的源补丁与可复现 staging 路径，禁止手改 zip 后只更新 hash。

本包对未执行结论统一标注四档证据强度：**源码可确认**（MCA 仓库源码/路径可直接复核）、**归档可确认**（payload 归档文本/文件内容可直接复核）、**待设备复现**（由源码或归档推导、尚未在真实设备触发）、**未验证**（缺路径或证据）。任何 `PASS` 只能在独立 run 目录记录后登记；本文件不新增 PASS，也不把“已读取/已推导”写成“已运行”。

除 C00、C14 的文档/清单外，任务卡不授权改历史验证报告。所有新结果写入独立 run 目录，保留旧证据。表内拟新增文件是职责建议，接手者先查是否已有等价实现；不得照表生成空接口。

### 1.1 分工与并行

整合者独占：公共契约、INFERENCE/公共生成事件、COMPOSE、settings/Gradle/版本目录、payload lock 与 staging 入口的最终写入。工作者提出精确 patch，由整合者合入，不能抢改共享文件。

可并行的独立准备工作：

- C02c 可与用户前五项并行准备，但必须在 C07 之前完成：C02c 的 overlay 行合并、composed tree 验证与设备 bash echo 先行；C07/C08/C09/C11 的任何“命令/工具已可用”证据都必须等 C02c 完成，否则测到的是未合并平台 patch 的旧执行器或 fail-closed 的 `bash-sandbox`。不得改变用户固定的前五项优先顺序。
- C01 停止结果修复；C02a/b 原语源补丁；C04 模型诊断 fixture 可并行。
- C03 官方扩展可行性研究；C07 入口映射与包装脚本 exec 负例可在 C02c 后分片推进；C08 的 P1–P5 PTY 设备探针须等 C07 的 Node/命令入口可用后，在 `:dsh` Node 内执行。
- C10 目录投影、C11 平台 port（依赖 C03/C02c）、C12 journal 算法，在共享契约冻结后可分写集推进。

C02c 的 seeder overlay 行合并与测试、payload 平台 patch 行取舍由配置/打包工作者持有；overlay 语法、合并顺序和启动调用点必须由整合者审查。不能把模板的 `danger-full-access`/`approval: never` 作为“先跑通”的捷径。

所有工作者必须知道不是独自在仓库工作，保留他人改动。不能在同一 build 目录并行构建/测试。UI 优化可继续在无交集写集推进；DSH 导航、WebView 与共享 component 的修改须指定唯一负责人。

### 1.2 依赖图

```text
C00 基线/账本/功能映射（含平台 patch 行未进入生成 overlay 的差异）
 ├─ C01a/b 停止事实 ── C03a/b 身份 ── C01c/d 结算 ── C03c-f 重绑
 │                                                  └─ C10 动态目录
 ├─ C02a 真实锁 / C02b 原子发布
 ├─ C02c 配置生效层合并与沙箱声明校准
 └─ C04 分层诊断 ─ C05 输入/工具策略 ─ C06 真实终态
                   │                   └─ C13 native/视觉等扩展
C02c + C02a/b ─ C07 命令 ─ C08 PTY ─┐
C02c + C07 ────────────────────────── C09 官方插件事务
C03 + C02c ────────────────────────── C11 Android桥
C01 + C02 + C03 + 相关插件兼容证据 ─ C12 升级事务
上述任务各片证据 ─ C14 一对一对标矩阵 ─ C15 整合发布门禁
```

C03 的只读 probe 与身份契约可提前；推荐顺序为 C01a/b→C03a/b→C01c/d→C03c-f，避免跨进程回执需要身份却形成整卡循环依赖。C02c 是 C07/C08/C09/C11 命令与工具证据的前置：已核实 home 级 `$DSH_HOME/cordis.patch.yml` 会被官方启动读取（归档可确认），缺的是 payload 平台 patch 行没有合并进 MCA 生成的 overlay；在该差异关闭前，`ctx.shell` 仍由 `bash-sandbox` 提供且无 Landlock runner 时 fail-closed（源码推导，待设备复现），命令/PTY/插件结果不成立。C07 依赖 C02c + C02a/b；C08 依赖 C07；C11 依赖 C03 + C02c。C09 可先验纯 JS（其命令/工具证据仍以 C02c 为前提），PTY 插件必须等 C08。C14 从 C00 起持续维护，最终结论必须等待所有关联证据。不能为绕过前置把任务状态提前写完成。

### 1.3 16 卡总览

下表与各卡正文一致；“验收 V”只引用验收规范中的 V01–V50，不新增编号。依赖是开工前置，不是“完成后才可读”。所有卡均未在本轮执行，状态均为未开始；表中不登记任何 PASS。表中依赖/写集是计划，不是已验证事实；具体证据强度以各卡正文标注为准（源码可确认/归档可确认/待设备复现/未验证）。

| 卡号 | Owner | 依赖 | 主要写集 | 验收 V | 失败处理要点 | 回退方式 |
| --- | --- | --- | --- | --- | --- | --- |
| C00 | 整合者（只读分析） | 无 | `completion-v1/` 的 BASELINE、PATCH_LEDGER、PARITY_MATRIX 与证据格式 | V01、V02 | 缺源或无法定位生产调用点写具体 BLOCKED，不猜上游版本；静态数量不当功能数 | 删除本卡新文档，不影响生产 |
| C01 | 生命周期工作者 | C00 | service/通知/停止策略及测试；owner 只修停止事实边界；结算映射由整合者 | V03、V04、V05 | 超时不当成功；UNKNOWN 保留 fence 与通知，不 `stopSelf` | 回退实现 patch；不恢复“失败等于已停止” |
| C02 | native/工具链工作者；C02c 由配置/打包工作者执行 | C00；C02c 先行支撑工具证据 | C02a 锁原语、C02b 原子发布、C02c seeder overlay 行合并与模板行取舍、composed tree 与测试 | V06、V07、V08 | addon/原语失败显式报错；C02c 合并失败时 C07/C08/C09/C11 工具证据保持未开始，不继承 `danger-full-access` | 平台补丁/payload 作为匹配闭包回退；保留 home 与上一有效 overlay |
| C03 | 整合者持契约、生命周期工作者持实现 | C00；C03c-f 依赖 C01 完整停止边界 | 维护契约、maintenance 小组件、官方扩展源补丁、IPC 增量字段与测试 | V09–V12 | quiescence 不足则明确自动维护不可用，仍走手工停止；不按计时器猜空闲 | 保留身份校验与停止 fence；可暂不热更但明确待重连 |
| C04 | 模型协议工作者 | C00 | 现有测试与合成 fixture、L0–L4 分层诊断脚本/说明 | V13 | 区分已复现/源码风险/未测；不为一卡全绿删断言 | 诊断工具不影响生产；不增加生产明文泄露 |
| C05 | 协议工作者；公共契约/请求策略整合者 | C04 | ADAPTER mapper/prompted protocol、GATEWAY parser、CONTRACT 增量字段；必要 INFERENCE/ENGINE 由整合者 | V14–V17 | 容量不足返回明确错误；不支持选项明确拒绝，不降级 | 版本化协议策略与契约一起回退；不默默裁剪历史 |
| C06 | 整合者持公共事件、协议工作者持映射 | C05 | INFERENCE typed completion 元数据、ENGINE/云 producer、ADAPTER 终态映射与 GATEWAY wire | V18–V20 | 无法证明 reason 记 unknown/失败，不伪造 STOP；终态只提交一次 | 字段向后兼容回退；新路径缺理由明确报错 |
| C07 | 工具链工作者；构建/归档接线整合者 | C02c + C02a/b | RUNTIME 窄接线、受管窄 launcher/native 源、工具补丁、`scripts/dsh` staging、官方 packageManager 配置 | V21–V24（V23 的 GNU Bash 项 V1 不承诺） | 包装脚本 EACCES 不 chmod/绕过；launcher 缺失显式报错；`--version` 不当真实执行 | launcher/依赖/payload 同闭包回退；保留 home；mksh 不冒充 bash |
| C08 | native/终端工作者 | C07 | 官方 subprocess seam 源补丁、模式诚实上报、可选 Termux JNI helper、P1–P5 探针 | V25、V26 | pts/^C 风险未排除只报 pipe 或 NOT_RUN；native 不可用显式错误；管道不计 PTY | 回退 seam/helper 集成 patch，保留明确能力状态；不强制全走 PTY |
| C09 | 插件兼容工作者 | C02（含 C02c）、C07；PTY 样本依赖 C08 | 官方扩展 seam 的配置/最小适配、兼容 fixtures、矩阵与脚本 | V27–V30 | 安装成功但工具未执行不算通过；取消看真实树退出 | 由官方 manager 按其支持范围回滚；不 Kotlin 重装覆盖 |
| C10 | 模型目录工作者 | C03；choice/capability 依赖 C05/C06 | ADAPTER `DshModelCatalog.kt` 与窄订阅、官方目录刷新扩展与测试 | V31 | 刷新失败冻结旧目录并显示失败；不杀 busy Node | 冻结旧 applied 目录；拒绝真实不存在的模型 |
| C11 | 平台工作者持 port、整合者统筹端点/契约 | C03 + C02c + C02 保存原语 | capability api server package、app capability owner/ActivityResult、官方 `mca-android-capabilities` 扩展与测试 | V32–V35 | 未知就是 OUTCOME_UNKNOWN；满额拒绝准入；后台返回 USER_PRESENCE_REQUIRED | 撤 capability 注册/凭据，保留用户文件；桥不宣称强沙箱 |
| C12 | 生命周期/数据工作者；owner/打包整合者 | C01、C02、C03 及所需插件兼容事实 | payload 事务/journal 小组件、owner 窄维护调用、seeder 必要兼容补充、版本化 manifest | V36–V40 | 不兼容显示 RECOVERY_REQUIRED；不自动重放未知 turn | 数据与闭包兼容证明成立才执行；保留数据与诊断 |
| C13 | 按后端分别委派，契约归整合者 | C05/C06 和受影响平台能力 | 各子片后端实现/测试与 manifest 闭环 | V41–V44 | 未完成子片进矩阵；不把“文字工具阶段”当完整对标 | 能力声明与路径一致回退；不靠关开关隐藏回归 |
| C14 | 独立审查者 | C00 起持续，结论依赖全部相关卡 | PARITY_MATRIX、PLUGIN_COMPATIBILITY、EVIDENCE_INDEX | V45 | 无主行、失效链接、静态 PASS 一律标注；PARTIAL 展开子项 | 矩阵是报告，不反向改产品 |
| C15 | 整合者 + 独立验收者 | 全部声明完成范围 | 合并工作树、串行模块测试/正式构建、候选身份与证据 | V46–V50 | 任一层未验证就分层标注；云 503 BLOCKED 不冒充 PASS | 用 C12 验证过的闭包/数据路径；交付未完成清单 |

## 2. C00：基线、补丁账本与功能追踪

**Owner：整合者。类型：只读分析与文档/清单。依赖：无。**

输入：当前工作树、子模块内部 diff、payload lock、CAPABILITY_MANIFEST、最新 APK 验证记录。

允许写集：本方案目录下新建 `completion-v1/BASELINE.md`、`PATCH_LEDGER.json`、`PARITY_MATRIX.json` 与证据格式说明；这些是后续产物，本轮未创建。不改生产代码、不覆盖旧 manifest。

执行：

1. 记录 branch/HEAD、tracked 与 untracked 生产输入、各子模块 revision/diff 指纹、payload/关键 ELF/SHA、有效 SDK/Node/DSH/profile。
2. 对照固定官方源、参考 APK payload、MCA staging，逐文件建立补丁来源与真实生产调用点。
3. 明确 no-op flock、stat→rename、PTY/resize、sandbox 声明、shell 替代、sharp 等已知补丁，不漏“行为没改但依赖被替换”的情况。
4. 沿用旧 manifest 的 row identity 增加运行证据列。静态扫描数量不解释为功能总数。
5. 为每项功能确定最小 probe、依赖与目标状态，记录默认/可选/平台不适用的官方依据。
6. 补丁账本必须单独记录“平台 patch 行未进入 MCA 生成 overlay”的差异，并逐行给出“进入/未进入 MCA 生成 overlay”的结论：payload 模板 `dshhome/cordis.patch.yml` 的哪些行（`bash-sandbox`/`pwsh-sandbox`/`session-log-download`/`session-telemetry-otel` 禁用，`bash-local` 插入，`permission` 预设，`tool-shizuku/tool-android/tool-accessibility/tool-vscreen/tool-browser` 插入）没有进入 `DshGatewayConfigSeeder.buildOverlay` 生成文件；差异在哪个生产调用点被发现（`overlayTarget = File(home, OVERLAY_NAME)` 写入 → 官方 `readProfilePatches()` 读取 home 层 `$DSH_HOME/cordis.patch.yml`）；以及哪些工具行已决定由 MCA capability 桥替代（归 C11）。不能只锁 payload zip SHA 就宣称平台 patch 等同官方。（seeder 源码可确认，官方读取点与模板行归档可确认）

失败处理：资料缺失、无法定位生产调用点或上游版本不可核对时写具体 BLOCKED，不猜上游版本；静态扫描数量不当功能总数；不把未验证行写成 PASS。

验收：V01、V02；每行都有来源和验证路径，未验证仍为 NOT_RUN；05:12 证据范围准确。资料缺失写具体 BLOCKED，不能猜上游版本。

回退：只删除本卡新文档即可，不影响生产。输出第一批可实施写集与共享契约变更建议。

## 3. C01：停止结果、service 与通知一致性

**Owner：生命周期工作者。依赖：C00。**

读取：`RUNTIME/DshRuntimeOwner.kt`、`process/DshProcessTree.kt`、`service/DshRuntimeService.kt`、`DshStopSnapshotPolicy.kt`、`DshRuntimeServiceClient.kt`、`DshRuntimeNotifications.kt`、`COMPOSE/DshModeAssembly.kt`。

允许写集：service/通知/停止策略及其已有测试；owner 只修停止事实边界。C01c 所需 ADAPTER call 结算映射、平台无关回执 DTO/窄 IPC 与 composition 修改由整合者持有，先冻结 C03b 身份字段再集成。禁止本卡扩展模型生成协议或改 WebView 布局。

分片：

- C01a：先用 owner.stop 返回 FAILED/STOP_TIMEOUT、scan UNKNOWN、client 断连等 fixture，暴露 service 仍 stopSelf 和通知误报。
- C01b：仅 STOPPED 且实际树退出证明成立时撤通知/停 service；超时保留 fence、通知、重核入口。区分进程退出、扫描失败和推理取消待结算。
- C01c：修 composition 的 FAILED→一律清理/重开假设，停止开始关闭该代新派发，活动调用按真实 lease 结算。旧代回调不能撤新代网关。
- C01c 的结算来源：app 既有执行 owner 基于 EngineStopSettlement 的 Released/NotDispatched 事实产出只读回执，绑定 HostIdentity/call/requestId/requestEpoch/load incarnation，转换为不含engine类型的DTO后经版本化IPC汇总到stop fence。复用现有真实结算能力，不把engine依赖注入runtime。cancel hook、Flow结束、registry.end、Binder/main死亡或记录淘汰不证明释放，缺证据继续UNKNOWN。Node已退出与组合stop未结算分别可观测，不能因此撤通知/解fence。
- C01d：真实子孙进程、拒绝优雅退出、孤儿及重复 stop 故障验证。

UNKNOWN 恢复：向原执行 owner 查询/重核同一身份；原资源进程已死亡或重建时，由资源 owner 核对原进程启动身份、load incarnation 与实际 lease/资源后出生命周期结算，不能单凭 main PID 变化释放。未结算记录有界保留且影响新准入，禁止先淘汰再解fence；无法证明时明确恢复条件，不无限自动重试。

错误处理：超时不当成功；停止服务本身不是停止证据；不扩大 kill 范围至任意同 UID 进程。仍未知则明确未确认，不能解除 fence。

验收：V03、V04、V05；对应 `DshRuntimeOwnerTest`、`DshStopSnapshotPolicyTest` 等行为测试有执行数量；device 注入必须看到真实退出和通知状态。

回退：可回退实现 patch；不得为恢复旧 UI 文案重新引入“失败等于已停止”。未解决时保留阻断新代的安全状态。

## 4. C02：平台语义修复

**Owner：native/工具链工作者（C02a/b）；C02c 由配置/打包工作者执行；归档集成与 overlay/调用点审查由整合者。依赖：C00。**

本卡必须分三次审查和交付，不能一个巨型补丁同时改锁、终端、插件和升级。

### C02a 真实文件锁

写集：受管源码补丁目录、必要 Android/Bionic addon 源与测试、staging 的最小接线建议。新增 native 路径在 C00 明确后固定，不预设无生产调用者的新模块。

先评估官方 node-addon-system 构建；不可行则仅实现当前调用到的接口。对当前 Node 的 fd 做真实 flock；绑定失败为具体错误；保留 errno、关闭与异常释放语义。

验收 V06：两进程竞争、持有者崩溃、再取锁、真实 DSH session/profile 入口。源码、Node/N-API、ABI、动态库、API level、16KB page 与许可齐全。

失败分支：不能回到 noopBinding；具体加载/依赖错误持续显示，不把 addon 文件存在当可用。

### C02b 原子不覆盖发布

写集：官方 fs seam 的最小源补丁、必要原语与 probe；保持既有 API/错误合同。

真实 no-replace、已有目标、symlink、跨文件系统、hardlink 不支持、磁盘满、异常清理分别测。若平台原语不支持，仅使用经过等价证明的替代；先 stat 后 rename 被明确禁止。

验收 V07：多进程竞争只有一个赢家、败者不覆盖，目标内容/元数据符合官方语义，中断后不存在伪成功。

### C02c 配置生效层合并与沙箱声明校准（对应方案 G14）

**Owner：配置/打包工作者；overlay 语法、合并顺序与启动调用点由整合者审查。依赖：C00；必须先行于 C07/C08/C09/C11 的命令与工具证据。**

**证据基线（2026-10-20 复核，不沿用备忘 F1/F2 旧结论；`payload/` 指解包归档，见可行性备忘）**：

- 官方启动会读取 home 级 `$DSH_HOME/cordis.patch.yml`，且 home 层 outranks profile 层：`payload/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/profile-boot-C14i-8IN.js` 的 `homePatchPath()` 返回 home 根 patch 路径；`dsh-app-boot/lib/index.js` 的 `readProfilePatches()` 依次合并 bundle patches、profile patch、`loadOptionalPatches(join(context.home,"cordis.patch.yml"))` 与启动 overlays；profile-boot 在 boot 前调用它。（归档可确认）
- 05:12 真机的 provider “MCA” 与模型列表只可能来自 MCA 生成的 overlay，反证 overlay 已生效。（既有验证记录可确认：`artifacts/dsh-verify/20261020-051217/dumps/f-picker-submenu.nodes.txt` 的 provider 行 text=MCA；不新增 PASS）
- 缺的是平台 patch 行合并：`RUNTIME/DshGatewayConfigSeeder.kt` 只复制 `cordis.yml`、`package.json`、`pnpm-workspace.yaml` 与 `settings.yaml`；home 根 `cordis.patch.yml` 由 `buildOverlay` 生成，仅 `permission`（`defaultPreset: workspace-write`、`approval: ask`）、`workspace-controller`、`llm-pi-ai` 三行；没有复制或合并 payload 模板 `payload/dshhome/cordis.patch.yml` 的平台 patch 行。（源码可确认）
- payload 模板行：禁用 `bash-sandbox`、`pwsh-sandbox`、`session-log-download`、`session-telemetry-otel`；插入 `bash-local`（cwd 用 `DSH_WORKSPACE`）与 `tool-shizuku/tool-android/tool-accessibility/tool-vscreen/tool-browser`；`permission.defaultPreset: danger-full-access`。（归档可确认）
- `dsh-base/cordis.patch.yml`：`bash-sandbox` 只在 win32 禁用（Android 启用）；`sandbox-policy.mode = DSH_PERMISSION_MODE ?? workspace-write`，MCA 不设 `DSH_PERMISSION_MODE`；`dsh-bash-sandbox` 在非 `danger-full-access` 时 `confine()`，Landlock runner 来自 `@deepseek-ai/node-addon-system-<platform>-<arch>`；payload 无 android 平台包、无任何 `.node` → runner 不可用 → fail-closed。`danger-full-access` 会绕过 `confine()`，但 MCA 明确拒绝继承模板该默认。（归档可确认 + 源码推导；设备行为待设备复现）

**写集**：`RUNTIME/config/DshGatewayConfigSeeder.kt` 的 overlay 行合并与 `core/dsh-runtime/src/test/.../DshGatewayConfigSeederTest.kt`；payload 模板平台 patch 行的取舍清单（以代码常量选择，不整文件复制）；composed tree 验证说明/测试；设备 bash echo 与 permission 决策证据。不新增平行 home 配置库，不复制模板危险默认；overlay 语法、合并顺序和启动调用点由整合者审查。

**执行**：

1. 行合并：`buildOverlay` 增加经审查的固定行（disabled/insert 顺序与官方 patch 语义一致），至少包含：禁用 `bash-sandbox`、`pwsh-sandbox`、`session-log-download`、`session-telemetry-otel`；插入 `bash-local`（cwd 绑定 `DSH_WORKSPACE` 或等价表达式）；必须保留 `sandbox`（`@deepseek-ai/dsh-sandbox-local`）启用——模板注释明确说明禁用会让 ptc-runtime 永久 pending、所有 preset 会话 create/resume 失败。`tool-shizuku/tool-android/tool-accessibility/tool-vscreen/tool-browser` 不继承，改由 C11 的 MCA capability 桥替代，必须在账本显式记录“模板行未继承 + 替代路径”；不得因未继承而删除 C11 目标。修复必须落在 `buildOverlay()`/seeder：home overlay 每次 host generation seed 都会重写，设备手改 `home/cordis.patch.yml` 不持久、不能作为证据；现有 `DshGatewayConfigSeederTest` 断言 overlay 不得出现 `approval: never` 与 `tool-android`，若本卡改变该策略必须显式更新测试并记录理由。
2. permission preset 诚实决策：MCA 默认保持 `defaultPreset: workspace-write` + `approval: ask`，不继承模板 `danger-full-access`/`approval: never`。同时必须承认：`bash-local` 的 `sandboxMode` 是硬编码声明（`dsh-bash-local/lib/index.js` 归档可确认），没有 enforcement。V08 把“声明/真实边界”分开记录：能证明的只有 fail-closed 的 `bash-sandbox` 已被替换；`bash-local` 的 workspace-write 不是内核隔离。需要更强隔离时单独立 ADR，不在本卡把该声明当沙箱通过。
3. composed tree 验证：设备启动后读取官方合成的 patch 列表/引擎状态，断言 `ctx.shell` 由 `bash-local` 提供、`bash-sandbox` 不再作为执行器、permission/defaultPreset 与插入行实际生效；证据绑定 home 文件 hash 与启动代次。若只能靠日志，保留原始日志与解析脚本。（待设备复现）
4. 设备 bash echo：在 `:dsh` 进程内通过 bash 工具执行 `echo ok`（不只在 adb shell），确认不返回 `SANDBOX_UNAVAILABLE`；记录实际 shell 方言（当前 `bin/bash → /system/bin/sh`，mksh）与 cwd。（待设备复现）
5. 失败分支：overlay 合并后语法/加载失败时 seeder 返回 typed refusal 并保留上一有效 home；C07/C08/C09/C11 的工具证据保持未开始；不得改用 `danger-full-access` 绕过，不得先写“命令可用”再补配置。
6. 回退：seeder/模板行补丁与 payload 作为匹配闭包一起回退；保留 home、上一 overlay 与用户数据，不清 home；配置层回退失败则停止受影响工具能力并保留具体错误。

**验收 V08**：两步且顺序不可颠倒——(1) composed tree 证明 home 级 overlay 被官方读取、payload 平台 patch 已合并，导出实际 `ctx.shell` provider、permission preset 与 overlay diff；(2) 再对报告的每个 sandbox mode 跑受控 fixtures 的工作区内外写入、symlink/路径穿越、子进程继承探针。第一步未过不得进入第二步；若 `bash-local` 替代 `bash-sandbox`，必须显式声明“无 OS enforcement”，不把 workspace-write 声明当已隔离；保留 `bash-sandbox` 缺 runner 的 fail-closed 负例。composed tree 与 `echo ok` 证据同目录归档；失败仍记 FAIL/NOT_RUN。禁止对真实 MCA 用户目录做破坏性试验，使用隔离合成数据。强隔离如果需要新的权限/进程架构，提交独立 ADR 与可运行原型范围，不在本卡把 proot 或 token 说成已经完成沙箱。

## 5. C03：宿主身份、命令幂等与网关重绑

**Owner：整合者持契约，生命周期工作者持实现。依赖：C00；C03a/b 可先行，C03c-f 整合依赖 C01 完整停止边界。**

读取：CONTRACT RuntimeContract、IPC/client/service、owner、seeder、DshGatewayAssembly、DshModeAssembly；归档内 pi-ai 的 immutable snapshot、volatile-update、credentials 与 profile reload 代码。

允许写集：独立 maintenance 契约、runtime maintenance 小组件、官方扩展源补丁、IPC 增量字段与测试。不能把 owner 扩成模型/插件业务总管。

分片：

1. C03a probe：证明官方设置更新入口、原子应用、applied revision、仅内存凭据 provider；输出源位置、调用序列和测试 fixture。不能只凭 hook 名字通过。
2. C03b 协商：HostIdentity/GatewayIdentity、protocolVersion、commandId、expected identity。旧 IPC 编号/外部 callId 保留；旧端不支持明确返回 Unsupported。
3. C03c 观测：区分 desired/applied；把 endpointHolder 写入与 Node 确认彻底分开。只先接检测与提示，不盲目重启。
4. C03d 热更：准备→验证→原子发布→Node ACK→在途结算→撤旧；失败仍有可查询结果。新配置不能污染旧 operation 的快照。
5. C03e fallback：只有官方各 owner 的准入屏障与完整活动证明都可验证时才自动受控重启；否则明确停止后更新。不得实现计时器猜空闲。
6. C03f 主进程独立死亡：保留 :dsh/Node，重建 main/gateway，验证新身份、旧调用未知语义、下一调用使用新绑定，且没有重放用户 turn。

命令语义：IN_PROGRESS/原终态/CONFLICT/STALE_GENERATION/OUTCOME_UNKNOWN；Binder 传输 requestId 不能替代业务命令 ID。旧身份确认或迟到停止不能影响新代。

验收 V09–V12。若 quiescence 接点不足，C03d 可通过热更分支，C03e 记录自动维护不可用；需要自动重启的完整目标仍未完成，不能把手工停止路径当全自动恢复通过。

回退：保留身份校验与停止 fence；可暂不提供热更但明确待重连。临时令牌不落盘，不能回退为偷偷复用旧认证。

## 6. C04：模型分层诊断与最终请求 fixture

**Owner：模型协议工作者。依赖：C00。先不改生产模型行为。**

读取：ADAPTER mapper/backend/protocol、GATEWAY request/wire、INFERENCE `ChatRequestTypes.kt`、ENGINE admission/stream/prefill、实际模型模板与有效参数。

写集：现有对应测试与合成 fixture、分层诊断脚本/说明；真实实验只在后续得到实现与设备操作授权时运行。

输出：L0–L4 的输入/输出结构、相同模型设置与最终渲染对比；复现 reasoning 指令附加和历史二次裁剪的最小用例；`DSH-OK` 离题各候选原因的可证伪条件。

验收 V13：诊断报告必须区分已复现、源码风险、未测；故障 fixture 可以作为预期失败复现通过，但被暴露的生产缺陷仍未解决。不能为了让本卡全绿删掉断言或归咎模型。

回退：诊断工具不影响生产。对最终 prompt 的明文采集只允许合成 fixture，诊断关闭后不增加生产泄露。

## 7. C05：DSH 输入完整性与工具策略

**Owner：协议工作者；公共契约/请求策略由整合者。依赖：C04。**

写集：ADAPTER mapper/prompted protocol、GATEWAY request parser、CONTRACT model request 的增量字段；必要 INFERENCE 窄输入策略与 ENGINE admission 分支由整合者统一改。

分片：

- C05a 输入：显式 typed request policy，默认保留旧聊天/Local API 行为。DSH 最终 prompt 的每项转换可解释，完整 transcript 不能静默裁剪；不足返回明确上下文错误，不绕过资源准入。该错误的 HTTP/shape 必须由 pinned pi-ai/DSH 实际识别为上下文溢出，并触发官方压缩路径；自定义错误码变成普通 PI_AI_ERROR 不能验收通过。native tools/history 同样纳入预算。
- C05b choice：建议保留已有 enum，并以默认可空字段增量携带 named name/parallel policy，或提供经兼容审查的等价设计。保留缺省旧请求行为；重复 schema、未知 name、无 tools 的 required 均明确校验。
- C05c transport：每个 provider 实际请求贯穿 choice。Responses 的 hardcoded auto 必须修；不支持的传输拒绝具体选项，不能降级。
- C05d prompted：builder 返回完整文本与实际 offered names，协议说明不截断；默认不静默删工具。parser 与实际 offered names 一致，required/named 无调用或 malformed 为语义失败，parallel=false 多调用不能成功。
- C05e history：工具调用/结果配对、多轮顺序、system/developer 的兼容边界明确；不能把 native 工具结果变 USER 文本后仍标 native fidelity。

验收 V14–V17；先断言最终 serialization，再测实际调用。回归普通聊天、云端、Local API 的既有 prompt/default/context 行为。业务重试只归官方 DSH。

回退：版本化协议策略与契约一起回退；保留日志可定位，禁止默默恢复损坏历史/强制工具失效的行为。必要时对具体不支持选项返回错误。

## 8. C06：真实终态、工具流身份与 usage

**Owner：整合者持公共事件，协议工作者持 DSH 映射。依赖：C05。**

读取真实 GenerateEvent.Done 定义与所有受影响 producer，不能仅看 gateway enum。

写集：INFERENCE typed completion 元数据、受影响 ENGINE/云 producer、ADAPTER 终态映射、GATEWAY assembler/wire 与相应行为测试。禁止为了 DSH 机械重写全仓事件消费者。

执行：

1. 新增元数据默认兼容旧调用者；区分 producer 的 EOS、limit、cancel、error、EOF。无法证明的 reason 先为内部 unknown/明确失败，不能发明 OpenAI wire finish_reason。
2. 保留模型事件到 adapter 到 SSE/buffered 的一条终态路径。只提交一次终态；取消后清 held envelope，不执行半个工具调用。
3. 校验 index/name/ID 稳定性、跨工具 ID 唯一、参数完整性、大小上限与增量切片。
4. 保留 usage 的实际/估计/未知/零，避免大于零过滤吞零值。
5. 用实际官方 pi-ai 处理响应，不只测自写客户端。

验收 V18、V19、V20；producer 测试必须能实际触发输出上限与取消，不能按数值猜。没有触达的后端记 NOT_RUN，并留在完整对标差距中。

回退：字段向后兼容，消费者不依赖新字段时保持原行为；新 DSH 路径对缺失理由明确报错，不能伪造 STOP。

## 9. C07：合法命令入口与依赖闭包

**Owner：工具链工作者；构建/归档接线由整合者。依赖：C02c（配置生效层合并）+ C02a/b 已完成受影响原语。**

写集：RUNTIME `service/DshAndroidRuntimeAssembly.kt` 的窄接线与 `TOOL_LINKS` 校正；受管窄 launcher 的 native 源与构建/打包接线（`scripts/dsh` staging 由整合者）；工具补丁与设备探针说明；官方 profile `packageManager.command + args` 探针（配置由整合者与 C02c 的 overlay/模板写集合一）。禁止引入另一插件安装器，禁止用 chmod、改 targetSdk 或字符串拼 shell 绕过执行策略。

**入口事实（2026-10-20 复核；归档可确认 + 源码可确认）**：PATH=`<payload>/bin:<payload>/runtime/bin:/system/bin:/system/xbin`，SHELL=`/system/bin/sh`，`LD_LIBRARY_PATH=<payload>/runtime/lib`，`PYTHONHOME=<payload>/python`，`GIT_EXEC_PATH=<payload>/git/libexec/git-core`。`DshAndroidRuntimeAssembly.prepareRuntimeLinks()` 把 `node/rg/curl/git/git-remote-*` 与 `bin/python*` 链接到 `nativeLibraryDir/libdsh*.so`，把 `bin/bash` 链接到 `/system/bin/sh`；归档中 `npm/npx/pnpm/pip` 仍是 `<payload>/bin` 下的可写目录包装脚本，targetSdk 36 下 execve EACCES（待设备复现）；`bash` 实为 mksh（归档可确认）。

分片：

- **C07a 入口映射与 exec 负例基线**：输出每个名字（node、git、curl、rg、bash、npm、npx、pnpm、pip、python、python3、sh）的 PATH 命中、payload 实体、运行时链接/替换、当前可用性与证据强度。负例基线必须包含：`node/git/curl/rg` 为 ELF（nativeLibraryDir 链接）；`npm/npx/pnpm/pip` 在可写目录，直接 exec 预期 EACCES；`python*` 归档为包装脚本但现行源码已重链到 `libdshpython.so`，C07a 必须用设备正负例确认解释器可用性（既不能按名称断言 EACCES，也不能按链接存在断言可用）；`bash` 命中 `/system/bin/sh` 符号链接（mksh，不是 GNU Bash）。失败输出必须含 `EACCES` 与命中路径，不把“PATH 能找到”当可执行。
- **C07b 官方 packageManager command+args 探针**：按官方 `profile.packageManager.command + args` 接点配置固定解释器调用——`command = <Node ELF>`、`args = [<payload>/pnpm/bin/pnpm.cjs, --config.package-import-method=copy, ...]`（真实路径以归档为准），跑一次真实 inspect/依赖查询；证明官方 manager 用的是实际配置，不是单字符串 `pnpmCommand` 或 PATH 猜测。验收官方 call trace + 子进程 argv + 真实 inspect 输出；失败不降级为直接 exec 可写脚本。
- **C07c 受管窄 launcher**：NDK 多调用 native 垫片（arm64、16 KB、Bionic），打包进 nativeLibraryDir；在 `<payload>/bin` 建 `npm/npx/pnpm/pip` 四类固定名的 shim 符号链接（`python*` 不纳入 shim），原 wrapper 脚本保留到受管固定目录（与 `bin` 同级且保持原相对布局，确保脚本内 `../npm`、`../python` 等 prefix 解析不变；垫片映射路径不能复用已被替换的 PATH 入口）。垫片只按 `argv[0]` basename 映射到代码常量固定的脚本路径，`execve("/system/bin/sh", ["sh", "<固定 wrapper 路径>", ...argv])`，保留原包装脚本的 prefix/env 行为（`NPM_CONFIG_PREFIX`、`--config.package-import-method=copy`、`PYTHONHOME`、`LD_LIBRARY_PATH`），不接受任意环境变量替换执行目标、不接受任意脚本路径。退出条件：`npm/npx/pnpm/pip` 经 shim 通过，`python*` 由现有 `libdshpython.so` 重链单独验证，`node/git/curl/rg` 直链通过；且 `.bin`/shebang/postinstall 单列验收（见 C07d）。native 构件记录来源/ABI/API level/16 KB/SHA/许可。
- **C07d package bins/shebang/postinstall**：覆盖 `.bin` 入口、`#!/usr/bin/env node`（npm-cli.js 的 Termux 残留 shebang 只在经 node 加载时不生效，需显式记录）、shell script、`postinstall`/脚本审批、子工具、prefix 与真实权限；区分“需解释器读取的 JS/Python 入口”与“需已打包 ELF 的原生入口”。不得全局禁安装脚本后把插件记为完整兼容；脚本审批归官方 manager。
- **C07e git/curl/Python 闭包与 mksh 方言披露**：受控 git 仓库/HTTPS remote/remote helper、curl 证书与 `OPENSSL_CONF`、Python stdlib/site-packages/`-m pip`、中文路径、超时与取消；逐项记录错误不被吞。当前 bash 指向 `/system/bin/sh`（mksh）：Heredoc/数组/pipefail 等 GNU 语义不得默认承诺；V1 只披露真实 dialect，GNU Bash 构件单独立项，不并入本卡，不以 `bash` 文件名掩盖 sh。
- **C07f native addon 正误路径**：正确 Bionic/N-API 构件做实际函数调用；错误 ABI/缺库/不可执行 ELF 给具体错误，不 no-op；glibc arm64 构件不视为 Android 兼容。

**验收**：V21–V24。V21 验 argv/cwd/env/stdio/exit/signal 与可写目录脚本直接 execve 的 EACCES 负例；V22 先验 EACCES 负例、再验受管窄 launcher 正例与官方 manager 实际配置下的 npm/npx/pip + package bin/shebang 链；V23 的 git/curl/Python 项按本卡验，GNU Bash 项 V1 不承诺（默认 mksh 必须披露），保持未满足并转入独立立项，不能以 mksh 通过记 V23 PASS；V24 验 addon 正误路径。所有条目区分源码可确认/归档可确认/待设备复现；`--version` 不能替代真实执行。

**失败处理**：包装脚本 EACCES 不得 chmod/改 targetSdk/复制到可写目录绕过；launcher 缺失或映射未覆盖显式报错；packageManager 接点不可用记 NOT_RUN/BLOCKED 并说明官方接点；GNU Bash 缺失只披露 mksh，不删除 Bash 能力目标；native 构件加载失败显示具体依赖错误。遇到依赖 glibc/动态 ELF 的关键插件，记录具体依赖与替代成本，进入可选 Linux 后端门；不要全局换架构或取消目标功能后宣称完成。

**回退**：launcher、TOOL_LINKS、staging、依赖和 payload 同闭包回退；保留用户 home。兼容别名必须记录真实 dialect，不能回退到以 bash 名称掩盖 sh。

## 10. C08：真 PTY 与终端生命周期

**Owner：native/终端工作者。依赖：C07（命令入口与 Node 启动可用；C07 依赖 C02c + C02a/b）。**

写集：官方 `dsh-subprocess-local` seam 的源补丁、模式诚实上报、可选 Termux terminal-emulator JNI native helper（NDK 构件 + staging 由整合者）、设备探针与验收说明。Kotlin owner 只做现有树/停止连线，不复制 terminal 业务。

**起点（归档可确认）**：payload 已有纯 JS ptmx/pts 真 TTY：`dsh-subprocess-local/lib/index.js` 的 `openAndroidPty()` 开 `/dev/ptmx` 并枚举 `/dev/pts/N`（按 `rdev` 识别，上限 64），`spawnWithPty()` 把 slave fd 作子进程 stdio，64 KiB 读泵；但 `resize()` 是空实现（`:1046`），无 `setsid`/`TIOCSCTTY`/controlling tty/前台进程组，失败静默降级 `spawnWithPipes()`（JS 行规程，`resize()` 同样空），模式只写 `console.log("[pty] …")`，没有 seam 能力位。`node-pty` 只在 package.json 声明、payload 未安装，且与“payload 不带原生模块”的红线冲突。（归档可确认）**待证事实**：`/dev/pts` 在 app 命名空间是否可见、^C 经 slave 行规程命中哪个进程组、`TIOCSWINSZ` 是否允许；均未验证。

**实施顺序**：

1. 设备探针 P1–P5 必须在 MCA `:dsh` 进程内的 Node 执行（`adb shell` 是 shell UID，不代表 app 域）：P1 开 ptmx 找 slave/rdev；P2 用 slave 作 stdio spawn `/system/bin/sh -i` 看提示符/回显；P3 `stty size`（预期 0 0，确认 resize 缺口）；P4 在会话写 ^C，用 `ps -o pid,pgid,sid,stat` 观察是否打到 Node；P5 读 `/proc/self/mountinfo` 确认 devpts 挂载。（待设备复现）
2. 模式诚实上报：seam 暴露 `pty|pipe` 与实际原因（`[pty]` 日志已有，扩到能力状态），增加 resize 能力位；`resize()` 明确返回“不支持”而不是静默 no-op。管道不计 PTY。
3. 决策：P1 有 slave、P2 有提示符且 P4 不命中 Node ⇒ 先接受“无 resize 的真 TTY”，把 resize/前台组/controlling tty 作为已知限制写进能力清单，V25 的 resize 项保持未满足；若 pts 不可见、P4 命中 Node 或必须支持 TUI resize ⇒ 接 Termux terminal-emulator JNI 的最小 native helper：`openpty + setsid + TIOCSCTTY + TIOCSWINSZ + fork/exec /system/bin/sh`，Node 侧经 N-API 或 socket 交互。helper 记录来源/ABI/16 KB/许可，不新增 node-pty。
4. 终止/回收与 owner 停止集成：输出背压有界；terminate 等待子树真实退出；关闭超时不显示已关闭。
5. 官方 Web terminal 交互回归。

**node-pty 不作为 V1 主线**：上游无 android 预编译、需 node-gyp/NDK 且与 payload 红线冲突；resize 收益可由 Termux JNI 以更低耦合取得；不得为了 node-pty 改写 payload 红线。

**验收**：V25、V26。V25 验 isatty(0/1/2)、交互输入/中文、Ctrl-C 命中正确前台组、resize→`stty size`（仅在 helper 分支可验，无 resize 分支保持 NOT_RUN）；V26 验高频输出/有界背压、断连重连、孙进程/后台 job 取消、树实际退出、超时不误报。pipe 路径单列，不得计入 PTY 通过；模式报告必须与真实路径一致。

**失败处理**：native helper 不可用显示具体错误；探针缺结果记 NOT_RUN，不猜“可用”；关闭超时不显示已关闭；不要只杀 shell 留后台进程；失败降级必须标注为 pipe。

**回退**：不强制所有非交互命令改走 PTY；可回退 seam 集成 patch 与 helper 闭包，保留明确能力状态和已有 pipe 路径；完整终端目标继续未完成。

## 11. C09：官方插件与技能端到端事务

**Owner：插件兼容工作者。依赖：C02（含 C02c 配置生效层）、C07；PTY 样本依赖 C08。**

写集：官方扩展 seam 的配置/最小适配、兼容 fixtures、矩阵与脚本；不在 Kotlin 创建 installer，不替换官方管理状态。

固定样本：纯 JS、package bin、需审批 postinstall、Python、正确/错误 native addon、PTY、离线缺依赖。技能按官方实际格式另测导入、启用、上下文加载与调用，不能插件通过便算 skills 通过。

每个插件执行 inspect→install→审批→activate→真实工具→重开→update/remove，插入下载、lockfile 写、脚本执行、激活阶段中断。区分 manifest/lockfile 恢复与完整 node_modules 恢复。取消后要看 pnpm/script 树真实退出。

失败处理：官方 manager 报错或状态未知时先诊断并保留原始事务记录，不 Kotlin 重装覆盖；安装成功但工具未执行不算通过；取消后核对真实树退出，不把“已取消”当已停止。

验收 V27–V30；用户 profile 配置/锁/插件数据保持，内置管理 bundle 保护不削弱。安装成功但工具未执行不算通过。

回退：由官方 manager 按其实际支持范围回滚；状态未知先诊断，不 Kotlin 重装覆盖。第三方插件脚本权限按真实信任边界提示。

## 12. C10：模型目录动态刷新

**Owner：模型目录工作者。依赖：C03；choice/capability 声明依赖 C05/C06 对应部分。**

写集：ADAPTER `DshModelCatalog.kt` 与窄变化订阅、官方目录刷新扩展和测试。COMPOSE 只连线，不能堆 catalog 业务。

执行：desired/applied revision→有界合并事件→新快照验证→官方 ACK→picker/下一调用验收。覆盖本地加载/卸载/删除、云配置修改、空目录、能力变化。每个 operation 冻结其模型与 binding；刷新不取消在途、不换模型、不重建页面。

验收 V31：不重启 Node 的列表与实际调用更新、旧 ID 新调用拒绝、在途结算、离页再入保持。目录显示更新不能替代实际下一回合命中。

回退：冻结旧已应用目录并显示刷新失败；拒绝真实不存在的模型，不继续显示虚假可用。不能改成自动杀 Node。

## 13. C11：Android capability 生产桥

**Owner：平台工作者持 port，端点/契约由整合者统筹。依赖：C03、C02c（配置生效层）与 C02 的保存相关原语。**

已有输入：CONTRACT `capability/` 全部 DTO/security/requests/results/取消端口及 fake。只按真实缺口增量实现，不重建协议。

建议写集：独立 `api` capability server package、`app/.../dsh/capability/` 平台 owner 与 ActivityResult 生命周期注册、官方 `mca-android-capabilities` 扩展源码及测试。新 package 名称在开工写集固定；没有实际收益不新建 Gradle 模块。

分片：

1. C11a 认证与幂等端点：复用 dshc_ authority、代次 header、requestId、取消与上限；新 HostIdentity 的协商不破坏旧字段。保持现有 REQUEST_IN_PROGRESS、USER_PRESENCE_REQUIRED 等精确错误枚举，不与 maintenance 状态混用。
2. C11b ActivityResult host：resumed 页面注册，后台返回 USER_PRESENCE_REQUIRED；配置变更/进程恢复/取消只有一个结果，长期 owner 不持 Activity。
3. C11c pick/save：受控 staging、大小/路径/symlink/TTL、SAF 真实读写，已有目标与失败保留；保存结果未知不再次写。
4. C11d notify：实际系统权限和 channel、拒绝/取消/重复/重建；requestId 重放不重复通知。
5. C11e 官方 plugin 工具注册与实际 loop 调用；一次逻辑 invocation 固定 requestId+payload fingerprint，断连/查询/重试复用，未知不换ID或换代重发；仅符合现有 status 条件的工具注册，不绕过 authority。
6. C11f 设备动作是独立后续子片，逐项 typed action/目标验证/明确同意/取消/真实权限；不加任意 Intent 执行器或 Accessibility 万能口。

验收 V32–V35。最小幂等记录在断连/进程重建后无法证明成功时返回 OUTCOME_UNKNOWN，不把“没有记录”当未执行。Web input 文件选择不代替 agent-tool 验收。

有副作用操作必须先写可恢复派发意图，后执行，再记终态；外部系统与记录不是原子事务，崩溃模糊窗口保守未知，不能宣传exactly-once。同ID参数变化用现有INVALID_REQUEST，不新增CONFLICT枚举。记录有容量、保留和回收策略，未结算不得淘汰为可重执行，满额拒绝新准入。

回退：撤 capability 注册/凭据并保留已有用户文件，不能清 staging 以外目录。桥不被描述为任意 Node 插件的强沙箱。

## 14. C12：候选运行闭包、升级与用户数据恢复

**Owner：生命周期/数据工作者；owner/打包由整合者。依赖：C01、C02、C03 及所需插件兼容事实。**

读取：RUNTIME payload installer、seeder、config、process host；现有 home 外 guard/marker/refusal 测试；scripts/dsh 资产来源；backup/data extraction 合同。

写集：payload 下事务/journal 小组件、owner 的窄维护调用、现有 seeder 的必要兼容补充、版本化 manifest 与行为测试。不得给 seeder 再建平行 home 数据库。

分片：

- C12a 受管 RuntimeClosureId 与兼容范围；用户部署另记 UserDeploymentFingerprint，受控trial不声称已测用户插件；实测旧 payload 对新 APK ELF，不可回退的分支先明确。
- C12b journal 算法与确定性中断测试：每个 durable intent、rename/指针、同步、disk-full 和重复恢复。
- C12c 单 owner trial：生产树停止证明→isolated home/workspace→受控 profile/假模型端点→真实 loader/平台探针→真实退出。通过后先完成真实home/schema预检与备份，再暂选候选进入PRODUCTION_HEALTH_PENDING；必要生产健康成立才最终ACTIVE。未完成/未知用户turn不得自动重放。
- C12d 健康失败保留 active/previous 的可恢复性；nativeLibraryDir 已升级时，不能只切 JS 便回滚成功。
- C12e 用户 home/schema 兼容与备份/恢复：marker/外 guard 延续、用户 profile/lock/plugins/credentials/DB 的实际覆盖范围，缺 home 拒绝重建。
- C12f 升级安装后的官方 session 语义回合：历史 ID、关联、产物/审批/继续执行，不只是文件 hash。

验收 V36–V40。trial 不带生产凭据、不运行任意用户插件、不派发真实模型/Android动作；同 UID 下不宣称天然无副作用沙箱。V1 候选来自 APK assets，不趁机加远程更新器。

回退：数据与闭包兼容证明成立才执行；不成立显示 RECOVERY_REQUIRED，保留数据与诊断，不强制降级或重置 home。

## 15. C13：完整能力扩展切片

**Owner：按后端分别委派，契约统一归整合者。依赖：C05/C06 和受影响平台能力。**

写集：各子片分别固定后端实现/测试与 manifest 闭环文件；公共契约与公共生成事件由整合者统一改，不新增无生产调用者的模块、接口或空适配。

本卡必须拆独立可回退子片，不能一次改所有引擎：

- C13a 本地 native tools：实际 template/tools/history/输出解析与 producer 终态，逐后端验收；仍用现有 engine owner。
- C13b 其他云传输 native tools：按 provider 请求与响应真实语义测 choice/parallel/reasoning，不用 Responses 证据替代。
- C13c 多模态：versioned content parts、大小/URI/inline/order、能力错误与已有视觉预处理，真实图片任务。
- C13d reasoning/辅助模型/压缩路由：主/辅助模型配置与离线闭包，DSH 官方功能与现有模型协议对齐。
- C13e 固定版本 manifest 中其他未闭环功能：先定位官方生产路径，再逐项给出输入/失败/证据，不凭名称捏造功能。

验收 V41–V44 及每个 manifest row 的具体 tests。未完成的子片继续进入对标矩阵；可以声明“文字工具模式阶段可用”，不能宣布完整对标。

回退：能力声明与路径一致回退，不能仅关闭 catalog 标志隐藏回归；已存在能力遵守原功能连续性合同。

## 16. C14：逐项功能与插件兼容矩阵

**Owner：独立审查者；不实现生产代码。依赖：从 C00 持续运行，结论依赖所有相关任务。**

写集：completion-v1 的 PARITY_MATRIX、PLUGIN_COMPATIBILITY、EVIDENCE_INDEX；不改历史原报告。

每项记录官方 revision/profile/row、生产调用者、协议、平台依赖、确定性 probe、实际 loop 任务、失败/恢复、APK/闭包/配置、状态与证据。默认未启用的功能说明启用方式；Android 不适用必须有实质理由，不能把待实现改 N/A。

验收 V45：无无主行、无失效证据链接、无包数冒充功能数；任何 PARTIAL 必须展开为具体子项后使用标准状态。旧证据失效不删，增加 supersession/适用性说明。

回退：矩阵是报告，不能反向修改产品让报告好看。

## 17. C15：整合、回归与候选验收

**Owner：整合者 + 独立验收者。依赖：全部声明完成范围。**

先审实际合并工作树，检查 owner/状态/契约/调用者/临时 adapter；再使用构建锁串行执行所需模块测试与正式构建，绑定候选 APK 身份。验证不是复用别人的“已完成”摘要。

验收 V46–V50：聊天/图片/Kotlin Agent/Local API/DSH 共存、资源竞争、前后台/进程恢复/renderer、同签名升级数据、离线闭包、长任务/输出背压/总 PSS。UI 变化按既有视觉合同做固定状态真机复核，不整体换肤。

结论分层：代码/协议、平台、模型任务、发布；任一层没验证就明确标注。外部云 503 留 BLOCKED；需要云能力的完整声明不能因此标 PASS。

回退：使用经 C12 验证的兼容闭包/数据路径；未验证降级不当默认救援。交付具体未完成清单，禁止自评分替代证据。

## 18. 测试和构建执行方法

本节是未来实施命令示例，本轮没有运行。开工先确认 JDK 21、当前 tasks 与工具链。已有 `verify-code.ps1 -FullTests` 的模块集合不自动包含全部 DSH 新模块；`-Changed` 也不能覆盖全部 untracked 输入。

架构检查独立运行，结论限于脚本覆盖范围：

```powershell
python tools/architecture/check_architecture_rules.py
```

DSH 模块任务示例，必须由整合者在现有锁内串行运行；不要同时调用会再次取锁的 build.ps1/verify-code.ps1：

```powershell
Import-Module .\tools\build-guards\McaBuildLock.psm1 -Force
$dshCompletionLock = Enter-McaBuildLock -Kind test -CommandLine 'DSH completion selected module tests'
try {
    & .\gradlew.bat ':core:dsh-contract:testDebugUnitTest' ':core:dsh-runtime:testDebugUnitTest' ':api:dsh-gateway:testDebugUnitTest' ':feature:dsh-workspace:testDebugUnitTest'
    if ($LASTEXITCODE -ne 0) { throw 'DSH module tests failed' }
} finally {
    Exit-McaBuildLock -Lock $dshCompletionLock
}
```

app 某类测试优先用已有受控脚本，例如：

```powershell
.\scripts\verify-code.ps1 -Tests '*DshTranscriptMapperTest' -Rerun
```

每个实际使用的 filter 要核对名称、执行数量与新报告。上述模块集合不包括 C06 所有引擎 producer，也不包括真机/native，所以仍按本片影响补具体任务。不能把多个测试任务加一个全局 `--tests` 后忽略其他模块未匹配。

完整候选构建使用现有 `scripts/build.ps1`，不绕过 SDK/native/资产要求，不 clean 掩盖失败。设备测试先绑定已安装 APK hash/签名；升级验证保留原 applicationId、用户数据与签名。具体故障注入脚本由对应卡审查，避免生产用户数据。

## 19. 接手 Agent 的可复制提示词

以下提示词只在用户另行授权实施后使用；当前“先写方案”不自动启动它。

```text
你接手 E:\MAC 的 DSH 补齐 V1。用户已授权的任务卡是：<Cxx / 明确子片>。

先读取适用项目指令、docs/architecture/MCA_PROJECT_HARD_LOCK.md，以及：
docs/proposals/agent-dsh-embedded-v1/DSH_COMPLETION_PLAN_V1_20261020.md
docs/proposals/agent-dsh-embedded-v1/DSH_COMPLETION_EXECUTION_PACK_V1.md
docs/proposals/agent-dsh-embedded-v1/DSH_COMPLETION_ACCEPTANCE_V1.md

你不是独自在仓库工作。检查 branch/HEAD、tracked/untracked 输入、子模块 revision 和内部差异，保留所有他人修改。你的允许写集：<由整合者填写>。
禁止触碰写集以外的实现；公共契约、composition、公共生成事件和构建/归档锁由整合者持有，你提出精确 patch 请求并继续独立工作。普通实现选择自主处理，超出授权的功能删减/数据破坏/发布不自行进行。

DSH 沿用官方 loop/会话/工具审批/插件管理，MCA 只做 Android 宿主与模型/平台适配。唯一 DshRuntimeOwner，唯一实际 MCA 推理资源 owner。不能增加 Kotlin Agent loop、平行 installer、MainUiState 写权限、大 Host、Any 注入、无 owner scope。

先建立风险复现和验收条件；最小实现；审查默认行为/协议/数据兼容；运行受影响验证。所有 Gradle/native 工作由整合者遵守构建锁串行执行，不并发共享 build。不能改低门槛、刷旧报告或将空测试当通过。

不得把 READY 当任务成功、IPC 超时当未执行、旧 endpoint holder 当 Node 已更新、零模型调用当 DSH 空闲、service 关闭当进程树退出、管道当 PTY、no-op 当锁、proot/token 当强沙箱。
模型输入按最终序列化验证，不只测 mapper；不静默裁 DSH 历史/工具；不重放结果未知的 turn、工具或 Android 副作用。

本卡完成后交付：实际改动文件与责任、生产调用路径、owner/状态 writer、影响与兼容性、测试命令/数量/日志/退出码、工作树与 APK/运行闭包身份、PASS/FAIL/NOT_RUN/BLOCKED/N/A、回退点、临时路径退出条件。只写可证明结论。
不要提交、推送、安装或执行额外发布动作，除非当前用户授权明确包含；历史授权范围由整合者附入任务包，不自行推断。
```

## 20. 每卡交接模板

```text
卡号/子片：
授权范围/允许写集/实际写集：
起点与终点 HEAD + 工作树/子模块补丁指纹：
复现问题/真实生产入口：
功能/默认值/数据/协议/视觉影响：
唯一 owner、只读投影、状态写入者：
契约演进与旧 fixture：
检查命令、开始结束时间、退出码、匹配测试数/失败/跳过：
证据路径/输入与输出 hash/候选身份：
PASS：
FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE 及具体原因：
未处理差距/临时适配/退出条件：
回退点与数据前向恢复限制：
下一张卡/依赖是否满足：
```
