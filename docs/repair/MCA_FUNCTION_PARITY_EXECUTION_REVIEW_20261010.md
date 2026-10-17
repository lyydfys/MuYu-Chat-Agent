# MCA 修复方案执行复核与补充意见

复核日期：2026-10-10，Asia/Shanghai。状态截点：**北京时间 01:30 左右**。工作区仍在被其他执行者修改，本文件是这一截点的复核，不替代后续构建结果。

**结论：已有明显、真实的修复进展，但只能认定 PARTIAL_VERIFIED。原有生产链得到恢复，部分实际模型执行已经成功；完整功能对等、最新 APK 交付和稳定性验收尚未完成。新请求模块的几个问题仍可复现，不能全部标为关闭。**

本轮仅核查源码和执行证据，并在临时目录重新编译两份现有生产 Kotlin 源码进行隔离复现；没有修改业务代码、仓库测试或现有修复方案，没有启动全工程 Gradle/设备安装，也没有向其他 Agent 发送消息。新增内容为本复核报告与本地复现证据。

依据：[修复方案](/E:/MAC/docs/repair/MCA_FUNCTION_PARITY_BUGFIX_PLAN_20261009.md)、[执行方修复报告](/E:/MAC/artifacts/repair/20261009-parity/final/repair-report.md)、最新构建记录、源码与各任务的原始日志。不能只读取约 00:31 更新的总报告，因为后续源码与构建状态已变化。

## 1. 阶段执行情况

| 阶段 | 当前可确认进展 | 仍需完成 |
| --- | --- | --- |
| F0 快照/参考 | 已有备份、reference/JNI/生产 caller 映射、冻结清单与 amendments；HEAD 仍为 afcc9c0a，修复大量在 dirty/untracked 工作树 | 最终可恢复的完整变更集与同快照功能清单，不以 HEAD 代替当前树 |
| F1 native 恢复 | MNN 真 Llm/Sana/SD1.5 实现恢复；64/64 JNI export 对账；QNN mutex 单一定义；真实 enabled 原生链接；MTP owner 红→绿 | MNN chat/Sana/SD1.5、QNN 真模型及真实 MTP 设备执行仍缺证据；最新改动尚无完成的 APK |
| F2 请求与回调 | 跨线程 lease、取消 reason/deadline、epoch 定向 drain、SSE overflow 明确失败等已改善 | RequestRegistry 双重状态/外部可变对象仍在；旧终态污染新 attempt；callback 顺序与记录身份仍不安全 |
| F3 产品/数据 | 恢复 stageContentImportPreview；修 coroutine 未启动时前台 lease 泄漏；首版升级数据保持；既有入口未被空 Facade 替换 | 完整原业务矩阵、原版 A/B、最新 APK 的 UI/API/Worker/数据复测 |
| F4 测试/门禁 | 新鲜 JVM 14 模块 3,775 tests、0 failures/errors、10 skipped；native sampler 后续 13 passed/1 skipped；app/api lint 后续成功 | 后续生产改动的定向测试与最终绑定；2 个未接线 SD 测试；缓存文本合同；生产告警逐项结算 |
| F5 设备/短稳 | build1 有认证 API 实际 GGUF 文本/SSE、SDCPP 实际 PNG、取消 409；真实诊断 JNI/错误用例 | build3/final artifact 的全部适用复测、3 次正常图像、取消/重试循环、worker death、原版 A/B、2 小时短稳 |
| F6 交付 | build1 APK 已产出并做签名/ABI/JNI/ELF 核验 | **build3 于 01:27 失败，无最新修复 APK。build1 只能作被替代的诊断/对照版本** |

不提供“完成百分之多少”。功能恢复和“代码修好/编译通过/真实执行/产物验收”是不同维度，缺模型的关键能力不能按源码恢复就计完全完成。

## 2. 实质修复与质量评价

### 2.1 MNN 恢复是真实恢复，不再是成功骨架

当前 [mnn_native_engine.cpp](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:5405)恢复 `Llm::createLLM` 和真实生成链；[Sana 路径](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:7366)使用现有 `MnnSanaSession`；[SD1.5 scheduler](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:4682)重新消费 guided prediction 并检查失败。

20 个丢失 JNI 已恢复，首版 APK 对账为 MNN 28、GGUF 14、QNN 14、SDCPP 8，共 64；诊断库真机 harness 还有 idle/错误调用证据。这比加空 export 或返回 true 有本质改善。但 idle JNI 调用不能证明正常模型 load/run；诊断 `.so` 的设备结果也不能自动继承给字节不同的 APK。

为恢复完整性，桥回升到约 7.7k 行合理，符合用户暂停重构的要求。CMake 暂时断开八个残缺抽取模块，并由恢复的单一真实桥承接功能，原则上也符合临时恢复单一路径；要继续用 caller/function 清单证明没有只在被排除模块中的原有能力。不能将其说成 native 解耦已经完成。

### 2.2 QNN 和 MTP 修复依据充分

QNN mutex 现在在同一 namespace 声明、现有 `.cpp` 唯一定义，桥明确引用；真实 SDK 的链接和符号检查有证据。另发现的 `qnn_native_effective_json` 缺定义，也按原源码恢复。修复超出了仅语法检查，方向正确。

[MTP environment](/E:/MAC/core/native/src/main/cpp/native_engine.cpp:811)保留 session owner，只借用 pointer，避免临时 environment 析构丢失 owner。保护测试从 134 checks/52 failures 到 156/0，是有意义的红→绿。设备上现有 GGUF 没有 nextn/MTP 层，因此普通 GGUF 成功不能代替真实 MTP 证明。

### 2.3 有真实产品执行证据，但目前绑定 build1

首版 APK SHA-256：`48bd388ac1e7a1c6f0a75b808c4b8de177c58e00bbdf2d3239ac119b68de20f1`，414,418,395 bytes，本轮独立复算一致。

- `device/P2-chat/chat-real-out.txt`：认证 API HTTP 200，真实 60 completion tokens，非空中文回答。
- `device/P2-chat/stream-out.txt`：3 个 SSE chunks、1 个 DONE，约 9.7 秒结束。尚不足以证明慢消费者/长流/取消/重连全正常。
- `device/P2-image/img1.json`：HTTP 200，SDCPP 四步执行，返回真实 512×512 PNG。独立 base64/PNG signature、全部 chunk CRC、IDAT 解压检查通过，PNG 461,343 bytes；这是实际图片产物，不仅是 ok/path 字段。
- `device/P2-image/img-cancel-out.txt`：停止后 generation HTTP 409，`image_generation_cancelled`。

图片检查证据：[build1-image-artifact-check.json](/E:/MAC/artifacts/repair-review/20261010/build1-image-artifact-check.json)。它证明该首版图像产物有效，不证明所有参数/图像选项与原版数值对等，也不能证明最新源码。

已有 T08 build-lineage 记录明确 build1 已被后续修改替代；最终报告也必须同步，不能保留旧总表中的“当前交付 APK”口径。

## 3. 新复现：请求模块尚未完全修好

这些类与新 Facade 目前尚未生产接线。因此以下是**未接线代码中确认的缺陷**，不冒充用户现有生产聊天已经发生同样故障；可以保持隔离继续验证现有生产链，但问题状态不能填写 CLOSED。

复现方式：先复制当前编译出的 core:engine classes；再使用缓存 Kotlin 2.3.21 编译器，在临时目录直接重新编译当前 `RequestRegistry.kt` 和 `CallbackRouter.kt`，以这两份新 class 优先执行 Java harness。无需 Gradle，无业务源码修改。source recompile exit 0，probe exit 0；probe 输出的是实际观察到的违反合同状态，退出 0 代表复现正常执行，不代表被测代码通过。

证据：[request-risk-reproduction.json](/E:/MAC/artifacts/repair-review/20261010/request-risk-reproduction.json)，[可重复运行的 harness](/E:/MAC/artifacts/repair-review/20261010/RepairResidualProbe.java)。source/class hashes 已记录。

### REV-01：只增加 snapshot，仍未收口 Registry 所有权

定位：[RequestRegistry.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/RequestRegistry.kt:32)。

`RequestEntry.stateMachine` 和 `terminalReached` 仍公开可变，`register` 返回 entry，`getEntry` 仍可取出它；`isActive` 只检查第二个终态字段。复现：外部调用 `getEntry(...).stateMachine.abort()` 后：

```text
state=Aborted registryActive=true registryTerminal=false
```

原审计 P1-06 的核心问题仍存在。增加只读 snapshot 并未关闭可变入口。快照读取也没有和终态提交共用临界区，可能读到不同字段的不一致组合。

补充修复：Entry/StateMachine/终态提交封装到 Registry；外部不能直接修改；唯一状态来源或同一锁内一致结算/读取。保留兼容 API 必须证明它不会绕过 owner，不能只在 KDoc 写“观察者应该用 snapshot”。

### REV-02：identity 只用于 evict，不用于所有状态改变

定位：[markTerminal](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/RequestRegistry.kt:81)、[evict](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/RequestRegistry.kt:136)。

`markTerminal/transitionState` 仍按 requestId。旧 attempt 结束并移除后，新 attempt 复用 requestId，旧异步任务再调用 markTerminal，会把新 attempt 标 Failed：

```text
accepted=true successorAttempt=1 successorState=Failed
```

identity-checked evict 仍可移除活跃请求；id-only evict 也仍公开。复现：Created 请求可被 evict，结果 `removed=true count=0`，无终态结算。

补充修复：异步变更、终态、取消、unsubscribe、清理全部携带完整 identity/owner token，compare-and-settlement；活动请求移除需明确 abort/cancel settlement，管理入口限定作用域。不要只修 delete 的 identity 而保留无身份 terminal。

### REV-03：callback 投递锁未与序列提交一起排序

定位：[CallbackRouter.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/CallbackRouter.kt:82)。

代码先在 stateLock 提交 sequence，再退出并竞争 deliveryLock，两个步骤顺序可不同。复现：seq0 的 consumer 执行中，seq1 已提交并等投递锁，consumer 重入投递 seq2；deliveryLock 可重入，实际收到：

```text
CALLBACK_DELIVERY_ORDER [0, 2, 1]
```

因此“消费者移到状态锁外、再套一个 ReentrantLock”不能自动证明 token order。普通多线程竞争也存在较晚 sequence 先取得投递锁的窗口。

补充修复：为既有 route 保持一个顺序队列/单 drainer，把身份和序列接受与入队绑定；投递不持状态锁，重入事件排在已接受事件后。只做此模块最小缺陷修复，不新造全项目事件框架。增加 reentrant + concurrent producer 顺序保护测试，包含 terminal 排在已接受 token 后。

### REV-04：旧 terminal delivery 能覆盖新 attempt 的记录

定位：[CallbackRouter.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/CallbackRouter.kt:110)。

`terminalRecords` 按 requestId 存储，旧 consumer 返回后无 identity/route 条件地重写记录。复现：旧 attempt terminal consumer 暂停 → subscribe 新 attempt → 新 terminal 成功 → 旧 consumer 返回：

```text
beforeOldReturns=1
afterOldReturns=0
```

查询的终态从新 attempt 被改回旧 attempt。新 subscribe 也未清理/区分旧记录，unsubscribe 仍 id-only。

补充修复：按完整 attempt 存储，或仅允许当前匹配 route/record 的 compare-update；查询显式携带 identity，旧完成不能修改继任者。给终态记录生命周期/归档上限，不靠 clear 全局清表作为所有请求的唯一清理。

建议把 REV-01/02 回挂 P1-06，REV-03/04 回挂 P1-08；在修复报告上改为 PARTIAL/OPEN，保持未接线隔离。原生产 SSE/runner 不因这些新模块测试而获得安全保证，应继续独立验收。

## 4. 其他必须补充的执行项

### 4.1 mode-0 是真实回归，不属于新架构工作

[known-regressions.json](/E:/MAC/artifacts/repair/20261009-parity/final/known-regressions.json)已经证明 `MCA_WITH_LLAMA_CPP=OFF` 原参考可用、抽取后坏掉。当前 CMake 将它改成 FATAL_ERROR。

改成清晰错误有诊断价值，**不是恢复原配置**。用“修 guard/条件 source 会造第二套架构”解释延期不成立：有原版 `#if/#else` 和 JNI 失败分支可参考，只需恢复原编译边界和 source 条件，无须新造引擎。

它不影响默认 ON 的生产 APK，优先级可排在生产回归之后，但不能签“原项目支持配置全部恢复”。补目标：ON 真执行不变；OFF 按原合同构建，只有明确无后端能力的原错误返回，不能伪成功；nm/链接无残留 llama 引用。

### 4.2 Rust panic 的根因尚未消除

[image_conditioning.cpp](/E:/MAC/core/native/src/main/cpp/image_conditioning.cpp:42)明确承认结构校验只检查 JSON/model/type，内部无效而结构有效的文件仍可能进入 [Rust unwrap](/E:/MAC/third_party/tokenizers-cpp/rust/src/lib.rs:27)。C++ catch 不能拦住 abort。

7 个结构 fixture 无 abort 是有效改善，但不等于根因关闭。建议作为独立可靠性修复项，用锁定版本的最小 vendor glue 补丁使解析/编码错误安全跨 FFI 返回，或依据原有真实实现采用安全错误接口；不升级算法/依赖，不用简化 tokenizer 替代。遵守现 vendor manifest/patch/build 验证流程，增加结构有效但内部非法、合法真实 tokenizer、encode/decode 错误用例。

“依赖冻结”应阻止无关升级，不应永久豁免已确认的可达进程 abort。若本轮暂不修改，应保留 OPEN 和适用限制，不写 tokenizer crash 已彻底修复。

### 4.3 缺模型、SDK include、测试接线不能统称环境不足

MNN-LLM、Sana、SD1.5 和 QNN 模型确实缺失，标 NOT_VERIFIED 正确；应给每项原包来源/构建方式/hash、所需存储与完成顺序。设备可用、原源码可读，不能让未验证项只剩一句“没模型”而没有关闭动作。

QNN SDK 在主机已经存在，host quantizer skip 的原因是测试工程没有接上 SDK include，不是机器没有 SDK。它是可以关闭的测试配置缺口。prefix cache host 测试缺本地 GGUF，也应检查已有模型可否安全复用/取回，不把设备上的普通 GGUF 当 MTP 模型。

两个 SD source contract 测试未被任何 runner/CMake 注册，应接入现有测试入口。runner 前项失败导致后项不执行，需要明确每项 RUN/FAIL/NOT_RUN，而不是只有最终成功计数。

### 4.4 仍有注释满足合同测试

[T07 的合同审计](/E:/MAC/artifacts/repair/20261009-parity/tests/T07-CONTRACT-COMMENT-AUDIT.md:105)确认 `MnnPromptCacheNativeContractTest` 的 9 条断言部分由桥注释满足。该项违反原方案，不应为保住已经产出的库 hash 一直延期。

最小补充：测试读取真实 cache manager，调用实际缓存行为验证 reuse/invalidate/history/metrics；删除只为测试保留的注释。测试-only 变更不需重新生成 APK；若删除生产注释改变 debug info/库 hash，按明确源码/产物变化重绑定，不能让 hash 纪律成为保留低质量测试的理由。

## 5. 五项拍板建议

### 事项 1：重建 APK

**支持最终交付包含 greedy+penalty 和 API 兼容修复的新版，不把 build1 作为修复完成版。** build1 可保留供内部诊断、对照，不删历史证据。

重建两次的必要性成立，浪费的主要部分来自测试目标遗漏、冻结后才跑完整检查、并发和环境失败。下一次优先在冻结前完成完整测试/风险 lint；生产补丁→定向回归→整合验证→一次最终产物→设备验收。新 bug 仍需修，不用冻结日期拒绝修。

**最新状态纠正：build3 已于北京时间 01:27:11 失败**，不是仍在跑：

- [build record](/E:/MAC/artifacts/apk-build-records/debug-20261009-171859-164Z.json)：status=failed、apkPath/apkSha256=null。
- [错误日志](/E:/MAC/artifacts/repair/20261009-parity/apk/build-logs/final-20261009-171857Z.log.err)：Gradle daemon disappeared，pid 41428，JVM crash log。
- [JVM crash log](/E:/MAC/hs_err_pid41428.log:2)：native malloc 1,223,056 bytes 失败；当时系统物理内存 902 MB free、pagefile 可用 341 MB。直接证据是构建进程 native memory exhaustion，不是已发现新的业务源码编译错误。

补充构建任务：检查主机 commit headroom、仅清理本任务确认闲置的 Gradle/Kotlin daemon，不杀其他任务；保持 Gradle 独占，限制 workers/parallel native jobs并为 JVM native memory 留余量。不要机械增大 Xmx，不能通过关后端/删 ABI 绕过。排除环境问题后重跑真实产品配方。

### 事项 2：公开模型元数据

**建议下一轮把 `/v1/models` 和别名 `/models` 改为认证访问；`/health` 可以只公开最小存活信息，`/` 和 OPTIONS 按现 UI/协议所需保留。** health 不应暴露模型、路径、执行配置或详细故障。对现客户端增加明确的认证支持/迁移方式和协议测试。

这不是本次重构回归，与“是否保持原功能对等”分开判定；不能把既有 public-route 设计直接归为本次 bug。同时 LAN 可达时的模型清单披露也不应永久因为“老设计”而忽略。纯健康检查公开不天然是缺陷，重点是暴露什么。

上述是本复核的建议裁定，不意味着已向执行 Agent 发指令或改了路由。

### 事项 3：untracked 真实生产文件

**最终提交必须包含 `mnn_sampling_strategy.cpp/.hpp` 及其他被生产构建引用的新源码。** `AGENTS.md` 与本地 policy test 的禁提交规则只适用于指定文件，不是“全部新文件不得 git add”。

本轮尚未提交时用 manifest/字节归档正确，但不能替代最终版本控制；否则 clone 后会缺真实实现。提交时按明确文件清单纳入，避免 `git add .`，保持本地 safeguards 排除。最终在干净 checkout 用同 vendor/runtime 建构，证明没有依赖未纳入提交的工作树文件。

### 事项 4：21 条生产告警

**不接受把 21 条全部无条件延期，也不要求机械清零。** 逐站点判定实际语义：nullability/构造 copy 绕不变量等真实行为风险进入修复；单纯恒真/无效 Elvis 的冗余可记录；SDXL 守卫不明确时重跑编译绑定源码哈希和列位置，再最小修，不能盲删。

最新完整 warning ledger 是 00:37 生成的 **149 条**，其中 34 条被分类为 P1；后续 test-side 11 条和 production 1 条等又有修改，amendment 记录生产侧还保留 21 条。149/34/21 是不同时间和范围，不能混成当前完整精确计数。

`RuntimeParameters.kt` 的八类私有构造 data class copy 暴露目前仍有新构建日志告警，应优先验证 canonical 工厂不变量。`@ConsistentCopyVisibility` 若适用属于收紧真实 copy 访问语义，不是 blanket suppress；选择它或显式实现需查 call sites/binary 兼容和非法 copy 保护。恒真条件不自动等于高风险 bug，报告应避免全按 P1 堆数。

API 31 `longValueExact` 修复方向正确，还应有 Long 最小/最大/越界、非整数、NaN/Infinity 等保护和旧 API 运行验证。原调用被 runCatching 包裹，旧 API 缺方法可能被吞成 null/证据校验拒绝；不能未经实机复现就说必然导致整个 app 崩溃。

### 事项 5：工具层修复

登记 native stderr、重复环境键、tracked 报告污染、并发 Gradle 很有价值，但“全部已修”范围要限定为已修改/验证的入口。仍需补两点：

1. [构建窗口 G5](/E:/MAC/artifacts/repair/20261009-parity/apk/t09-final-build.ps1:133)比较 git status，**已为 M 的文件再次变动仍为 M、untracked 文件内容变化仍为 ??**，检测不到这些写入。开始/结束内容哈希比 status 更强，但事后才知道产物失效。补真正内容检查/不可变构建快照和写者约束。
2. launch 前扫 GradleWrapperMain 只证明扫描瞬间没有另一个 wrapper，不能阻止启动后另一个执行者进入，也不能说明闲置 daemon 不占内存。让 build/test/lint 共享同一排他锁/单执行队列，而非仅各自启动前检查。新发生的 native OOM 也需要纳入工具层未关闭项。

不需要为此新造平台，复用现构建入口做小范围持锁/快照/日志修复即可。

## 6. 建议追加到修复方案的最小任务清单

| 补充任务 | 优先级 | 完成条件 |
| --- | --- | --- |
| S01 当前 build3 失败闭环 | 立即 | 确认 native OOM/资源预算，真实全能力最终构建通过；fresh APK/hash/provenance |
| S02 最终 artifact 设备闭环 | 立即 | build1 证据保留为对照，final APK UI/auth API/图像/取消/数据/worker 重新执行；原版 A/B 和必要循环 |
| S03 重开请求问题 | 未接线缺陷修复，保持隔离 | REV-01–04 每项失败保护→修复后通过；不启用新 Facade |
| S04 恢复原 OFF 编译配置 | 生产恢复后，仍属回归修复 | 按原 guard/source 合同 OFF build + ON 行为不变，不只是 FATAL_ERROR |
| S05 tokenizer FFI 根因 | 可靠性修复 | 最小锁定 vendor patch/真实错误合同，内部非法 tokenizer 不 abort，合法包无误拒绝 |
| S06 告警与测试残留 | 在最终宣告之前 | 21 站点判定、真实风险修复；当前计数和证据绑定；cache 注释测试、SD 未注册测试、SDK include skip 闭合 |
| S07 生产文件完整性 | 提交/归档前 | 新源码及必要 build 文件完整版本化；本地 guard 不发布；干净 checkout 可构建 |
| S08 报告整合 | 每次关键状态变化 | acceptance/report/deferred/lineage 同步；OPEN/PARTIAL/PASS 均绑定 snapshot/APK/model 与验证范围 |

“时间窗口只剩几分钟”可以解释为什么当时没完成，不能作为缺陷永久降级理由。补充项仍以原源码与最小修复为依据，不恢复行数压缩、模块扩拆或新架构迁移。

目前可准确表述：**已恢复多项核心实现并取得部分真实产品执行证据；仍处于修复与验收阶段，最新 APK 构建失败，未达到完整功能对等。**
