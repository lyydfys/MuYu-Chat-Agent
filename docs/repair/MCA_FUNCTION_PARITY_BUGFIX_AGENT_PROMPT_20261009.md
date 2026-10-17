# MCA 暂停重构、恢复原有功能：执行 Agent 提示词

日期：2026-10-09。用途：交给后续执行 Agent 修复；本文件本身不代表修复已经完成。

## 一、主执行 Agent 提示词

复制以下文本给主执行 Agent。若只委派一个子任务，在末尾补充任务编号和独占写集；不要让多个 Agent 同时修改同一桥/CMake/header。

```text
你在 E:/MAC 维护 MCA 项目。用户最新决策是：暂停继续重构/解耦，先修当前 bug，尤其重构回归，
恢复原项目已有功能后交付真实测试 APK。不要继续压缩行数，不要继续新增模块/新架构接线，
不要为了 S 级标签写完成报告。我们有原源码参考，任务是按源码恢复和最小修复，不是重新发明。

先读：
1. E:/MAC/AGENTS.md（本地硬规则，不得发布）
2. E:/MAC/docs/repair/MCA_FUNCTION_PARITY_BUGFIX_PLAN_20261009.md
3. E:/MAC/docs/repair/MCA_FUNCTION_PARITY_REFERENCE_LEDGER_20261009.json
4. E:/MAC/docs/architecture/audits/MCA_REFACTOR_QUALITY_AND_FUNCTIONAL_RISK_AUDIT_20261009.md

执行优先级以新的修复方案为准。旧审计/旧重构路线中“接入新 Facade、进一步拆 module、继续达标 LOC”
的建议本阶段暂停。先恢复原真实 runner/provider/UI/API/Worker 路径，不接入只有 Accepted 的新 Facade。

硬约束：
- 工作树已有大量他人 dirty/untracked 文件。先 git status、备份约定写集和 hashes，不 reset/clean/全树 restore。
- 不把 git show 重定向到业务源码；用历史函数、相关 helpers、锁定 vendor 的对照做最小补丁。
- 恢复必须保留参数默认/顺序、JNI 签名、错误、取消、progress、缓存/资源/持久化副作用。
- 保留后续有效 bugfix，不整个项目回退。原版确实有 bug 时另记 original-defect，最小修，不复制旧 bug。
- 复用原 tokenizer/scheduler/Sana/图执行/缓存，不编写简化替代算法、假引擎、成功 stub。
- 同一能力只有一个真实生产实现。可以暂时恢复原函数使 LOC 增长，不保留两套可生产选择的逻辑。
- 不删断言、不加关键词注释、不关能力宏、不移除真实能力源、不拿旧 APK/旧 XML 冒充当前验收。
- 锁定 MNN/llama/SDCPP/SDK/runtime 和 toolchain，不升级依赖、不开新 R8/体积优化。
- 未知 chipset/model/profile 不能阻断下载/导入/load/run；generic fallback + 真实 native 结果决定成功。
- AGENTS.md 与 app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt 继续 .git/info/exclude，
  不 stage/commit/push/发布，不在最终证据包误打包本地 guard 或密钥。

固定源码参考（均为 SOURCE_INSPECTED 候选，不是天然整项目已验收版本）：
- afcc9c0ae7309b13efdd0b9844751529b4f1aa41：MNN 真实 7613 行桥和 28 JNI 原实现、当时 Kotlin JNI 声明。
- 76348a5b55baa49052c5a02674c29baf4307d5a6：最后 native 抽取前 GGUF/MTP、SDCPP、QNN。
- 02fc01810a5fb4fb5fbda2e61f1f20dd47351d01：最后 VM 抽取前方法/副作用对照。
- aa0f9bf1c70a199ef952cdc657a223c331329479：保留已有指标回归修复，不恢复其旧 bug。
- b6f8e0cd3bd762cb78f1c4c1ad5a336711a91866：必要时继续追溯更早物理抽取前实现。
每个能力查完整函数与传递依赖，不只从旧文件找到一个调用名。

按以下顺序执行，持续推进，不停在只写建议：

F0：记录 current dirty/untracked snapshot，备份写集，建立全部原能力 inventory、source/function/helper→
current production caller→test/evidence 映射。重新确认每个问题仍存在，已有别人修好的项验收后保留。
基线如不能运行就诚实标 SOURCE_ONLY/NOT_VERIFIED，不称动态对照通过。

F1：先修真实构建依赖和 QNN mutex namespace/唯一定义；再恢复 MNN 完整真实实现和 JNI；
修 GGUF MTP owner；对照 QNN/SDCPP 原阶段；最后重新 configure/compile/link/JNI 和真实模型 smoke。
必须真实启用 MNN LLM/diffusion、QNN SDK、llama、SDCPP 的原支持目标，不能 stub build 验收。

MNN 恢复重点：
1. 20 个丢失 JNI（8 chat + 12 diffusion）完整签名/真委托，检查所有剩余 JNI 是否空实现。
2. createLLM/set_config/load→prefill/response→真实 token/chunk→cancel/unload/shutdown；
   真缓存/多轮/视觉/UTF-8 和统计，不允许 ready flags 替代模型、null 替代生成、固定 prefill_ms。
3. 复用原 MnnSanaSession 真执行/输出/进度/取消，不使用 SanaSessionStub/假 hash。
4. SD1.5 恢复真实 embedding、seed Gaussian/init_noise_sigma、UNet cond/uncond、
   CFG guided prediction→scheduler.step、eta/失败检查、VAE/output。
5. TI prepare/consume/finalize、共享 CLIP/SDXL conditioning/tokenizer、QNN payload、progress/cancel，
   nativeEffective 来自实际执行而不是补字段。
6. 修错 include/缺 helper，对照真实 vendor API；所有 JNI 有正确错误边界/RAII。
   先检查已有 JniExceptionBoundary 的 pending Java exception 语义再复用，不把错误都吞成 null/false。

GGUF：g_speculative 是 persistent unique_ptr。当前 move 到 temporary environment 后 generate 没恢复。
按原 session owner 或覆盖所有 return/throw 的 RAII 修，至少多 chunk/stop/max tokens/异常/reload；
不得禁用 MTP 让普通 decode 代替验收。

QNN/SD：锁、SDK/runtime/transport、context/tensor、conditioning、denoise/CFG/scheduler/VAE/output/evidence
各阶段与原码一一核对；LoRA/TI/ControlNet/img2img/inpaint/Ultrafix/preview 等原支持项必须验证，
仅 JNI 名字在/语法通过不算行为保留。

F2：修 session lease 跨线程/幂等、admission/epoch/shutdown、唯一请求终态、attempt 条件 cleanup、
单调 deadline、callback 异常/慢消费、SSE 满队列/断连等缺陷。
先确认哪些已生产接线；未接线 Runtime/Facade 修局部缺陷或隔离骨架，禁止新接线和新 executor 框架。
不能因新的 Owner 测试绿，就跳过已有生产 runner/provider 的相同风险检查。

F3：VM/Delegates/Host/Coordinator 全方法/字段/回调/Job 映射；实际 MainActivity、认证 API、根包 WorkerService；
聊天/图片/下载导入/数据 migration 与导出/云端/Agent/知识库/设置更新逐项回归。
不要继续瘦 VM，只修已丢失或改变的行为。用户数据和 schema 不能被回退。

F4：每个 bug 保护测试在坏版本红、修复版绿。文本 contains 测试只作静态补充；
重跑同快照完整 Gradle tests/compile/lint、先 build 全 native host exe 后 CTest、真实 enabled compile/link/JNI。
修 gate 漏 target_sources/SD/真实宏/链接/JNI；在隔离 fixture 中做负向 gate 检查。
重新导出 warning 原文，优先修 nullability/copy 绕工厂/错误吞噬/资源等风险，不全局 suppress。

F5：代表性兼容 ARM64 真机，同 APK 的生产 MainActivity + authenticated Local API + 原 worker 路径；
真实模型 A/B、fault、cancel/retry、load/unload、多轮/多 chunk、后台/重启和最低 2 小时短稳，
具体次数和判定看方案。诊断 Activity/script 和 Python fault model 不等于生产验证。
一个代表设备通过打开兼容设备能力，不建立 per-chipset/profile 白名单。

F6：复用 scripts/build.ps1 真实产品 debug 配方，核验签名/ABI/全部 ELF 与 16KB/依赖/JNI，
复制最终测试 APK 到 artifacts/repair/<runId>/，登记 SHA-256/provenance，对将交付的同一 APK 验收。
如果重构建改变哈希/来源，重跑受影响证据，旧设备绿不能直接复用。

并行时采用方案 T00–T09 写集。CMake/shared headers/full build 一个集成 owner；MNN/GGUF/QNN/SD 桥各唯一写者。
发现跨写集 bug 先给对应 owner 具体复现/最小建议，不直接覆写其工作。

每完成一项提供：
issue/feature、原失败、reference SHA/path/function/helpers、生产调用链、最小 diff 原因、
修复前红/后绿、命令与退出码、snapshot/lib/APK/model hashes、设备实际结果、未验证项。
报告区分：生产 bug 修复、未接线代码修复/隔离、功能验收、延期架构债务。
状态只可 IN_PROGRESS/PARTIAL_VERIFIED/PARITY_VERIFIED_FOR_DECLARED_SCOPE，不能因 LOC 达标写 S_GRADE_READY。
任何必需功能缺模型/设备/基线/执行证据记 NOT_VERIFIED，不改名为 NOT_APPLICABLE 或 PASS。

目标是把已知回归关闭、原有支持功能逐项恢复并验证。不能宣称软件绝对没 bug；发现新 bug 继续并入本轮修复。
现在按原源码开始实际修复，不继续架构重构，不停在报告“可能可行”。
```

## 二、单个能力 Agent 的附加任务卡

在上面的通用提示词末尾增加以下模板。将占位值改成实际分配；未分配写集不得自行扩大。

```text
你的任务编号：T__。
负责关闭：列出 audit issueIds 和原 featureIds。
独占业务写集：列出具体文件/目录。
独占测试/证据写集：列出测试文件与 artifacts/repair/<runId>/<taskId>。
不得修改：CMake/Gradle、其他桥、生产 composition root 等，按任务具体列出。
源码参考：完整 SHA + path + functions/helpers。
依赖：先完成哪些任务；跨 backend/shared conditioning 由谁收口。
第一步：读取现实现/原码/调用链，重新确认缺陷与参考有效；建立能失败的保护用例。
最小验收：列出必须运行的行为、构建、设备用例；设备无法跑时不得假通过。
交付：修复 patch + tests + evidence + remaining risks，不只写建议，不启用未完成新架构。
```

建议任务卡重点：

| 任务 | 必须提供的特有证据 |
| --- | --- |
| T01 构建 | fresh CMake 目标/TU/宏、真实全链接、namespace 符号、dynamic JNI、runtime closure |
| T02 MNN | 真 Llm 与多 chunk/视觉/cache；Sana 真产物；SD1.5 数值；20 JNI 实调用；资产/取消/异常 |
| T03 MTP | 第 1 块之后 owner 仍存活、多个 chunk 同一 state、所有退出恢复/释放一次 |
| T04 QNN/SD | 原完整阶段对照；实际参数/资产消费；关键 image 选项/preview/cancel；真 SDK/模型 |
| T05 请求 | 跨线程 lease、旧 identity cleanup、epoch/shutdown、callback 异常、满队列终态；无新接线 |
| T06 产品 | VM 方法/副作用映射、真实三入口、migration/golden 和其余原业务 inventory |
| T07 可信测试 | red→green、完整新鲜 suites/CTest、Not Run/skip、负向 gate、当前风险 warning ledger |
| T08 设备 | 同 APK MainActivity/auth API/Worker，真实 fault、cancel/retry、循环/soak、原版 A/B |
| T09 交付 | 当前 APK/provenance/hash、验收矩阵、过期证据标记、未验证和延期分类 |

## 三、最终审查 Agent 提示词

```text
对本轮修复做独立审查。先读新的 MCA_FUNCTION_PARITY_BUGFIX_PLAN_20261009.md，
不要沿旧 S 级重构方案要求继续拆分/新接线。检查真实行为恢复，不替写达标数字。

确认：
1. 各 patch 有原源码依据与完整 helper/参数/取消/错误/资源映射，后续有效修复未回退。
2. 六个 P0、MTP 和新发现生产问题确实关闭，不是关宏/删源/空实现/吞错。
3. MNN 全部 JNI 签名与打包库一致且真调用，真 create/load/prefill/chunk；Sana 真图/图像，
   SD1.5 guided noise 真进 scheduler，MTP 第二块之后同一 owner。
4. 原 UI/API/Worker 路径仍真执行，未接线新 Facade/Owner 没被启用冒成功。
5. 27 issue dispositions 与原 feature inventory 完整；未确认丢失的原业务也有回归覆盖。
6. 保护测试能在原坏实现失败，结果与同源码快照/ABI/macros/vendor/model/APK 绑定。
7. 必需 CTest 缺 exe/Not Run、测试 skip、设备/基线缺失、Python-only fault 没被算成真实通过。
8. 当前告警语义治理、SSE/terminal/identity/资源和数据安全有可执行证据，无关键词合同游戏。
9. 真机验收包含生产 MainActivity 和认证 API，未知设备/profile 可操作，没有新增准入名单。
10. 最终 APK 是要交付的同一验收产物；签名/库/JNI/16KB/provenance/hash 可追溯。

如发现 bug，输出具体 source path/function/line、触发、影响、原源码依据和最小修复/保护用例。
已完成项保留；遗漏项继续修。缺证据不能给通过。只对声明且确实验证的范围给功能恢复结论，
不宣称绝对无 bug 或综合 S 级。列清延期架构债务，但不要求本轮继续拆模块。
```
