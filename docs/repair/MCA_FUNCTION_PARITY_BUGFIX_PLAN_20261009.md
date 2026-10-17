# MCA 重构回归修复与原有功能恢复方案

日期：2026-10-09。工作区：`E:/MAC`。本文件状态：**修复方案，尚未执行修复或验收**。

本轮目标是：**暂停继续重构，修复已发现的问题，恢复原项目已有功能和行为，再交付经过验证的测试 APK。** 当前不以文件行数、模块数量、Facade 接线率或“S 级”标签作为完成目标。允许真实实现恢复后文件暂时变长。

本方案以[重构质量与功能风险审计](/E:/MAC/docs/architecture/audits/MCA_REFACTOR_QUALITY_AND_FUNCTIONAL_RISK_AUDIT_20261009.md)的 27 个问题组为起点，并覆盖尚未证实丢失的原有功能。审计不等于发现了全部潜在缺陷；执行时必须继续沿原有调用链查漏。“没有 bug”的可执行含义为：已知回归全部关闭、适用功能矩阵通过、没有未受控的生产 P0/P1、必需项无未验证空洞，发现新问题继续修复。不能从短期测试推导软件绝对无缺陷。

**执行优先级以本方案为准。** 旧审计修复顺序中“新 Facade 接真实 executor、进一步拆 owner/模块”的建议，在用户要求暂停重构后不属于本轮任务；这里只恢复和验证原有生产链，处理未接线代码缺陷，不继续完成新架构接线。

## 1. 执行原则：恢复源码中的功能，停止新一轮架构发明

1. **停止新增物理拆分、Gradle 模块拆分、继续压缩桥/VM 行数和大面积改名。** 原重构方案的行数退出条件本阶段暂停；不得通过少搬代码、删除分支来达标。
2. **以原有真实生产实现为依据。** 先查 `git show`、已有 helper、第三方锁定源码和测试，再修改。已存在的 tokenizer、scheduler、Sana session、图执行器、缓存和参数逻辑必须复用，不另造“简化等价版”。
3. **保留已经正确迁移的代码和后来有效的修复。** 对照历史版本逐函数、逐依赖恢复；不得整个仓库回退，不得直接覆盖桥文件，不得覆盖其他 Agent 的未提交/未跟踪文件。
4. **同一能力只有一个被生产入口调用的实现。** 能完整恢复到现有模块就恢复；迁移依赖实在无法证明完整时，可以临时将该能力的原真实实现恢复到单一现有入口，并明确断开该能力的残缺委托。不能同时保留两个可被 UI/API/Worker 随机选中的生产实现。
5. **不接入尚未完成的新 Facade/Owner。** 既有 UI、Local API、Worker 的真实路径先稳定。`Accepted` 不是执行成功，未接线骨架不得取代它们。本方案不要求为了“统一入口”先造执行框架。
6. **不改变对外合同。** JNI 签名、API 字段/SSE 事件、参数默认值、错误分类、取消语义、会话数据、文件布局和已有模型兼容性都纳入对照。原实现中确实存在的 bug 另立修复项；不把旧 bug 当目标复制。
7. **禁止伪成功和门禁降级。** 不得返回固定 true/空文本/伪图片/固定耗时/字面量哈希；不得加关键词注释过测试、删断言、跳测试、关能力宏、移除真实能力源码、使用旧 APK 冒充验收。
8. **冻结依赖和打包策略。** 不升级 MNN、llama.cpp、stable-diffusion.cpp、QAIRT/QNN、Kotlin/AGP；不在本轮盲目开启 R8、删后端或做体积优化。若已证实的 bug 必须改变依赖，单独说明最小补丁和 ABI 风险。

通用设备准入仍是硬要求：未知芯片/机型/profile 只能影响推荐，不能隐藏或拒绝下载、导入、加载、运行；使用 generic compatible 路径，由真实 native load/执行决定成功。一个代表性兼容 ARM64 真机通过生产 `MainActivity` 和认证 Local API 即构成该能力的设备验收，不建立逐机型认证名单。具体运行失败需要窄范围修复。

本地 `AGENTS.md` 和 `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 必须继续被 `.git/info/exclude` 排除，不能 stage、commit、push 或发布。执行前后都检查；本阶段不进行发布或发送给外部人员。

## 2. 当前必须先解决的问题

本轮再次读取关键生产源码时，以下问题仍存在。最终执行必须重新核对源码哈希；其他 Agent 后续修好某项，应验收现有修复，不重复回退。

| 优先级 | 当前事实 | 用户后果/风险 | 本轮处理 |
| --- | --- | --- | --- |
| P0 | MNN 移除 20 个仍被 Kotlin 声明的 JNI 方法 | 加载/检查/取消/tokenizer 等出现 missing native symbol，部分错误被吞为“未打包” | 恢复原签名及真实委托，并在新 APK 中调用 |
| P0 | MNN `loadModel` 设置成功标志但未创建 Llm，`generateNextChunk` 无真实输出 | 模型看似加载成功但不能聊天/视觉生成 | 恢复 create/config/load/prefill/response/chunk/unload |
| P0 | Sana 使用 `SanaSessionStub` 和伪执行字段 | 报成功但没有真实图执行和有效图片 | 恢复已有 `MnnSanaSession` 全链 |
| P0 | SD1.5 全零 embedding/latent，scheduler 使用错误输入 | prompt/seed/CFG/UNet 语义损坏，即使产生 PNG 也不合格 | 按原实现恢复完整数值数据流 |
| P0 | QNN mutex 的全局 extern 与 namespace 定义是不同符号 | 生产链接失败风险，跨 TU ODR 风险 | 同 namespace 唯一定义，真实 SDK 全链接 |
| P0 | MNN 新模块错误 include/缺 helper 声明 | 启用真实 MNN 的 ARM64 构建阻断 | 对照锁定源码修 include 和迁移依赖 |
| P1 | GGUF MTP 的 speculative unique_ptr 移入临时环境后不恢复 | 第一 chunk 后状态/owner 丢失 | 保持 session 级 owner 或完整 scope guard |
| P1 | MNN 周边取消、进度、TI 资产事务、执行证据和异常边界不完整 | 请求卡住、取消污染下一请求、API 拒绝结果、native 异常风险 | 与原有实现一起恢复，补故障测试 |

QNN 较早的语法错误已经被工作区后续改动修好，不列为仍然存在的语法失败；当前 mutex 问题有实际 object 的未定义/定义符号证据，但此前没有完成整库链接。执行报告必须区分“符号问题确认”和“已观察到完整构建失败”。

## 3. 固定源码参考：不能凭印象修复

机器可读参考在[MCA_FUNCTION_PARITY_REFERENCE_LEDGER_20261009.json](/E:/MAC/docs/repair/MCA_FUNCTION_PARITY_REFERENCE_LEDGER_20261009.json)：包括完整提交、git blob ID、历史源码 SHA-256、行数、定位锚点、当前对应文件哈希及关联源码快照。

| 参考 | 固定提交 | 用途 | 证据限制 |
| --- | --- | --- | --- |
| 当前 HEAD | `afcc9c0ae7309b13efdd0b9844751529b4f1aa41` | MNN 桥原有真实实现；当时 Kotlin JNI 声明；保留已提交 SD/GGUF 抽取 | 不是当前 dirty 工作树，也不是整项目已验收版本 |
| 最后 native 抽取前 | `76348a5b55baa49052c5a02674c29baf4307d5a6` | GGUF/MTP、SDCPP、QNN 原执行链对照 | 仍须检查更早 helper 和后续有效修复 |
| 最后 VM 抽取前 | `02fc01810a5fb4fb5fbda2e61f1f20dd47351d01` | VM 方法、状态、回调及 composition 映射 | 不等于所有函数都还在单个 VM 中 |
| 已有指标回归修复 | `aa0f9bf1c70a199ef952cdc657a223c331329479` | 防止恢复旧实现时重新丢失后来有效修复 | 用 diff 确认具体修复内容，不全局倒退 |
| 更早物理抽取前 | `b6f8e0cd3bd762cb78f1c4c1ad5a336711a91866` | 最后两次抽取参考无法解释某行为时继续追溯 | 该版本已含部分架构骨架，也不是天然原始稳定版本 |

已确认的源码参考体量：HEAD MNN 桥 7,613 行并有 28 个唯一 JNI 名称；`76348a5b` 的 GGUF 桥 3,125 行、SDCPP 桥 2,718 行、QNN 桥 11,892 行；`02fc0181` 的 VM 1,929 行。这里的行数用于辨认参考，不是修复后的预算。

### 3.1 每项恢复必须填的映射表

执行 Agent 为每个原有能力建立一行或多行记录，不能仅写“已从旧文件搬回来”：

| 字段 | 内容 |
| --- | --- |
| featureId / issueId | 功能与审计问题编号 |
| reference | 完整 SHA、源码路径、函数/类、相关 helper 和锁定 vendor 版本 |
| destination | 当前唯一生产实现及桥委托位置 |
| production callers | UI → runner/provider → JNI；API/Worker 的对应调用点 |
| contract | 参数/默认值/顺序、返回和错误、progress/cancel、状态/缓存/资源副作用 |
| differences | 必须恢复的缺失部分；保留的后续修复；主动修正的原始 bug |
| baseline status | SOURCE_ONLY / BUILD_VERIFIED / DEVICE_VERIFIED，分别登记 |
| protection | 修复前失败的行为测试、原版对照、设备用例和证据文件 |
| result | 修复后源码/库/APK/模型哈希、命令、退出码、未验证项 |

读参考使用 `git show <SHA>:<path>`。若导出到对照目录，使用 Python subprocess 的原始 bytes 写入，避免 PowerShell 重定向改变编码。参考文件导出到专用目录，不写到业务源码路径。禁止 `git reset --hard`、`git clean`、全树 `git restore` 或用 `git show ... > 业务源码`。

### 3.2 建立能够运行的对照基线

优先寻找已有可追溯的原版 APK/构建记录，核对源码、vendor、model 和 package 哈希。如果没有，使用只用于对照的隔离 checkout/build 目录构建候选历史版本，不改变当前工作树。选择每个能力最近且真实完整的实现，不以提交新旧自动判定好坏。

基线与修复版固定同模型/包、同设备、同 backend/precision、同 seed/参数、同线程、同 tokenizer、同 SDK/runtime 和可控缓存状态。旧版没有构建/设备证据时，只能称“源码参考候选”；不能用“源码里看见 generate”冒充行为基线已通过。

若受 SDK/模型/设备限制无法建立动态基线，仍可先关闭源码和构建缺陷并跑可执行行为测试；该能力的原版动态对照留作必需未验证项，不宣称功能已完全恢复。已有明确原始 bug 应记录 original-defect，再验证最小修复，不复制它。

## 4. 冻结与执行阶段

阶段使用 F0–F6，避免与已暂停的重构 R 阶段混淆。生产调用路径在 F0 固定；后续仅允许修复缺陷导致的最小必要调整。

| 阶段 | 工作 | 退出条件 |
| --- | --- | --- |
| F0 快照与功能账本 | dirty/untracked 安全备份、源码/依赖清单、候选基线、入口映射、复现 | 可以逐文件追溯修改；所有原能力有账本；6 个 P0 和 MTP 有复现或可执行失败证据 |
| F1 真实 native 恢复 | 构建阻断、JNI、MNN 聊天/Sana/SD1.5、取消/进度/资产/异常、MTP、QNN/SD 对照 | 启用原能力的 ARM64 全配置/编译/链接通过；JNI 对应；真实执行最低 smoke 通过；P0 关闭 |
| F2 请求与回调缺陷 | 保持现生产入口；修原有线程/资源问题；修复或隔离未接线 Runtime/Facade 缺陷 | 已接线行为有并发/故障验证；未接线骨架不能被当生产成功；没有隐式新接线 |
| F3 产品行为回归 | VM/Coordinator、UI/API/Worker、数据、下载/导入/云端/Agent/知识库/更新 | 全功能账本逐项有行为证据，已支持功能无删减；原路径不被残缺新路径替换 |
| F4 测试与门禁可信化 | 全模块新鲜测试、native host 可执行文件、实际构建图/JNI、告警语义治理 | 必需测试无失败/Not Run；门禁能识别故意引入的目标/符号/JNI 缺失；证据同快照 |
| F5 设备、故障与短稳 | 生产 MainActivity/认证 API/Worker、真实错误注入、重复/后台/重启、基线 A/B | 实际运行恢复，取消后可再运行，资源无持续失控，所有必需设备用例闭合 |
| F6 测试 APK 交付 | 同快照真实产品构建、签名/ELF/ABI/安装核验、最终回归报告 | APK SHA-256 与验收一致；报告无伪绿/过期证据；仅写声明范围内功能恢复 |

F1 的内部顺序：**先修构建依赖/namespace → 恢复真实 MNN 函数及全部 JNI → 修 MTP/QNN/SD → 重新完整配置、链接与 smoke**。不能先用空函数补齐入口作为 F1 通过。F2 的未接线代码修复可与 native 修复并行，但不改生产 composition root；F3–F6 按依赖推进。

## 5. F1：具体恢复 native 生产行为

### 5.1 构建、命名空间和迁移依赖（T01）

写集由一个集成 owner 管理：[CMakeLists.txt](/E:/MAC/core/native/src/main/cpp/CMakeLists.txt)、native Gradle 必要修复、共享 include/声明和产物验证。桥函数分别由其能力 owner 修改。

1. 读取真实 ARM64 CMake configure 参数、File API/`compile_commands.json`、`target_sources`、include paths、defines 和 imported libraries。先登记真实 target、每个 TU、ABI、能力宏；旧编译数据库只作复现参考。
2. 对照锁定 `third_party/MNN` 和历史桥，修不存在的 `diffusion/mtok_tokenizer.hpp`、`prompt_language_rules.hpp`，确认原本正确 header 和依赖路径。session manager 的 `extract_advanced_params`、`normalize_mnn_contract_enum` 必须找到真实已有定义/声明及合同，不补空 helper。
3. QNN mutex 选择与原执行锁语义一致的一个 namespace，在现有合适 `.cpp` 保留**一个**定义，header 仅 extern 声明，调用处明确引用。消除全局 extern/namespace 定义分裂及 header 的 ODR 风险；不能改成每个 TU 一个 static 锁。
4. 记录 mutex 对 graph execution、preview、cancel、unload 的作用，检查 callback 中重入、锁顺序和等待取消。修符号时不能通过删锁引入并发 native 执行。
5. 保留真实能力的源文件、宏和 runtime closure。只有经构造点审查证明为纯测试骨架的代码，才允许移至测试 fixture/测试 target，且先确认没有真实 JNI/生产依赖；这不等于移除编译失败的真实模块。
6. MNN runtime 必须使用一致的 MNN/Express/OpenCL/CV/Audio/llm 库组和 vendor patch，不混用旧新目录。QNN SDK headers、System/HTP/runtime/transport 库保持同版本来源。

验收：重新配置当前快照，所有真实启用 TU 编译，所有共享库在 `--no-undefined` 下链接；检查 `llvm-nm`/`readelf` 的声明定义及 `DT_NEEDED`；APK 内实际库与链接输出对应。语法通过不能关闭链接问题。

### 5.2 恢复全部 MNN JNI（T02，与真实函数同时恢复）

原依据：[HEAD 中的 mnn_native_engine.cpp](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp)对应 ledger 固定提交；外部合同以 Kotlin `external` 声明为准。

| 类 | 必须恢复的 20 个入口 |
| --- | --- |
| NativeMnnBridge | initBackends、isRunnerReady、getRuntimeStatsJson、requestStopIfActive、isGenerationRunning、getPrefillProgressJson、resetPrefillProgress、shutdown |
| NativeMnnDiffusionBridge | isRunnerReady、resetImageCancellation、inspectBundle、runUnetSmoke、tokenizePromptTokenIds、tokenizePromptTokenIdsWithConfig、tokenizePromptTokenIdsFromJson、measurePromptTokens、encodePromptTokenIdsWithWeightsFromJson、encodeSdxlPromptConditioning、cancel、getRuntimeStatsJson |

恢复步骤：逐个登记 JVM descriptor、是否 overload、static/instance、返回类型、参数顺序、字符串/null/数组合同、原有错误处理、真正委托。同时核查仍保留入口有没有空实现。所有 JVM external 声明必须对应打包 `.so` 的动态 export 或真实 `RegisterNatives` 表，不能只对名称计数；JNI 长名/重载签名和 shrink 后名称也需一致。

`NativeMnnBridge.runnerReady` 不再把丢失 native symbol 无声解释成“未打包”。保留调用方可兼容的错误合同，区分真实无能力、native 库/入口缺失、真实 load 失败；沿既有错误通道返回可定位原因。未知设备 profile 不在这些失败分类中。

测试：全部入口在新 ARM64 产物中实际调用；idle 的 stats/cancel/reset/shutdown 安全且幂等；tokenizer/conditioning/smoke 使用真实包验证；错误参数产生原合同的可解释失败；不靠反射存在或 symbol 存在判定真实实现已恢复。

### 5.3 MNN 聊天、session、缓存与视觉（T02）

以 HEAD 原真实 `loadModel/beginCompletion/generateNextChunk/unloadModel` 及其 helpers 为主要参考，按现有模块位置恢复，不引入新的 MnnEngine 骨架。

1. **加载事务**：原配置规范化/路径验证 → `Llm::createLLM` → 真实 set_config/load/backend 初始化 → 成功后发布 ready/loaded。失败时回滚临时资源，不把上一模型或 nullptr 宣告为新模型成功。加载、卸载和推理按原有执行锁/取消规则串行化。
2. **模型 owner**：保留实际 Llm 唯一 owner 和原释放顺序。禁止 sentinel 替代真实 handle；仅在 load/native smoke 完成后报告成功。unload/shutdown 排空真实请求、解绑回调、清理缓存/句柄，不能仅切换布尔值。
3. **请求**：恢复系统提示/历史/模板、sampling、context/max token、视觉输入、缓存 namespace/复用失效、response/prefill 的原调用顺序和默认值。已正确迁移的参数/metrics 修复必须保留。
4. **输出**：恢复真实 token callback、队列与分块；UTF-8/中文/emoji 和协议过滤保持原行为。null chunk 的 EOS/尚未有数据语义必须与 runner 一致，不能把“未执行”当生成完成。
5. **统计和进度**：来自实际计时/执行计数；删固定 `prefill_ms=10`。读取统计不能与 unload 形成 UAF；prefill reset 不影响其他请求。
6. **取消和重载**：复用原 stop/reset/cancel helper，完整身份只作用于当前请求；取消 prefill/decode 后必须结算并允许下一请求。不能持全局锁等待回调线程关闭自己。

必须行为用例：有效/损坏/缺失模型；冷加载和重复加载；非空多 chunk；中文分片；至少 5 轮同会话及 cache A/B；session 切换/历史删除/参数变化后缓存失效；真实视觉模型两张不同图片；prefill/首 chunk/末 chunk 取消；连续 10 次 load→run→unload；失败后立即重试。具体支持项来自原模型能力，不给不支持视觉的模型造视觉能力。

通过条件：真实模型对象存在且实际计算产生结果，原参数/缓存/视觉合同保留；不存在假 ready、空成功、固定统计和未释放资源；UI 与认证 API 均完成真实聊天。

### 5.4 Sana（T02）

复用[原有 mnn_sana_session.hpp/cpp](/E:/MAC/core/native/src/main/cpp/mnn_sana_session.hpp)和 HEAD 的 `NativeMnnDiffusionBridge_generate` 真实调用，删除生产 `SanaSessionStub` 的成功路径。

恢复 bundle/options、prompt/negative conditioning、steps/seed/sampler/flow 参数（仅原支持项）、session 构建、进度、取消、真实 run、VAE/输出写入、错误清理和执行证据。成功必须同时满足真实 session 执行、文件完整写入、可解码尺寸/像素、真实 artifact 哈希；成功 JSON 的 `path` 不能是预先占位文件。

故障用例：损坏图、缺 tokenizer/encoder/VAE、无法写输出、半途中取消、重复生成与参数变化、取消后再生成。单 step 只适合作为 smoke；正式恢复至少使用一个原版正常生成配置做 A/B。nativeEffective 只能从实际执行收集。

### 5.5 MNN SD1.5：恢复原数值链，不能自行改算法（T02）

主要参考：HEAD `run_mnn_sd15_interpreter_direct` 及真实 scheduler/tokenizer/tensor/asset helpers。

| 计算阶段 | 恢复要求 | 能拦住当前回归的验证 |
| --- | --- | --- |
| prompt → embedding | 社区 CLIP 写入的 tensor 文件必须读取并验证长度、shape、dtype、finite；原双路/负向 conditioning 顺序不变 | 注入已知非零输出，实际 UNet 输入等于该 tensor；文件缺失/截断失败，不能补零 |
| 初始 latent | 恢复原 seed RNG、Gaussian 分布、`init_noise_sigma` 和 latent 形状 | 同 seed 复现原序列/容差；不同 seed 改变数值；无全零替代 |
| 模型输入 | timestep、scheduler scale、batch 复制和 cond/uncond 顺序按原实现 | 用实际 tensor seam 校验 shape/值及 timestep 顺序 |
| UNet 输出 | 检查 execute 成功和输出 shape/finite，正确取 cond/uncond noise | 改变 UNet 预测会改变下一 latent，不允许输出算完被丢弃 |
| CFG | 恢复 `uncond + cfgScale * (cond - uncond)`，与原支持的 CFG 关闭/特殊分支一致 | 已知 cond/uncond + CFG 的数值测试，不能只断言文件含公式 |
| scheduler | 将 guided prediction 交给 step；恢复 eta/steps 等参数；检查 set/scale/step 的失败结果 | 实际 scheduler 输入为 guided；非法配置/失败及时终止 |
| decode/output | 恢复 VAE 解码、像素/尺寸验证、output transaction 和真实 hash | 损坏 VAE/写入失败不能 ok；PNG 可解码且对应实际 latent |

测试优先使用已有真实 scheduler/计算函数的 seam；需要小范围提取已有纯函数时，只作保护性修复，不改变公式/执行顺序。允许测试 double 提供指定 UNet tensor，禁止它进入生产 target，最终必须真实模型运行。

同模型/backend/precision 的确定性配置优先对中间 tensor、token IDs、参数和误差容限做比较。实际 GPU/HTP 有浮点差异时，先记录原版重复运行的差异，确定可解释容差，再对照修复版；不能把生成图片必须逐字节一致作为所有后端的通用要求，也不能只凭“看起来像”判通过。

### 5.6 图像周边合同和跨后端 MNN conditioning（T02/T04）

恢复 TI 的 prepare→实际消费→finalize asset 事务、选中/消费/hash 对应、共享 CLIP 和 QNN payload、prompt token 计量、SDXL 双 encoder/pooling、progress callback、取消/reset 和错误终态。不能仅复制 nativeEffective 字段或添加 `using` 声明。

检验请求选择的模型/TI/LoRA/conditioning 与真正消费文件相同；执行途中文件被替换、缺失、取消、写入失败必须按原事务失败/回滚，不能留下下一请求可误用的临时资产。预览/进度回调需实际触发；对回调异常和进程退出有资源释放。

涉及 QNN 的 MNN tokenizer/conditioning 恢复后，再测试 QNN preflight/SD1.5/SDXL。不能只证明 MNN 自己生成成功，就断言其他 backend 的 shared encoder 合同已恢复。

### 5.7 JNI 错误与资源边界（T02/T03/T04）

先审查已有[JniExceptionBoundary](/E:/MAC/core/native/src/main/cpp/mca/jni/jni_exception_boundary.hpp)，补足实际需要的语义后复用。现 wrapper 不能未经测试直接包所有入口：当前 `throw_java_exception` 直接 FindClass/ThrowNew，需要明确 pending Java exception 的保留规则和失败退路。

每个恢复入口都覆盖 C++ exception、JSON 类型错误、非法配置、callback Java exception 和分配失败。遵循原 API 的 error JSON/status 或 Java exception 合同，未知异常作可定位失败；不要将所有异常吞为 false/null/成功。JNI string/array/local/global ref 和 native owner 使用现有 RAII；释放线程、JNIEnv attach/detach、异常 pending 时哪些 JNI 调用允许继续必须正确。

请求错误保留 request/attempt/session/backend 和 cause，敏感 prompt/密钥不写进日志。复用现有 `ErrorEnvelope/StableErrorCode`，不新建第二套全项目错误框架。错误映射变化需兼容既有 UI/API。

### 5.8 GGUF/MTP：持久 owner 不应消失（T03）

对照 `76348a5b` 的全局/persistent `g_speculative` 和多 chunk 生成链，以及当前 `mca/llm` modules 的全部 return/throw。

优先采用对当前 diff 最小的可靠修复：原 session 保持 unique_ptr owner，临时 environment 借用真实 state；若原抽取接口暂时必须 move，则采用既有 scope-exit/RAII 模式，保证所有 return/throw 恢复 owner，且恢复早于释放 model/context。不要新建 speculative engine，不升级 llama，不通过禁用 MTP 或强制普通 decode 验收。

覆盖至少 20 个 chunk 或完整长于该边界的生成、正常 EOS、max tokens、stop、decode/helper 异常、反复 begin、cache reuse、unload/reload。证明多个 chunk 访问的是持续存活的同一 owner，最终只释放一次；普通 GGUF 与 MTP 启用/关闭的原支持配置均通过。后台/native GPU 参数和原支持采样合同同步对照。

### 5.9 QNN 和 SDCPP：已迁移部分逐阶段对照（T04）

不能因 JNI 名称仍在、语法通过就跳过。以 `76348a5b` 的完整桥和更早 helper 按以下阶段核对当前实现：validate → runtime/load → tokenizer/conditioning → graph/session → denoise/CFG/scheduler → VAE/output → evidence → cleanup。

QNN：同一执行 mutex、真实 SDK/runtime/transport、context cache、tensor 分配/生命周期、SD1.5/SDXL/两阶段请求 lease、预览、取消、fallback 和 nativeEffective。实际 graph smoke 与生产生成均通过；HTP 不适用/失败只走原兼容路径，不能新增未知机型拒绝。

SDCPP：原真实 generate/upscale、seed/steps/sampler/negative、LoRA/TI、ControlNet、img2img/inpaint/mask/strength、Ultrafix/upscale、进度/preview、prompt token 计量、配置统计、取消/shutdown 和 output transaction。每项仅对原已有支持范围做验收；不为了修 bug 新增原项目没有的特性。

对每个选项要证明它到达 native 实际计算/消费，不能只看 Kotlin 参数或响应字段。取消后不发布成功图片，失败不留下可被 UI/历史收录的半成品。

## 6. F2：请求/session/回调修复，保持现有生产路径

负责人 T05。先列出构造点和调用点，将 **已接线生产代码** 与 **未接线新代码** 分开记录，不混淆当前用户故障与将来接线风险。

未接线新 Runtime 可以修局部缺陷，但不能顺势接入 UI/API/Worker；没有真实 backend 的加载骨架应只留在 fixture/明确实验目标，或提供明确失败而不是成功。保持既有 runner/provider 为唯一真实生产链，等修复验收完成后另开重构阶段。

| 缺陷 | 最小修复要求 | 必需行为测试 |
| --- | --- | --- |
| Session lease | 短临界区登记 opaque lease token/完整 owner identity；异步请求不长期持有线程 ReentrantLock；compare-and-release 幂等 | A 线程 acquire，B 线程 close；重复 close；旧 lease 关闭时已有新 lease；close/acquire 竞态 |
| admission/epoch/shutdown | 在现有 supervisor 内原子检查和登记；按旧 epoch 精确 drain；closing 后拒新注册；覆盖释放旧 backend | 新 epoch 登记与旧 drain 并发；register/shutdown；替换 owner；一个 close 抛错仍尝试其余 close |
| 请求状态 | Registry/现 request owner 为唯一状态提交者；只读 snapshot；终态与清理一次结算；取消不能先 evict 活跃对象 | terminal/abort/timeout/cancel 并发；ID 重用；新 attempt 不被旧 cleanup 清理 |
| cancellation | 使用完整 requestId/attempt/epoch/generation/worker identity；条件删除；reason+flag 原子一致；单调 deadline | 墙钟调整不影响超时；旧 unregister 延迟；同时读取 reason；cancel/reset 只针对自己 |
| callback | 终态先由 request owner可靠记录；序列顺序受控；投递/失败与终态查询区分；慢/异常 consumer 不占长临界区 | consumer 抛错/慢读/重入；失败后可查终态；无死锁；资源全部清理 |
| 新 SSE adapter | 不忽略 trySend/send 失败；有界背压，必要时显式失败/断连；终态可查询；未开始收集也有 owner 生命周期 | 超实际容量事件、满队列终态、断连、未 collect、重复/重连；无静默丢 token/terminal |
| 假 loading owner | 不通过 ready=true/sentinel/byte tokenizer 报真加载；唯一 identity；原 owner 释放 | 同 basename/同毫秒创建不覆盖；坏模型不能 loaded；close 确实释放；骨架无生产构造点 |

复用已有身份/错误合同和线程保护思路：`ChatRunOwnership`、`GenerationForegroundLeaseRegistry`、`SdxlTwoPhaseRequestLease`、`NativeCancellationGate`。它们是参考模式，不是可任意复制的“已证明安全库”；不能引入 core→app 反向依赖。按所在层复用既有原子/锁工具即可，不造新 orchestration framework。

已经存在的 UI/API/Worker 请求也要接受相同取消、重入、旧回调、终态和资源检查。如果检查发现已接线版本也有该问题，先修已接线版本，不能仅把新 Runtime 测试做绿就结束。

## 7. F3：VM、产品入口与数据恢复

负责人 T06。用 `02fc0181` 与更早参考，建立所有 public/internal 业务方法、fields、effect、Job、订阅和 Coordinator 调用的映射。当前 Delegates 接受整个 VM 是维护性债务，本阶段不再大拆；优先找遗漏/改变的函数体和副作用。

重点检查：

1. `MainViewModel` → 各 Delegates/HostAdapter → Coordinator 的方法一一对应；构造/初始化、observer、coroutine scope、线程调度、错误处理和 finally 保留；默认参数/顺序、method overload 没有丢失。
2. 聊天 send/stop/retry/edit/delete/regenerate、tail prune 与 native cache invalidation、conversation/session 切换、prompt/history/attachment、流式 UI 和保存终态。禁止一次请求落多个 owner 或 effect 重放重复执行。
3. 图片设置/模型选择/token 计量/生成/预览/停止/保存/分享/历史/失败提示；原支持图像选项确实传到 backend，失败不在历史显示为成功。
4. production `MainActivity`、Local API runtime/provider、根包 WorkerService、前台通知、后台切换、worker death/restart 的实际路径。新 Facade 保持未接线；存在真实闭环后才能讨论未来迁移。
5. Local API 认证、错误/status、模型管理、chat/image、SSE token/terminal、重连和原幂等语义、客户端断连后的原资源策略。认证 token 不进入报告。原版没有的 replay 协议不在本轮凭空增加。
6. Room/schema/migration、历史/模型索引、参数持久化、文件 URI/权限、导入导出、部分写入/失败回滚、进程重启。复制旧方法时不能回退 schema 或丢用户数据。
7. 模型下载/暂停/续传/校验/安装/卸载、Hub/云端账号/对话、Agent 工具/停止、知识库索引/检索、应用设置/更新。使用已有流程和 fixture，不另写产品逻辑。
8. 扫真实下载/导入/load/run 入口和所有 fallback：未知 profile 无法触发隐藏/禁用/拒绝。已有 policy test 只是补充，真实 UI/API 必须可操作。

现有可复用保护测试包括 `MainUiIntentEffectTest`、`ChatSessionPersistenceOperationsTest`、`McaRoomMigrationExecutionTest`、`GenerationForegroundLeaseRegistryTest`、`WorkerBoundaryTest`、Local API server 测试等；每个相关功能还要有直接行为/设备证据，不能只靠文件中有某个函数名。

## 8. 原有功能验收矩阵：必须逐项挂账

先从原生产 source/manifest/router/设置 UI/已有测试生成完整 inventory。下表是最低覆盖范围，实际账本必须列出每个原支持分支；不能把未枚举能力当“不在范围”。

| 能力组 | 原有范围确认与最少用例 | 验收入口 |
| --- | --- | --- |
| MNN chat | CPU/OpenCL 的原支持配置、文本/多轮/视觉/缓存/sampling/tokenizer/stop/reload | JNI + runner + MainActivity + 认证 API |
| GGUF chat | 普通/MTP、原 GPU offload、模板/上下文/前缀缓存/sampling/多 chunk/stop | native + UI/API + 原 worker 路径 |
| 其他本地 chat | QNN/Genie/LiteRT 及原支持后端，适用多模态/stop/model lifecycle | 不升级实现，原生产入口回归 |
| MNN images | SD1.5、Sana、原支持 SDXL/conditioning；TI/共享 CLIP/tokenizer/preview/取消 | 数值保护 + 真实包 + UI/API/Worker |
| QNN images | SD1.5/SDXL、graph/pipeline/semantic、cache/two-phase/共享 encoder/preview | SDK 全链接 + 实际图 + UI/API/Worker |
| SDCPP images | 原支持 family，LoRA/TI、ControlNet、img2img/inpaint、Ultrafix/upscale、negative/seed/CFG/sampler | 原参数到计算的证据 + 真实图 + UI/API |
| API/SSE | auth、chat/image/model operations、错误、慢读/满缓冲/断连/重连/重复请求 | 认证生产 loopback server，含并发客户端 |
| 生命周期 | load/unload、session 切换、cancel/retry、后台/前台、worker/process restart | 实际 app/service 与资源证据 |
| 数据 | 数据库 migration、聊天/图片历史、参数/model 索引、导入/导出、重启 | golden fixture + 真机持久化恢复 |
| 模型管理 | 下载/续传/完整性/导入/安装/删除/权限/错误包 | 产品 UI/API 的原有入口 |
| 云端/Agent/知识库 | 原连接配置、工具执行/停止、索引/检索和错误回滚 | 原服务/测试环境/已有 fixture，真链未测则挂账 |
| 设置/更新/UI | 参数默认/迁移、语言/权限/导航、更新检查/安装流程、失败恢复 | MainActivity 与原测试工具 |
| 设备准入 | 已知和未知 profile 的用户操作都可达；generic fallback；真实错误才拒绝 | UI + authenticated API + 本地 guard |

对每项记录 `PASS / FAIL / NOT_VERIFIED / NOT_APPLICABLE`。NOT_APPLICABLE 必须引用原源码/合同说明原本不支持，不能因为没有模型、设备或时间就填写。缺设备/模型应记 NOT_VERIFIED，并阻止该能力的“完全恢复”结论。

参数测试优先覆盖默认值、边界、一个有效非默认组合、负向/取消以及与其他选项的已知交互。组合多时先做 pairwise/原历史风险组合，再重点验证跨 encoder、LoRA/TI、mask/ControlNet、cache/session、后台/cancel 等高风险交互，不能只测默认配置。

## 9. F4：使测试和门禁保护实际行为

### 9.1 修复前红、修复后绿

每个确认缺陷至少有一个能在当前坏实现失败的保护用例。测试应调用实际生产函数/类或同一编译实现的窄 seam，而非复制一套“正确模型”。测试 fixture 可模拟网络、UNet 输出或异常，但生产 target 不能链接这些成功 fixture。

现存 4 项 app 测试失败先分类：函数迁移导致的文本定位失败，不直接等于功能丢失；行为失败必须修实现。文本查找要同步到真实位置，同时增加对应行为保护，不得只改 contains/readText 让它绿。删除以“Contract needles”注释冒充真实合同的做法；静态源码测试仅保留依赖/禁止项等适用目的。

重点保护：MNN 实际 create/load/response、20 JNI 实调用、Sana 输出真实性、UNet→CFG→scheduler 数值、MTP 多 chunk owner、lease 跨线程、epoch/attempt 竞态、terminal/SSE 满队列、取消重试、UI side effect 和数据回滚。

### 9.2 当前快照完整构建与测试

所有 required 证据必须来自同一整合快照。最小清单：全 Gradle 模块适用 unit tests、Kotlin compile、lint、native host **先编译全部所需 executables 再 CTest**、真实 ARM64 native enabled 构建/链接/JNI、测试 APK 和生产设备行为。旧 XML、旧 `.so`、旧 APK 不能拼接为当前通过。

native host 目前“注册 13 项”与“只执行 2 个已存在文件”不是完整通过。缺 exe、CTest Not Run、没有真实模型导致 skip 都必须入账；必需项不允许按成功计数。区分 host stub/宏关闭 helper 测试和真实 MNN/QNN/SD 生产目标。

### 9.3 门禁最小修复

先修 native gate 对 `target_sources`、`core/sd-native`、真实 macros/links/JNI 的漏检。CMake File API/实际 compile DB 是 target/source 图的依据，不能只正则解析 add_library。语法 gate 继续保留，但必须标为 syntax-only。

受控负向测试在隔离 fixture/build 目录进行：一个 target_sources TU 语法错误、缺真实外部符号、丢 JNI export、能力分支未编译/打包错误，门禁应失败；不在共享业务工作树做污染性注入。实际执行出错而“终审绿”属于门禁 bug，优先修。

本阶段要求一个**可重复运行的受控真实 native 验收入口**，可复用现 Windows 工具链/scripts/build，不强迫先建设设备云/新 CI 平台。现公开 JVM/stub CI 仍有价值，但不能盖章 production native pass。长期 CI runner/SLO/S 级基础设施另排计划。

### 9.4 编译警告与错误处理

重新强制执行 Kotlin/native 编译和 lint，输出当前 warning 清单，含文件/行、原文、编译器版本、分类、修复或保留理由。历史“58/50/126/128”等数字不能代表当前工作树。

第一优先处理可能导致行为变化的告警：Java/Kotlin nullability、nullable receiver、恒真条件/无效 Elvis、data class 私有构造但 copy 绕过 canonical 工厂、资源/线程误用、native 未检查返回/生命周期告警。通过最小语义修复和行为测试关闭。弃用和冗余转换等低风险项目排在功能恢复后；无法立即移除时使用局部、理由明确的记录，不能全局 suppress。

`catch(Throwable)`、`runCatching`、`getOrNull/getOrDefault` 的次数不是 bug 数。逐调用审查生产 load/run/cancel/API：取消是否透传、错误是否吞掉、资源是否释放、是否把 native 故障变为成功/未打包。建议性探测 fallback 与核心运行失败分别保留原合理语义，不机械清零。

## 10. F5：真实设备、故障与短期稳定性

设备脚本是诊断工具，不等于生产 `MainActivity`/认证 API 的完整验收。每个能力至少同时取得诊断执行证据和实际产品入口证据。以原项目支持的兼容 ARM64 设备/真实包执行，不要求逐芯片准入。

### 10.1 原版/修复版对照

聊天：比较调用参数/模板、token IDs、缓存命中/失效、stream 序列、取消/终态、统计来源；确定性模型可比较 token/text，非确定性模型看真实执行与合同，不能强求逐字一致或仅要求非空。

图像：比较选中的实际资产、conditioning shape/value、seed 噪声、scheduler/steps/CFG、图调用、输出尺寸/像素/真实哈希、nativeEffective 和 cleanup。先用原版重复运行估算同环境数值容差；跨硬件/backend 改变需单独解释。两张可解码图片并不能证明数值链相同。

数据/UI/API：比较事件/状态/错误和持久化副作用；用原 golden 数据验证 migration/import/export，确认未知字段/旧配置与原合同兼容。

### 10.2 最低稳定性回归目标

以下是本轮测试计划，不是已经达到的 SLO，也不能推导生产 crash-free 率：

| 场景 | 本轮最低执行目标 | 判定 |
| --- | --- | --- |
| 模型 lifecycle | 每个适用 backend 连续 10 次 load→真实 run→unload | 不假成功、不死锁/UAF；资源回到可解释 idle |
| 聊天重复/多轮 | 每个 chat backend 至少 30 请求，含 5 轮同会话、切换、中文、多 chunk | 无丢 chunk/串会话/挂终态；MTP 单独覆盖持续 owner |
| 图像 | 每个修复 family 至少 3 次正常配置生成；每个原支持关键选项至少一次 | 真执行/真图片/参数有效；不使用只有一步 smoke 代替全部 |
| cancel/retry | 每个关键 backend 在 load/prefill/denoise/输出等适用阶段取消后再运行，累计至少 10 次 | 当前请求终态明确，下一请求不受污染 |
| API/Worker | 慢客户端/队列满/断连/后台/worker death/restart 各 3 次 | 无永久 Accepted/失终态/重复执行；清理符合原产品合同 |
| 短期 soak | 代表设备上至少 2 小时交替 chat/image/load/cancel/后台，记录温度、PSS/native/FD/threads | 无未处理 crash/ANR/死锁；无持续无界资源增长 |

采集 warm-up 后稳定窗口与循环结束 idle。缓存可有有界保留，不能把每次都立即归零当通用标准；增长阈值参考同设备原版 baseline 和实际 owner 计数。为每个模型记录耗时/内存正常范围，测试超时用单调时钟并记录理由；不能因测试 runner 超时把后台成功结果当通过。

若设备/模型/运行时间不足，缺项明示，不能签“全部恢复”。短稳通过后仍可继续长期可靠性验证，但不在本轮以新 S 级平台建设阻塞源代码修复。

### 10.3 真实故障矩阵

至少覆盖：坏模型/缺库/无效 JSON 类型/不合法参数、load 失败、graph/helper 异常、损坏 embedding、输出目录写失败、取消与终态并发、callback 异常、API disconnect、worker/process death、执行资产替换/失效。资源分配失败在支持的隔离 host seam/测试配置模拟，不在用户设备上故意耗尽系统内存。

每项验证：错误可定位且不误报 success；该 request 的 cancel/registry/callback/lease/文件资源结算；其他 session/新 attempt 不被误清理；允许后续有效请求。已有 Python fault matrix 只作模型测试，`production=0/device=0` 不得改成真实注入通过计数。

## 11. 实际可复用命令与工具

本段命令是执行指导，**本次没有执行这些构建/设备命令**。从 `E:/MAC` 运行，执行者先核对脚本参数/当前环境；每条命令单独记退出码。PowerShell 原生命令失败后不能因为下一条成功就丢失失败。

### 11.1 读取参考与 vendor 校验

```powershell
git show afcc9c0ae7309b13efdd0b9844751529b4f1aa41:core/native/src/main/cpp/mnn_native_engine.cpp
git show 76348a5b55baa49052c5a02674c29baf4307d5a6:core/native/src/main/cpp/native_engine.cpp
git show 76348a5b55baa49052c5a02674c29baf4307d5a6:core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp
git show 02fc01810a5fb4fb5fbda2e61f1f20dd47351d01:app/src/main/java/com/muyuchat/mca/MainViewModel.kt
powershell -NoProfile -File .\tools\vendor\verify-mnn-vendor.ps1
```

MNN runtime 必须重建时使用 `tools/build-mnn-runtime.ps1` 已有的 `MnnSourceRoot/BuildDir/Abi/ApiLevel/AndroidSdkRoot/AndroidNdkRoot/CmakePath/NinjaPath/VendorManifestPath` 参数，依据锁定 vendor。不要对共享构建目录使用 `-Clean`。

### 11.2 新鲜 JVM/编译/lint

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --stacktrace -PmcaQnnTypedBindingsRequired=true
.\gradlew.bat :app:compileDebugKotlin :app:lintDebug --rerun-tasks --stacktrace -PmcaQnnTypedBindingsRequired=true
```

这是原模块结构下的推荐入口；检查所有模块任务/XML 实际覆盖，不因 root task 成功假设每种测试都已执行。SDK 路径来自现受控环境的 `mcaQnnSdkRoot` 或 `MCA_QNN_SDK_ROOT`，报告不输出密钥。公开 CI 的 `mcaQnnTypedBindingsRequired=false` 结果只能叫 isolated JVM/stub 结果。

### 11.3 native host 与真实产品构建

```powershell
powershell -NoProfile -File .\tools\tests\run-native-image-contract-host-tests.ps1 -KeepBuildArtifacts
powershell -NoProfile -File .\scripts\build.ps1
```

`scripts/build.ps1` 无自定义参数；当前为真实产品 debug 配方，执行 assembleDebug、`mca.abis=arm64-v8a,x86_64`、检查 QAIRT/runtime/MNN provenance，输出 `artifacts/apk-build-records`。不要给它编造 `-Output` 等参数。保持原已有 ABI 能力；明确 x86_64 的原支持/disabled 能力，不把 emulator stub 当 ARM64 生产证明。

其他 host 测试根是 `core/native/src/test/cpp` 和 `core/native/src/test/cpp/mnn_sampler_host`。在独立 `build/repair-<runId>/...` 内 configure→build→`ctest --output-on-failure`，Windows 多配置生成器使用一致的 `--config`/`-C`。需要真实 GGUF 的 prefix-cache 测试设置已有 `MCA_LLAMA_PREFIX_TEST_MODEL`；没有模型就挂账。不列未经核验的 generator/toolchain 硬编码命令。

native production 属性来自现 Gradle：`mcaWithMnnDiffusion`、`mcaWithLlamaCpp`、`mcaMnnSourceRoot`、`mcaMnnAndroidBuildRoot`、`mcaMnnVendorManifest`、`mcaMnnVendorRequired` 等。必须从实际 CMake cache/compile DB/APK 核验有效值，不只记录“命令想启用”。

### 11.4 现有设备 smoke 示例

将 Android 绝对路径替换为真实已安装且哈希登记的模型/包，不使用占位模型声称运行通过：

```powershell
powershell -NoProfile -File .\tools\device\run-mnn-chat-smoke.ps1 -ModelPath '/sdcard/MCA/models/REAL_MODEL/config.json' -SmokeMode full -ContinuousTurns 5 -Lifecycle reuse -OutDir 'artifacts/repair/RUN_ID/mnn-chat'
powershell -NoProfile -File .\tools\device\run-mnn-diffusion-smoke.ps1 -BundleRoot '/sdcard/MCA/models/REAL_SD15_BUNDLE' -Mode generate -Family SD15 -Steps 20 -Seed 42 -Runs 3 -WorkerProductPath -OutDir 'artifacts/repair/RUN_ID/mnn-sd15'
powershell -NoProfile -File .\tools\device\run-qnn-image-smoke.ps1 -BundleRoot '/sdcard/MCA/models/REAL_QNN_BUNDLE' -Mode semantic -Family SD15 -WorkerProductPath -OutDir 'artifacts/repair/RUN_ID/qnn-image'
powershell -NoProfile -File .\tools\device\run-sdcpp-image-smoke.ps1 -ModelPath '/sdcard/MCA/models/REAL_SD_TURBO_MODEL' -Family SD_TURBO -Steps 1 -Runs 3 -WorkerProductPath -OutDir 'artifacts/repair/RUN_ID/sdcpp-image'
```

MNN Sana/SDXL、其他聊天后端、选项和取消用例使用原 script 的适用参数或现产品入口补足，不能认为 SD15 一条命令覆盖所有 family。`-VisionValidated` 等现诊断参数只是场景参数，不得转化为机型认证限制。认证 API 和 MainActivity 操作另保存证据；脚本默认值/诊断 Activity 不能替代它们。

QNN 示例默认只做单步诊断，SDCPP 示例明确用于真实 SD_TURBO 的原支持单步配置；其他 family 必须改用其原版验证过的完整参数/steps/conditioning，不把 smoke 默认值误用为通用生成配置。

### 11.5 APK 检查

```powershell
python .\tools\release\verify_16kb_pagesize.py --apk 'artifacts/repair/RUN_ID/MCA-parity-debug.apk'
Get-FileHash -Algorithm SHA256 -LiteralPath 'artifacts/repair/RUN_ID/MCA-parity-debug.apk'
```

16 KB 工具输出固定报告位置，不支持自造 `--output`；运行后归档其实际输出。补现 Android SDK 的 `apksigner verify`、manifest/ABI/ELF/库依赖检查，工具路径按实际 SDK 发现。debug 构建内部使用 native Release CMake 不代表 Android release APK 已验证。

## 12. 全部 27 个审计问题的本轮处置

“延后”仅用于架构演进/长期工程目标，不用来绕过可见回归。新的生产 bug 一律进入当前修复。

| 审计 ID | 处置 | 执行项/关闭标准 |
| --- | --- | --- |
| P0-01 | 立即修复 | T02：20 JNI 真委托、完整签名/产物核验和实调用 |
| P0-02 | 立即修复 | T02：MNN 真 Llm、prefill/response/chunk、cache/vision/释放 |
| P0-03 | 立即修复 | T02：Sana 真 session/图/图片/证据，无成功 stub |
| P0-04 | 立即修复 | T02：embedding/seed/UNet/CFG/scheduler 数值链 |
| P0-05 | 立即修复 | T01/T04：namespace 唯一定义、锁语义、实际全链接 |
| P0-06 | 立即修复 | T01/T02：正确 include/helper/vendored API，真实能力目标通过 |
| P1-01 | 立即修复 | T03：MTP 多 chunk persistent owner，异常/取消释放一次 |
| P1-02 | 修缺陷，禁止新接线 | T05：lease 跨线程/幂等/旧新 owner 竞态行为测试 |
| P1-03 | 保持既有生产链，延后 Facade 迁移 | T05/T06：明确未完成；无新生产构造点；既有 UI/API/Worker 真执行 |
| P1-04 | 消除假成功，隔离测试骨架 | T05/T02：无生产 fake ready/tokenizer；不造新引擎 |
| P1-05 | 修缺陷，禁止新接线 | T05：atomic admission/epoch/close、故障 cleanup |
| P1-06 | 修缺陷，禁止新接线 | T05：唯一终态与只读 snapshot、identity cleanup |
| P1-07 | 修缺陷，禁止新接线 | T05：完整 identity/取消理由原子/单调时钟 |
| P1-08 | 修缺陷，禁止新接线 | T05：终态可恢复查询、callback 异常/慢读不死锁 |
| P1-09 | 修缺陷，禁止新接线 | T05：新 adapter 满队列不静默丢帧；T06 检查原生产 SSE |
| P1-10 | 立即修复 | T02/T04：进度/取消/TI 资产/真实 evidence |
| P1-11 | 立即修复 | T02：JNI 边界、pending exception、RAII、类型错误测试 |
| P1-12 | 修复测试可信度 | T07：红→绿行为保护，不能以关键词代替执行 |
| P1-13 | 修复门禁漏检 | T01/T07：真实 CMake 图/宏/SD/链接/JNI，负向门禁测试 |
| P1-14 | 本轮补受控生产验收；长期 CI 平台延后 | T01/T07/T08：真实 build/JNI/device 绑定快照；stub CI 不冒充 |
| P1-15 | 必需设备/故障/短稳现在做，长期 SLO 延后 | T08/T09：同 APK 真机/MainActivity/API/fault/循环/soak |
| P2-01 | 停止继续拆 VM；排查功能遗漏 | T06：方法/副作用原版映射与行为保持；窄 Port 改造另排 |
| P2-02 | 停止继续按行数拆分 | T02/T03/T04：大函数保持真实功能，LOC 回升允许；无再次删减 |
| P2-03 | 修真实入口检查漏扫；物理模块改造延后 | T07：WorkerService/生产图检查不漏；不新增 Gradle 模块 |
| P2-04 | 风险告警/吞错优先修，风格债务分级记录 | T07：当前 warning 清单、取消/失败语义，无全局 suppress |
| P2-05 | 立即纠正验收状态 | T09：旧报告过期标记；hash/时间/范围；不写 S_GRADE_READY |
| P2-06 | 维持原打包并验证产物；体积/R8/供应链扩建延后 | T09：真实 APK/签名/ABI/ELF/依赖完整，不能删功能减包 |

不得将“延期架构改造”写成“缺陷已修复”；不得把“隔离未接线代码”写成“新架构已完成”。最终报告分明填写：生产 bug 修复、未接线代码风险关闭、功能验证、延期架构债务。

## 13. Agent 执行拆分、依赖和写集

本方案不自动创建 Agent/新聊天。执行时可由一个 Agent 顺序完成；有并行任务时先分配写集，不让多个 Agent 同时修改同一桥/共享 header/构建文件。

| 任务 | 负责人写集 | 依赖 | 交付 |
| --- | --- | --- | --- |
| T00 快照/基线/功能账本 | 专用 repair evidence/ledger，不改业务逻辑 | 开始 | 当前文件 hashes/backup、原能力 inventory、reference 状态 |
| T01 构建与集成 | CMake/native Gradle、共享符号/target 与产物验证；不代写桥业务 | T00；最终链接等待 T02–T04 | 编译/链接/JNI/库 closure 证据 |
| T02 MNN 恢复 | MNN bridge、mca/mnn、原 Sana/conditioning helpers、对应测试 | T00 + T01 初始依赖修复 | P0-01/02/03/04/06、P1-10/11 关闭 |
| T03 GGUF/MTP | native_engine、mca/llm 相关 owner 与测试 | T00 | MTP 生命周期和普通 GGUF 行为 |
| T04 QNN/SD 回归 | QNN/SD 桥和各自 pipeline/helper、对应测试；shared lock 与 T01 协商 | T00；shared conditioning 验收等待 T02 | 原阶段/选项对照、真模型结果 |
| T05 请求风险 | core/engine/runtime、新 Facade/adapter 的必要缺陷修复/隔离及测试 | T00；禁止生产新接线 | 跨线程/identity/终态/SSE 证据、构造点清单 |
| T06 UI/API/数据 | VM Delegates/Host/原 runner/provider/Worker/persistence 的最小回归修复 | T00；设备整链等待 T01–T04 | 产品功能账本、migration/golden/UI/API |
| T07 测试/告警/门禁 | 测试可信化、gate、warnings ledger；涉及业务代码交对应 owner | 各修复项完成后集成 | 当前快照完整测试和负向 gate |
| T08 设备/故障/短稳 | 专用 test/evidence；不顺势改产品架构 | T01–T07 的集成快照 | 同 APK 生产 UI/API/Worker、fault、soak |
| T09 最终产物/报告 | APK/evidence/最终报告；不代改能力实现 | T08；构建后再核验同哈希 | 测试 APK、修复/未验证/延期清单 |

T01 独占共享 CMake、关键共享符号声明和全量 configure/build；T02 独占 MNN 桥；T03 独占 GGUF 桥；T04 的 QNN/SD 若拆成两个执行者，另列具体互斥写集。T02/T04 交叉的 conditioning header 不允许双方同时写，由指定 owner 收口。

业务修复 owner 自己维护相关行为测试；T07 不在其他 Agent 修代码时改同一个测试文件。全工程 build/test/device 使用一个集成队列，避免不同源码快照交错产生误报。T08 发现 bug 按能力回送修复，所有受影响证据重跑，不能只改报告。

T09 应核对的已有相反结论包括 [architecture-final-report.json](/E:/MAC/architecture-final-report.json) 中仅凭 LOC 通过的 S-C，以及 `E:/MAC_REFACTOR_EVIDENCE/A11-final-integration-release-candidate.md` 的旧 S_GRADE_READY。保留可追溯历史证据，显著标明适用快照/已过期范围，新的总验收指向本轮报告；不能保留一个当前“未通过”和另一个当前“全部通过”的相反总状态。

## 14. 快照、回退和证据目录

当前工作树大量 dirty/untracked，不得当作可随时 reset 的临时文件。F0 将约定写集的当前 bytes/hash、原 diff 和 untracked 文件备份到任务专用路径。备份/patch 和外部参考包含本地敏感内容时仅本地保存。

修复回退以“撤销本次最小 patch”为单位，先核对文件现 hash 和其他写者变更；不恢复整个旧版本。不为缩减修复 diff 丢弃已有有效改动。发生冲突按 feature/source ledger 重新对照，无法证明的语义保持未验证。

执行证据建议布局：

```text
artifacts/repair/<runId>/
  snapshot/source-manifest.json
  baseline/reference-map.json
  baseline/function-inventory.json
  reproduction/<issueId>/
  native/configure-compile-link/
  native/jni-signatures-and-exports.json
  tests/junit-and-ctest/
  warnings/current-warning-ledger.json
  device/<featureId>/
  faults/<caseId>/
  soak/resources-and-events/
  apk/MCA-parity-debug.apk
  apk/build-provenance.json
  final/repair-report.md
  final/acceptance-matrix.json
```

本目录已经属于本地 artifacts 排除范围，证据不自动发布。一个 PASS 必须包含：命令、开始/结束时间、退出码、源码 snapshot、vendor/patch/toolchain、ABI/宏、库/APK/模型/包哈希、执行入口、实际结果和验证范围。只生成 JSON、改 counter 或读取旧 XML 不算执行。

源码 snapshot 包括 dirty/untracked 业务文件、Gradle/CMake、vendor 状态；`git rev-parse HEAD` 不能代表工作树。构建时如果其他写者改变源码，应停止把该产物归入既定快照，重新绑定来源并验收。

## 15. 最终测试 APK 和完成标准

T09 使用现真实产品 `scripts/build.ps1` 生成 debug 测试 APK。保留原 capability/ABI/runtime 和 debug 签名方式，复制到版本化 artifacts 路径，核验 manifest/version/ABI、全部 required native 库、dynamic JNI、依赖 closure、签名及 16 KB ELF/ZIP 对齐。不要拿旧 APK 重命名交付。

如果最终构建改变 source/config/库或产物 SHA-256，设备证据不再自动适用。优先对即将交付的同一 APK 安装并做验收；必须重构建时解释差异并重跑受影响的功能/设备测试。未经授权不卸载用户生产 app 或删除数据；测试安装使用已约定环境并保留持久化数据。

本轮可以写“功能恢复候选已通过声明范围验收”的条件：

1. 6 个 P0、生产 MTP 缺陷、MNN 周边/异常以及执行中新增生产回归关闭；无未受控生产 P1。
2. 原支持能力完整 inventory，每个适用必需项均 PASS；缺模型/设备/基线不能填已恢复。NOT_APPLICABLE 有原合同依据。
3. 同源码快照的真实 native compile/link/JNI、全部必需 JVM/native 行为测试、lint/风险告警处理通过；必需 Not Run/skip 为零，其他 skip 有明确范围和理由。
4. 生产 MainActivity、认证 Local API、适用 Worker、cancel/retry、数据/后台/重启通过；数值/参数/资产执行与原版对照没有未解释回归。
5. 真实故障/重复/短稳通过，无 crash/ANR/死锁、明显资源泄漏、假成功或静默丢终态。
6. 交付 APK provenance 和 SHA-256 与验收绑定；最终报告清楚标明延期架构债务/长期可靠性项，不自称综合 S 级完成。

任一必需项 NOT_VERIFIED：可以交付标明“未完全验收”的内部诊断 APK 和已完成修复，但不能叫全部功能恢复完成。用户要求修 bug 的工作应继续推进，不以新架构建设替代。

重新开始重构只能在功能恢复基线成立后按用户的新指令进行。届时以本轮的真实行为保护为前置条件，逐垂直切片迁移，不再靠删实现实现行数预算。

## 16. 最终修复报告模板

```text
状态：IN_PROGRESS / PARTIAL_VERIFIED / PARITY_VERIFIED_FOR_DECLARED_SCOPE
源码 snapshot/HEAD/dirty hashes：
原版参考及验证状态：
vendor/SDK/模型/包：
APK 路径/SHA-256/签名/ABI：

逐 issue：原失败 → 原源码依据 → 最小修复 → 保护测试红/绿 → 真实执行证据。
逐 feature：原支持范围 → UI/API/Worker 结果 → A/B 参数/数值/数据差异 → 判定。
构建/测试：命令、退出码、test counts、failures、skip/Not Run、日志。
编译警告：当前清单、风险修复、保留理由，不引用过期总数。
故障/资源：真实注入/重复/短稳次数、结果、原 baseline 对比。
未接线代码：缺陷修复/隔离情况，确认没有新生产构造点。
未验证：缺什么、影响哪些能力、下一动作，不默认 pass。
延期：LOC/物理模块/Facade 迁移、R8/体积、长期 SLO/CI，不写已完成。
已发现新 bug：并入当前修复，不用“审计未列”排除。
```

可直接交给执行 Agent 的完整提示词见[MCA_FUNCTION_PARITY_BUGFIX_AGENT_PROMPT_20261009.md](/E:/MAC/docs/repair/MCA_FUNCTION_PARITY_BUGFIX_AGENT_PROMPT_20261009.md)。
