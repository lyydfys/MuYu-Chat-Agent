# 天玑 NPU 参考实现到 MCA 的接入架构与执行切片

日期：2026-10-31。文档性质：源码审计后的设计方案；不代表生产接入或真机验收完成。

## 1. 决策与适用边界

将参考项目作为运行时、模型容器和数据流的对照对象，在现有 MCA 图片执行链内接入，不另建图片 owner、后台任务系统或 UI/API 专用引擎。首选已锁定公开NPUG拓扑stub + Neuron DLA做单图恢复验证；同时分开管理两条技术路线：

1. 当前自有 TFLite 图的 JIT + CPU/NPU 混合执行，用于验证自有模型与数值修复。
2. 社区公开预编译产物的 NPUG/AOT 执行，用于验证参考模型与运行时；它有独立容器、组件与原生接口契约，不能作为现有 TFLite 模型的同义文件。

两条路线共享请求身份、资源仲裁、图执行证据、终态与图片保存；各自保留真实模型、conditioning、采样和精度契约。公开模型能下载与当前 MCA 能加载是两项不同事实。不存在“把 .npug 改名为 .tflite”或“复制几个 .so 即接入完成”的步骤。

当前授权是分析参考 APK、交付报告和详细架构。本文件不授权修改生产代码、恢复推荐下载、发布第三方二进制或改变冻结 UI。

## 2. 本次基线及可复核的事实

MCA 分支 codex/enterprise-refactor，HEAD 68b6eef1e67870b02bfef6afda2e625d36663da9。工作区已有大量改动；core/mediatek-native 为未跟踪目录，不能把下面实现表述为这个 HEAD 内的干净代码。

子模块输入包含 revision 与内部补丁：llama.cpp 为 4ceb1719101f32637b841206c172f3f058ffc182，stable-diffusion.cpp 为 16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0，tokenizers-cpp 为 acbdc5a27ae01ba74cda756f94da698d40f11dfe；三者都有内部修改，方案不得覆盖这些补丁。

| 事实 | 当前证据 | 工程含义 |
| --- | --- | --- |
| MCA 已有真实 MediaTek 调用分支 | app/LocalImageGenerationOperations.kt:393；LocalImageProvider.kt:107 | 继续补齐该分支，无需另开生产入口 |
| 当前 runner 使用 LiteRT 2.3.0 的 Accelerator.NPU | core/mediatek-native/MediaTekNeuronGraphRunner.kt:363；build.gradle.kts:26 | 不含最新独立 spike 的混合调度 |
| MCA 已有 prelude/block/final 门禁与诊断回调 | MediaTekAnimaPipeline.kt:340、389、446；app/LocalImageGenerationOperations.kt:146 | 门禁存在不等于门禁与合法数值分布一致 |
| 实际分区证据仍缺失 | MediaTekNeuronGraphRunner.kt:495；MediaTekAnimaPipeline.kt:209 | 请求 NPU 的成功调用只能记为归因未验证 |
| 当前 MCA 门禁最大绝对值全局上限为 1,000 | MediaTekAnimaGenerationProfileContract.kt:137、161；MediaTekAnimaTensorOps.kt:119 | 与新参考合法热块幅度冲突，必须按真实分布修复 |
| 推荐仍是待开发占位 | core/download/ModelScopeRecommendationCatalogPart3.kt:564 | 分析期间维持当前产品状态 |
| 独立 spike 当前为 1.0.17 | C:/mca-mtk-spike/mediatek-npu-smoke | 独立工程的版本、单测不能替代 MCA 集成验收 |

表中 app 源码路径基于 app/src/main/java/com/muyuchat/mca/；core 路径基于 core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/。行号是本次读取快照，后续执行前重新定位。

历史 MT6993 CPU 正常/NPU 黑图证据与 MT6899 黑图证据保持分开。新的 PC 模拟是新的证据类别，不能改写历史日志中的实际后端或把独立 APK 的结果写成 MCA MainActivity/Local API 已通过。

## 3. 先修正诊断闭环，再迁移参考方案

独立 spike 的以下缺陷由当前源码直接证明，必须先处理，不能仅在文档中解释：

| 问题 | 路径与机制 | 正确结果 |
| --- | --- | --- |
| 计划 CPU 块被算成 NPU 失败 | AnimaPackRunner.kt:870 没有 cpu-pinned 分支；:971 将其算 openFallback | 计划 CPU、量程转 CPU、实际编译/执行失败分开统计 |
| 混合成功无法得到文档的 NPU_OK | 12..21 总被算10个 openFallback；AnimaAcceptance.kt:79、86 | 验收依据实际采用的 NPU 证据及匹配基线，不能靠篡改计数通过 |
| CPU 合法热块被判 EXPLODED | BlockHealth.kt:70 为 1e5；热块参考可达约2e5 | 数值健康按 stage/block/精度契约判定 |
| CPU 重试不健康仍被采用 | AnimaPackRunner.kt:859–877 只记日志后赋值 hidden | 真正非法输出立即失败；合法幅度不误报 |
| 插件替换常被内部 CPU fallback 掩盖 | acquire:333 先NPU后CPU；外层:777 仅 pair==null 才换插件 | 尝试顺序由一个 scheduler 决定，每次 adapter 尝试只做一个后端 |
| 非有限比较值被当作未比较 | AnimaAcceptance.kt:67 用 isFinite 决定是否检查 | 区分未比较与比较无效；NaN/Inf 不能变成质量通过 |
| 最近 latent 不等于 CPU 参考 | AnimaPackRunner.kt:1506 仅挑最近同长度文件 | 基线绑模型、输入、参数、运行时、参考后端身份 |
| 验收 FAIL 后仍返回执行成功 | AnimaPackRunner.kt:1171–1173 无条件 status=OK / Result(true) | 执行完成、生成有效、质量对照、NPU归因分别建模 |

此表是复核到的 spike 缺陷，不是要求把 spike UI、原生包和所有实现复制进 MCA。迁移时把策略和测试先提炼到已有 core/mediatek-native 中，保留独立试验工程作为对照。

PC 只在块输出进行 fp16 舍入，证明的是该模拟边界下候选拆分的数值表现。它没有模拟全部中间算子、厂商融合、截断或饱和。LayerNorm 对有限整体缩放的性质不能推导它能恢复 Inf。旧粗门禁的“健康”不能证明真实 NPU 与 CPU 一致。上述限制必须保留在后续验收说明中。

## 4. 生产调用链与唯一 owner

~~~mermaid
flowchart TD
  UI[现有图片 UI / 聊天生图] --> O[ImageGenerationOwner]
  API[已认证 Local API Images] --> G[OwnerBacked Local API adapter]
  G --> O
  O --> C[LocalImageWorkerClient / executionGate]
  C --> W[既有 LocalImageWorkerService 进程]
  W --> L[NativeMediaExecutionGate lease]
  L --> P[LocalImageProvider / runtime 分派]
  P --> M[MediaTek image executor]
  M --> S[typed graph scheduler]
  S --> J[LiteRT JIT CPU / NPU adapter]
  S --> A[NPUG native adapter]
  S --> N[Neuron compiled-network restore adapter]
  M --> R[既有结果发布 / journal / Gallery]
~~~

ImageGenerationOwner 管理产品请求与业务租约；LocalImageWorkerClient 管理请求身份、IPC、取消与 worker release barrier；worker 内的 MediaTek executor 管理本次模型运行；graph scheduler 只负责本次运行的图尝试，不写 UI、不分配产品 jobId、不创建第二个全局引擎。

现有 ImageGenerationOwner.kt 中 LocalApiImageOperationGate 已将 UI 与 API 准入、取消所有权、活动模型和 awaitOwnedWorkerRelease 成对。LocalImageWorkerClient.kt:46 的 executionGate 复用进程级 RuntimeSupervisor。NativeMediaExecutionGate 的跨进程文件 lease 保护本地媒体资源；不得替换为只在 Kotlin 进程内有效的新 Mutex。

不修改 MainActivity 页面布局、不恢复天玑自检入口、不在 feature/modelhub 访问 native、不让 Local API 直接调用 MediaTek runner。API 认证、现有 HTTP/SSE 字段与错误语义、批次和图库提交时机保持兼容。

## 5. 最小契约设计：计划、尝试、实际证据分开

下列是建议结构，实施时根据已有类型合并，不是要求逐一创建接口或模块。

~~~kotlin
enum class GraphTransport { LITERT_JIT, LITERT_AOT, NPUG_NATIVE, NEURON_COMPILED_NETWORK }
enum class ComputeTarget { CPU, NPU }
enum class SelectionReason {
    DEFAULT, MODEL_RANGE_PIN, INPUT_RANGE_PIN, MEMORY_BUDGET,
    NPU_COMPILE_FAILED, NPU_EXECUTION_FAILED, NUMERIC_MISMATCH
}
data class GraphSelection(
    val graphId: String,
    val transport: GraphTransport,
    val target: ComputeTarget,
    val reason: SelectionReason
)
data class GraphAttempt(
    val graphId: String,
    val requested: GraphSelection,
    val runtimeFingerprint: String,
    val outcome: GraphAttemptOutcome,
    val actualEvidence: BackendEvidence,
    val elapsed: GraphTiming
)
sealed interface BackendEvidence {
    data object Unverified : BackendEvidence
    data class Confirmed(
        val provider: String,
        val executionScope: ExecutionScope,
        val npuPartitions: Int?,
        val fallbackPartitionCount: Int?,
        val source: EvidenceSource
    ) : BackendEvidence
}
~~~

GraphAttemptOutcome 区分资产/编译/输入/执行/输出失败、取消与成功。成功携带有类型的 tensor 输出和健康检查结果；失败不携带可被继续采用的半成品。选型原因不能伪装成失败原因，request NPU 也不能自动变成 Confirmed。

ExecutionScope区分已归因的具体graph/partition/整链覆盖；原生Neuron不一定暴露LiteRT式partition计数，因此计数可为空，不能为满足类型填造数字。Confirmed必须携带可核验的实际执行来源与覆盖范围，缺少选中device/runtime或实际compute证据时保留Unverified。

纯契约不得依赖 Context、CompiledModel、ByteBuffer 实例所有权或 Compose。资源实例只在 adapter 内；跨层元数据使用不可变标识与基本数值。已有 core:inference-contract 只有在 UI/API 确实需要稳定类型时才补窄契约，不让 feature 依赖整个 native 模块。

整图结果至少区分：execution 完成状态、输出结构有效、数值/基线质量状态、采用的后端组成、真实 NPU 归因状态。三种常见结果必须能同时表达：图片有效且 NPU 归因未验证；图片有效且混合 NPU 已确认；流程结束但质量失败。产品不得把任一条成功布尔值当成全部通过。

## 6. CPU/NPU 调度和输出提交顺序

1. 根据包中图拓扑、精度语义和已验证的范围策略生成图计划。12..21 只是当前自有图的实验策略，不能套用到所有 Anima 模型、NPUG chunk 或不同分辨率。
2. 检查本次输入的 shape、dtype、布局与有限值，再决定范围转 CPU。已经是 NaN/Inf 的状态立即终止；CPU不会凭空修复已损坏输入。
3. 执行明确的单后端尝试。JIT adapter 不自行先NPU再CPU；允许的插件版本/目录必须有来源和哈希，由 scheduler 按固定顺序尝试。
4. 对成功输出执行阶段健康检查。诊断模式可对同一输入执行 CPU 参考比较；失败时保留原输入，丢弃不健康输出。
5. 确需 CPU 重试时执行相同输入、相同语义图；CPU 输出也必须通过结构/有限值及本阶段参考范围验证。
6. 只有采用的输出通过检查才更新 hidden/latent；正负CFG分支均成功后一次提交，不能前半分支覆盖状态后再回退另一半。
7. 记录每次 attempt 和最终 adoption。整图计数从 typed adoption 派生：计划CPU、范围CPU、NPU成功、NPU失败后CPU，不再通过字符串包含关系反推。

参考 NPU 插件不可用时可以尝试同一运行时契约下的另一个已固定版本插件；不能混搭未经验证 ABI 的 .so，不能拿硬编码系统路径隐式替代缺失依赖。NPUG 没有等价 CPU 图时，不能假设它存在 CPU fallback；报告具体 native 编译/加载/执行错误。

## 7. Bundle 兼容性：保留旧包，显式扩展 payload

保持 MCA 外层 v1 envelope、required components、大小/哈希、路径保护、事务安装和原有模型识别语义。当前 Anima v3 的结构和 fixture 继续可解析；未知 schema 必须返回明确不支持，不能猜测为 GGUF/CPU。

建议增加新的 MediaTek payload schema 版本，由外层明确引用：

~~~json
{
  "schema": "mca.mediatek-image/4",
  "modelIdentity": {"architecture": "ANIMA", "modelHash": "<sha256>"},
  "executionVariants": [
    {"id": "npug-aot", "transport": "NEURON_COMPILED_NETWORK", "manifest": "configs/npug-aot-v1.json"}
  ],
  "conditioning": {"mode": "DYNAMIC_TEXT", "contract": "configs/text.json"},
  "generationProfiles": ["configs/reference-profile.json"]
}
~~~

上例只是新内部 schema 草案；既有 envelope 字段在实施前以真实 parser 核对，不要求旧包凭空增加字段。自有JIT模型保留各自的模型身份和旧v3合同，不能因都叫Anima而作为同一个modelHash的可互换变体。transport 不能单独推导精度、算子支持或CPU能力。

| payload | 必需契约 | 不可偷换的含义 |
| --- | --- | --- |
| JIT TFLite | 图/asset hash、tensor ABI、LiteRT插件版本、精度请求、拓扑 | FLOAT32 I/O不保证内部FLOAT32 |
| LiteRT AOT | 经证实可加载的stub、编译产物及hash、编译器/runtime ABI、tensor和量化契约 | 这是独立格式能力，不代表当前公开包已有stub |
| NPUG native | 容器版本、chunk顺序、张量/量化记录、配套native ABI、conditioning/modulation与VAE | 自有容器不能默认由CompiledModel直接加载 |
| Neuron compiled network | NPUG拓扑stub、.dla编译产物、runtime恢复API版本、tensor/量化ABI、shape与完整性 | NPUG stub没有TFL3标记，不等于LiteRT stub |

conditioning 必须明确 dynamic text、固定 context 或外部文本服务。固定 ctx.bin 只能声明固定conditioning能力；不能让用户输入任意prompt后仍生成同一个预计算上下文，并称为动态文生图。Qwen/T5 tokenizer、padding、权重、文本编码器、context adapter、sigma/modulation、latent/VAE缩放和CFG顺序都要绑定包版本。

兼容性依据具体native加载与执行结果，设备元数据用于推荐与运行变体排序。失败记录到具体包、ABI或图阶段，可尝试其它实际具备组件的兼容变体。

包升级采用新目录完整校验后原子切换，保持旧已安装包可恢复；失败不能覆盖旧manifest。公开权重/二进制的来源、许可证、分发权和校验清单独立核实；公开可下载不自动授予产品再分发权。

## 8. NPUG / AOT 原生适配的实施边界

同日参考APK的native审计已发现：npug::initApi通过dlsym解析NeuronModel_create/addOperand/setOperandValue/setOperandSymmPerChannelQuantParams/addOperation/identifyInputsAndOutputs，以及Compilation/Execution和restoreFromCompiledNetwork V1/V2；Graph::run另有LitertGraph::run分支。这证明参考实现至少包含自有NPUG到原生Neuron构图、编译/执行和编译网络恢复能力，不能简化成全部经过LiteRT的插件替换。具体包类型选择哪个分支、张量如何绑定、恢复参数和回退时机仍以同日精确路由分析为准，符号存在本身不证明每次调用均走该分支。

公开ModelScope模型仓本次核实master为08bfcdb5d00874fc382bf5407fdf4fb38edf71e7（build360），最新soc_index中mt6993 tiers=[t1024,t2304]。完整tree进一步核实公共目录aot/stub/*.npug确实提供拓扑stub，L1024s4文件为1,306,364 B，与manifest.stub记录的大小相符；整文件检查为NPUG magic且没有TFL3标记。因此正确产物组合是“公开NPUG拓扑stub + Neuron .dla”；不能沿用早先仅查看SoC子目录而得出的“无stub”结论，也不能把它等同于可直接交给LiteRT加载的stub。应按原生Neuron构图/恢复契约绑定两个文件及其hash、容器版本、tensor ABI。机型索引作为包变体排序线索，兼容性最终以具体native装载与执行结果验证。

参考模型的二进制布局也已经推翻旧报告中的形状推断，必须作为新payload的独立版本契约：

| 组件 | 本次读取到的头部/尺寸 | 接入要求 |
| --- | --- | --- |
| ACTX context | version=1，维度字段256、1024；1,048,592 B = 16 + 256×1024×4 | 本文件是256×1024 FP32，不能按512×1024 FP16解释；轴语义和分支绑定另按实际调用核实 |
| AMOD modulation | 头部[magic,1,N,2048,28]；已见N=6/8/12；总长度20 + N×2048×(9×28+2)×2 | 每个N单元含28块各九段4096 B和final两段4096 B；按头部及实际调用解释N，不由文件名s4猜为4步，也不把九段猜成旧三路语义 |
| ATXW text.bin | version=1，428项目录 | DEX已接线nativeLoadText与nativeEncodePrompt；任意prompt质量仍需运行验收，不得把存在接线等同生成质量通过 |
| NPUG AOT topology stub | L1024s4=1,306,364 B；magic=NPUG；无TFL3 | 与匹配.dla及manifest原子绑定，禁止喂给要求TFLite flatbuffer的入口 |

因此不能复用旧Anima v3的context形状、步数、mods切片偏移或采样调度来解释参考包。parser必须按magic/version、维度、精度、字节长度和真实拓扑逐项校验，并为ACTX/AMOD/ATXW/NPUG分别建立最小可分发头部fixture与错误长度测试；运行时读取的sigma/step循环和final modulation索引应由DEX/native证据和CPU/参考执行共同锁定。

完整小stub解析进一步给出确定边界：ditc1c为8,416 tensors / 3,481 operations / 70 inputs / 1 output；dva1为8,210 / 3,398 / 68 / 1，引用ID有效且metadata恰好结束于EOF。stub数据区为0 B，但metadata仍保留4,865 / 4,744个非零dataSize；ditc1c的metadataLen + max(dataOffset+dataSize) = 443,597,568，精确等于完整NPUG体积。这证明它是剥离权重的拓扑，不是可以自行JIT的完整图。

NPUG parser必须显式选择FULL_GRAPH或STUB_TOPOLOGY模式。FULL_GRAPH必须验证每个constant的数据区边界与字节数；STUB_TOPOLOGY允许合法的外置constant范围描述，但不能读取不存在的数据，必须绑定匹配DLA并标记不可独立重建。DLA恢复失败后，只有另行安装并完整校验了匹配FULL_GRAPH及其权重，才可尝试NPUG→Neuron构图；只有stub时停止该变体，禁止自动把它送JIT或填零。70/68个输入要求完整graph route合同，不可压成旧三路modulation接口。

动态文本的实际链已由REA/DEX核对：GenerateService.ensureText复制assets/tok.bin后nativeLoadText(text.bin,tok.bin)；applyPrompt调用qwenPieces/t5Pieces再nativeEncodePrompt(...,model.lk,cacheKey)。可以nativeLoadTextNpu(qwenGraphs,adapterGraphs,...,512,512,needMB)，内存紧且DiT已常驻时使用文本CPU，编码后releaseTextNpu。ensureText失败会明确日志“退回固定ctx.bin（提示词不生效）”，随后loadCtx读取ACTX为float[]交nativeSetCtx。MCA应保留动态文本能力并对文本资产缺失报具体不可执行原因；固定ctx只用于明确的参考诊断模式，不能静默替用户prompt。证据为rea-method-work-GenerateService-{ensureText,applyPrompt,loadCtx}.json及对应源码文本。

参数能力必须显式协商。此Anima coroutine只向nativeGenerate传steps、seed、Progress和double[]；negative与cfg仅用于History.add，未传入该native执行。新payload应声明supportsNegativePrompt、cfgMode及effectiveDefaults；不能把MCA现有正负双分支CFG直接套到这条链。对于非默认、不支持的negative/CFG请求，在worker执行前返回现有明确unsupported参数错误；只有产品本来允许且用户可审查的默认配置才按该模型实际语义执行，并在metadata记录有效参数。不能静默忽略界面/API字段，也不能要求用户批准一个尚未具体的实现。

AMOD loader按两字节原样读取，无加载层缩放；nativeGenerate每块复制九段4096 B，final另复制两段4096 B。九段的语义名称尚未全部恢复，不能仅因字节数等价就命名为当前三路接口。w16 context路径按目标tensor.scale对FP32 context做除法、clamp和有符号舍入后写int16，另一路才使用FP16缓存。tensor契约必须区分IEEE_HALF与SCALED_SIGNED_INT16，绑定scale、zeroPoint及舍入/饱和规则并测饱和率，不能一律按FP16读写。

实现上保持两类原生adapter：NPUG adapter只对FULL_GRAPH负责已核实容器到Neuron model的图重建；compiled-network adapter对STUB_TOPOLOGY + DLA负责明确版本的恢复API和输入输出绑定。当前首个实验采用后者。LiteRT adapter只有另外取得匹配标准TFLite AOT及依赖时才启用，不假定公开NPUG包包含该文件。两类Neuron adapter可以复用同一个有owner的Neuron API loader，但不能共用含糊的“load任意文件”入口。

在 core/mediatek-native 内新增窄 native adapter 与JNI桥接，仅暴露 open/run/cancel-if-supported/close 及证据能力；不要把参考 App 的 Activity、下载页或全局单例迁入 MCA。最小桥接应能回答：初始化参数、模型容器加载、输入tensor绑定、执行、输出tensor读取、实际device证据、释放顺序。

分析APK得到的JNI签名只能证明调用ABI表面，不能自动证明内部tensor语义或稳定性。优先采用可复现、许可明确的上游源码；仅有二进制时标明ABI/版本锁定、缺失调试面和维护退出条件。未经核实的函数地址、猜测结构体和符号字符串不得进入生产。

将预编译产物直接作为候选模型独立验收，比先自行离线重编译更快判断参考链可复现；随后才评估自有模型AOT导出。无法获得厂商SDK时，将“自编AOT”保持BLOCKED，不把社区现成包归为不可用，也不把社区包等同自有模型。

## 9. 参考基线与数值门禁

基线身份至少包含：模型/图/资产hash、transport与runtime/plugin版本、prompt处理后token/context hash、seed与RNG、分辨率、步数、sigma、CFG、negative分支、latent布局及VAE参数。记录完整指纹，日志中不展开用户原始私密内容。

基线CPU输出必须标记其真实后端并通过其自身数值/结构检查。不得从输出目录挑“最近同长度文件”当参考。每次比较返回 NOT_COMPARED、MATCH、DIVERGED、INVALID；有基线但计算NaN/Inf属于INVALID，缺基线属于NOT_COMPARED，二者都不能被写为“质量已验证”。

门禁分三层：

- 不可妥协的结构/数值要求：shape、dtype、布局、字节数、输入输出全部有限、正负分支对应、更新后latent有限。
- 按真实参考校准的阶段分布：每个block/chunk/prelude/final/vae的合法std、absmax与变化范围；把现有全局1000和spike的1e5替换为有证据的阶段规则，不单纯放宽断言。
- 同输入CPU/NPU误差：relL1、绝对最大误差、必要分位数/归一化误差，阈值与量化精度、模型版本和解码质量绑定。实验的0.3–3倍std或0.05 latent relL1不是所有模型的公理。

所有比较先使用相同输入，以定位首次分歧；仅整链与CPU最终latent不同不能定位是哪个块、采样、文本或传输引入偏差。额外覆盖VAE解码图像与语义响应：PNG体积、像素std能拦截部分黑图/噪声，不能单独证明图像正确。

## 10. 实际后端归因与证据格式

按graph/chunk记录 requested target、actual provider、runtime/plugin fingerprint、partition数量/非CPU标志及证据来源。取不到就保存null或UNVERIFIED，不用NPU请求参数、运行速度、文件名、JNI布尔参数替代证据。

若参考native只能给nonCpu/fully指标，先确认指标来自真实执行图接口还是调用配置；证据应注明覆盖的粒度。NPU参与不等于全部算子上NPU，18/28块也不等于耗时比例或64%的整图加速。

运行证据清单包含APK hash/签名/版本、源码HEAD与补丁、依赖二进制hash、模型清单hash、设备OS/runtime版本、有效参数、每阶段耗时/后端/错误、最终latent/PNG hash、质量对照身份、取消与释放结果。图归因与整图采用状态分开；诊断失败的尝试不能计为最终NPU贡献。

## 11. 编译驻留、内存与缓存预算

MCA 当前generation内sessions map会持有打开的图到finally；spike按block释放则可能每步重新编译。两者是不同权衡，不能不测量地复制任一个。

先测cold/warm open、compile、run、transfer、text、VAE与总耗时；测worker/native/RSS、共享映射、其它进程及可获取NPU内存。根据实际峰值制定 residentBytes、最大session数和可取消预取深度；默认单任务、单图或有限相邻chunk驻留，不并发加载28块。

缓存key至少包括图/权重hash、shape、精度、runtime/compiler/plugin/dispatch版本、编译选项与真实目标ABI。只有插件明确实现且实际cold/warm命中才记“编译缓存生效”；缺GetCacheKey时记录不支持，不能靠创建缓存目录宣称优化完成。

参考Graph::load采用只读mmap，dropWeightPages调用madvise(MADV_DONTNEED)，析构执行munmap；run分routed/streaming/linear，routed有join与下一图prefetch；generate先释放DiT再建/跑VAE。MCA先实现串行有界驻留、文本/DiT/VAE阶段切换，再测量后决定预取；不能同时readBytes所有约GB级产物，也不能把madvise文件页回收当驱动内存已释放。预取有容量、失效、取消与close owner；没有测得收益前不增加并行编译、永久native缓存或后台保活。

## 12. 取消、进程隔离与释放证明

继续使用既有worker进程隔离和release barrier。每次图编译前后、正负分支之间、块间、步骤间、VAE前后与发布前检查取消；新回调携带requestId/epoch，过期回调不能改新任务状态。

参考nativeCancel只置原子标志，生成迭代读取标志；未观察到能硬中断驱动调用的接口。nativeGenerate与release持有/等待同一全局mutex，析构先join预取；MCA按合作式取消等待当前native调用返回，不能在UI线程等待全局release。沿现有watchdog/worker终止路径处理不可中断编译或run，确认进程死亡/资源释放后才放行下一业务租约。不得从另一线程关闭仍在run的native句柄制造竞态。

创建顺序是运行时→graph/session→buffer；释放按buffer→session→runtime，finally覆盖成功、失败、取消与打开半成品。独立close失败记录诊断且继续释放其余资源；输出先写请求临时目录，通过终态和质量要求后才交给既有Gallery提交。取消/超时不得留下已发布半张图或错误成功响应。

## 13. 弱模型执行切片与文件写集

执行前重读架构合同、真实源码和dirty diff。每片只改指定边界；新增文件如与既有类型重叠先合并。以下均是未来实施任务，本次没有改动它们。

公开参考包主线的依赖顺序是S0→S1/S2→S4→S5→S6；S3是自有TFLite JIT路线的独立改进，不是验证现成NPUG stub+DLA的前置。S1负责产品公共验收闭环，S2负责完整格式/route合同，不能等到native整图后才处理。

### S0：锁定参考与模型证据（不动生产行为）

- 写集：docs与artifacts中新的审计清单、公开模型manifest/下载来源/许可证说明；不提交APK和模型大权重。
- 记录参考APK hash、包版本、native依赖、公开模型revision与组件hash；区分反编译事实、符号推断和真机实证。
- 验收：能从清单复现依赖来源；尚未核实的ABI、许可、conditioning明确标NOT_RUN/BLOCKED。回滚：移除本片报告即可。

### S1：纯调度和验收闭环（不触碰native）

- 主写集：core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekNeuronContract.kt；新增MediaTekGraphScheduler.kt与相应测试，必要时拆出小型execution evidence类型。
- 实现typed selection/attempt/adoption、计划CPU和失败fallback统计、质量状态；删除字符串backend反推业务规则的需要。
- 测试至少包含：18NPU+10计划CPU不算open失败；reference-NPU失败后bundled-NPU成功；全部CPU；CPU重试非法终止；正负分支原子采用；NaN/Inf比较INVALID；无基线NOT_COMPARED；验收失败不能映射成功。
- 验收：测试经真实调用转换覆盖，不手工注入理想计数替代runner映射。回滚：未迁移生产调用者前可删除新策略；迁移后回退同片调用点。

### S2：包schema与真实能力（不改旧v3含义）

- 主写集：MediaTekAnimaBundleContract.kt、MediaTekAnimaManifestJson.kt；app/MediaTekImageBundleManifest.kt、LocalImageProviderBundleManifest.kt；相关manifest/readiness测试。
- 添加新payload版本与transport分派、NPUG组件/tensor/conditioning契约，保留全部旧v3 fixtures与外层v1事务安装。
- 测试：旧包仍能解析；损坏hash/越界路径/缺组件/未知schema拒绝；NPUG不会落入TFLite或GGUF；FULL_GRAPH缺constant拒绝，合法STUB_TOPOLOGY可解析但无匹配DLA不可执行，DLA失败且无完整权重禁止JIT；70/68个I/O引用完整且路由可核对；固定context能力不会被当动态prompt；不支持negative/CFG参数在执行前明确返回错误。
- 回滚：已有包继续走旧解析器；新schema未完整安装不得激活。不得为了此片修改推荐下载或UI布局。

### S3：JIT单后端adapter与数值策略

- 主写集：MediaTekNeuronGraphRunner.kt、MediaTekAnimaPipeline.kt、MediaTekAnimaGenerationProfileContract.kt、MediaTekAnimaTensorOps.kt及tests。
- adapter一次只做指定CPU/NPU，scheduler负责顺序；替换全局absmax上限为阶段合法参考策略；完整trace携带step/branch/block/request；CPU输出同样验证。
- 测试复用同输入CPU/NPU边界fixture，覆盖合法约2e5的CPU热块与非法NaN/Inf；保留校验和、shape、buffer安全、取消清理。
- 验收：compile通过+逻辑测试不等于硬件通过。回滚：按transport局部选择旧runner，不影响其它图片后端和已安装模型数据。

### S4：参考NPUG/AOT最小native执行

- 主写集：core/mediatek-native内新的窄JNI/native adapter、该模块构建与固定依赖清单；必要时app打包规则仅由整合owner修改。
- 第一目标是已锁定公开NPUG拓扑stub + Neuron DLA的一个chunk在真实native运行时load/restore/run/close并取回tensor；先明确输入输出ABI和route，再接完整conditioning/采样/VAE。没有匹配标准TFLite文件时不实施LiteRT AOT装载分支；没有完整权重时不实施stub的JIT回退。
- 不复制第三方Activity/下载器，不在MainActivity调用JNI，不在Java层猜结构体；许可或必需接口缺失则停在明确BLOCKED，不换成假结果。
- 验收：单chunk实证、重复执行、失败释放、buffer边界；再做整链CPU/参考对照。回滚：新adapter可整体不装配，旧JIT/CPU路径保持可运行。

### S5：接入现有worker与产品终态

- 主写集：app/LocalImageGenerationOperations.kt、LocalImageGenerationContracts.kt、LocalImageProvider.kt、MediaTekAnimaDiagnosticLog.kt；仅确有协议需求时改LocalImageWorkerProtocol/Journal及其兼容fixture。
- 将新executor装入已有MediaTek runtime分支；UI/API仍经ImageGenerationOwner与LocalImageWorkerClient；合并typed结果到现有metadata，不改外部必需字段。
- 测试：UI/API共享busy/租约、取消作用于正确request、过期回调隔离、worker死亡、等待release barrier、失败不进图库、合法输出只提交一次。
- 回滚：停止装配新transport即可，不能删除用户模型、图库、历史或改应用ID。

### S6：真机验收后更新推荐状态

- 写集仅在前述结果实际通过后涉及core/download推荐清单及feature/modelhub必要展示；冻结布局与入口位置保持。
- 兼容性依据具体native执行结果，设备元数据用于推荐；生产MainActivity与已认证Local API需完成真实加载、生成、失败、取消及释放验收。
- 推荐模型必须指向真正可下载、组件完整、许可核实且与transport匹配的包；没有该证据就继续待开发，不把分析结论当发布依据。

## 14. 分层验收与停止条件

| 层级 | 最小证据 | 失败时动作 |
| --- | --- | --- |
| L0 解析/依赖 | 来源、hash、schema、ABI与必需组件一致 | 停止本transport，不改旧包 |
| L1 纯策略 | S1/S2/S3边界测试，实际执行数量和退出码 | 修复规则，不删断言 |
| L2 native smoke | 真运行时单图load/run/close，输出tensor与实际归因 | 定位编译/执行/量化/接口，禁止假NPU成功 |
| L3 完整链正确性 | 同输入CPU或参考导出基线、逐块首次分歧、最终latent和VAE图像 | 有等价完整图时窄化回退，否则记录具体route/restore/数值错误 |
| L4 产品集成 | MCA MainActivity与认证Local API、失败、取消、恢复 | 修复owner/worker/协议，不建旁路 |
| L5 稳定与性能 | 多seed/prompt/支持profile、冷暖耗时、内存、重复运行与进程释放 | 保留正确路径，限制明确功能而非夸大加速 |

不得用固定PNG字节数、58条JVM单测、dex有常量、NPU请求参数、参考App能出图或PC模拟无NaN任一项替代L3/L4。性能对比在同设备、模型、有效参数、构建和相近热状态下记录样本与波动；CPU fallback产图可作为正确性结果，不能计为NPU加速。

出现非有限状态、错误tensor ABI、参考本身不健康、输出不对应请求、stub无匹配DLA且无完整权重、70/68输入route缺项、取消后旧结果提交、release未确认等情况必须停止当前请求并保留证据。缺少外部SDK只阻塞自行编译AOT的分支；不阻塞已存在合法预编译包的独立验证，也不授权降级为虚假通过。

## 15. 本文验证与交接说明

本次完成源码/配置/测试内容只读审计和方案设计，没有运行Gradle、native编译或设备测试。历史XML仅作为已有测试覆盖范围参考，没有改日期宣称本次PASS。真实MTK混合执行、NPUG原生链、完整MCA UI/API及发布验收均不能由本文件宣告通过。

实施负责人逐片记录：起点HEAD和dirty输入、文件owner、调用者迁移、实际检查与原始证据、未验证项、回退点。共享根构建/公共契约/装配只有一个整合owner，构建使用项目既有锁。禁止以缩短大文件、新建模块数量或模型下载成功宣称接入完成。

## 16. 当前实施状态（2026-10-31，追加）

本节只记录任务书的落地进度，不改变上文的设计与边界。

- **S0 完成**：10 个公开资产按锁定 revision 复算一致；profile、70/68 route、四 chunk 结构已锁定。
- **S1 完成**：`MediaTekGraphScheduler.kt` 接入 v3 pipeline，typed attempt/adoption、计划 CPU 与失败 fallback 分开、质量/归因状态分离；core 模块 72 项测试通过。
- **S2 完成**：`mca.mediatek-image/4` 合同、严格 JSON 解析、route/量化/conditioning/参数能力校验、app 端 v3/v4 分派已落地；v3 路径保持兼容，v4-only 包在 readiness 明确报告执行路径未接入 image worker；app 受影响回归 148 项通过。锁定 profile 的 v4 manifest 已作为测试资源通过真实 route 校验（core 73 项）。
- **输入基线**：70/70 已绑定。`rope_32x32.bin` 由 `nativeSetGrid` 按 2 B 元素读入、`nativeGenerate` 取中点切成两个 `[1024,128]` int16 输入（DERIVED，非真机）；`[1,1,256]` 是 ctx 之后由参考实现 memset 的 256 元素 int16 零填充槽位，语义角色仍未定。字节级调用方输入包 `reference-input-pack.json` 覆盖 #1–#69，共 1,315,328 B（SHA-256 `56d589e8…`）；hidden #0 是引擎滚动状态。见 `artifacts/.../rope-split-evidence.json`、`zero-slot-evidence.json`、`single-chunk-probe-plan.json` revision 3（`unresolvedSlots=[]`）。
- **S4 实现完成，真机 NOT_RUN**：`MediaTekNeuronRuntimeAdapter` + JNI 桥（`src/main/cpp/mediatek_neuron_adapter.cpp`）已实现 `probe → restore → padded I/O → execution → compute → close`；ABI 来自 MediaTek 公开 API 文档（`neuron-api-signatures.txt`），运行时 dlopen 厂商库，构建期不链接任何厂商二进制。arm64/x86_64 `.so` 构建成功，core 98 项测试通过；`probeOnly`、`restoreBindRunClose`、`vaeOnly` 与四段链 `fullChain` 冒烟均已编译。尚未接入 `ImageGenerationOwner → worker`（S5）；真机恢复/执行、padding、内存与输出统计仍 NOT_RUN。
- **S3 未开始；S5 已接线，S6 待设备**：S3 是自有 JIT 数值路线，不与 S4 互为前置；S5 已把 v4 执行接入 worker（见下），但真机结果与 S6 验收仍未完成。
- **完整链数据契约（部分）**：公开树包含四块 DiT DLA（dva1/dva2/dva3/ditc1c）和 VAE DLA（26 MB），**五件资产已本地全量复算一致**；dva2/dva3 的 68 输入 route 与 VAE I/O 已本地解析：`[1,64,64,16]` int16 scale 1.901989e-4 → `[1,512,512,3]` int16 scale 4.065646e-5，NHWC。四块 DiT 输出的量化尺度与下一块输入尺度完全衔接。MCA v3 的 NCDHW VAE 布局与参考 NHWC 不同，必须显式转置并 dequantize。证据 `chain-dataflow-evidence.json`；采样/CFG/调制语义与真机执行仍未完成。
- **68 特征已解析（2026-10-31）**：dva1 的 `[1,1024,68]` 不是“64 + 4 个条件通道”，而是 **17 通道 latent（16 VAE 通道 + 1 个恒零 pad 通道）× 2×2 patch**；feature = `c*4 + patchY*2 + patchX`。最终块 `[token,64]` 使用相反打包（通道最快）。txt2img 下第 17 通道恒零，所以 64..67 为零；这由模型自身 68→2048 prelude 合同和两份独立实现确认，但**未**由参考 APK 真机运行证实，inpaint 语义仍未解析。见 `first-chunk-condition-evidence.json`。
- **AMOD 编码已解析（2026-10-31）**：`modsq16s4_1024_6.bin` 的 16 位单元是 **有符号 int16 量化值，不是 IEEE FP16**。首段按 int16 解码为 min -24267 / max 19954 / std 1285 / 0% 饱和；按 binary16 解码在第 1 个元素即为 NaN。载荷按 step-major、每步 28×9 段后接 2 个 final 段、每段 4096 B，由各 route 输入的 tensor scale 在图内还原为浮点，因此必须原样复制字节。见 `amod-encoding-evidence.json`。
- **S5 已接线，真机 NOT_RUN（2026-10-31）**：v4 包在 `LocalImageGenerationOperations.kt` 中不再被拒绝，而是走 `executeMediaTekAotGeneration`：校验 profile 与参数能力、按 pinned size+SHA-256 解析 route/资产、`route.verifyAgainst`、构建 chain plan、用 Torch MT19937 初始化 latent、调用 `MediaTekNpugExecutor`（每段 DLA restore→run→close，段间与 VAE 前检查取消），输出 PNG 和 metadata（per-chunk 统计、sigmas、`deviceVerification=NOT_RUN`）。readiness 对完整链返回可执行，对少于 2 个 variant 或缺少固定 ctx 的包报 incomplete。v4 目前只支持固定 ACTX；按参考实现缺文本资产时的回退，prompt 被接受但**显式标记不生效**（progress 提示 + metadata `promptApplied=false`/`promptSuppressed=true`），不再整单拒绝，因此 Local API（要求非空 prompt）与 UI 都能跑到执行链；动态文本仍未实现。另用真实 kit 的 manifest/route/ACTX/RoPE/AMOD/latent 做宿主 dry-run（假 runtime 产生与输入相关的非恒定输出）：6 步×4 段+VAE 全部通过；断言证明 patch tokens 随 Euler 更新变化、AMOD 段随 step 前进、hidden 沿链传递、同一 latent 两次运行逐字节一致、不同 latent 产生不同 PNG。设备数值仍未验证。执行器另有健康门：任一段输出恒定或 VAE 输出恒定即拒绝，不把坏状态传给下一段或发布黑图。资产在每次生成开始时按 pinned size+SHA 解析一次（避免每段重复哈希 447 MB DLA）。`fullChain` 设备冒烟已改为直接跑生产 `MediaTekNpugExecutor`，不再复制槽位装配；app androidTest lane 的既有编译错误已用一行测试桩修复，现可编译。链计划还校验四段共享的 context/zero/RoPE 形状必须一致，避免运行到中途才因槽位尺寸失败。Local API 的执行证据门改为按 runtime 分支：MediaTek 要求 `backend`/`runtime`/`outputSha256` 且不得 `fallback`，QNN 保留 `nativeEffective`/`nativeGenerationSequence`/`npuActive`/`qnnGraphExecution`；v4 metadata 增加 `nativeExecution`/`fallback`。此前 API 统一按 QNN 形状校验，MediaTek 结果即使成功也会被拒。core 105 项、app MediaTek 22 项测试通过。core 101 项、app MediaTek 13 项测试通过；app main 编译通过；app androidTest 仍有既有的 `VideoNativeBudgetDeviceTest` 编译错误，与本改动无关。真机恢复/执行、MainActivity 与 Local API 验收仍 NOT_RUN。
- **链编排已实现，真机 NOT_RUN（2026-10-31）**：`MediaTekNpugChainPlan.kt` 把 4 个 variant 绑定为 0..27 块的四段链（每段 7 块×9 段，末段另有 2 个 final 段），校验 68/68/68/70 输入、连续尺度、RoPE 两半、输出元素数与 VAE 输入一致，并提供 patch/unpatch、Euler 更新和 VAE 转置+重量化；`MediaTekNpugModulationFile` 读取 AMOD。`fullChain` 设备冒烟与 `tools/mediatek/build_chain_kit.py` 已就绪（kit 已本地生成并由解析器/规划器验证），但**没有 MediaTek 设备，链执行、numerics 与取消/内存验收仍 NOT_RUN**。
- **v4 VAE 合同**：profile 的 `vae` 现在声明 transport、DLA/NPUG asset、输入输出 NHWC dims/dtype/scale/zeroPoint；样例使用 `vaedec_64.dla`。旧的 `.npug` 强制已移除。`MediaTekNpugVaeCodec` 已实现 int16 dequant/quant（half-away、饱和）与 NHWC↔NCDHW；`vaeOnly` 设备冒烟已编译。
- **参考 sigma 调度**：从 `nativeGenerate` 反汇编恢复 `t=1-i/steps`、`sigma=3t/(2t+1)`（i=0..steps，末值 0）；v4 profile 新增 `sigmaSchedule`，`MediaTekNpugSigmaSchedules` 与测试已落地。采样与 CFG 组装仍未实现。core 88 项、app 8 项测试通过；真机未验证。

### 完成审计（2026-10-31，按原目标逐项）

| 目标项 | 现有证据 | 状态 |
|---|---|---|
| 1. 固定模型/分辨率/步数/资产清单，建立带身份的参考张量基线 | S0 锁文件、v4 profile、70/68 route、`reference-input-pack.json`、`build_chain_kit.py` 与真实 kit 宿主 dry-run；身份由 manifest `modelIdentity` 与全资产 size/SHA pin 约束 | 输入侧完成；**输出基线未采集**（需真机） |
| 2. 修正执行/质量/归因契约，独立 payload schema，保留 v3 | v4 合同、严格解析、route 校验、请求拒绝、`MediaTekGraphScheduler`；v3 合同与测试保留 | 代码/测试完成 |
| 3. 窄 Neuron adapter 单 chunk restore→bind→run→close | adapter + JNI + 单 chunk 设备冒烟，已编译；真机未运行 | 代码完成；**真机 NOT_RUN** |
| 4. 对齐 conditioning/调制/采样/VAE，再补动态文本 | 固定 ACTX、AMOD int16、sigma/Euler、VAE 转置+重量化、68 布局、四段链计划；动态文本已实现：ATXW v1 428 项 FP16（0x2A80）+ ATOK v2（Qwen BPE/T5 unigram）+ Qwen3-0.6B 28 层 + 6 层 adapter，`encodePrompt` 按路由 ACTX 长度（256）截断/Qwen 空输入 151643/T5 EOS=1；`DYNAMIC_TEXT` 已接 `contextValues`。核心 30 项宿主测试通过（含真实 1.46 GB 权重），app main/core androidTest 编译通过；app 单测被外部文件阻塞。尚未与参考运行时数值对照，negative/CFG 仍显式拒绝，inpaint 未实现。证据 `artifacts/architecture/2026-10-31-mtkpaint-rea/atxw-text-evidence.json` | 代码完成；宿主验证通过；真机未运行 |
| 5. 接回 Owner→worker→provider，请求身份/租约/取消/释放屏障 | v4 分支接生产 executor，取消检查、资产一次解析、健康门；租约与释放屏障沿用原链路 | 代码完成；**真机 NOT_RUN** |
| 6. MainActivity + 认证 Local API 的正确性/连续/取消/内存/性能验收，更新推荐状态 | 代码侧已排除两个 API 可达性阻塞（prompt 显式回退、按 runtime 的证据门），并有 `t08-npug-acceptance` 设备/主机脚本；无设备、无验收记录 | **NOT_RUN** |

仍未满足：真机 Neuron 恢复/执行与数值、单图输入/输出基线、动态文本、CFG/负面提示、inpaint 语义、连续生成/取消/内存/性能验收、推荐状态更新。完成审计不改变这些结论。

参考入口：[morluto/rea](https://github.com/morluto/rea)。APK与REA分析见[同日参考实现分析报告](MCA_MTKPAINT_REA_ANALYSIS_2026-10-31.md)、[原生调查](../artifacts/architecture/2026-10-31-mtkpaint-rea/native-findings.md)、[公开模型调查](../artifacts/architecture/2026-10-31-mtkpaint-rea/model-findings.md)。本文件只把已核实接口映射进架构，不把扫描字符串或历史乐观描述提升为设备通过结论。
