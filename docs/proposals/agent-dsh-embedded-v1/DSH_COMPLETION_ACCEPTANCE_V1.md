# DSH 补齐 V1：验收规范与证据矩阵

日期：2026-10-20。本文件定义未来执行的门禁。本轮仅写方案，以下 V01–V50 均不能据本文登记为已运行或 PASS。

配套：[总体方案](DSH_COMPLETION_PLAN_V1_20261020.md)、[任务卡](DSH_COMPLETION_EXECUTION_PACK_V1.md)、[C07/C08 可行性备忘](DSH_COMMAND_AND_PTY_FEASIBILITY.md)、[架构合同](../../architecture/MCA_PROJECT_HARD_LOCK.md)。

2026-10-20 配置层更正（源码级已核实，可按路径复核）：payload 官方启动**会读取** home 级 `$DSH_HOME/cordis.patch.yml`（`dsh-app-boot/lib/index.js` 的 `readProfilePatches()` 内 `loadOptionalPatches(join(context.home,"cordis.patch.yml"))`；`profile-boot` 的 `homePatchPath()` 中 home 层 outranks profile 层）。因此 overlay 路径本身没有写坏；当前缺的是 **MCA home overlay 未合并 payload 模板 `dshhome/cordis.patch.yml` 的平台 patch 行**。MCA seeder 只生成 permission（`defaultPreset: workspace-write`、`approval: ask`）、workspace-controller、llm-pi-ai 三部分，未复制/未合并模板中 disable `bash-sandbox` 等平台 patch。可行性备忘的修订 1 已更正 F1/F2；后续引用以本节和 V02/V08 的表述为准。上述是源码级结论，设备上的实际合成树仍是 NOT_RUN，须由 V02 的账本与 V08 的两步探针确认。

## 1. 状态、适用范围和证据优先级

只使用 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`。每项可有独立的“诊断已完成/实现已合入/平台已验证/发布已验证”字段，但不得混成一个笼统完成状态。

源码风险成立不代表真机故障已经复现；fake 通过不代表真实 provider/ELF/模型通过；旧候选 PASS 不代表新候选 PASS。引用旧证据时明确 revision、APK、配置与变更后仍适用的理由。

最新已知历史基线为 [20261020-051217 原始记录](../../../artifacts/dsh-verify/20261020-051217/FINAL-RELEASE-VERIFY.md)。本方案接受其基础链路证据，不接受仅凭离题输出就完成模型归因的结论。后续索引以原始日志/产物身份为依据，同时保留历史汇总与冲突说明。

BLOCKED 必须有具体条件，例如受控云服务返回 503、缺可审计 ELF 源、特定内核原语调用失败；没有做测试应写 NOT_RUN。NOT_APPLICABLE 必须给出 pinned 功能/profile/平台的实质理由，不能用来隐藏未实现项。

## 2. 证据目录与最小记录

未来每次运行创建独立目录，建议：

```text
artifacts/dsh-completion/<YYYYMMDD-HHMMSS>-<runId>/
  run.json
  identity/       # 工作树、子模块补丁、依赖闭包、APK、签名、设备/软件
  tests/          # 命令、XML、stdout/stderr、计数和退出码
  runtime/        # 脱敏的阶段、身份、绑定/目录修订、树/终态证明
  model/          # 合成输入、结构化实际参数、协议结果与任务产物摘要
  plugins/        # 版本、依赖、审批、安装/取消/重开证据
  faults/         # 注入位置、触发时间、恢复路径
  shots/          # 必要 UI/通知/首帧证据
  outputs/        # 可分发的合成产物及校验和
  verdict.md
```

这些是本地验证产物，不默认提交二进制、私有内容或设备完整标识。可分发证据索引引用脱敏摘要；测试私有凭据不进入 fixture/命令行/截图/日志。

run.json 最小字段规范如下。示例所有值都是说明，不能复制为真实 PASS：

```json
{
  "schemaVersion": 1,
  "runId": "<本次唯一ID>",
  "startedAt": "<带时区ISO时间>",
  "endedAt": null,
  "source": {
    "head": "<实际HEAD>",
    "worktreeFingerprint": "<含未跟踪生产输入的指纹>",
    "submodules": [
      { "path": "<路径>", "revision": "<SHA>", "patchFingerprint": "<实际补丁指纹>" }
    ]
  },
  "candidate": {
    "apkSha256": "<实际APK哈希>",
    "signerSha256": "<签名证书哈希>",
    "runtimeClosureId": "<完整运行闭包身份>",
    "dshRevision": "<固定源revision>",
    "profile": "<实际profile及配置版本>"
  },
  "environment": { "deviceAlias": "<脱敏别名>", "sdk": null, "abi": "<实际ABI>" },
  "cases": [
    {
      "id": "V03",
      "scope": "<JVM/device/provider/backend等>",
      "status": "NOT_RUN",
      "expected": "<可检查结果>",
      "actual": null,
      "commandRef": null,
      "testCount": null,
      "exitCode": null,
      "evidenceRefs": [],
      "blockedReason": null
    }
  ]
}
```

另外记录模型资产/模板/量化/后端/有效参数/工具协议/主辅助模型路由/采样值、插件版本/lock、包与 ELF 依赖。终态记录安全的 host/gateway/call 身份，不记录 bearer、launch URL、cookie 或用户全文；不以 token hash 替代安全的独立身份。

## 3. 确定性协议与宿主验收 V01–V20

表中“动作”是未来测试要求。测试应使用可注入依赖/受控 fixture；故障注入接口不能成为生产未认证后门。

| ID / 任务 | 场景与动作 | 必须证明的结果 | 最低证据 |
| --- | --- | --- | --- |
| V01 / C00 | 记录当前 tracked/untracked、子模块内部差异和二进制输入 | 任一生产输入可追溯，不能只凭父 HEAD 复现声明 | 清单、指纹方法、实际文件 hashes |
| V02 / C00 | 固定上游与 payload 补丁逐项比对；补丁账本逐行登记 payload 模板 platform patch（`dshhome/cordis.patch.yml`）是否进入 MCA home overlay | 四项语义弱化有生产调用者与退出 probe；平台 patch 行逐行有“进入/未进入”结论与生效证据（源码级已核实：seeder 未复制/未合并该 patch）；静态功能映射不漏行 | patch ledger（含 platform patch 行→MCA overlay 映射）、source refs、矩阵校验 |
| V03 / C01 | owner.stop 返回 STOP_TIMEOUT/UNKNOWN；核对 IPC、service 与通知 | 不 stopSelf、不撤通知、不显示已停止；fence 保留，新代被阻止 | 行为测试、状态序列、通知截图/系统状态 |
| V04 / C01 | shell/工具启动子孙与后台进程，包含不响应 TERM 的进程，再停止 | 分阶段终止；实际树退出与app权威EngineStopSettlement回执齐全才完成stop；无误杀新代/无关任务 | 树快照、信号/等待、绑定host/request/epoch/incarnation的结算与IPC时间线 |
| V05 / C01 | scan 抛错、Binder/main死亡、cancel/registry结束但native仍活、回执丢失/记录淘汰、重复stop、旧回调/PID复用 | UNKNOWN不等于释放；Node退出不替代推理证明；同树停止幂等，旧代不能作用新代 | owner/receipt事件序列、故障日志、fence/通知仍可见 |
| V06 / C02a | 两个独立 Node 竞争实际 session/profile 锁，持有者崩溃后重取；缺 addon 负例 | 同时只有一个持锁，竞争有正确 errno，死亡可释放；缺 binding 不成功 | 两进程输出、实际 fd/锁 probe、归档/ELF hashes |
| V07 / C02b | 强制 hardlink 不可用，多进程对同一目标发布；已有目标/symlink/跨FS/磁盘满 | 一位赢家，败者不覆盖；不暴露伪成功/半文件；不回落 stat→rename | 过程日志、目标内容 hash、errno、重开结果 |
| V08 / C02c | 两步：(1) 证明 composed tree 的实际生效层——home 级 overlay 被官方读取，且 payload 模板 platform patch 已合并，导出实际 `ctx.shell` provider、permission preset 与 overlay diff；(2) 对报告的每个 sandbox mode 运行路径/子进程/网络合成探针 | 第一步未通过不得进入第二步；若 `bash-local` 替代 `bash-sandbox`，必须显式声明“无 OS enforcement”，不得把 `workspace-write` 声明当已隔离；保留 `bash-sandbox` 缺 runner 的 fail-closed 负例（源码推导，待设备复现）；缺真实隔离的能力不得标 PASS | composed tree 转储（provider/preset/overlay diff）、`SANDBOX_UNAVAILABLE` 原始 stderr、探针结果、具体边界说明 |
| V09 / C03 | 重建 service/main/gateway，模拟两者计数都从1开始，旧命令迟到 | instance identity 消除代次碰撞，旧命令返回 stale，不修改新代 | IPC/契约测试、实际身份时间线 |
| V10 / C03 | commandId 重复、同ID不同payload、派发后断连与查询、service记录丢失 | IN_PROGRESS/原结果/CONFLICT/OUTCOME_UNKNOWN 正确，无未知副作用重放 | 幂等表与请求数、故障日志 |
| V11 / C03 | 保持 Node，独立重建主进程网关，变端口/token；验证官方 live update | Node ACK 实际 applied identity；下一请求用新认证，旧请求明确结算，未自动重发turn | 前后进程、认证正负例、applied revision、实际请求证据 |
| V12 / C03 | 工具/子Agent/job/PTY/安装/审批/压缩等活动与维护竞争；插入未知owner | 自动维护必须等同代屏障和全部活动 proof；UNKNOWN 不重启；proof失效不能消费 | 官方owner接点、barrier序列、活动故障fixtures |
| V13 / C04 | 同模型配置运行 L0–L4，比较最终序列化和完整输入 | reasoning附加/二次裁剪可定位；离题有可证伪分层结论 | 合成fixture、最终请求摘要、模型/模板/参数身份 |
| V14 / C05a | DSH 含 system、工具历史、长结果与超容量；并回归普通聊天/Local API | DSH 不静默删历史/添不明指令；超限被官方pi-ai识别并触发DSH压缩；旧入口默认不变 | 最终serialization/admission测试、错误分类及压缩trace、旧fixture |
| V15 / C05b/c | none/auto/required/named，缺工具/错名字/重复定义；parallel=false 多调用 | wire到实际provider保真；不支持明确拒绝，不能降级；required非法不成功 | parser、adapter、provider真实请求fixture |
| V16 / C05d | 目录超预算、大schema、被省略工具、逐字符协议片段、malformed | 必需协议闭合；完整提供或明确拒绝；parser仅许可实际offered；不凭空补调用 | builder结构输出、解析行为、负例工具调用数为零 |
| V17 / C05e | 多轮call/result、并行交错、工具长结果与跨session | ID关联、顺序、role语义和KV/session隔离正确 | 真实传输/模板fixture、session A/B对照 |
| V18 / C06 | 从真实producer触发EOS、输出上限、取消、异常、EOF | 实际reason贯通；unknown不伪造STOP；唯一terminal，后续事件被隔离 | producer事件、adapter、SSE/buffered三段对照 |
| V19 / C06 | index后换ID/name、跨index重复ID、参数不完整/溢出、held envelope取消 | 拒绝串ID与半参数；无意外工具执行；正确流可重组 | assembler测试、官方客户端消费结果 |
| V20 / C06 | usage为0/缺失/估计/实测，only-usage chunk、取消/错误响应 | 保留语义，streamed与buffered一致，官方pi-ai实际可处理 | wire fixture、客户端调用、字段对照 |

V06/V07 的并行 Node 是明确的竞争测试 fixture，不是允许生产双宿主。V11 必须独立重建主进程并确认 :dsh/Node 仍活；整包 force-stop 会同时杀两者，不能代替该场景。

V05 还必须验证 UNKNOWN 的恢复出口：原 owner 可查询时按原身份核验；资源生命周期改变时必须有原进程/load incarnation/lease 的权威结算，不能只凭 main PID 变化释放。记录满额/淘汰负例不能使未结算请求变为可重执行。

## 4. 工具、插件与平台验收 V21–V35

以下 C07/C08 结论按证据强度标注：**源码级已核实**=可按路径复核；**源码推导**=需设备复现后才能当故障事实；**未验证**=不得写成通过。V21–V35 全部待执行，本文不登记任何 PASS。

| ID / 任务 | 场景与动作 | 必须证明的结果 | 最低证据 |
| --- | --- | --- | --- |
| V21 / C07 | 经受管窄 launcher 的直接路径与 PATH 调用工具，参数含空串、空格、中文、引号、换行及字面量 `$()`；另设可写目录包装脚本直接 execve 负例基线 | argv/cwd/env不二次解释；stdin/out/err、exit/signal不丢；launcher 的 argv[0]→固定入口映射不被任意环境变量替换；可写目录脚本在 targetSdk 36 下 execve 得 EACCES（平台机制已核实，设备侧待复现） | fixture记录argv JSON、launcher/命令hash、EACCES stderr |
| V22 / C07 | 官方manager经profile packageManager运行pnpm；npm/npx/pip与package bin/shebang链；先测可写目录包装脚本直接 execve（EACCES 负例），再测受管窄 launcher 正例（`python*` 例外：现行源码已重链到 `libdshpython.so`，须设备正负例确认解释器可用，不得按名称断言 EACCES 或可用） | 不依赖可写脚本直接exec；固定解释器读入口；管理器用的是实际配置；负例错误具体且不静默降级（EACCES 机制已核实，设备侧待复现），正例保持原脚本 prefix/env（`NPM_CONFIG_PREFIX`、`--config.package-import-method=copy`、`PYTHONHOME`、`LD_LIBRARY_PATH`） | official call trace、子进程路径/argv、EACCES stderr、launcher 构件身份/hash、真实工具输出 |
| V23 / C07 | Bash数组/条件/pipefail等（若声明 GNU Bash 则必须是真实 GNU 语义；V1 默认 `bash` 实为指向 `/system/bin/sh` 的 mksh（源码级已核实），必须在 UI/能力清单披露，不得以 sh 冒充 bash），git受控仓库/HTTPS remote，curl证书，Python离线包CLI | 声明与真实方言一致；helper/证书/stdlib/site-packages完整；错误不吞；mksh 缺失语义单列为已知限制而不是“通过” | 版本/方言探针、披露位置、闭包、命令结果、取消与超时日志 |
| V24 / C07 | 正确Android addon实际函数调用，错误ABI/缺库/不可执行ELF负例 | 正确路径可调用；错误具体且不no-op；已有插件不被破坏 | dlopen/调用结果、ELF/N-API/库依赖与SHA |
| V25 / C08 | 在 `:dsh` 进程内 Node 跑 kernel PTY 探针（app 域，不得以 adb shell 代替）：官方 terminal 的 isatty、输入/中文、resize→stty、Ctrl-C/前台组；现有 payload 纯 JS PTY 为 `/dev/ptmx`+`/dev/pts/N` 真 pts，但 `resize()` 空实现、无 controlling tty/会话/前台组，须按实测声明（源码级已核实；`/dev/pts` 设备可见性未验证） | 真 kernel PTY 和控制终端，不是 pipe；resize 实效（`stty size` 反映）；Ctrl-C 命中正确前台组而非宿主 Node；若选择不实现 resize，必须把“无 resize”列为已知限制并保持完整终端声明未完成，不得据此记 PASS；native 收口走 Termux terminal-emulator JNI（openpty+setsid+TIOCSCTTY+TIOCSWINSZ+fork/exec），不引入 node-pty | `:dsh` 内 P1–P5 探针原始输出、stdout/stderr 的 isatty、`stty size`、ps 的 pid/pgid/sid、resize 空实现证据 |
| V26 / C08 | 终端高频输出、断连重连、启动孙进程/后台job再取消或stop；管道回退单列对照 | 有界背压、输出合同正确、树实际退出；pipe 回退不计 PTY 通过（不算 isatty/控制终端通过），失败降级必须显式上报原因；超时不误报closed；native 路径失败给具体错误 | buffer/总PSS、进程树、terminal done/terminate序列、`[pty]` 降级日志、模式上报状态 |
| V27 / C09 | 固定纯JS插件inspect/install/activate/实际tool/reopen/update/remove | 全程官方manager，profile/lock保持，工具真的运行 | manager日志、lockdiff、产物hash、重开 |
| V28 / C09 | postinstall先拒绝、后批准；下载/lockfile/脚本/激活分别中断 | 拒绝零执行，取消等待树退出；恢复范围真实，不能盲目重装/重复脚本 | 审批、脚本计数、事务文件、恢复结果 |
| V29 / C09 | skills导入/启用/调用、官方可选扩展与MCP等固定源功能 | 固定版本实际路径可用；配置/权限/工具关联不丢 | 官方source row、设置、实际任务trace |
| V30 / C09 | 纯JS/bin/Python/addon/PTY插件各一个正例及错误依赖负例 | 兼容按具体版本/闭包声明，不把一类结果推广全部 | 插件兼容矩阵、依赖/锁/证据链接 |
| V31 / C10 | Node不重启时加载/卸载/删除本地模型、改云配置、空目录/密集变化 | desired/applied闭环；picker与下一请求更新；在途不换模型/重播 | catalog revisions、实际点击/请求、前后PID |
| V32 / C11a | 错token/旧代/同ID不同payload/重复pending/派发后断连重建；插件重试同逻辑调用 | 认证隔离，REQUEST_IN_PROGRESS/冲突/未知正确；插件不换ID重发，不重复保存或通知；取消独立可服务 | HTTP/authority与插件测试、实际副作用计数 |
| V33 / C11b/c | 官方tool触发SAF选择/保存，后台、配置重建、取消、迟到结果、路径越界 | resumed UI执行，后台返回USER_PRESENCE_REQUIRED；只一terminal，staging/大小/路径正确 | 系统picker、文件hash、生命周期trace |
| V34 / C11d | 通知权限允许/拒绝、channel、重复请求、重建未知结果 | 真通知与状态一致，重复不双发；未知不补发 | 系统通知记录、权限状态、幂等证据 |
| V35 / C11e/f | 官方loop调用file/notify；后续每个device action单独测许可/目标/拒绝 | agent-callable真实链路；不存在任意Intent/设备万能口 | 真实DSH tool trace、系统结果、拒绝零副作用 |

V32 包含持久派发意图之前/之后、外部效果之后/终态记录之前的崩溃。结果可保守未知，必须没有自动重复；同ID参数冲突返回现有 INVALID_REQUEST。容量满应拒绝新准入，不能无限增表或淘汰未结算记录。该门禁证明受控路径的至多一次派发，不声称外部系统与本地记录之间存在原子 exactly-once。

### 4.1 插件兼容矩阵（C09 / C14 PLUGIN_COMPATIBILITY）

矩阵行是 C09 的固定样本类别，也是 C14 `PLUGIN_COMPATIBILITY` 的行来源；V27–V30 负责逐类执行，V45 校验矩阵、证据与状态一致。每行当前状态均为 **NOT_RUN**：维度格写“未执行”，其状态即 NOT_RUN，不是 NOT_APPLICABLE；执行后按实际结果填 `PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE`，不得把安装成功、`--version` 或 inspect 通过当作执行通过。固定源、版本、lock、依赖闭包与真实工具任务必须随行记录，不能把一类插件的结果推广到其他类别。

| 插件类别 | inspect | install | 审批 | activate | 执行 | reopen | update | remove | 取消 | 回滚 | 证据 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 纯 JS | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| package bin | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| 受控 postinstall | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| Python | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| 正确 Android N-API addon | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| 错误 ABI/缺依赖 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| PTY 子进程 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| 离线缺依赖 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| skills | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |
| MCP/官方可选扩展 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | 未执行 | NOT_RUN |

对应关系：V27=纯 JS 与 package bin 的 inspect→remove 基线；V28=受控 postinstall 的审批、取消、回滚与中断恢复；V29=skills 与 MCP/官方可选扩展；V30=Python、正确/错误 addon、PTY 子进程、离线缺依赖的正负例。C09 产出每次事务证据，C14 汇总为可机读 `PLUGIN_COMPATIBILITY`；V45 对行、版本、证据链接与状态做一致性校验。

## 5. 升级、扩展和整合验收 V36–V50

| ID / 任务 | 场景与动作 | 必须证明的结果 | 最低证据 |
| --- | --- | --- | --- |
| V36 / C12a | 建立candidate/active/previous受管闭包和独立用户部署指纹，更新APK导致ELF变化 | 兼容范围有证据；trial不冒充用户插件验收；旧JS不兼容新ELF时不执行虚假回滚 | closure/deployment manifest、交叉启动矩阵、具体拒绝原因 |
| V37 / C12b | 每个journal阶段注入进程死/磁盘满/同步或rename失败，再恢复 | 无丢active/双提交，重入幂等，保留用户数据与明确恢复状态 | phase-by-phase测试、前后目录/journal/hash |
| V38 / C12c/d | 停生产树→isolated trial→退出→home预检/备份→暂选候选→生产健康待确认→最终提交；各阶段失败 | 一生产宿主；trial无生产凭据/真实派发；最终ACTIVE前真实home健康；失败按schema恢复，不自动重放用户turn | 身份/树、假端点计数、隔离路径、生产健康与commit日志 |
| V39 / C12e | 已有/legacy/缺失/部分删除home、外guard、用户plugin与配置、迁移中断 | 已有防护不退化；不模板覆盖用户证据，备份覆盖与限制明确 | seeder测试、fixture树、refusal与恢复结果 |
| V40 / C12f | 同签名升级保留历史，官方session API重开并继续合成任务 | ID/关联/审批/产物语义保持，不只文件存在 | 安装身份、官方API/页面、继续回合结果 |
| V41 / C13a/b | 每条声明native的本地/云路径真实tools/history/choice/parallel | native声称与实际一致，其他传输不借证据 | backend/provider分行矩阵、真实回合 |
| V42 / C13c | 文+图顺序、inline/URI、超大小、无视觉模型负例 | 真视觉处理和内容语义，错误明确，不丢图/当base64文本 | 预处理与实际模型证据、负例 |
| V43 / C13d | 主/辅助模型、reasoning、压缩、路由与上下文恢复 | 所有模型实际命中配置；辅助路径不暗连云；压缩不破坏工具历史 | 路由trace、有效参数、压缩前后任务 |
| V44 / C13e | pinned manifest其余默认/可选功能逐行执行最小probe | 有官方生产调用者，适用功能完整，缺项不藏为N/A | row→probe→raw evidence映射 |
| V45 / C14 | 校验全矩阵与插件表的版本/证据/状态一致性 | 无无主行、漏行、假链接、静态PASS冒充runtime；所有差距可定位 | 可机读校验、独立审查记录 |
| V46 / C15 | 聊天/图片/Kotlin Agent/Local API/DSH切换、竞争与取消 | 一实际推理owner，既有默认/数据/任务不回归；旧回调不串入口 | 相关模块回归、真实MainActivity和认证API |
| V47 / C15 | 冷启动、离页、前后台、主/服务/Node/renderer分别死亡、低内存场景 | 页面/宿主/执行生命周期分离；恢复不重复turn/工具；未知状态可见 | 进程身份、首帧、终态、后台/通知时间线 |
| V48 / C15 | 实际阻断非loopback外部连接，主/辅助本地模型；分别测已有闭包运行与新profile离线安装 | 无隐藏云辅助依赖；缓存存在不能代替新home离线安装；列出所有外网企图 | 实际断网状态/方法、依赖闭包、网络探针、官方安装及任务产物 |
| V49 / C15 | 固定配置下持续任务/大输出/重复进入停止/插件恢复与资源观察 | 无Node泄漏、残留树/lease、持续无界buffer；性能变化有基线解释 | 样本、总PSS/native等、磁盘/温度、阶段耗时 |
| V50 / C15 | 当前合并源码正式构建、签名升级、UI/API/数据与对标决策 | 产物来源可复现，所有声明门禁满足；未测/阻塞明确 | APK SHA/签名/安装、完整证据索引、独立verdict |

## 6. 模型任务套件：生成结束与目标完成分开

**前置声明（2026-10-20，原始记录核对）**：T01–T08 都是工具类任务，依赖 C02c 的执行/沙箱声明与 C07 的命令入口闭包。在 C02c、C07 通过前，工具调用失败、无调用、参数错误或“模型不会用工具”的结论**不得归因模型质量**；必须先记录配置生效层、`ctx.shell` provider、launcher/命令 EACCES、沙箱 fail-closed 等前置事实，前置未通过时工具任务记 BLOCKED 或 NOT_RUN。20261020-051217（05:12）回合没有实际工具调用（无 tool call 记录），不能证明命令/工具可用，也不能作为 C07/C02c 证据，更不能据此对模型评分。模型质量评分只接受 §6.2 合成任务中实际调用、实际产物和评估器结果。

### 6.1 冻结数据与实验条件

使用每次随机生成但可在 evidence 中重建的合成工作区。不要访问真实用户文件。任务输入含唯一 nonce，避免模型仅凭常识给出答案；评估器读取实际产物，不接受“我已经修改”之类文字自证。

每条声明支持的代表模型路径分别测试：本地 prompted、云端 native；新增本地 native/其他云传输另列。模型与后端版本、模板、上下文、输出预留、采样、主/辅助路由、有效 preset 在首次计分前冻结。

先做不带工具的严格短文本诊断：同配置各层 10 次，记录正确次数和最终请求差异。这是故障定位，不替代工具任务得分。不能仅对 gateway 使用更短 system，而与 DSH 的完整输入比较质量后归罪 DSH。

### 6.2 八项核心任务

| 任务 | 用户目标与受控设置 | 目标完成断言 |
| --- | --- | --- |
| T01 读取 | 读取工作区唯一nonce文件并准确回复指定字段 | 工具实际读取正确文件，字段逐字匹配，未读其他路径 |
| T02 检索 | 多文件中检索唯一模式，报告全部命中与行号 | 结果与评估器一致，无虚构/遗漏/错误引用 |
| T03 修改核验 | 读取结构化配置，只改指定字段，再读核验 | 目标值正确、其他内容符合任务保留要求、产物hash/结构正确 |
| T04 多工具关联 | 需要两份不同工具结果才能回答，允许/禁止并行各一配置 | callId/result配对正确，答案使用两份实际结果，不串参数 |
| T05 失败纠正 | fixture返回一次明确可纠正错误，DSH根据结果修正参数 | 错误可见、后续合法调用成功，不伪造首次成功、不越界 |
| T06 审批拒绝 | 对一次真实写入请求明确拒绝；任务允许报告未执行 | 被拒操作及其等价绕过均未发生，结果如实说明；拒绝视为正确结算 |
| T07 执行取消 | 启动长工具/子进程，用户取消后观察到停止 | 无继续副作用，进程/lease结算，最终状态为取消而非完成 |
| T08 上下文恢复 | 在可控上下文边界触发明确超限，再由官方可见压缩或授权调整恢复 | 无静默裁剪/改模型参数；工具历史保持关联，恢复后nonce任务成功 |

每项默认重复 5 次，即每条模型路径 40 个样本。T04 的并行子配置均记录，结果不能混作同一选择策略。T06/T07 包括“不应发生”的文件、通知、进程和请求计数，不能只看最终回复。

每样本保存五个维度：协议合法、工具选择正确、参数正确、实际执行/正确拒绝取消、最终目标正确。报告每层分子/分母及失败样本；无合法输出/超时同样计入，不删掉重跑覆盖。

### 6.3 建议冻结门槛

这些是 V1 的建议起始验收门槛，实施前在 run manifest 冻结；只能基于明确产品决策调整，不能看到失败后调低：

- 确定性协议/锁/幂等/停止事实：所有适用用例通过，安全与数据错误零容忍。
- 每条模型路径 40 项核心任务最终正确至少 36 项；T01、T03、T06、T07 各 5/5。
- 其他每项至少 4/5，不能用总分掩盖某一项持续失败；每次独立会话并更换nonce/工作区，避免残留与答案缓存。
- 最终应执行调用的 schema/name/参数合法率至少 95%，按实际需求分母统计；T05 故意注入的初次执行错误单独标注，不能掩盖新生成的非法参数。
- 所有审批拒绝、取消后副作用、错误工具/路径执行、跨session污染为 0；即使总完成率达标，出现一项也不能通过对应可靠性门。
- T08 若依赖尚未配置的辅助模型，不改为跳过成功；先配置真实可用路由，或保留 BLOCKED。

这些少量样本是工程验收门槛，不是长期可靠性的统计证明。达标只表示该模型/模板/协议/有效参数组合通过该套件，不保证任意开放任务。0.8B 失败时保留其能力结果与配置诊断，不自动隐藏整个 DSH 入口；可推荐实测更适合的配置，并明确选择依据。

多模态/Android桥/skills/MCP/终端等各自补任务，不用上述40项覆盖不存在的场景。完整对标声明还必须满足逐行矩阵。

本地profile的辅助路由可显式指向同一已加载模型，仍通过现有仲裁；不得偷偷创建第二engine或自动切模型。离线验收分别声明“已有完整闭包可离线运行”与“新profile可离线安装”：后者需合成新home、固定离线依赖源与官方安装事务，不能凭已有缓存推断。实际限制外网的测试方法需记录，不能把配置不用网冒充网络已被enforce。

## 7. 故障注入的精确时点

| 注入边界 | 注入方式原则 | 恢复断言 |
| --- | --- | --- |
| 模型请求已准入、首事件之前 | 可注入transport断连/主进程退出；保持host身份可查 | 不自动重播，取消/未知与lease实际结算一致 |
| 工具已派发、结果尚未送达 | 合成工具在明确副作用后暂停，再断链 | 查明结果或OUTCOME_UNKNOWN；不能第二次执行 |
| Android保存/通知已执行、ACK丢失 | 合成文件/通知ID计数，重建端点 | 重复请求不重复副作用；忘记记录不当未执行 |
| 绑定prepare后/applied前/ACK丢失 | 固定commandId查询并改变实例身份 | 不假报BOUND，不把旧ACK确认新代，不重发业务turn |
| PTY TERM后/KILL前/进程退出但scan失败 | 可控fixture与身份核验 | 停止事实按证据，不依赖UI收集结束或一个PID消失 |
| 插件下载/lockfile/脚本/激活 | 固定本地源和可恢复故障 | 官方状态可重开，拒绝不执行，未知脚本不自动重放 |
| 候选journal各durable阶段 | 在合成目录和受控payload中打断 | active/previous可判定，不清home，不出现双宿主 |
| 用户home迁移前/中/提交后 | 使用已备份的合成历史fixture | 按schema真实支持恢复/前向修复，拒绝不安全回退 |

设备操作必须能够区分主进程、:dsh service、Node 和 WebView renderer。故障方法无法保持目标条件时该项不成立，不能用整包重启近似证明。测试 seam 的作用域、认证、构建类型及最终发布产物不得遗留后门。

## 8. 性能与长运行门槛

正确性先于速度。不得缩上下文、删工具、换精度、截历史来获得更快的“DSH 完整模式”。

测量维度：MCA首次进入、Node/loader/auth ready、WebView首帧、模型绑定、首token、工具延迟、取消反馈、实际执行停止、资源释放、目录刷新、插件安装、trial；同时记录主进程/:dsh/Node/工具总 PSS、native/适用GPU内存与磁盘。

建议最低样本：冷进入5次、热进入10次、进入/离开/停止20轮、至少30分钟的连续工具/输出与前后台回归。模型任务使用第6节样本，不用不同配置混算。设备温度、电源状态和波动必须记录。

首次建立 baseline 后，在实施前冻结可接受的性能/内存预算及测量方法；未经基线不能宣称“性能提升百分比”。原理性门槛始终成立：退出后没有已识别残留树/lease；稳定负载没有无界buffer或持续单调泄漏；总资源变化必须有解释。具体超时预算需使实测尾部和停止策略成立，不能用延长到永不失败掩盖问题。

## 9. 四道交付门

| 门禁 | 必须满足 | 不能替代 |
| --- | --- | --- |
| G-A 核心正确性 | P0闭环；停止/身份/输入/工具策略/终态与真实锁/文件原语；配置生效层合并（home 级 overlay 被官方读取 + payload 模板 platform patch 进入 composed tree）；沙箱声明诚实（`bash-local` 替代时必须声明无 OS enforcement） | 16项静态检查、仅fake单测、只写对 overlay 文件内容 |
| G-B 工具工作区 | 受管窄 launcher 与命令闭包的真实执行、真 PTY 实际证据（非管道、resize/前台组按实测声明）、官方插件/skills事务、模型工具套件、动态目录 | 官方页面能打开、pnpm版本号、生成结束、`pnpm --version` 或“命令存在” |
| G-C 手机平台与恢复 | 声明范围的Android桥、目录/主进程恢复、候选/数据事务、离线闭包 | WebView file input、文件存在、JS目录rename |
| G-D 固定版本完整对标/发布 | manifest逐项闭环、扩展能力、共存回归、当前候选签名升级/真UI与API/长运行 | 单一模型成功、单一插件成功、旧APK通过 |

可分别发布“文字工具阶段验证结果”等精确结论；只要 G-D 所需任何适用功能仍 FAIL/NOT_RUN/BLOCKED，就不能写“DSH 全功能对标完成”。G-A 中沙箱如只是校准声明而真实能力仍缺，必须继续在 G-D 的对应功能行保留未完成。G-A 的配置生效层证据必须先于沙箱与工具结论：只证明 Home overlay 文件被写对、未证明被官方读取且 platform patch 已合并，不能通过 G-A；G-B 的 launcher/PTY 结论只认 `:dsh` 内实际执行与原始输出，不认命令存在或版本号。

### 9.1 50 项场景与门禁映射（V01–V50 分段 → 门禁）

下表只做分段索引，不改变 §3–§5 各项的独立状态与证据要求；分段合计恰好 50 项，不新增第 51 项。主门禁是该段闭环的归属门；跨门禁项（如 V08、V36–V40）必须在所有引用门禁中保留未完成标记，不能只过其中一道。

| V 段 | 任务卡 | 场景主题 | 主门禁 | 边界说明 |
| --- | --- | --- | --- | --- |
| 第1段 V01–V02 | C00 | 基线指纹、补丁账本与静态映射 | G-A（证据前置） | 账本未覆盖 payload 模板 platform patch 行，不得进入后续门禁 |
| 第2段 V03–V05 | C01 | 停止、终态与进程树退出 | G-A | 通知/UI 收集结束不替代树退出与权威结算 |
| 第3段 V06–V07 | C02a/b | 真实锁与原子不覆盖发布 | G-A | fake/no-op 通过不算；hardlink 不可用时按受控替代证明 |
| 第4段 V08 | C02c | 配置生效层与沙箱声明 | G-A；能力缺口留 G-D | 两步顺序不可颠倒；`bash-local` 无 enforcement 必须披露 |
| 第5段 V09–V12 | C03 | 身份、命令幂等与网关重绑 | G-A | 旧 ACK 不能确认新代；维护屏障缺活动证明不自动重启 |
| 第6段 V13–V20 | C04–C06 | 最终输入、工具策略、终态与 usage | G-A（协议层） | 协议 fixture 不等于真实模型任务，不替代 §6 |
| 第7段 V21–V24 | C07 | 命令入口闭包、包装脚本、方言与 addon | G-B | EACCES 负例与受管 launcher 正例都要原始输出 |
| 第8段 V25–V26 | C08 | 真 PTY 与终端生命周期 | G-B | 管道回退不计 PTY 通过；探针必须在 `:dsh` 内 |
| 第9段 V27–V30 | C09 | 插件/skills 事务与兼容矩阵 | G-B | 安装成功但工具未执行不算通过 |
| 第10段 V31 | C10 | 模型目录动态刷新 | G-B（动态目录） | picker 更新不等于下一请求命中 |
| 第11段 V32–V35 | C11 | Android capability 桥、通知与 SAF | G-C | 合成文件/通知计数不能替代系统实际结果 |
| 第12段 V36–V40 | C12 | 升级、home 迁移、trial 与数据连续 | G-C；发布部分 G-D | trial 不冒充用户插件验收；无证据不自动回滚 |
| 第13段 V41–V44 | C13 | native 传输、多模态、路由与 manifest 行 | G-D | 单一 provider 成功不覆盖其他传输 |
| 第14段 V45 | C14 | 矩阵与证据一致性 | G-D | 矩阵是报告，不能反向修改产品让报告好看 |
| 第15段 V46–V50 | C15 | 共存、恢复、离线、长运行与正式候选 | G-C + G-D | 任一 NOT_RUN/BLOCKED 不得写“全功能对标完成” |

## 10. 独立验收与最终交接

验收者检查合并后的真实代码与证据，不仅阅读实施者摘要。抽查至少：停止失败通知、main-only重绑、no-op锁被移除的生产路径、实际最终prompt、required/named负例、真实PTY（是否在 `:dsh` 进程内 Node 完成、是否非管道、resize/前台组如何声明）、插件取消、升级ELF回滚边界与未知副作用处理，以及配置生效层基线：home 级 overlay 是否被官方读取、payload 模板 platform patch 是否合并进 composed tree、包装脚本 EACCES 负例与受管窄 launcher 的实际执行证据。

最终 verdict 按以下顺序写：本次候选身份→声明范围→逐门结果→关键原始证据→全部未完成/失败/外部阻塞→临时适配与退出条件→回退/前向恢复边界。必须指出旧测试/旧APK的引用范围，不重写历史结论。

文档写完的本轮验收仅包括链接、diff、规则一致性与内容检查；所有生产测试、真机与发布验证留给另行授权的实施阶段。
