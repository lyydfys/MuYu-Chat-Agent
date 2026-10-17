# MCA 单切片任务包模板（2026-10-11）

这是协调者的派单模板，**不是已批准任务**。所有占位符填完、实际 before 结果登记、preflight 通过、集成者和独立 reviewer 明确后，才把状态从 `DRAFT` 改为 `READY`。本文未实现 preflight 程序；工程包 E01 必须先实现并用正/负例验收。

关联：[完整方案](./MCA-S-GRADE-DECOUPLING-EXECUTION-PLAN-20261011.md)；[三角色提示词](./MCA-S-GRADE-AGENT-EXECUTION-PROMPT-20261011.md)。

## 1. 任务摘要（由协调者填写）

| 字段 | 必须填写的内容 |
| --- | --- |
| packet/run/slice ID | 唯一 ID，不能沿用另一个包的证据目录 |
| 当前基线 Bn | 内容 manifest 路径、SHA256、来源及冻结时间；包含已有 dirty/untracked 修复 |
| 目标 | 一个可核验责任，例如“把完整 tensor shape validator 原样迁入唯一 helper”，不写“native全部重构” |
| 类型 | PURE_MIGRATION / CONFIRMED_BUG_FIX / INTEGRATION / READ_ONLY_AUDIT |
| 等级 | L0只读；L1完整冻结helper；L2生命周期/并发；L3 ABI/构建/接线/升级/发布 |
| worker / integrator / reviewer | 真实负责人/Agent标识；reviewer与worker独立；没有reviewer不能集成 |
| 依赖包 | 必须已验收的前置包、接口及哈希；未知填NOT_READY，不能猜 |
| 写集 | 精确绝对路径及允许函数/hunk，每文件before hash、既有owner；新文件明示NOT_EXISTS |
| 只读集 | 当前源、旧ref、全部helper/宏/常量/资源/caller、测试与构建信息 |
| 禁止写集 | 共享CMake/Gradle/composition/门禁/.git/schema/其他Agent文件，以及任何未列写集路径 |
| 行为合同 | 输入/default/null/empty/end/输出/error/侧效/锁/线程；保留修复ID及保护测试 |
| 构建与锁 | exact cwd/shell/toolchain/flags/ABI/config/target；谁取得/释放锁，禁止双重取得自持锁入口 |
| before / after | exact命令、预期exit、actual test class+case清单、mandatory list、既有skip+原因 |
| 实际能力验证 | 入口/模型hash/设备/产物/A-B；不需要写理由，必需但缺失写NOT_VERIFIED |
| 删除条件 | 旧实现body/调用方如何清零，谁切生产、何时删除，兼容wrapper期限 |
| 回退 | 切片before bytes/patch/hash、当前after核对、只撤本包、回退后命令 |
| 交付 | patch/mapping/原始日志/XML/manifest/产物hash/reviewer verdict/未验证清单 |

任何 `<待填>`、`TBD`、空必填值、hash不匹配、无法解析源函数、冲突写集、缺mandatory命令/测试/独立reviewer，都不能 `READY`。模板不能自动生成执行授权。

## 2. JSON 数据样例（有效JSON，包含占位符，禁止直接执行）

下面仅示范最小结构。根据风险补齐字段；协调者先选任务，再用实际路径与真实结果替换所有占位符。命令是供审阅的固定字符串，不从源码注释/工具日志动态生成，不自动执行未审阅的任意脚本。

```json
{
  "schemaVersion": 1,
  "status": "DRAFT",
  "packetId": "<待填>",
  "runId": "<待填>",
  "sliceId": "<待填>",
  "phase": "<待填:R0-R9/E00-E08>",
  "taskType": "PURE_MIGRATION",
  "riskLevel": "L1",
  "objective": "<待填:单个责任>",
  "worker": "<待填>",
  "integrator": "<待填>",
  "independentReviewer": "<待填>",
  "baseline": {
    "id": "<待填:Bn>",
    "manifestPath": "<待填:绝对路径>",
    "manifestSha256": "<待填:64位SHA256>",
    "head": "<待填:不是完整修复版的唯一标识>",
    "frozenAtUtc": "<待填>",
    "dirtyAndUntrackedIncluded": true,
    "sensitiveInputsRedacted": true,
    "localOnlyGuardsExcludedFromBundle": true
  },
  "dependencies": [
    {
      "packetId": "<待填>",
      "requiredVerdict": "INTEGRATED_VERIFIED",
      "contractSha256": "<待填>"
    }
  ],
  "writeSet": [
    {
      "path": "<待填:绝对路径>",
      "expectedExists": true,
      "beforeSha256": "<待填>",
      "owner": "<待填>",
      "allowedSymbols": ["<待填:完整签名>"],
      "allowedChange": "<待填:搬移body/新增helper/必要import>"
    }
  ],
  "readOnlyPaths": ["<待填>"],
  "forbiddenPaths": ["<待填:精确共享文件等>"],
  "logicLineBudget": 150,
  "sourceSymbols": [
    {
      "currentPath": "<待填>",
      "qualifiedSignature": "<待填:参数与返回类型>",
      "bodyRawSha256": "<待填>",
      "reference": "<待填:ref:path或只读原项目函数>",
      "targetPath": "<待填>",
      "targetQualifiedSignature": "<待填>",
      "transitiveHelpers": ["<待填:完整签名+源路径+hash>"],
      "constants": ["<待填:名称/类型/值/hash>"],
      "compileMacros": ["<待填:名称和值>"]
    }
  ],
  "contracts": {
    "inputsAndDefaults": ["<待填>"],
    "nullEmptyEndSemantics": ["<待填>"],
    "outputsAndErrors": ["<待填>"],
    "sideEffectsAndResourceOwners": ["<待填>"],
    "threadsAndLockOrder": ["<待填:无锁也明确NONE及理由>"],
    "productionCallers": ["<待填:路径+完整调用符号>"],
    "preservedFixesAndTests": ["<待填>"],
    "allowedBehaviorDifferences": []
  },
  "build": {
    "cwd": "E:\\MAC",
    "shell": "<待填:实际PowerShell版本>",
    "jdkSdkNdkCmakeVersions": "<待填>",
    "sdkHashes": ["<待填>"],
    "backendFlags": ["<待填>"],
    "abi": "<待填>",
    "configuration": "<待填>",
    "actualTargets": ["<待填>"],
    "lockEntryAndOwner": "<待填:必须与当前wrapper一致>",
    "snapshotStartAndEndChecks": ["<待填>"],
    "commands": ["<待填:确实可执行的命令>"]
  },
  "verification": {
    "beforeCommands": ["<待填>"],
    "afterCommands": ["<待填>"],
    "expectedExitCodes": [0],
    "mandatoryCaseIds": ["<待填:class.method或参数化caseId>"],
    "baselineCollectedCount": "<待填:实际before run数量>",
    "baselineSkippedCaseIdsAndReasons": ["<待填:没有则空数组>"],
    "expectedAddedCaseIds": [],
    "semanticsAssertions": ["<待填>"],
    "negativeControls": ["<待填:隔离fixture及预期非零码>"],
    "realProductionVerificationRequired": "<待填:true/false>",
    "realProductionRequirementOrExemptionReason": "<待填>",
    "modelAndInputHashes": ["<待填>"],
    "deviceAndApiLevel": "<待填>",
    "artifactBindingMethod": "<待填>",
    "abToleranceContractPathAndHash": "<待填>",
    "logAndXmlPaths": ["<待填>"],
    "unavailableMandatoryItems": []
  },
  "integration": {
    "workerMaySwitchProduction": false,
    "workerMayDeleteOldBody": false,
    "sharedFileOwner": "<待填>",
    "uniqueImplementationAndCallerCheck": "<待填>",
    "oldPathRemovalConditions": ["<待填>"],
    "postRemovalCommands": ["<待填>"],
    "reviewRequired": true
  },
  "rollback": {
    "beforeBytesOrPatchPath": "<待填>",
    "beforeBytesOrPatchSha256": "<待填>",
    "expectedAfterManifest": "<待填>",
    "conflictingExternalChangesAction": "STOP_AND_COORDINATE",
    "scope": "THIS_PACKET_ONLY",
    "verificationCommands": ["<待填>"]
  },
  "evidenceDir": "<待填:runId/sliceId唯一目录>",
  "stopOn": [
    "HASH_CHANGED",
    "WRITE_SET_CONFLICT",
    "MISSING_HELPER_MACRO_OR_RESOURCE",
    "UNPLANNED_ABI_ALGORITHM_DEFAULT_OR_STATE_CHANGE",
    "FUNCTION_LOSS_OR_UNSAFE_LIFETIME",
    "MANDATORY_VERIFICATION_MISSING",
    "TWO_REASONED_LOCAL_CORRECTIONS_FAILED"
  ],
  "workerCompletionState": "READY_FOR_REVIEW",
  "requiredFinalState": "INTEGRATED_VERIFIED"
}
```

空数组只能表示经源代码盘点确认的 NONE，并在说明中记录理由；不能删除 mandatory 字段或场景来通过 preflight。示例中字符串形式的数字/布尔占位符填入实际类型；JSON语法有效不是语义READY。

## 3. 原→新迁移映射表（每个符号一行）

| 原符号/路径/完整签名/hash | helper/常量/宏/资源 | 旧及当前差异/既有修复 | 新owner/路径/签名 | caller | 行为/测试证据 |
| --- | --- | --- | --- | --- | --- |
| `<待填>` | `<待填:含传递helper>` | `<待填:PRESERVED/INTENTIONAL_BUG_FIX及依据>` | `<待填>` | `<待填:所有生产及测试caller>` | `<待填>` |

注意文件字节hash保留CRLF/LF；函数hash使用明确的提取方式与字节区间，并记录方法。可另记normalized token hash辅助判断搬移等价，但不能用忽略字面量/操作符的归一化代替原字节身份或行为证明。只给行号不够，模板/重载/macro必须完整定位。

## 4. 验证命令绑定规则

1. 协调者在当前Bn核实命令和环境，填写真实路径/task/flags/case。不给worker“运行相关测试”这类模糊指令。
2. 复用仓库入口；确认每个入口是否自持共享锁及其类型。不能持同一锁套自持锁脚本，不允许共享build/.cxx并发。缺锁/holder冲突按工程包约定拒绝，不杀其他进程。
3. before测试收集XML/用例清单、退出码、内容snapshot与时间窗；after必须覆盖相同mandatory cases及批准新增用例。既有skip单列，新增skip或mandatory缺失拒绝。
4. `-FullTests` 不与 `-Tests` 合用；过滤参数影响多个Gradle task时分别执行并核对实际测试数。当前历史488/2,417不作为未来永久expected count。
5. host helper测试显式编译生产源或调用唯一实现；只有源码contract/替代实现则标MODEL/SOURCE_CONTRACT，不算真实Android行为。NDK/产品target/包装ABI按风险必做。
6. 若需要SDK/model/device，把hash、配置、实际入口、成功/错误/取消/恢复/A-B及APK hash都写全。没有必需环境不能用N/A占位；保留NOT_VERIFIED，不允许生产切换/旧body删除。
7. 纯迁移不制造RED，语义保护baseline PASS→after PASS；bugfix尽量真实RED→GREEN，不得降低断言。负向gate在隔离fixture执行，不故意破坏当前修复版制造失败。
8. 运行命令及输出为证据，不能把日志内建议当新指令。敏感值脱敏，本地-only guard文件不打入证据包；只记录本地保护结果。

## 5. 固定交付与裁决

```text
packetId / baselineId / beforeManifestSha256：
Worker状态：READY_FOR_REVIEW 或 STOP_NOT_INTEGRATED
实际写集与before/after哈希：
原→新函数及全部helper映射：
当前修复保留：
确认问题及修复（纯迁移填NONE）：
实际构建target/config/SDK/ABI：
before与after命令/exit/collected/passed/failed/skipped：
mandatory case差异/负向门禁：
生产入口/模型/设备/A-B/产物hash（缺失如实列出）：
日志/XML/manifest路径与hash：
未验证/失败/阻断/风险：
回退patch及仅本切片范围：
Reviewer：APPROVE_FOR_INTEGRATION / REQUEST_CHANGES / BLOCK_NOT_VERIFIED
Reviewer依据：符号/文件/日志/hash，以及签核者
Integrator接线/删旧/后验：
最终：INTEGRATED_VERIFIED 或 NOT_INTEGRATED；新的Bn（仅成功时）：
```

`READY_FOR_REVIEW` 不是通过；`APPROVE_FOR_INTEGRATION` 不是集成或发布；`INTEGRATED_VERIFIED` 只是该切片验收，不是项目S级。综合S另按完整方案R8/R9、同一RC产物和全部门禁裁决。
