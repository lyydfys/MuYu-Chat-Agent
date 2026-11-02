# DSH Completion V1 — 证据格式与状态约定（C00 固化）

本文件把验收规范的证据要求固化为可直接执行的格式，供 C01–C15 与 C14 引用。

- 主要依据：[`DSH_COMPLETION_ACCEPTANCE_V1.md`](../DSH_COMPLETION_ACCEPTANCE_V1.md) **§1（状态与证据优先级，行 9–17）** 与 **§2（证据目录与最小记录，行 19–79）**。
- 补充依据：[`MCA_PROJECT_HARD_LOCK.md`](../../../architecture/MCA_PROJECT_HARD_LOCK.md) **§12.4 禁止伪造绿灯（行 313–318）**。
- 本文件不新增验收项，不改变 V01–V50 编号；只规定“怎么记”。

## 1. 每次运行的证据目录

每次真实运行建独立目录，不覆盖历史 run：

```text
artifacts/dsh-completion/<YYYYMMDD-HHMMSS>-<runId>/
  run.json
  identity/    # 工作树/子模块补丁/依赖闭包/APK/签名/设备软件身份
  tests/       # 命令、XML、stdout/stderr、计数、退出码
  runtime/     # 脱敏的 host/gateway/call 身份、绑定与目录修订、树/终态证明
  model/       # 合成输入、结构化实际参数、协议结果、任务产物摘要
  plugins/     # 版本、依赖、审批、安装/取消/重开证据
  faults/      # 注入位置、触发时间、恢复路径
  shots/       # 必要 UI/通知/首帧证据
  outputs/     # 可分发的合成产物及校验和
  verdict.md
```

约定：

- 目录名用本地时间 `YYYYMMDD-HHMMSS` + 唯一 `runId`；**不复用、不追加进旧 run 目录**。
- 本地证据默认不提交二进制、私有内容、完整设备标识（`deviceAlias` 脱敏）；可分发索引只引用脱敏摘要。
- 凭据、bearer、launch URL、cookie、用户全文不进入 fixture/命令行/日志/截图。
- `identity/` 必须包含工作树指纹与三个子模块内部补丁身份；只记父仓 HEAD 不足以复现（硬锁 §11）。

## 2. `run.json` 最小字段

字段集合与含义以验收规范 §2（行 39–79）为准；下表把“必填/可为空”写清楚。示例值都是说明，**不能复制成真实 PASS**。

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `schemaVersion` | int | 是 | 当前为 `1`；字段结构变化时递增 |
| `runId` | string | 是 | 本次唯一 ID，与目录名后缀一致 |
| `startedAt` | string(ISO+时区) | 是 | 真实开始时间，如 `2026-10-20T05:12:00+08:00` |
| `endedAt` | string(ISO+时区) \| null | 是 | 未结束/未跑完必须 `null`，不得填计划时间 |
| `source.head` | string | 是 | 实际 HEAD SHA（不是分支名） |
| `source.worktreeFingerprint` | string | 是 | 含未跟踪生产输入的指纹；不能只覆盖 tracked diff |
| `source.submodules[]` | array | 是 | 每项 `{path, revision, patchFingerprint}`；无内部补丁也要写出实际指纹 |
| `candidate.apkSha256` | string | 是 | 被测 APK 的实际 sha256；未构建/未安装则整块 `null` 并说明 |
| `candidate.signerSha256` | string | 是* | 设备/发布 run 必填；纯 JVM run 可为 `null` |
| `candidate.runtimeClosureId` | string | 是* | payload/宿主/MCA 的完整运行闭包身份；仅 JVM 测试可为 `null` |
| `candidate.dshRevision` | string | 是 | payload 固定的 DSH 源 revision（`687f282e…`）或实际替换值 |
| `candidate.profile` | string | 是 | 实际 profile 与配置版本（如 `web` + overlay 版本） |
| `environment.deviceAlias` | string | 是* | 设备 run 必填（脱敏）；JVM run 用 `null` |
| `environment.sdk` | int \| null | 是 | 实际 SDK；未知必须 `null`，不得猜 |
| `environment.abi` | string \| null | 是 | 实际 ABI（如 `arm64-v8a`） |
| `cases[]` | array | 是 | 每个 V 项/探针一条；测试类 run 可一条覆盖多个断言并写清 `scope` |
| `cases[].id` | string | 是 | V01–V50 / 探针 id（如 `V08`、`P1`） |
| `cases[].scope` | string | 是 | `JVM` / `device` / `provider` / `backend` / `static` 等 |
| `cases[].status` | enum | 是 | 五状态词之一，见 §3 |
| `cases[].expected` | string | 是 | 可检查的期望结果（不是“工作正常”） |
| `cases[].actual` | string \| null | 是 | 实际观察；未跑为 `null` |
| `cases[].commandRef` | string \| null | 是 | 指向 `tests/` 内的命令记录，或完整命令 |
| `cases[].testCount` | int \| null | 是 | 实际执行/匹配数；0 不等于通过 |
| `cases[].exitCode` | int \| null | 是 | 真实退出码；未跑为 `null` |
| `cases[].evidenceRefs` | string[] | 是 | 证据文件相对路径 + 行号/命令；可为空数组但 `actual` 必须说明 |
| `cases[].blockedReason` | string \| null | 是 | 仅 `BLOCKED` 时必填具体条件 |

\* 标记为“是*”的字段：对不适用的 run 必须显式写 `null`，不能省略。

另需在本 run 的 `identity/` 或 `verdict.md` 记录（验收规范 §2 末尾要求）：模型资产/模板/量化/后端/有效参数/工具协议/主辅助模型路由/采样值；插件版本与 lock；包与 ELF 依赖。终态只记录安全的 host/gateway/call 身份，不以 token hash 替代安全身份。

## 3. 状态词定义与适用约定

全局只允许五个词：`PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`。

| 状态 | 何时使用 | 必须附带 | 禁止 |
| --- | --- | --- | --- |
| `PASS` | 本次 run 在指定 revision/APK/配置/范围内真实执行并通过，满足 `expected` | 实际命令、退出码、证据路径、APK/revision、范围 | 用旧 run/旧 APK 的通过；用 stub/fake 代替生产链路；用“没有报错”代替断言通过 |
| `FAIL` | 本次 run 真实执行且结果不满足 `expected`，或生产路径缺失且已由源码证据定位 | 实际结果、期望结果、证据、范围 | 把未运行当失败；把模型质量离题（`MODEL_QUALITY_ONLY`）当协议失败 |
| `NOT_RUN` | 尚未执行/未覆盖，或执行被跳过 | 为什么没跑（计划、缺授权、环境未就绪） | 用“未测试”当拒绝理由；把 `NOT_RUN` 汇总成通过 |
| `BLOCKED` | 有具体外部条件导致无法执行且短期不可解 | `blockedReason` 写具体条件（如上游 503、缺可审计 ELF 源、平台原语不可用） | 用 `BLOCKED` 掩盖没排期 |
| `NOT_APPLICABLE` | pinned 功能/profile/平台确实不存在该场景 | 实质理由（pinned 版本/平台差异） | 用来隐藏未实现项 |

补充约定：

- 一项可有独立的“诊断已完成/实现已合入/平台已验证/发布已验证”子字段（验收规范 §1），但**不得合并成一个笼统完成状态**。
- 源码风险成立 ≠ 真机故障已复现；fake 通过 ≠ 真实 provider/ELF/模型通过。
- 失败必须能归因：区分参数/资产问题、平台失败、网络失败、内部异常；不能吞掉原始错误。

## 4. 旧 PASS 不得当本次（硬规则）

依据验收规范 §1 与硬锁 §12.4：

1. **旧证据只能引用，不能结转。** 引用时必须写明原 revision、原 APK sha256、原 run 日期与“为何仍适用”；不得改日期、改 runId 冒充本次。
2. **源变了就是 `NOT_RUN`。** 只要 HEAD、工作树指纹、子模块补丁、payload sha256、APK sha256、seeder/overlay 版本、模型资产或有效参数任一变化，受影响项必须重跑；未跑写 `NOT_RUN`。
3. **测试类 PASS 必须证明“真的执行了”。** 记录匹配到的测试数、执行数、跳过数、失败数、退出码；过滤词无匹配或空报告不能写 PASS。
4. **聚合结果不能替代原始日志。** 聚合脚本分級不是“生产全通过”；不得自行提升为 10 分或发布通过。
5. **历史冲突显式记录。** 旧汇总与新原始证据冲突时，以原始 run 证据为准，并在 `verdict.md` 写冲突说明，不批量改旧汇总。

### 4.1 C00 相关示例（防止误用）

- `artifacts/dsh-verify/20261020-051217` 的 PASS 只属于 APK `b610e1d0…`；当前 `app-release.apk`（`70746C4C…`，12:15:48）在重新安装验证前只能是 `NOT_RUN`。
- `PATCH_LEDGER.json` 的 `DIAGNOSED` 表示“源码/归档已定位”，**不是**运行时 `PASS`；对应探针未跑时仍是 `NOT_RUN`。
- C02c 的平台 patch 合并目前只有源码/测试（未构建、未设备验证），所以 V02/V08 仍是 `NOT_RUN`。

## 5. 证据引用格式

`cases[].evidenceRefs` / `PATCH_LEDGER.evidence` 统一用以下可复核形式：

```text
<相对仓库根路径>#L<起>-L<止>                 # 例如 core/dsh-runtime/.../DshGatewayConfigSeeder.kt#L567-L631
payload.zip::<payload 内相对路径>:<起>-<止>    # payload 归档内文件，行号指文件自身
<命令> → <关键输出/退出码>                     # 可复制的命令证据
sha256(<文件>)=<64 hex> size=<字节>            # 身份证据
artifacts/dsh-verify/<run>/<file>#L<n>          # 历史 run 原始证据
```

规则：

- 文件证据必须给行号区间；只给文件名不算可复核。
- 命令证据必须含实际退出码；`0` 与“未执行”不同。
- 截图证据必须指明对应 dump/节点文本（Compose UI 不能用像素猜状态）。
- 归档内证据必须带 sha256：payload 更新后旧行号/旧 hash 必须能被识别为失效。

## 6. `verdict.md` 最小内容

```text
runId / 日期 / 设备或 JVM 环境
source：HEAD + worktreeFingerprint + 子模块指纹
candidate：APK sha256 + signer + runtimeClosureId + dshRevision + profile
cases：id | scope | status | 证据 | 备注
汇总：PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE 各自数量
未运行/阻塞：逐条写具体条件
冲突与例外：与旧证据/旧摘要的差异
结论边界：本次不覆盖什么
```

## 7. 禁止清单（摘自硬锁 §12.4，执行时逐条核对）

- 不删测试、不减断言、不扩大 suppress、不改“总是成功”。
- 不接受空报告、不盲刷 golden、不拿旧 XML 冒充新运行。
- 不用 fake executor / SDK stub 的通过替代真实生产链路。
- 不把 `-SkipTests` 编译、Preview、编译成功写成行为/UI 通过。
- 不把“未测试/未知设备/未认证”当作拒绝或失败理由。
