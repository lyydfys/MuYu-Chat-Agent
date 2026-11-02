# 天玑 NPU 交接文档（2026-10-31）

**主题**：改用参考实现的方式实现我们的天玑 NPU —— 首选验证方向 **NPUG 拓扑 + Neuron DLA**，LiteRT 保留为另一条执行通道。

**这份文档是什么**：换人/换会话后的单页交接。它记录目标、已核实事实、已锁定的资产、下一步的第一件事、以及不可越过的边界。
**它不是什么**：不是"天玑已跑通"的结论。截至本文，**没有任何真机 NPU 装载/执行证据**，也没有改过 MCA 生产代码。

**与前一份交接的关系**：`docs/MCA_DIMENSITY_NPU_HANDOFF_FINAL.md` 描述的是旧路线（自有 TFLite 图 + 混合钉块 + 独立 spike APK 1.0.17）。该路线**降级为第二条通道**，不再作为主线；其历史证据仍然有效，但不要拿它的结论代替本主线。

---

## 1. 当前状态（一句话）

参考实现的三条原生通道已静态核实（NPUG→Neuron JIT、NPUG 拓扑+DLA 恢复、LiteRT AOT），**公开资产已定位并锁定到具体字节数与 SHA-256**。**S0 已完成**：10 个资产按锁定 revision 全量下载并本地复算，SHA-256 全部一致（含 447,541,580 B 的 DLA 全量一次）；profile 已钉入 `reference-profile-lock.json`，`ditc1c`/`dva1` 的 **70/68 输入**路由合同已导出并逐项校验通过。**S1/S2 已完成**：调度/验收闭环接入现有 v3 pipeline；`mca.mediatek-image/4` NPUG/AOT payload 合同、严格解析器、route 校验与 app 端 v3/v4 分派已落地，旧 v3 解析路径保持兼容。输入基线 70/70 已绑定：#68/#69 是 `rope_32x32.bin` 的 int16 两半，#2 是参考实现零填充的 256 元素槽位（语义未定）。**S4 窄 Neuron adapter 已实现**（JNI + Kotlin wrapper；arm64/x86_64 `.so` 构建通过；full-chain 五件 AOT 资产本地复算一致）。**S5 已接线**：v4 包从 readiness 到 `executeMediaTekAotGeneration` 再到四段链执行器，取消检查、pinned 资产一次解析、恒定输出健康门、PNG/metadata 均已落地；`fullChain` 设备冒烟直接跑生产执行器。core 105 项、app MediaTek 22 项测试通过，app main 与 app androidTest 均编译；真实 kit（manifest/route/ACTX/RoPE/AMOD/latent）在宿主以假 runtime 完成槽位装配 dry-run。**真机 restore/run 与 S6 验收仍 NOT_RUN**（本机仅高通设备）。动态文本已按恢复合同实现（2026-10-31）：ATXW v1 目录 428 项、FP16 连续权重、数据区 **0x2A80**（`text.bin` 1,461,438,080 B，SHA-256 `685e2d45…`）；ATOK v2 tokenizer 已解码并在 `MediaTekAotTokenizer` 中复现（Qwen BPE + T5 unigram；6 项宿主测试通过）；`MediaTekAotTextWeights`/`MediaTekAotTextEncoder` 实现 Qwen3-0.6B 28 层 + 6 层 adapter，输出 lk×1024 ACTX；`DYNAMIC_TEXT` 已接入 `executeMediaTekAotGeneration`（contextValues）与 readiness，metadata 记录 token 数和 context SHA。真实权重前向宿主测试已通过（prompt "a"，单线程约 25.9 s，256×1024 ACTX，确定性且提示词敏感，超出 T5 行全零）；app main 与 core androidTest 编译通过；app MediaTek 定向测试 22/22 通过。**文本 lk 必须取路由 `inputs[1].dims[1]`（L1024s4 为 256），不能用 `profile.tokenCount`（1024 是 latent token）**；app 与 kit 生成器已修正。动态文本设备 kit：`build_chain_kit.py --dynamic-text --prompt ...`，驱动 `run_neuron_smoke.py --mode chain --dynamic-text`（会推送 1.46 GB `text.bin`）。尚未与参考运行时做数值对照，真机仍 NOT_RUN；S3 独立。

---

## 2. 目标与方向（已决策）

| 项 | 决策 |
|---|---|
| 主线 | 用参考实现的容器与运行时：**NPUG 拓扑 stub + Neuron 9 DLA**，先做单 chunk 的 restore→绑定→run→close |
| 第二通道 | 自有 TFLite 图的 JIT / 混合执行（旧 1.0.17 路线），用于验证自有模型与数值修复 |
| LiteRT AOT | 只有另行拿到版本匹配、组件完整的标准 TFLite AOT 才启用；**公开包里没有** |
| 不做的 | 不把 `.npug` 改后缀喂 LiteRT；不在 stub 上做 JIT 回退；不复制参考 App 的 Activity/下载器/全局单例 |

---

## 3. 已核实的参考实现事实（有证据，非推测）

1. **原生 Neuron 路径真实存在**：`npug::initApi` 通过 dlsym 解析 `NeuronModel_create` / `addOperand` / `setOperandValue` / `setOperandSymmPerChannelQuantParams` / `addOperation` / `identifyInputsAndOutputs`，以及 Compilation/Execution 与 `restoreFromCompiledNetwork` V1/V2；存在独立的 LiteRT CompiledModel 通道。
2. **公开资产已定位**：ModelScope `ledi123456789/anima-npu-dimensity9400`，revision `08bfcdb5d00874fc382bf5407fdf4fb38edf71e7`（build 360），其 APK 的 SHA-256 与用户样本**完全一致**。
3. **旧数据契约不能沿用**：ACTX 是 **256×1024 FP32**（不是 512×1024 FP16）；AMOD 每块 **九段**调制 + final 两段；部分 16 位输入是**带 scale 的有符号 int16**，不是 IEEE half。
4. **动态文本有真实调用链**：tokenizer→`nativeEncodePrompt`→context adapter；缺文本资产才退回固定 `ctx.bin`，且会明确记录"提示词不生效"。本次恢复的 Anima 调用**没有把 negative/cfg 传进 native**。
5. **旧诊断闭环有缺陷**（必须先修，否则设备结果不可信）：计划 CPU 块被计成失败 fallback、CPU 不健康输出可被采用、基线挑"最近同长度文件"、验收 FAIL 仍返回成功。

---

## 4. 已锁定的参考 profile（S0 产出，可复现）

**来源身份**

| 项 | 值 |
|---|---|
| 参考 APK | `D:/下载/base.apk.1`，53,019,224 B，SHA-256 `dcf99f85dd3ea72875b408d74c1a3b69b48d6a809cab6558558dc98d98ff4f09` |
| 包/版本 | `com.anima.npu`，0.9.9 / versionCode 360 |
| 主原生库 | `libanima.so`，738,376 B，SHA-256 `aa6815c21d15b0cb2f0f1bb7e126e21021ac717278f8bbb41a55151ed9cd5df0` |
| 模型仓 | ModelScope rev `08bfcdb5…`（= 360）；**不要**混用 HuggingFace `ledisbsb/…`（最新仍是 0.2.4/139） |

**首验资产组合（t1024 / mt6993 / np9，单 chunk `ditc1c`）**

| 用途 | 路径（模型仓内） | 字节 | SHA-256 |
|---|---|---|---|
| 拓扑 stub | `aot/stub/ditc1c_w8_w16L1024s4.npug` | 1,306,364 | `5d92980596391a3aa67e94251eccaff75d799e59d93ddd8aad589891dab0c013` |
| 编译网络 | `aot/mt6993/np9/ditc1c_w8_w16L1024s4.dla` | 447,541,580 | `3a5b1ac7310d71d68f8b9262b2b923d6954e801d594c29c952fb7dea6460307d` |
| 路由清单 | `aot/mt6993/manifest_np9.json` | 6,078 | （见证据目录同名文件） |
| 固定上下文 | `models/miaomiao8/ctx.bin` | 1,048,592 | `bd6ac6be…`（ACTX v1，256×1024 FP32） |
| 调制（N=6） | `models/miaomiao8/modsq16s4_1024_6.bin` | 6,242,324 | `d2cc0256…`（AMOD v1，N=6） |
| RoPE | `models/miaomiao8/rope_32x32.bin` | 524,288 | `efa63465…` |
| VAE 解码 | `models/miaomiao8/vaedec_64.npug` | 25,433,180 | `b0937332…` |
| 完整权重（仅对照，**不用于** stub 路径） | `models/miaomiao8/ditc1c_w8_w16L1024s4.npug` | 443,597,568 | `b0bd7e79…` |

> 上表 hash 取自 ModelScope 文件树的证据快照（`artifacts/architecture/2026-10-31-mtkpaint-rea/model-ms-tree.json`），**现已全部本地复算一致**：stub/ctx/mods/rope/manifest/VAE 全量下载，DLA 先校验首尾 1 MiB、再做 447,541,580 B 全量一次。复算命令、工具版本与逐项结果见 `reference-profile-lock.json` 的 `recompute` 与 `assets`。

**I/O 契约（`ditc1c` 已解析）**

- `graphInputIDs` = **0…69（共 70 个输入）**；`graphOutputIDs` = `[8415]`（1 个输出）
- 张量描述字段：`id / fileBegin / fileEnd / field0 / field1 / quantTag / rank / dims / quant{scale,zeroPoint} / dataOffset / dataSize`
- 已见前 3 个输入的 dims 与 per-tensor scale：
  - `id0`：[1,1024,2048]，scale≈3.3459e-4
  - `id1`：[1,256,1024]，scale≈4.6098e-5
  - `id2`：[1,1,256]，scale≈4.1209e-11
- `dva1`：**68 个输入**、1 个输出；约 8,210 张量 / 3,398 ops（与 ditc1c 的 8,416 / 3,481 同量级）
- **70 个输入不能压成旧版"三路 mod"接口**；九路调制的具体语义尚未逐项恢复 —— 这是 S2 的主要工作量

**分辨率/步数的对应关系**

- `s4` **不等于 4 步**：AMOD 实际可见 N=6/8/12 三档，文件名 `s4` 指的是别的维度
- `rope_32x32` + 1024 token ⇒ 32×32 grid（**推断为 512×512 输出的候选**，⚠️ INFERRED，需用 `nativeSetGrid` 与 VAE scale 证据确认后再写进合同）
- `vaedec_64` / `vaedec_96` / `vaedec_128` 与输出尺寸的映射同样是 ⚠️ INFERRED

---

## 5. 执行切片状态

| 切片 | 内容 | 状态 |
|---|---|---|
| **S0** | 锁定参考与模型证据（文档/证据，不动生产行为） | **完成**：10 资产复算全一致；`reference-profile-lock.json`、`ditc1c.route.json`、`dva1.route.json` 已产出；分辨率/步数规则已 ESTABLISHED |
| S1 | 纯调度与验收闭环（typed selection/attempt/adoption，计划CPU与失败fallback分开，FAIL 不返回成功） | **完成**：`MediaTekGraphScheduler.kt` 接入 v3 pipeline；core 模块 72 项测试通过 |
| S2 | 新 payload schema + 完整 route/量化/conditioning 合同，旧 v3 保持兼容 | **完成**：`mca.mediatek-image/4` 合同/解析/route 校验 + app v3/v4 分派；app 受影响回归 148 项通过 |
| S3 | 自有 TFLite JIT 单后端 adapter 与阶段化数值策略 | 未开始（独立于主线） |
| S4 | 窄 Neuron adapter：单 chunk restore→绑定→run→close，取回 tensor | **实现完成**：JNI + Kotlin wrapper + 8 项单测；arm64/x86_64 `.so` 构建通过；真机 restore/run 仍 **NOT_RUN** |
| S5 | 接入既有 `ImageGenerationOwner → worker → LocalImageProvider` | 未开始 |
| S6 | 真机验收后更新推荐状态 | 未开始 |

**依赖顺序**：S0 → S1/S2 → S4 → S5 → S6；S3 与主线并行、互不为前置。

---

## 6. S0 收尾结果（2026-10-31 已完成，可复核）

**1）资产复算**：按锁定 revision `08bfcdb5…` 下载 10 个文件到仓库外隔离目录，逐项比对 `model-ms-tree.json` 的 size+sha256，**10/10 一致**。包含此前只做过头前缀证据的文件：

| 资产 | 字节 | 复算方式 |
|---|---|---|
| `ditc1c` / `dva1` / `dva2` / `dva3` stub | 1,306,364 / 1,304,888 / 1,309,772 / 1,346,852 | 全量 |
| `manifest_np9.json` | 6,078 | 全量 |
| `np9/ditc1c…L1024s4.dla` | 447,541,580 | 首尾 1 MiB + **全量一次** |
| `ctx.bin` / `modsq16s4_1024_6.bin` / `rope_32x32.bin` | 1,048,592 / 6,242,324 / 524,288 | 全量 |
| `vaedec_64.npug` | 25,433,180 | 全量 |

命令、工具版本（curl 8.19.0 / Python 3.12.10 / coreutils 8.32）与逐项结果写在 `reference-profile-lock.json` 的 `recompute`、`assets`。复现入口：`python artifacts/architecture/2026-10-31-mtkpaint-rea/reference-profile-lock-build.txt`。

**2）分辨率与步数（原 ⚠️ INFERRED → ESTABLISHED）**

- `pixel = 16 × grid`，`VAE 文件名 = 2 × grid`（正方形只写一个数）。证据：`Catalog.clinit` 的 `Canvas(gridH, gridW, rope, vae)` 五组配对（72×56→`vaedec_144x112`、56×72→`112x144`、84×48→`168x96`、48×84→`96x168`、63×64→`126x128`）+ `manifest_np9.json` 中 L4032=1MP 的注记（63×64 → 1008×1024）。
- 因此 **1024 token = 32×32 grid ⇒ 512×512 输出，VAE 用 `vaedec_64.npug`，RoPE 用 `rope_32x32.bin`**。图契约侧独立印证：`dva1` 输出 `[1,32,32,2048]`，`ditc1c` 输出 `[1024,64]`（=32×32×64 latent）。
- **AMOD 选择规则**：`modsPath(ctx, steps)` → `<modsPrefix>_<steps>.bin`，文件头 N 等于后缀，`nativeLoadMods` 返回载入步数。本 profile 的 `modsPrefix = modsq16s4_1024`。
- 修正交接前文的说法：**N 不是只有 6/8/12**。锁定树里 1024 前缀有 **N=6…12 全档**；同一 N 下 1024/2304/3072/4096 与 s4/s7 的文件**字节完全相同**，只有 **4032** 档是另一份内容 —— 即 AMOD 只跟 N 走（4032 例外），不跟分辨率走。

**3）路由合同**：`ditc1c.route.json`（70 输入 / 1 输出）与 `dva1.route.json`（68 / 1）已导出，含每项的 `dims`、`field0/field1`、`quantTag`、`scale/zeroPoint`、`dataOffset/dataSize`，并带 6 项自检（数量、ID 合法、scale 有限、索引连续）。生成器：`model-npug-route-export.txt`。

**4）附带确立的分块结构（结构性证据，非真机）**：四张 DiT 图每张 7 个 block 的九段调制 —— `dva1/dva2/dva3` 各 63 个 `[1,1,2048]`，`ditc1c` 65 个（63+final 2 段），合计 `63×3+65 = 254 = 28×9+2`，与 28-block 模型及 `nativeGenerate` 的九段/两段复制完全吻合。即 **4 chunk × 7 block = 28 block，`ditc1c` 是含 final 的末块**。

执行顺序与张量流向（与 `Catalog` 中 `ditGraphs` 列表一致）：

| 顺序 | 图 | 首输入 | 输出 |
|---|---|---|---|
| 1 | `dva1` | `[1,1024,68]`（latent 64 + 4 条件，推断） | `[1,32,32,2048]` |
| 2 | `dva2` | `[1,1024,2048]` | `[1,32,32,2048]` |
| 3 | `dva3` | `[1,1024,2048]` | `[1,32,32,2048]` |
| 4 | `ditc1c` | `[1,1024,2048]` | `[1024,64]`（latent） |

块间 `[1,32,32,2048] ↔ [1,1024,2048]` 只是同元素数的 reshape（32×32×2048 = 1024×2048），adapter 必须显式处理该布局转换，不能把两者当成不同带宽。证据：`model-npug-chunk-crosscheck.json`。

**S0 验收状态**：`reference-profile-lock.json` 的 `status.profileLocked=true`；`recompute.result=ALL_MATCH`；路由 70/68 项与 schema 逐项对齐。**仍未解决**：`[1,1,256]`（route #2）的含义与取值、UI 的 stepChoices 集合、`s4/s7` 词缀语义、DLA restore 是否读取被剥离的权重。

### S1/S2 收尾结果（2026-10-31）

**S1（调度/验收闭环）**

- 新增 `MediaTekGraphScheduler.kt`，把「请求目标 / 实际尝试 / 采用结果」拆成 typed 记录；计划 CPU、量程 CPU、NPU 失败后 CPU 分开统计，采用必须引用成功尝试，CPU 修复标签必须有同图 NPU 失败记录。
- v3 pipeline 的真实调用点已接入：每次 `runGraph` 记录 attempt 与 adoption，`MediaTekAnimaPipelineResult` 携带 `MediaTekRunSummary`；质量保持 `NOT_COMPARED` 时不冒充通过，NaN/Inf 比较记为 `INVALID`，`DIVERGED/INVALID` 不会映射为 accepted。
- 验证：`:core:mediatek-native:testDebugUnitTest` 72 项通过（含 S1 的 11 项调度测试与全部 v3 合同测试）。

**S2（v4 payload 合同与分派）**

- 新 schema `mca.mediatek-image/4`：transport 仅允许 `NEURON_COMPILED_NETWORK` / `NPUG_NATIVE`；`STUB_TOPOLOGY` 不允许权重数据、必须有匹配 DLA 才可执行；`FULL_GRAPH` 必须带常量数据；JIT fallback 被 schema 拒绝。
- route 合同严格校验 70/68 输入的 `dims/quantTag/scale/zeroPoint/dataOffset/dataSize`、连续性、shape groups、生成器 checks；要求 stub/DLA 的 size+sha+basename 与 manifest 一致。
- 张量编码合同区分 `IEEE_HALF` / `SCALED_SIGNED_INT16`，int16 必须声明饱和与舍入；ACTX 固定为 v1 256×1024 FP32；固定 context 不得接受 prompt；不支持 negative/CFG 时在执行前返回明确错误；AMOD 强制 28 block×9 段+final 2 段。
- app 端按 schema 分派：v3 路径保持原解析器；v4 资产/route 仍须逐个匹配 MCA v1 envelope 的 required 组件；v4-only 包在 readiness 明确报告「NPU AOT 执行路径未接入 image worker」，不会被当作 v3 JIT 执行。
- 验证：app 受影响回归 148 项通过（MediaTek 4 类、LocalImageModelReadiness 67、ExecutionProfile 22、ModelStore 26、BundleRecovery 3、BundleZip 11 等），0 失败。

**输入基线（70/70 已绑定）**

- `nativeSetGrid` 按 `tokens×512 B` 校验 `rope_32x32.bin`、按 `tokens×256` 个 2 B 元素读取；`nativeGenerate` 显式计算 `begin + half*2` 中点指针，把同一缓冲切成两个 `[1024,128]` 输入。因此 route #68/#69 是 `rope_32x32.bin` 的 int16 两半：
  - #68：bytes [0,262144)，SHA-256 `e3cf6930…`
  - #69：bytes [262144,524288)，SHA-256 `c5d2a565…`
- route #2 `[1,1,256]` 已由 `nativeGenerate` 的输入装配确认：ctx（256×1024 int16）之后紧接 `memset(count×2 B)`，count=256，即 512 B 零填充；语义角色（mask/timestep/未用默认值）仍未定，但零填充是参考实现的真实行为，不是占位猜测。
- 证据与反汇编地址见 `rope-split-evidence.json`、`zero-slot-evidence.json`；`single-chunk-probe-plan.json` 已升到 revision 3，`unresolvedSlots=[]`。两处结论均为 **DERIVED**，真机未验证。
- **字节级输入包**：`reference-input-pack.json`（生成器 `reference-input-pack-build.txt`）按 native 顺序打包 #1–#69（ctx int16、零槽、block 21–27 的 63 段、final 2 段、rope 两半），总计 **1,315,328 B**，SHA-256 `56d589e8…`；ctx 量化饱和 0。route #0 hidden 是引擎滚动状态，不在调用方包内。仍是 DERIVED，非真机。
- **真实 profile 的 v4 manifest**：测试资源 `core/mediatek-native/src/test/resources/npug/reference-aot-manifest.json` 使用锁定 stub/DLA/route/ctx/AMOD/rope/VAE 的 size+sha，route 逐项校验通过；core 模块 73 项测试通过。

### S4 窄 Neuron adapter（实现完成，真机 NOT_RUN）

- `MediaTekNeuronRuntimeAdapter.kt` + `src/main/cpp/mediatek_neuron_adapter.cpp`：`probe → restore(DLA) → query padded I/O → create execution → setInput/setOutput → compute → close`。
- ABI 依据是 MediaTek 公开 API 文档（`neuron-api-signatures.txt`）：`NeuronModel_restoreFromCompiledNetwork` 填充 model+compilation；`setInput/setOutput` 的 `type` 必须为 NULL；`getInputPaddedSize`/`getOutputPaddedSize` 给出运行时 padding。
- 构建期不链接任何厂商库；运行时用 `dlopen` 尝试 `libneuronusdk.so` / `libNeuronAdapter.so` 及 vendor 路径，失败以具体错误返回。
- 适配器只做单后端、同步执行；不做 JIT 回退、不猜结构体、不静默 pad。输入必须已按 runtime padding 准备好；调用方持有 padding 责任。
- 验证：`:core:mediatek-native:assembleDebug` 构建 arm64-v8a + x86_64 `.so` 成功；当前 core 测试 **105 项，0 失败**。
- **设备冒烟链已就绪**：`MediaTekNeuronSmokeTest`（core androidTest）读取 `input-pack.bin` / `hidden-int16.bin` / `smoke-config.json`，执行 `probe → open(DLA) → 按 runtime padding 绑定 70 输入 → 连续两次 run → close`，输出 SHA/统计到 `report.json`；`vaeOnly` 用 `vaedec_64.dla` 跑 `[1,64,64,16]` 零输入，经 `MediaTekNpugVaeCodec` dequant + NHWC→NCDHW 后记录 RGB 统计。`tools/mediatek/run_neuron_smoke.py` 支持 `--mode probe|restore|vae|chain`；`chain` 模式用 `build_chain_kit.py` 生成真实 kit 并跑生产 `MediaTekNpugExecutor`。设备端 `kitAsset` 会按 manifest 的 size+SHA-256 校验每个资产（含 447 MB DLA），首次推送后预计多花数十秒，但基线可自证与锁定资产一致。androidTest APK 已构建且包含双 ABI `.so`；真机未运行。
### 完整链资产与数据流（2026-10-31 追加）

- 公开树中已有四块 DiT DLA（`dva1/dva2/dva3/ditc1c`，`aot/mt6993/np9/`）和 VAE DLA（`vaedec_64.dla`，26,432,870 B，SHA-256 `90c9dccb…`）；**五件 AOT 资产已全部下载并本地全量复算，size/SHA 与树完全一致**（`reference-aot-chain-assets.json`）。
- `dva2`/`dva3` 的 68 输入 route 已本地解析并导出；四块输出→下一块输入量化尺度完全衔接：
  - dva1 out `2.7253208827e-5` = dva2 in
  - dva2 out `3.1735206721e-4` = dva3 in
  - dva3 out `3.3459131373e-4` = ditc1c in
- VAE 是完整 NPUG 图（753 tensors / 277 ops，metadata + data = file size），运行时用 `vaedec_64.dla` 恢复；I/O 为 **`[1,64,64,16]` int16 scale `1.901989e-4` → `[1,512,512,3]` int16 scale `4.065646e-5`，NHWC**。
- MCA v3 的 VAE 路径是 NCDHW（`[1,16,1,64,64]` / `[1,3,1,512,512]`）；元素数一致但布局不同，S4/S5 必须显式转置并 dequantize，不能直接按 NCDHW 喂 VAE。
- 证据：`dva2_w8_w16L1024s4.route.json`、`dva3_w8_w16L1024s4.route.json`、`vaedec_64.contract.json`、`reference-aot-chain-assets.json`、`chain-dataflow-evidence.json`。仍为 DERIVED，真机未验证；采样/CFG/调制语义与转置实现尚未完成。
- **v4 合同已补 VAE**：profile 的 `vae` 现在带 transport（`NEURON_COMPILED_NETWORK` / `NPUG_FULL_GRAPH`）、asset、input/output dims/dtype/layout/scale/zeroPoint；样例使用 `vaedec_64.dla` + `[1,64,64,16]→[1,512,512,3]` NHWC。旧的“VAE 必须是 .npug”限制已移除，避免把 DLA 误判成 full graph。core 88 项、app 8 项测试通过。
- **参考 sigma 调度已恢复**：`nativeGenerate` 的 `t=1-i/steps`、`sigma=3t/(2t+1)`（i=0..steps，末值 0）由反汇编确认，见 `sigma-schedule-evidence.json`；v4 profile 新增 `sigmaSchedule: SHIFTED_FLOW_MATCHING_3T`，`MediaTekNpugSigmaSchedules` 已实现并测试。
- **S5 已接线**：v4 包在 `LocalImageGenerationOperations.kt` 中走 `executeMediaTekAotGeneration` → `MediaTekNpugExecutor`；readiness 对完整链返回可执行，对 <2 variant/无固定 ctx 且无文本资产报 incomplete。执行前按 pinned size+SHA 解析资产一次；每段 restore→run→close，段间与 VAE 前检查取消；任一段或 VAE 输出恒定即拒绝。**v4 支持 FIXED_CONTEXT 与 DYNAMIC_TEXT**：固定 ctx 的 prompt 被接受但显式标记不生效（progress + metadata `promptApplied/promptSuppressed`）；动态文本走 ATXW/ATOK → Qwen3+adapter → `contextValues`，metadata 写入 token 数、context SHA、耗时与统计；文本资产缺失/解析失败报 `mediatek_aot_text_invalid`/`mediatek_aot_text_encoding_failed`，不回退固定 ctx。
- 真机恢复/执行、实际 device 名称、padding、内存与输出统计均 **NOT_RUN**。

**下一轮第一件事**：在一台 MediaTek 设备上依次运行 `run_neuron_smoke.py --mode probe --install --build`、`--mode restore`、`--mode chain`（真实 kit 全 6 步），通过后再做 MainActivity / Local API 的 S6 验收。没有设备时不要改推荐状态。

---

### S6 Local API 验收脚本（2026-10-31 追加）

`tools/device/t08-npug-acceptance.ps1 -ModelId <image-model-id> [-Runs 2] [-Serial ...]` 是主机包装器（校验 adb 在线、推送执行、拉取 evidence 到 `artifacts/device/npug-acceptance`）；设备侧脚本 `tools/device/t08-npug-acceptance.sh <image-model-id> [runs] [out-dir]`：在已安装 APK、已导入 v4 bundle 的设备上，经认证 Local API 连续生成 N 次（b64_json → PNG 签名/字节数/SHA），再用 `X-MCA-Request-Id` + `/v1/generate/stop` 做一次在途取消；输出 `NPUG_ACCEPTANCE_PASS/FAIL`；默认断言响应 `execution` 含 `promptSuppressed:true`（固定 ACTX 的显式回退），可用 `EXPECT_PROMPT_SUPPRESSED=no` 关闭。API key 默认经 `run-as` 读取，非 debuggable 构建用 `MCA_API_KEY` 覆盖。脚本不安装、不构建、不改应用。API 响应证据门按 runtime 分支：MediaTek 只要求 backend/runtime/outputSha256 且不得 fallback（v4 metadata 已写入 PNG 的 SHA-256），QNN 保留原 nativeEffective 合同；否则 MediaTek 成功结果会被 QNN 形状校验拒绝。

### 设备首跑判据（`--mode chain`，2026-10-31 追加）

- **成功**：`report.json` 的 `status=COMPLETED`；`chunks` 共 24 条（6 步 × 4 段），每步四个 variant 都在；`output.pngBytes>0` 且 `output.pngSHA256` 非空；`runtime.status=READY`；`modelIdentity`/`manifestSHA256` 与本地 kit 一致。
- **可诊断的失败**：`status=FAILED` 且 `error` 含 restore/rc → 记录具体阶段；任一段输出恒定或 VAE 恒定 → 健康门拒绝（不得当成功）；资产 SHA 不符 → 推送/锁定问题，先修资产再跑。
- **基线内容**：`latentSHA256`、`modelIdentity`、`manifestSHA256`、24 个 chunk 的 sha/min/max/mean/std、最终 PNG SHA。它们与 kit 的 latent 共同构成“锁定模型单图基线”。
- **边界**：不要求与参考 APK 输出逐位相同——参考初始噪声 PRNG 尚未恢复；本基线证明的是“锁定资产 + 已恢复契约”下的可重复执行。
- **失败后**：保留 `report.json`、logcat 和 kit；不要通过放宽 manifest/资产 pin 或跳过健康门来“通过”。

### 当前验证矩阵（2026-10-31 收尾）

| 检查 | 结果 |
|---|---|
| `:core:mediatek-native:testDebugUnitTest` | **PASS**，123 项 / 20 类（2026-10-31 在 `finalLatent` 结果字段加入后全量复跑；含四段链执行器、真实静态/动态 kit dry-run、AMOD/VAE/sigma/route、S7 文本与缓存） |
| S7 文本定向测试（2026-10-31） | **PASS**，32 项：tokenizer 6、ATXW 4、真实 1.46 GB 权重编码 1（256×1024、确定性、提示词敏感、零填充）、执行器 7（含外部 context）、manifest 12（含动态 kit 解析）、文本上下文缓存 2 |
| 动态文本设备 kit 构建 | **PASS**（`--dynamic-text`，`textLk=256`，text.bin/tok.bin verified pins） |
| 动态 kit 端到端宿主 dry-run（2026-10-31 最新） | **PASS**：`MediaTekNpugKitDryRunTest` 2/2（17.947 s）。真实 `build/chain-kit-dynamic` + 真实 1.46 GB `text.bin`/`tok.bin`，prompt `"a cat"` → tokenizer → Qwen3+adapter → 256×1024 ACTX → 生产 `MediaTekNpugExecutor` 四段链（假 NPU runtime）→ 24 chunk runs → 512×512 PNG 签名；断言第 1 个 chunk 的 context 输入与 `MediaTekNpugVaeCodec.quantizeInt16LittleEndian` 按路由 scale 量化结果逐字节一致。证明文本编码、ACTX 量化、槽位装配与采样循环真实贯通；NPU 数值仍未被此测试覆盖 |
| 设备 baseline 取证代码 | **已编译并测试**（2026-10-31）：`fullChain` 写 `baseline`（model/manifest/latent/chunk/output SHA，增加 `finalLatentFile/Elements/SHA256/Min/Max/Mean/Std`）与 `final-latent.f32`；`dynamic-actx.bin` 仍保留；驱动自动拉取 report、ACTX 和 final latent。执行器 7/7、真实 kit 2/2（20.248 s，含 finalLatent 尺寸/有限/确定性/敏感性断言）通过 |
| ACTX/latent 对照工具 | `tools/mediatek/compare_actx.py`（shape、max/mean abs diff、RMSE、cosine、Pearson；已用参考 `ctx.bin` 自比与半幅变体验证）；`tools/mediatek/compare_latent.py`（raw f32 16×64×64，增加 relative_l1；已用合成张量自测） |
| 链测证据硬校验 | `tools/mediatek/validate_chain_report.py`：校验 `status=COMPLETED`、baseline 必填字段、chunk 统计、PNG/输出 SHA 一致、`final-latent.f32` 尺寸与 SHA、动态 ACTX 尺寸与 SHA；只证明证据完整，不判质量。`run_neuron_smoke.py` 在 chain 成功后自动调用，失败即返回 1。已用合成报告自测（有效 → `CHAIN_REPORT_VALID`，篡改 latent SHA → `CHAIN_REPORT_INVALID`） |
| app MediaTek 定向测试 | **PASS**，24 项（2026-10-31 复跑，含 S7 动态文本接线与 DYNAMIC_TEXT 信封/readiness：manifest 10、request mapping 5、v4 gating 6、runtime 证据门 3；0 失败/0 跳过） |
| `:api:local:testDebugUnitTest` | **PASS**，237 项 / 14 类（2026-10-31；Local API 图像/视频合同、协议 golden、运行时证据门；0 失败/0 跳过） |
| app 图像生成 owner/API 定向测试 | **PASS**，116 项 / 7 类（2026-10-31：`LocalImageGenerationCoordinatorTest`、`…LeaseTest`、`…OwnershipContractTest`、`LocalImageApiRequestMappingTest`、`LocalImagePromptAlignmentV4Test`、`LocalImagePromptLanguageV4HardContractTest`、`LocalImageModelReadinessTest`；0 失败/0 跳过） |
| `:app:compileDebugKotlin` + `:core:mediatek-native:compileDebugAndroidTestKotlin` | **PASS**（S7 动态文本接线 + 设备 kit 改动；Gradle 临时目录改到 E: 避开 C: 磁盘不足） |
| `scripts/verify-code.ps1 -SkipTests` | **VERIFY OK**（S7 复跑；代码层，非 release） |
| 证据静态复核 `verification-script.txt` | **PASS**（127 文件 / 79 JSON / 5 文档） |
| 全量 `:app:testDebugUnitTest` | **COMPLETED，4666 项 / 1 失败 / 11 跳过**（2026-10-31）：唯一失败为 `SharedPreferencesSurfaceGoldenTest` 发现新出现的 `mca_video_settings` 偏好文件，属于视频功能的 golden 守卫，与本分支 MediaTek/NPUG 改动无关；未擅自修改该 golden。其余 4654 项通过 |
| 真机 Neuron 恢复/执行、S6 | **NOT_RUN**（无 MediaTek 设备） |

### 诊断缺口闭环审计（2026-10-31）

| 原始发现 | 当前代码 | 证据/测试 |
|---|---|---|
| 计划 CPU 被算成失败 fallback | `MediaTekGraphScheduler` 区分 `plannedCpu` 与 `npuFailedThenCpu`；CPU repair 必须已有对应 NPU 失败记录 | `plannedCpuWorkAndNpuSuccessAreNotCountedAsFallback`、`cpuRepairLabelRequiresARecordedNpuFailure` |
| 不健康 CPU 输出被采用 | graph runner 拒绝非有限输出；失败的 CPU 重试不得被 adopted，运行保持 incomplete | `graphRunnerRejectsNonFiniteFloatOutputAndAttachesStats`、`failedCpuRetryCannotBeAdoptedAndLeavesTheRunIncomplete` |
| 错误基线选择 | 无基线时保持 `NOT_COMPARED`，不能声明已验质量 | `missingBaselineStaysNotComparedAndCannotClaimVerifiedQuality`；pipeline 明确记录未捆绑参考基线 |
| 验收失败仍返回成功 | app 端 `verifyMediaTekAnimaPipelineResult` 在 `!summary.accepted` 时抛错；质量分歧不能映射为 accepted | `divergedQualityCannotMapToAccepted` |
| 统一阈值误拒合法 CPU 热块 | 结构验收与质量事实分离，诊断只报告 drift，不设统一 absmax 门 | `graphRunnerStillAcceptsFiniteFloatOutput`、`nonFiniteComparisonIsInvalidRatherThanUncompared` |
| v4 结果归因 | metadata 分开写 `executionStatus=NPUG_CHAIN_AND_VAE_COMPLETED`、`qualityStatus=NOT_VERIFIED_NO_REFERENCE_BASELINE`、`acceptanceStatus=NOT_RUN`、`backend/transport` | `MediaTekAotExecutionGatingTest`、`ImageExecutionEvidenceGateTest` |
| UI 参数被静默忽略 | 执行前 `requestRejections` 协商：negativePrompt 在 `supportsNegativePrompt=false` 时拒绝、非默认 CFG 在 `cfgMode=NONE` 时拒绝、steps 必须属于 profile；不支持的 taskMode/batch/inputImage/mask/LoRA 等在 `mediaTekAnimaRequestFromOptions` 直接拒绝。FIXED_CONTEXT 的提示词按参考行为接受但不生效，并以 `promptApplied/promptSuppressed` 明示。seed 通过 `standardNormalLikeTorchMt19937` 真正驱动 latent，metadata 写 `latentInitialization` | `fixedContextRejectsPromptsNegativeAndNonDefaultCfgBeforeExecution`、`quick` 参数协商测试；生产调用点 `LocalImageGenerationOperations.kt:286` |

### 主线设备测试包（2026-10-31）

- `D:/anima-spike/dist-probe/mca-mediatek-chain-androidTest-20261031-r2.apk`（22,381,497 B，SHA-256 `1884eb09e1b5bfc98949d9db6390310b10292bb1aee579484b5b742cc8541e2d`）包含生产 `MediaTekNpugExecutor` + 窄 Neuron JNI adapter，并新增 `final-latent.f32` 输出。**r2 取代** 旧 `…20261031.apk`（`98e862bf…`）。
- 运行顺序见 `docs/MCA_NPUG_DEVICE_TEST_2026-10-31.md`：`--mode probe` → `--mode restore` → `--mode chain` → `--mode chain --dynamic-text`。
- 需要 4 个 447 MB DLA、VAE、AMOD、rope 和（动态文本）`text.bin`/`tok.bin`；设备预留约 5 GB。报告写 `baseline` 与 `textEncoding`，质量仍标 `NOT_VERIFIED_NO_REFERENCE_BASELINE`。

### 一键链测 APK（2026-10-31，1.0.17 风格）

- `D:/anima-spike/dist-probe/mca-mtk-chain-harness-20261031-r2.apk`（684,193,834 B，SHA-256 `6d79da798784ecb250cae023e0d5725e2eaa20996b3f412e13337222610b1523`，package `com.muyuchat.mca` debug，launcher `MCA MTK Chain Test`）。
- debug 入口 `MediaTekChainSmokeActivity`：选择 v4 bundle ZIP → 解压 → 生产 `executeMediaTekAotGeneration` → 写 `result.png`/`metadata.json`/`final-latent.f32`/`run.log` → 一键分享结果 ZIP。无需 adb 脚本或 Python。
- 2026-10-31 r3：修复 VAE 输入反归一化（见“主线真机结果”），期望消除 8 px 网格。
- `executeMediaTekAotGeneration` 增加可选 `onFinalLatent` 回调（默认空，生产行为不变），用于该诊断入口导出 latent。
- 2026-10-31 首次天玑真机运行：bundle 解压 1,745 MB、`blockers=[]`、4 variants，失败于 `dlopen /system/lib/libneuronusdk.so not found`。r2 改为优先加载 1.0.17 实际使用的 `libneuronusdk_adapter.9.mtk.so`，并扫描 vendor/system 目录；失败信息会列出发现的库。
- 这是诊断桥，不替代生产 UI/Local API 验收；但提供与 1.0.17 同形态的真机证据。

### 主线真机结果（2026-10-31，天玑）

测试者用一键 APK r2 在 MediaTek 设备上完成固定上下文与动态文本两轮，均为 **真实图像**，不是白噪：

| 项 | 固定上下文 | 动态文本 |
|---|---|---|
| bundle | `mediatek-reference-anima-512-fixed` | 动态变体（id 仍为 fixed，仅命名问题） |
| conditioning | FIXED_CONTEXT | DYNAMIC_TEXT，prompt `a cat` |
| chunks / steps | 24 / 6 | 24 / 6 |
| 文本编码 | — | 73,649 ms，context SHA `75bd6aa1…`，lk=256 |
| 去噪耗时 | 44,750 ms（约 7.35 s/step） | 43,674 ms（约 7.17 s/step） |
| final latent | SHA `48839cf3…`，std 0.5623，min/max -2.052/2.100 | SHA `130c874e…`，std 0.5744，min/max -2.140/1.668 |
| PNG | 630,510 B，SHA `4b0a3990…` | 602,237 B，SHA `c5e217ca…` |
| 图像统计 | mean 0.138 std，非白噪 | mean 0.118 std，非白噪 |
| 后端归因 | nativeExecution=true，fallback=false | 同左，promptApplied=true |

- 目视：固定上下文为动漫人物，动态 `a cat` 为猫肖像；两轮 latent relL1=1.014、cosine=0.506，图像 meanAbsDiff=0.203，证明提示词真实生效，不是同一张图。
- 宿主复算 `a cat` 得到 ACTX SHA `75bd6aa1…`，与设备 metadata 的 `textEncoding.contextSha256` **逐位一致**，证明端上 tokenizer/Qwen3+adapter 文本路径与宿主实现一致。
- 与 1.0.17（JIT，白噪，relL1=0.9536）相比：主线四段 DLA 链在真机上完成了语义正确的出图，这是实质进展。
- 质量边界：两图都有可见的 8 px 周期细网格；**尚未与同上下文参考/CPU 基线做数值对照**，不能声称画质达标。报告仍为 `qualityStatus=NOT_VERIFIED_NO_REFERENCE_BASELINE`、`acceptanceStatus=NOT_RUN`。
- 网格根因（已修复）：`MediaTekNpugChainPlan.vaeInput` 之前把**归一化后的 sampling latent 直接**量化给 VAE，漏了每通道 VAE 均值/方差反归一化。宿主用同一 VAE 对同一 final latent 做 `latent*std+mean` 后解码，得到干净、无 8 px 网格的图（`fixed-denorm.png`），而直接解码与设备图几乎逐像素一致（meanAbsDiff 0.0050、cosine 0.99976）。说明网格来自 VAE 输入归一化，不是 DLA 数值/布局。
- 修复：`vaeInput` 现先按 16 通道 VAE mean/std 反归一化，再 NHWC/int16 量化；单测改为断言反归一化后的 int16 值；core 全量 **123/123** 通过。
- 复测包：`mca-mtk-chain-harness-20261031-r3.apk`（684,193,834 B，SHA-256 `7021196f2dd1e5d16140358733d4329aa5eec111b78f3ed735e62f0b8796ee79`）。期望固定/动态输出分别接近 `fixed-denorm.png` / `dynamic-denorm.png`，网格消失。
- 生产 UI/Local API、连续生成、取消、内存/性能验收仍未执行；负向提示/CFG 仍不支持（已声明）。
- 证据：`artifacts/device/mca-chain-20261031/chain-1793461673299.zip`、`chain-1793461932443.zip`。

#### r3 复测（VAE 反归一化修复后，2026-10-31）

| 项 | 固定上下文 | 动态 `a cat` |
|---|---|---|
| final latent SHA | `48839cf3…`（与 r2 完全一致） | `130c874e…`（与 r2 完全一致） |
| PNG | 617,385 B，`24aa87b3…` | 500,767 B，`f6efb467…` |
| 8 px 峰 | 消失 | 消失 |
| 设备图 vs CPU 反归一化参考 | meanAbsDiff 0.00495，cosine 0.999711 | meanAbsDiff 0.00257，cosine 0.999949 |
| 目视 | 干净动漫人物 | 干净猫肖像 |

- final latent 两轮 SHA 与 r2 逐位一致，证明修复只影响 VAE 输入，四段链输出未变。
- 设备 DLA VAE 输出与宿主 CPU VAE（同一 final latent，先 `latent*std+mean`）几乎逐像素一致，8 px 网格完全消失。
- metadata 仍为 `qualityStatus=NOT_VERIFIED_NO_REFERENCE_BASELINE`、`acceptanceStatus=NOT_RUN`；这是“无整链参考基线”的诚实标注，生产 UI/Local API 验收仍未做。
- 证据：`chain-1793510702554.zip`、`chain-1793510795116.zip`、`host-vae-reference/`。

### 模型目录接入（2026-10-31）

- 推荐目录新增 **一张** `mediatek_anima_npu_np9`「Anima · 天玑 NPU (NPUG + DLA)」卡：同一个 Anima 模型的 AOT 变体矩阵（按 SoC/DLA 与分辨率编译）；已验证 MT6993/NP9/L1024s4，512×512、6 步、固定 ACTX + 动态文本；`EXPERIMENTAL`，`manualImportRequired=true`，主操作「导入本地包」，不自动下载（资产再分发受限）。
- MT6993 L2304r/L4032、MT6897/6899 NP9 作为**变体**写进卡片说明，不再作为独立模型卡；它们未在 MCA 验证，需匹配 bundle。对标 app 也是“一个 Anima 模型 + 按设备/分辨率选 AOT 变体”，`ensureEngine` 复用同模型/网格，仅 steps 变化时换 mods。
- bundle envelope 增加 `recommendationId=mediatek_anima_npu_np9`，导入后目录卡可识别已安装；生成器 `build_v4_bundle.py` 已默认写入。
- 验证：`:core:download:testDebugUnitTest` + `:feature:modelhub:testDebugUnitTest` 通过。
- App APK：`D:/anima-spike/dist-probe/mca-mediatek-app-20261101.apk`（684,193,834 B，SHA-256 `1515360a19a025376cae1d28a65cb83e100a8806eeeeb82ba7b42f750e5fa374`），含 VAE 修复、1 张 Anima 天玑卡（说明中列出 AOT 变体矩阵）、导入入口和链测入口。
- 使用：卸载旧 `com.muyuchat.mca` → 安装 APK → 模型页「推荐」→「天玑 NPU」→ Anima 卡「导入本地包」→ 选 v4 bundle ZIP；导入后打开本地模型生成 512×512/6 步。参考变体卡标注「参考/未验证」，只在自备匹配 bundle 时导入。

### 生产 v4 bundle（2026-10-31）

- `E:/MAC/build/mca-mediatek-reference-v4-fixed.zip`（1,830,262,377 B，SHA-256 `438440754de27f49a395bce448dc8c2fe2df944c83f90a980b438e431fdbce9f`）由 `tools/mediatek/build_v4_bundle.py` 从四链 pinned manifest 构建；envelope `mca.image_engine.bundle.v1`、AOT `mca.mediatek-image/4`、profile `reference-1024-32x32`（512×512、6 步、FIXED_CONTEXT），含 4 stubs / 4 DLAs / 4 routes / ctx / AMOD / RoPE / VAE，共 17 个组件。
- 动态文本变体：`mca-mediatek-reference-v4-dynamic.zip`（3,292,929,429 B，SHA-256 `e4c020640fcf567ce743c364a36a67a85a4eb29d54a166025f6db132cd7070fe`），DYNAMIC_TEXT，18 组件。
- ZIP 完整性校验通过：包内 `manifest.json`、`engine/aot.json` 与全部组件路径存在，尺寸/SHA 与 envelope pin 一致（fixed 17、dynamic 18）。生产解析器 readiness 宿主测试 **PASS 2/2**（`MediaTekReferenceBundleHostTest`，7.968 s）：两个 envelope、AOT manifest、4 variants 与全部资产 pin 均被生产导入器接受。测试期间发现并修复了两处真实契约问题：CONFIG 组件缺 size/SHA、route `dla` 未绑定且 AOT route pin 过期。
- 该 bundle 仅限本地：资产再分发权仍 BLOCKED，不发布 ZIP 或其内容。

### 1.0.17 JIT 真机结果（2026-10-31，第二通道）

- 设备 vivo V2545A / MT6993 / API 37 / Neuron SDK 9.0.11；测试包 `com.muyuchat.mca.mediateknputest` 1.0.17。
- CPU 轮正常（latent std 0.862065、absmax 2.85219、SHA `24b47821…`；猫图 79,714 B）。
- NPU JIT 轮：16 个块真实上 NPU（每步 16/15/15/14；60 次成功 run），但输出白噪；latent 与 CPU `relL1=0.953636`、`absMaxDiff=3.3155`；根因是 fp16 动态范围（hidden[27] absmax 4.23×）。
- 1.0.17 验收有缺陷：pinned CPU 被算 fallback、基线未绑定身份（选中与 NPU 输出逐位相同的文件）、divergence 未检查仍为 true，导致失败轮报 `CPU_FALLBACK_OK/status=OK`。
- 已在独立 harness 修复并构建 **1.0.18 (30)**，SHA-256 `c3373d84…`；单测 60/60 通过。NPU 轮现在应报 `FAILED_DIVERGED`/`NOT_COMPARED`，不会再假成功。详见 `docs/MCA_DIMENSITY_NPU_DEVICE_ANALYSIS_2026-10-31.md`。
- 该结果属于 **LiteRT JIT 第二通道**；NPUG 拓扑 + Neuron DLA 主线（v4 四段链）仍未在真机运行。

## 7. 阻塞与未验证项（不要含糊）

| 项 | 状态 |
|---|---|
| 真机 Neuron 装载/恢复/执行 | **NOT_RUN** —— 本机只有高通设备（2026-10-31 复核：`98a37aa7` SM8750P / 25091RP04C、`f9d98aa3` SM8550 / 2304FPN6DC），无 MediaTek 硬件 |
| 分辨率与 AMOD 选择规则 | **ESTABLISHED**（32×32 grid ⇒ 512×512 + `vaedec_64`；AMOD 按 N 选、与分辨率无关，4032 例外）；仅 UI stepChoices 集合未取到 |
| 分块结构 | **结构性 ESTABLISHED**（4 chunk × 7 block + final，计数 254=28×9+2）；真机未验证 |
| `restoreFromCompiledNetwork` 的具体版本与合法 ABI 声明 | **UNRESOLVED**（已见 V1/V2 与 probe 调用，未确定目标机走哪条） |
| DLA 与 runtime/driver 组合是否兼容 | **UNRESOLVED** |
| 70/68 输入的语义 | **已绑定**：`dva1` 首输入 `[1,1024,68]` = **17 通道 latent（16 VAE + 1 个恒零 pad 通道）× 2×2 patch**，不是 64+4 条件（`first-chunk-condition-evidence.json`）；`dva2/dva3/ditc1c` 首输入 `[1,1024,2048]`（hidden）；ctx `[1,256,1024]`；调制 `[1,1,2048]` 63/65 段；两个 `[1024,128]` 是 `rope_32x32.bin` 的 int16 两半；`[1,1,256]` 是参考零填充槽位（零值已确认，语义未定）；inpaint 语义未解析 |
| 模型权重/二进制的许可证与再分发权 | **BLOCKED** —— 公开可下载 ≠ 可进产品；仓库 README 是模板 Apache 声明，不足 |
| 单 chunk Neuron restore/run（S4） | **实现完成，真机 NOT_RUN**：ABI 来自 MediaTek 公开 API 文档；运行时 `dlopen` 厂商库；需 MediaTek 设备执行 probe → restore → bind → run → close。只读取证脚本 `tools/mediatek/probe_neuron_runtime.py` 已在 SM8750P 上记录 `NOT_RUN_MEDIA_DEVICE_ABSENT`（`artifacts/device/mediatek-neuron-2026-10-31/probe.json`） |
| 自行离线编译 AOT（Neuron SDK） | **BLOCKED** —— 需厂商 SDK，公网无公开下载；上游 LiteRT MediaTek 适配层是 Apache-2.0 的调用参考，但不含厂商 runtime |
| MCA 生产集成（S5/S6） | **S5 实现完成（真机 NOT_RUN）**；S6 仍需真机验收后才能更新推荐状态 |
| 动态文本（S7） | **代码完成、宿主端到端验证；真机 NOT_RUN**：ATOK/ATXW/Qwen3+adapter 实现，真实 1.46 GB 权重 256×1024 输出通过；`DYNAMIC_TEXT` 已接 `contextValues`；动态 kit 的 tokenizer→编码→量化→四段链→PNG 宿主 dry-run 通过（2/2，17.947 s）；**尚未与参考运行时做数值对照**；单线程；文本上下文 LRU=2 缓存；参考文本 NPU 图未公开。真机 `fullChain` 会写 `baseline`（model/manifest/latent/chunk/output SHA）并导出 `dynamic-actx.bin` 供参考比对 |
| S7 app 单测复跑 | **PASS**：24/24（含动态文本 readiness）；此前曾被另一会话未跟踪的 workflow 测试文件短暂阻塞，已解除 |
| 构建磁盘 | C: 仅约 4.7 GB，Gradle 曾在 `.gradle/.tmp` 写失败；后续构建使用 `-Dorg.gradle.tmpdir=E:\gradle-tmp -Djava.io.tmpdir=E:\gradle-tmp` |

---

## 7.1 目标完成审计（2026-10-31）

| # | 交付/成功标准 | 当前证据 | 状态 |
|---|---|---|---|
| 1 | 锁定模型/分辨率/步数/资产 + 输入身份基线 | revision/profile/资产 pin；70/70 输入语义绑定；kit latent/asset SHA；final-latent 导出 | **输入侧完成**；缺少参考运行时输出基线（需真机/参考运行时） |
| 2 | 执行/质量/后端归因契约 + 独立 payload schema + v3 兼容 | v4 合同与解析器；metadata 三态；core 123、app 116、Local API 237；v3 路径保留 | **完成（宿主）** |
| 3 | 窄 Neuron adapter 单 chunk restore→bind→run→compare→close | JNI + Kotlin wrapper；假 runtime 宿主测试；真实 kit dry-run；r2 设备 APK | **实现/宿主完成；真机 NOT_RUN** |
| 4 | conditioning/调制/采样/VAE + 动态文本 + 参数能力 | ACTX/AMOD/sigma/Euler/VAE；ATOK/ATXW/Qwen3+adapter；参数协商；动态 kit 端到端宿主测试 | **完成（宿主）** |
| 5 | 接回 Owner→worker→Provider + 身份/租约/取消/释放 | v4 执行接线；Local API 237；owner/API 116；fixed/dynamic v4 bundle 导入 readiness 2/2 | **完成（宿主）** |
| 6 | 生产 MainActivity + 认证 Local API 正确性/连续/取消/内存/性能 | 生产执行器四段链 + 动态文本 + VAE 已在真机出干净图（r3：固定/动态 6 步，图像与 CPU VAE 参考 cosine 0.9997/0.9999）；生产 UI/Local API、连续/取消/内存/性能未测 | **PARTIAL：真机链+VAE 成功；S6 未完成** |

**结论：目标未完成。** 2026-10-31 天玑真机已跑通生产四段链并出真实图像（固定/动态两轮），但画质未与参考基线对照、生产 UI/Local API 与连续/取消/内存/性能未验收；不得据此更新推荐状态。

## 8. 边界（违反即返工）

- 生产入口唯一：UI 与已认证 Local API 都经 `ImageGenerationOwner`，不新建图片 owner/Service/UI 专用引擎。
- 不修改冻结 UI、不恢复天玑自检入口、不在 `feature/modelhub` 访问 native。
- 旧 `mca.mediatek-image/3`（Anima v3）包继续可解析；新能力用**新 payload schema**，不改旧含义。
- 不把"解析了正确文件""API 能 dlopen""导出过 PNG""PC 模拟无 NaN"任一项写成 NPU 已跑通；四类结论分开记。
- 第三方二进制（`libanima.so`、厂商插件、模型权重）在来源、ABI、分发权明确前，只可作为**隔离研究输入**，不进产品依赖。

---

## 9. 证据索引

| 用途 | 路径 |
|---|---|
| 参考实现分析（248 行） | `docs/MCA_MTKPAINT_REA_ANALYSIS_2026-10-31.md` |
| 架构与执行切片（319 行，S0–S6 任务书） | `docs/MCA_DIMENSITY_NPU_REFERENCE_ARCHITECTURE_2026-10-31.md` |
| 证据总索引 | `artifacts/architecture/2026-10-31-mtkpaint-rea/README.md` |
| 公开模型调查 | `artifacts/.../model-findings.md` |
| 原生调用链调查 | `artifacts/.../native-findings.md` |
| 版本锁 | `artifacts/.../model-version-lock.json` |
| ModelScope 文件树（含全部 size+sha256） | `artifacts/.../model-ms-tree.json` |
| 两份 stub 完整元数据 | `artifacts/.../model-npug-schema-{ditc1c,dva1}_w8_w16L1024s4.json` |
| **S0 锁文件（机器可读）** | `artifacts/.../reference-profile-lock.json`（+ 生成器 `reference-profile-lock-build.txt`） |
| **I/O 路由合同 70/68** | `artifacts/.../ditc1c.route.json`、`dva1.route.json`（+ 生成器 `model-npug-route-export.txt`） |
| **四 chunk 结构校验** | `artifacts/.../model-npug-chunk-crosscheck.json`（+ 脚本 `model-npug-chunk-crosscheck.txt`） |
| **rope 两半与输入基线** | `artifacts/.../rope-split-evidence.json`、`zero-slot-evidence.json`、`single-chunk-probe-plan.json`（revision 3） |
| **单 chunk 字节级输入包** | `artifacts/.../reference-input-pack.json`（+ `reference-input-pack-build.txt`） |
| **参考 profile 的 v4 manifest** | `core/mediatek-native/src/test/resources/npug/reference-aot-manifest.json` + `MediaTekAotReferenceProfileTest.kt` |
| **S1 调度/验收合同** | `core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekGraphScheduler.kt` + `src/test/.../MediaTekGraphSchedulerTest.kt` |
| **S2 v4 合同与解析** | `core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekAotBundleContract.kt`、`MediaTekAotManifestJson.kt`、`MediaTekNpugRouteJson.kt`；测试资源 `src/test/resources/npug/*.route.json` |
| **S4 adapter** | `core/mediatek-native/src/main/cpp/mediatek_neuron_adapter.cpp`、`src/main/java/com/muyuchat/core/mediatek/MediaTekNeuronRuntimeAdapter.kt` + 测试 |
| **VAE codec** | `core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekNpugVaeCodec.kt` + 测试；`vaeOnly` 设备冒烟 |
| **sigma 调度** | `artifacts/.../sigma-schedule-evidence.json`；`core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekNpugSigmaSchedule.kt` + 测试 |
| **S4 设备冒烟** | `core/mediatek-native/src/androidTest/.../MediaTekNeuronSmokeTest.kt`、`tools/mediatek/run_neuron_smoke.py`、`artifacts/device/mca-neuron-smoke/` |
| **完整链数据流** | `artifacts/.../chain-dataflow-evidence.json`、`dva2_w8_w16L1024s4.route.json`、`dva3_w8_w16L1024s4.route.json`、`vaedec_64.contract.json`、`reference-aot-chain-assets.json` |
| **Neuron API ABI 证据** | `artifacts/.../neuron-api-signatures.txt`（MediaTek 公开 API 文档的精确签名与 NULL type 约束） |
| **S2 app 分派** | `app/src/main/java/com/muyuchat/mca/MediaTekImageBundleManifest.kt` + `app/src/test/.../MediaTekImageBundleManifestTest.kt` |
| **S4 前置与 ABI 取证** | `docs/MCA_DIMENSITY_NPU_S4_PREREQUISITES_2026-10-31.md`；`tools/mediatek/probe_neuron_runtime.py`；`artifacts/device/mediatek-neuron-2026-10-31/probe.json` |
| Schema 校验与解析器说明 | `artifacts/.../model-npug-schema-validation.json`、`model-npug-schema-parser.txt` |
| JNI 表面 | `artifacts/.../rea-class-NativeBridge.json` |
| ACTX 实际 FP32 读取 | `artifacts/.../rea-method-work-GenerateService-loadCtx.json` |
| 旧交接（spike 路线，降级为第二通道） | `docs/MCA_DIMENSITY_NPU_HANDOFF_FINAL.md` |

---

## 10. 交接检查表（接手时先做这四件）

1. 读 `docs/architecture/MCA_PROJECT_HARD_LOCK.md` 与上述两份 2026-10-31 文档。
2. 确认当前分支/HEAD 与 dirty 状态；`core/mediatek-native` 是否为未跟踪目录（若是，先确认归属再动）。
3. 确认本机是否有 MediaTek 设备：`adb devices -l`（无则一切设备结论保持 NOT_RUN）。adb 不在 PATH 时用 `D:\model\android-sdk\platform-tools\adb.exe`。
4. S0–S5 实现已完成（S6 未开始）。下一步：在一台 MediaTek 设备上运行 `python tools/mediatek/run_neuron_smoke.py --mode probe --install --build`，再跑 `--mode restore` 与 `--mode chain`（真实 kit，生产执行器，全 6 步）；通过后再做生产 MainActivity / 认证 Local API 的 S6 验收。没有设备时一切结果保持 NOT_RUN。S3 是独立路线，不互为前置。
