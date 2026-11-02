# 天玑 NPU 生图问题深度分析与修复方案

日期：2026-10-28  
状态：基于当前仓库、设备记录和独立 spike 源码形成的执行方案；本文件不代表 NPU 已修复或产品已接入。  
目标：先查清 Anima NPU 黑图的可复现根因，再建立正确性保护，最后按 MCA 现有图片执行架构接入；任何阶段证据不足都保持 `UNVERIFIED/BLOCKED`。

## 1. 结论

当前有一个**已证实的 NPU 数值失败**，但没有已证实的单一根因，也没有 MCA 产品级天玑 NPU runtime。

MT6899 上独立 smoke App 的 CPU 流程输出与 PC 参考接近；同一 spike 的 NPU 流程中 28 个 DiT block 均报告 delegate/非 CPU 状态，但最终得到约 2.2 KB 的纯黑 PNG。LiteRT 的 `nonCpu=1`、`fully=1`、run status OK 或图被 delegate 接管，都不代表输出被正确写回或数值正确。旧文档中“设备 NPU 确实正确执行”的判断已被 10 月 28 日根因报告修正，应以修正后的判断为准。

还要区分两条链路：

1. **独立 spike**：包含 TFLite block、Kotlin sampler 和 ONNX VAE，用于调试目标机算子和数值。现有 CPU 对照使用 PC 预计算的 text context/modulation，不是完整手机端 prompt-to-image。
2. **MCA 产品**：Anima 当前正式 runtime 是 `STABLE_DIFFUSION_CPP`。仓内 `core:mediatek-native` 只有数据契约、SDK marker 检查和 preflight；app 未依赖该模块，且无 Neuron JNI、delegate runner 或图执行器。独立 smoke 的 CPU/NPU 结果不能替代 MCA `MainActivity`、`ImageGenerationOwner`、worker 和 Local API 验收。

因此目前状态是：**根因诊断待 MT6899 运行 1.0.4；生产接入尚未开始；NPU 生图未达到可交付状态。**

## 2. 证据分级

| 项目 | 当前证据 | 判断 | 说明 |
|---|---|---|---|
| Anima CPU 数值基线 | 独立 smoke 在 MT6899 上 4 步；latent 相对误差约 `5.68e-6`，图像 PSNR 约 `39.75 dB` | `PASS`，仅限 spike CPU 对照 | 输入 context/modulation 由 PC 预计算；不是产品端完整 prompt 链路 |
| Anima NPU 输出 | 28 个 block 返回成功/被 delegate 接管，最终纯黑图，PNG 约 `2,227 B` | `FAIL` | 输出可以是未写、全零、NaN/Inf 或其他错算；黑图本身不能区分根因 |
| delegate 覆盖 | `nonCpu=1`、`fully=1` | `UNVERIFIED` 的执行线索 | 不能证明 NPU 侧实际计算和回写，更不能证明语义正确 |
| 1.0.4 诊断 APK | 已构建；文件 `D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.4.apk`，SHA-256 `cb5c93acbc802cbfdbd89c016994a731e4bc9e5cd94f8131f6a5e40557b9fbeb`；外部 build log 为 `BUILD SUCCESSFUL` | 构建 `PASS`；设备诊断 `NOT_RUN` | 约 647 MiB；安装、Kotlin 探针驱动、资产读取和 MT6899 运行都没有本轮设备证据 |
| 探针覆盖范围 | 3 个 block00 格式变体、10 个微算子、对照图；`block00_int8_full` 因需要真实量化输入而设计为 `NOT_RUN` | 仅定位线索 | 单个 block 和微算子通过不证明 28 block × 多 step 的完整 pipeline 正确 |
| MCA MediaTek runtime | `core:mediatek-native` 无 native runner，`app` 无该模块依赖，runtime 枚举无 MediaTek 项 | `NOT_IMPLEMENTED` | SDK marker 或 manifest preflight 不是 runtime 可用证明 |

### 已排除或不能再作为首要假设的判断

- CPU spike 与 PC 结果一致，说明该 spike 中 sampler、block CPU、VAE 和 PNG 路径可作数值参考；不能据此排除产品 MCA CPU/native 入口问题。
- 10 月 28 日第二版根因报告将 int8 weights-only / per-channel `DEQUANTIZE` 降为较低优先级。不能因为插件二进制里有 `Int8 is not supported.` 就直接把 int8 定为根因。
- 插件 strings 找不到 `NEURON_SLICE` / `NEURON_TRANSPOSE`，以及静态解析得到 SoC 支持列表，都只是线索。旧设备日志声称图编译成功，两者有冲突；需用目标机加载的真实插件、SoC 参数和编译日志解开。
- `toByte(NaN) == 0` 可以解释黑色像素的一种可能路径，纯黑并不证明 latent 中有 NaN。

## 3. 问题树和优先级

### P0：NPU 正确性根因仍未知

目前可保留的主要假设，按需由设备证据裁决：

1. NPU 执行中间把 FP32 松弛为 FP16，激活溢出或精度崩坏。
2. 单个或多个算子在 MediaTek legalizer/Neuron 上静默错算，例如 `SLICE`、`TRANSPOSE`、`GELU` 或相邻 reshape/广播逻辑。
3. dispatch / tensor-buffer requirements / padded buffer 契约错误，调用返回但逻辑输出区未正确写回。
4. 目标机实际加载的插件、runtime、SoC target 或 MDLA 能力与编译目标不匹配。
5. int8 权重反量化或其他格式路径存在问题。

这些假设可能同时成立。没有可信的 1.0.4 设备报告前，根因状态必须写 `UNKNOWN`，不要先转换全量模型格式或改模型图。

### P1：探针虽可执行，结果解释仍需约束

- `ProbeMatrix.kt` 会打印比较差异，但 NPU/CPU 与 PC reference 的差值主要是诊断字段，不会自动按逐模型阈值产出最终 `PASS/FAIL`。测试者或后续执行者容易把 `run=OK`、输出有变化或 `fp16Equivalent` 当作模型正确。
- `expectedSanity` 是统计量范围检查，且只针对 `block00_fp32` CPU 输出；它是必要的输入接线 sanity，不等于整组真实输入和输出逐元素 golden 验证。
- block00 使用真实 step0 输入的前提是完整 5 个输入齐全且形状/字节数匹配。每个可比较探针都必须在报告中实际看到 `inputSource=pack_step0`，不能只引用 APK 打包时的资源盘点。
- poison 结果必须按逻辑 tensor bytes 解释，明确区分模型 packed bytes、requirements buffer bytes、读回 bytes 和 padded 尾部。只扫描错误长度会把合法 padding 误判成未写回，或把 padding 写入误判成输出有效。
- 外部 APK 测试说明写“16 个模型”，但 `block00_int8_full` 有意 `NOT_RUN`；报告应按实际结果汇总 `PASS/FAIL/NOT_RUN`，不能把计划数当成功样本数。
- 整轮异常时 `MediaTekNpuSmokeActivity.kt` 的异常分支会用简短异常文本覆盖 probe progress 内容；Anima pack 异常分享也存在丢失前序详细 evidence 的路径。这可能使唯一远程设备数据无法定位。

### P2：即使数值修好，当前 NPU 路径也没有性能优势

独立 runner 的 `runGraph` / `runGraphPair` 在每次调用后 `release(name)`。4 步、28 个 block 的生成会触发约 112 次图编译。设备记录约 268 秒在编译，总耗时约 356 秒，而 CPU spike 约 216 秒。因此这是**确定的性能缺陷**，但它不是黑图根因，必须在数值正确性之后单独修复。

不能简单把 28 个编译图全部留在内存：按每图约 64 MiB 估算可接近 1.8 GiB，且需要计入 delegate 内部内存、模型权重、VAE、系统压力和热降频。要先实测峰值，再选择 request-scoped 有界缓存或 runtime 支持的离线编译缓存。

### P3：打包和故障证据存在独立正确性缺陷

- `ZipSource` 当前暴露容器 APK 的 fd；native 侧若使用 `offset=0`、`size=fstat(container)`，读到的是整个 APK/zip 而不是 zip entry。当前 `packSource=files` 把它掩盖了。应改为校验后提取单模型临时文件，或严格传 entry `dataOffset` 和 `uncompressedSize`，并验证压缩方式、长度、边界及 hash。
- 诊断异常覆盖 progress 的问题会影响复现和远程支持，应在下一轮请求用户运行前修复并重新构建小体积诊断包；大资产矩阵 APK 是否需要重打由代码改动决定。

### P4：MCA 契约和调用链无法表达真实模型

- `MediaTekAnimaManifest` 当前 stage 唯一，不能表达 28 个有序 DiT block。
- 一个 `MediaTekGraphContract` 只有单个 shape/dtype/layout，不能描述多个不同 tensor 的 IO contract，也未表达量化参数、buffer requirements、block 顺序和每张图的 hash/provenance。
- `MediaTekExecutionEvidence.provesNpuExecution` 使用全局 partition 计数和 stage 集合；无法说明每个 graph 实际在哪个 delegate 上执行，也不能正确描述“DiT 走 NPU、文本编码/VAE 走 CPU”的 hybrid 模式。需完善证据模型，不能通过放松现有断言来迁就混合执行。
- `MediaTekSdkResolver` 的 marker 命中只说明某些文件存在，不是 SDK/API/ABI/目标架构可用，更不是运行通过。

## 4. 修复阶段

依赖顺序如下。每一阶段都可独立停止；上游未达到退出条件时，不进入下游产品化。

### 阶段 0：锁定基线和不变量

**目的**：避免把工作区里其他并行代码当成本次修复，或覆盖已有 native 工作。

**动作**：

1. 保存 `git rev-parse HEAD`、分支、`git status --short`、递归子模块状态和各脏子模块的差异清单；建立本次方案实际写集。
2. 本轮已核实基线为 HEAD `68b6eef1e67870b02bfef6afda2e625d36663da9`、分支 `codex/enterprise-refactor`、约 704 条 status 项；stable-diffusion.cpp、tokenizers-cpp 子模块均有本地差异。旧 handoff 中“子模块未动”不能作为当前构建基线。
3. 保持 `AGENTS.md` 和 `UniversalDeviceAdmissionPolicyTest.kt` 在 `.git/info/exclude`，不得发布这两个本地 safeguards。
4. 将独立 spike、MCA 生产应用、模型转换与设备测试分别记录 SHA/version，禁止用一个项目的 PASS 替另一个项目背书。

**写集**：执行记录/evidence 索引；除非后续阶段明确授权，不更改现有生产代码和脏子模块。

**通过条件**：执行者能精确指出测试 APK、源码 revision、模型包 hash、插件/runtime 版本和目标设备；无法绑定时标记 `NOT_REPRODUCIBLE`，停止比较结论。

### 阶段 1：修复独立探针的证据保全和判读

**目的**：先保证唯一远程设备的一次运行不会丢报告，并确保输出有可重复的判据。

**建议写集**（仓库外 standalone 工程）：

- `C:\mca-mtk-spike\mediatek-npu-smoke\app\src\main\java\com\muyuchat\mca\mediateknputest\MediaTekNpuSmokeActivity.kt`
- `...\ProbeMatrix.kt`
- 必要时 `...\app\src\main\cpp\mtk_runner.cpp`
- standalone probe 的 host/unit tests、APK manifest/资产盘点和 tester instructions。

**实施项**：

1. 用 append-only journal 保存完整 progress。最终报告写入独立文件，附带 journal 路径/hash；异常时只追加 `status=FAILED stage=... error=...`，绝不 `writeText(exceptionSummary)` 覆盖之前的记录。每个探针完成、开始和失败都落盘并 flush。
2. 报告 header 固定记录 APK version/code/hash、probe inventory、每个 asset 的 SHA-256/长度、Android API、设备 SoC 原始属性、LiteRT/runtime/plugin 版本、runner ABI、运行参数、开始/结束时间。
3. 把每个探针结论做成机器可判读字段：`status=PASS|FAIL|NOT_RUN|INVALID_INPUT`、`reason`、input source、逻辑元素数、packed/buffer/readback bytes、finite/zero/unchanged 统计、CPU/NPU/PC golden 指标和阈值。
4. 比较必须先验证相同 shape/dtype/元素数。至少报告 `maxAbsDiff`、`meanAbsDiff`、`relL1 = sum(abs(y-yref))/max(sum(abs(yref)), epsilon)`、NaN/Inf 计数。`fp16Equivalent` 是一个参考信号，不是总体验收状态。
5. 将 poison 统计严格限定在 output logical region；额外报告 padded tail，不把它混入数值比较。分别记录“buffer 未变化”“全 0”“非有限”“有限但偏差超阈值”。
6. 输入 sanity 失败时，将该探针 NPU 结论标 `INVALID_INPUT`，不要继续拿它支持任何根因。要求五个 block00 输入都来自 `pack_step0` 且大小精确；`block00_fp32` CPU 输出还需与 PC golden 做逐元素比较，不只比均值/标准差。
7. 给当前可执行探针配置逐模型阈值和版本化配置；初始建议 block 数值候选阈值 `relL1 <= 1e-3`、无 NaN/Inf、shape/元素数相同、输出不全零且 poison 无残留。绝对误差和 golden 阈值必须用 CPU 重复运行、模型量化误差和设备 runtime 做校准；任何阈值未校准时写 `DIAGNOSTIC_ONLY`，不冒充产品 PASS。
8. 把设备报告中“计划探针数”和实际 `PASS/FAIL/NOT_RUN/INVALID_INPUT` 个数分开。`block00_int8_full` 是有意 NOT_RUN，不能计入双路样本。

**验证**：

- 主动注入异常、单探针异常、磁盘写入失败，确认 journal 保留已完成结果和末尾错误。
- 测试输出区填充 `0xCD`、全 0、NaN/Inf、正确输出、padded buffer 五类 fixture，确认分类正确。
- 在无设备环境运行 parser/fixture 测试；构建 APK 并核验签名、asset hash、总体大小。此阶段构建 PASS 仍不等于目标机 PASS。

**退出条件**：每条探针证据可单独判读；故障不会覆盖已落盘数据；probe report 可确认真实输入与 logical output 范围。

### 阶段 2：在 MT6899 上运行一次决定性诊断

**目的**：验证根因假设，不重跑完整盲测、不先生成数 GB 新模型包。

**设备操作**：

1. 确认测试者手中的 APK SHA 是阶段 1 产物；保存旧版 evidence 后再覆盖安装，不清应用数据、不卸载旧版。
2. 运行矩阵一次，并分享完整最终报告和 append-only journal。若安装、运行或分享中断，也要先取回现有文件，标清中断阶段。
3. 对每项结果依序确认：资产 hash/长度通过；五个输入 `inputSource=pack_step0`；CPU `expectedSanity=PASS` 且 CPU 对 PC golden 有效；input/output shape、dtype、元素数和 bytes 一致。任何前置项失败则该探针为 `INVALID_INPUT`。
4. 保存 plugin 原始日志，特别是实际 `soc_model`、加载的 plugin/runtime/version、编译目标、分区数量、失败 op、dispatch buffer requirements、各图 run 的错误。

**结果决策表**：

| 有效观测 | 结论 | 下一步 |
|---|---|---|
| 多个 NPU `open=FAIL` | runtime/plugin/SoC target/SDK ABI 尚未成立；不是数值问题 | 先核对真实 plugin 版本、SoC 参数、Neuron API/ABI 和设备 runtime；修 transport/target 后仅重跑受影响探针 |
| output logical region 全是 poison，或 readback 无数据 | 缓冲区绑定、requirements、sync/writeback 路径失败 | 只调 C++ buffer allocation/type/size/lock/copy；用 sum/FC fixture 和单个 block 做对照，不能以 run status 0 放行 |
| 输出写过但有 NaN/Inf；FP32 变体有限且正确 | 倾向内部降精度/范围溢出 | 用真实输入和幅度扫描找首次非有限位置；检查 relaxation 设置、敏感 op 的中间 dtype；在最小图复现后修精度或图表达 |
| FP32 通过、FP16/INT8 失败 | 格式/量化路径有因果证据 | 核对转换输出、量化 scale/zero point 和设备 runtime；只对必要层换精度并重新做完整 28 block golden |
| 单算子微探针明确偏离 CPU golden | 该算子/legalizer 或 buffer 语义可疑 | 缩成最小模型复现并核对原始 op 参数；再选择模型改写、受支持算子等价展开或局部 CPU 执行 |
| block00 所有候选格式都通过 | 只能说明该 block/该输入通过 | 不结案；进入阶段 3 的全 28 block、全 step 追踪 |
| probe input sanity、PC golden 或 asset 检查失败 | 实验无效 | 修探针资产或输入 plumbing 后重新实验，不做根因推断 |

**特别处理 SoC 矛盾**：静态 plugin strings/SoC 列表与旧报告“28 图成功”相冲突。必须依据此轮真实设备日志确认实际载入的 plugin 文件、版本/hash、`soc_model` 入参和编译产物；不要先做 MT6899 allowlist 或将未知设备拦截。

**退出条件**：至少一个候选图的执行状态与数值结果可重复，能形成具体命中点或把问题定位到“runtime/plugin/target 尚未打开”。若报告仍矛盾，修测试仪器，不进入全量转换。

### 阶段 3：在独立 spike 中修正数值根因和验证全图

**目的**：由最小复现修 NPU 输出；将“单块通过”推进到 28 个 block、多 step、VAE 语义图像正确。

**根因定位要求**：

1. 对同一张图、同一组输入、相同 runtime 运行 CPU、NPU 和 PC reference；把预处理/输入 hash 固定下来。
2. 先在 `block00` 确认根因，再用同一修复方式验证全部 28 个 block；逐块记录输入输出 hash、shape/dtype、range、finite、relL1/maxAbsDiff、delegate/CPU op 计数。
3. 单块通过后跑完整真实 step0，再跑固定 seed 的全部采样 step。记录 `prelude`、每个 block、`final`、latent 和 VAE 输入的中间统计/hash，找到第一个与 CPU 分叉的 block/step。
4. 候选格式必须先在 PC CPU 上与既有参考/原模型对齐，再在目标 NPU 上比较。不得根据 NPU 失败直接把全模型改成 fp32，也不得把单块 fp16 PASS 推广为全网 PASS。
5. 最终图像门禁检查 PNG 解码成功、尺寸正确、像素方差/非黑分布正常、latent 无 NaN/Inf，并与同 seed CPU 图对比。临时诊断图、格式兄弟对照和产品可接受视觉质量是不同门槛，需分别记录。

**数值门槛建议**：先用 CPU 对同一图连续运行校准 nondeterminism。block/latent 候选门槛可从 `relL1 <= 1e-3` 且无 NaN/Inf 开始；完整固定 seed 输出可从 PSNR `>= 35 dB` 开始作为诊断验收建议。最终值应由实际 Anima 质量要求和多次 CPU 基线确定；未校准的临时值不能伪装成既定产品规范。无论阈值如何，纯黑、全常数、shape/元素数错误、非有限值始终是硬失败。

**写集**：仅 standalone spike 的图转换器、Anima runner、native LiteRT/Neuron wrapper、探针/回归测试和转换配置；每次改动保留 PC 原始参考和候选模型 hash，不污染 MCA 产品源码。

**退出条件**：全部 28 个 block、全部采样 step、VAE 和输出图像在设备上通过有效数值/语义门禁；至少重复两次固定 seed 运行；所有候选 artifact 与 runtime/plugin hash 记录齐全。否则返回阶段 2 对第一个失败层迭代。

### 阶段 4：加入安全的运行时健康门禁和诚实 fallback

**目的**：任何 delegate 静默错算都不能变成“成功的黑图”。

**每个 graph 调用后的最低门禁**：

- native status 成功；实际 delegate/partition/cpu fallback 计数被记录；
- output buffer logical bytes、元素数、dtype、shape 符合 manifest，padding 单独处理；
- output poison 无残留；全部数值有限；不是全零/全常数；均值、标准差、极值落在由对应 stage/block 参考分布校准的健康范围；
- 关键模型/固定 smoke 输入做 CPU golden 差分。线上普通 prompt 不必每次完整跑 CPU gold，但仍需执行有限值、结构和范围 guard；定期 smoke 用固定输入做严格比较；
- 最终 PNG 做 MIME/解码/尺寸和退化图检查。失败时不提交图库、不向用户报告成功，不保存为有效历史产物。

**Fallback 语义**：

- 若同一个 graph 的 CPU 执行契约可用，则使用完全相同输入在 CPU retry，并用健康的 CPU 输出替换该 graph 的 NPU 输出；证据标成 `HYBRID`，列出 graph-level 实际 backend 和 fallback reason。
- 若无法安全在该层切换，放弃当前 NPU 任务并按现有 owner 错误/重试语义处理；不能把零值或 NPU 失败输出继续送到后续层。
- fallback 不改变请求身份、参数、取消语义或图库提交 owner；不能偷偷新建 NPU 队列/owner。
- `fullyAccelerated` 与“图片生成成功”是两个独立字段。delegate 覆盖率只说明覆盖情况，数值/语义门禁才说明结果正确。

**测试**：fake runner 注入全 poison、NaN、Inf、全零、shape mismatch、成功但大偏差、CPU retry 失败、取消时释放等情况；证明坏结果不可提交、fallback backend 记录正确、native handle 恰好释放一次。

**退出条件**：对所有失败注入，均不会生成 success evidence 或保存坏图；fallback/最终失败能通过同一请求 owner 正确结算。

### 阶段 5：修复编译复用和模型包来源

**数值闭环之后再做**，避免性能优化掩盖错误。

**编译复用**：

1. 分开计时冷 compile、热 run、图释放、整个请求；同设备/相同 seed 对比 CPU 和 NPU/hybrid。
2. 测编译后 RSS/PSS、delegate 内存、峰值临时文件和连续运行热降频。
3. 评估 SDK/offline compilation cache。若 runtime 无持久缓存，采用 request-scoped 的有界 LRU/串行缓存；额度按设备内存压力决定，不基于 SoC allowlist 过滤功能。
4. cache key 至少含模型 SHA、转换器版本、runtime/plugin 版本、设备编译 target、precision/shape/signature；cache invalidation 和 native handle 销毁归同一个 runner owner。
5. 取消、异常、worker 终止和进程死亡路径都须释放 handle/cache 临时资源。不得在没有内存数据时一次性常驻 28 张图。

**模型包来源**：优先将 zip entry 经边界/hash/长度验证后流式解压到 app cache，再交 native 按真实文件长度打开；若采用 fd region API，要求显式使用 entry `dataOffset` 与 `uncompressedSize`，拒绝压缩 entry 不匹配、越界和长度溢出。添加 Stored/Deflated entry、损坏目录、越界 offset、文件截断 fixture。

**退出条件**：重复 step 不再无谓重新编译；内存上限和热表现有实测记录；file 与 embedded pack 两种来源结果相同，错误来源清晰报错。

### 阶段 6：完善 MCA contract 并接入唯一生产执行链

仅在阶段 3、4 有设备 PASS 且 SDK/runtime/许可/分发条件确认后启动。此阶段之前，ModelHub 中的独立 smoke 入口不是产品 NPU 入口。

**契约先行**（重点改 `core/mediatek-native/.../MediaTekNeuronContract.kt`）：

- Manifest 允许 `TEXT_ENCODER`、有序 `DIFFUSION_BLOCK[0..27]`、`VAE` 等多 graph；每个 graph 有稳定 ID、stage/index、artifact 路径/hash/size、转换器/模型/runtime/plugin provenance。
- 每个 graph 独立描述每个输入/输出 tensor 的名称、dtype、shape、layout、quantization、logical bytes 和允许的 buffer alignment/padding；不能用一个 graph 级 shape 表达所有 IO。
- 定义图间依赖顺序、签名兼容、必要/可选组件和混合执行 policy。manifest 解析需拒绝路径穿越、duplicate ID/index、hash/size 不符、签名不兼容、缺图和无效拓扑。
- execution evidence 按 request、stage、graph 记录 `requestedBackend`、`actualBackend`、delegate/runtime version、执行次数、CPU fallback ops、compile/run latency、输出健康指标和错误；运行时报告的 partition 必须能映射到该 graph。
- 分开 `FULL_NPU`、`HYBRID`、`CPU`、`FAILED/UNVERIFIED` 语义。`provesNpuExecution` 不能用某一处全局 partition 数和三个 stage 的集合证明所有 block 都执行正确；完整 pipeline 和 DiT-only NPU 应有不同、可审计的谓词。
- SDK resolver 检查确切头文件/API、ABI、所需 runtime/plugin 库、版本和许可来源。单个 `VERSION`/AAR marker 命中只作为 provisioned hint，不得给 `READY` 执行状态。

**生产接入**：

1. 增加独立 `MEDIATEK_NEURON` runtime/accelerator 语义，不复用或改写 `QNN_HTP` 的 Snapdragon/QNN 含义；在 schema/枚举持久化变化处做旧 bundle/历史兼容测试。
2. 在 `:core:mediatek-native` 内实现 typed JNI bridge、runner 生命周期、manifest/artifact 校验、取消和释放；C++ runner 只拥有 native handle，不持有 UI/Activity。
3. 由 app composition root 构造并注入 runtime adapter。现有任务仍走 `ImageGenerationOwner` → worker → `NativeMediaExecutionGate` → provider/runtime 分发 → 结果校验/图片资产提交；UI、Local API 和后台 Worker 共享这条 owner 和结果终态。
4. 继续使用现有参数、seed、prompt、requestId、lease、cancel、journal 和图库提交链。不要在 debug 按钮、Composable、Local API route 或新服务里另建队列和状态写入者。
5. 下载、导入、加载和运行操作对所有用户保持可见。硬件检测只用于推荐或 runtime transport 选择；未知 device/profile 退回通用兼容路径，最后由真实 native load/graph smoke 判断。禁止芯片/型号 allowlist。
6. 允许 DiT 在 NPU、文本编码器和/或 VAE 在 CPU 的明确 hybrid 产品形态，但 UI/证据应如实说明阶段 backend；不把 hybrid 宣称成全 pipeline NPU。

**MCA 验收入口**：从生产 `MainActivity` 用户流程和 authenticated Local API 分别提交同一固定 fixture；验证最终图、request identity、取消、worker crash/recovery、native handle 释放、图库去重/保存和历史状态。独立 smoke App 仅作诊断工具，不作为产品验收替代。

### 阶段 7：发布前设备验收和回退

**发布门槛**：

- 当前源码、Gradle 配置、SDK/runtime/plugin、模型转换器、所有模型 artifact、APK 都有精确 revision/version/hash；Release APK 确认 ABI 与依赖打包正确，许可满足分发要求。
- 代表 ARM64 实机上 production MainActivity 与 authenticated Local API 都通过；同一请求通过同一个 owner/终态提交。
- fixed-seed 完整 pipeline 至少重复两次，28 block/step、中间 latent、VAE 输入和最终图片门禁全部通过；NPU/hybrid backend 证据逐 graph 完整。
- CPU fallback 可用；未测试或缺 profile 不阻止用户触发通用路径；失败以具体损坏/格式/缺库/native load/真实 graph execution 错误返回。
- CPU/NPU/hybrid 冷热延迟、内存、热稳定性和失败率有同设备对照；若仍慢于 CPU，也要如实将性能状态列为不达标，不隐藏 backend 差异。
- 现有 SDCPP、MNN、QNN、ONNX、自定义 runtime 测试和旧数据/模型 bundle 兼容通过。

**回退策略**：出现图数值失败时关闭该 runtime 的实际选择，任务落回现有可用 CPU runtime 或明确失败；不删除模型、不破坏任务/图片历史、不撤销下载能力。代码回退保留 bundle/schema 的向前修复策略；执行证据仍保留为失败诊断。

## 5. 验收矩阵

| 层级 | 必测项 | 通过证据 |
|---|---|---|
| 探针工具 | exception append、progress 保全、hash/大小、poison 分类、padding、parser 阈值 | host tests + APK inventory/hash + 错误注入 journal |
| Runtime 单图 | tiny fixture、单 op、block00 fp32/fp16/int8 变体 | 同输入 CPU/NPU/PC 参考、raw plugin/runtime log、逐 tensor 数值指标 |
| Anima 图 | 全 28 block、prelude/final、真实多 step、VAE | 每层执行和输出 evidence、latent/golden diff、最终 PNG 检查 |
| Runner 生命周期 | cold compile、hot reuse、cancel、异常、worker kill | 编译/运行耗时、RSS/PSS、释放计数、重启后的终态 |
| MCA 产品 | MainActivity、authenticated Local API、图库、历史、旧 runtime 回归 | 真实生产 APK/设备 evidence，绑定 requestId 和 artifact hashes |
| 通用兼容 | 无 SDK/profile、未知设备、Native load 失败、CPU fallback | 入口仍可用；状态 advisory；具体 runtime 失败可解释且不伪报 NPU |

`PASS` 必须对应该层实际测试。编译成功、JVM 单测、delegate coverage、standalone CPU 图或设备属性匹配都不能替代完整设备 NPU PASS。

## 6. 当前卡点和依赖

1. **第一阻塞：没有 1.0.4 MT6899 完整报告。** 这阻塞根因定点，不阻塞先修证据 append/报告判读工具。
2. **第二阻塞：模型单块结果不够。** 即便 block00 成功，仍需要完整 28 block × 多步与 VAE 对照，才能决定转换方案是否可产品化。
3. **第三阻塞：MCA 尚无 runtime。** SDK/runtime/ABI/许可、完整图格式与 native runner 是生产接入前置；独立测试 app 的成功不能填补这些工作。
4. **性能阻塞独立于正确性。** 约 112 次编译导致的开销明确，但优化必须在正确性闭环后进行，并避免过量常驻内存。
5. **工作树基线需谨慎。** 本方案撰写时工作区约 704 项差异，stable-diffusion.cpp 和 tokenizers-cpp 子模块已有本地修改。任何后续代码执行必须重新核对 status/submodule diff，并且只写本次授权文件集。

## 7. 下一位执行者任务清单

1. 重新读本地 `AGENTS.md` 和 `docs/architecture/MCA_PROJECT_HARD_LOCK.md`，锁定当前 HEAD/status/submodule diff；不要复用旧 handoff 的干净状态声明。
2. 仅在 standalone smoke 工程实施阶段 1 的证据保全、逻辑 bytes 分类和自动判读；不要碰 MCA 生产代码或子模块。
3. 运行 standalone host/unit tests，构建并核对 APK asset/hash/signature。若构建输出不能证明 probe journal 行为，先补故障注入验证。
4. 给测试者一份单次运行说明，要求回传完整 report 和 journal；不得要求重复盲跑整包 NPU。
5. 收到 MT6899 report 后，严格按阶段 2 的输入有效性和决策表分类；没有效设备报告时结论写 `UNKNOWN/NOT_RUN`。
6. 用最小图命中一个失败根因，再实现阶段 3，并对所有 28 block、多步、VAE 复验。单 block 的 fp16/fp32 结果只作局部证据。
7. 完成阶段 4、5 后再提交 MCA runtime contract 设计和生产写集；复用唯一 ImageGenerationOwner/worker，不加硬件准入名单。
8. 每阶段交付记录改动文件、owner/state writer、命令和 revision、设备/模型/APK hash、`PASS/FAIL/NOT_RUN/BLOCKED`、尚未验证项及回退方法。

## 8. 参考材料

- [10 月 28 日 Anima/NPU 交接](MCA_DIMENSITY_NPU_ANIMA_HANDOFF_2026-10-28.md)
- [10 月 28 日根因分析与 1.0.4 决定性实验](MCA_DIMENSITY_NPU_ROOTCAUSE_2026-10-28.md)
- [MediaTek 契约与 SDK preflight](../core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekNeuronContract.kt)
- [图片 runtime 与 Anima family 契约](../app/src/main/java/com/muyuchat/mca/LocalImageBundleContracts.kt)
- [Anima SDCPP 结果 evidence 校验入口](../app/src/main/java/com/muyuchat/mca/LocalImageGenerationSdcppOperations.kt)
- 独立 spike 关键源码：`C:\mca-mtk-spike\mediatek-npu-smoke\app\src\main\java\com\muyuchat\mca\mediateknputest\ProbeMatrix.kt`、`AnimaPackRunner.kt`、`MediaTekNpuSmokeActivity.kt`、`app\src\main\cpp\mtk_runner.cpp`。

## 9. 执行状态记录（2026-10-28 晚，lead）

### 9.1 阶段 1 = PASS（host 侧闭环）

按本文件 §阶段 1 的 8 条实施项与验证清单执行完毕，明细见 `D:\anima-spike\analysis\08-stage1-delivery.md` 与
`D:\anima-spike\analysis\06-verdict-engine.md`、`07-evidence-journal.md`。

- 新 APK：`D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.5.apk`（647,102,354 B，SHA-256 `57798279…25a813a`，versionCode 17 / 1.0.5，签名与 1.0.3 相同可覆盖安装；`assets/probe/**` 71 条目 / 638,052,567 B）。
- 验证：`gradlew --no-daemon testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`；单测 `ProbeVerdictTest` 17 条 + `EvidenceJournalSelfTest` 1 条，`failures=0 errors=0`。
- 已修的真实缺陷：探针矩阵异常分支不再 `writeText` 覆盖已落盘进度；Anima 包异常分支不再把 evidence 换成 3 行；5 处覆盖写改为唯一文件名；每条探针新增 `phase=start`/`phase=done`；报告与 journal 分离，journal 带 SHA-256 封条。
- 结论字段：`status=PASS|FAIL|NOT_RUN|INVALID_INPUT|DIAGNOSTIC_ONLY` + `reason` + `inputSource` + 五个字节口径（logical/packed/buffer/readback/padded-tail）+ `relL1`/`maxAbsDiff`/`meanAbsDiff` + `threshold=<版本>`；未校准模型只能出 `DIAGNOSTIC_ONLY`。

### 9.2 仍阻塞

| 项 | 状态 |
|---|---|
| 阶段 2 MT6899 运行（含真机 journal 行为、真 ENOSPC） | `NOT_RUN` —— 阻塞于测试者，是第一阻塞 |
| 本地 APK 安装预检 | `BLOCKED` —— 两台 HyperOS 的「USB 安装」物理开关未开（非签名/空间问题） |
| 阈值真机校准 | `NOT_RUN` —— 现用 PC 基线（int8 0.577%、fp16 2.2e-7、block00 参考 mean≈-2.98e-4/std≈0.6536） |

### 9.3 对方案的两处补充（执行中出现，建议纳入）

1. `block00_int8_full`（全 int8 激活）已在 PC 上验证 **精度不可用**（vs fp32 `relMeanAbs` 1.647、`cosine` 0.208、50.5% 输出为 0），因此它不仅"输入类型不匹配"，作为产品路线也不成立；判定表里应把它从"对照实验"降为"能力探针，永不计入 PASS 样本"。
2. 阈值里的 `relL1 <= 1e-3` 目前只有 `block00` 系列可按 PC 基线校准；10 个微算子探针的 golden 是同一台 PC 的 CPU 输出，设备 CPU 若与之偏差 > 1e-3 应优先怀疑**输入喂法/字节序**而不是 NPU。


