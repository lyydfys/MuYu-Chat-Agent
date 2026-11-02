# MCA 天玑 NPU / Anima 交接文档（2026-10-29 · 整图跑通结果版）

> 本文是 `docs/MCA_DIMENSITY_NPU_ANIMA_HANDOFF_2026-10-28.md`、
> `docs/MCA_DIMENSITY_NPU_ROOTCAUSE_2026-10-28.md`、`docs/MCA_DIMENSITY_NPU_REPAIR_PLAN_2026-10-28.md`、
> `docs/MCA_DIMENSITY_NPU_MT6993_REPORT_ANALYSIS_2026-10-28.md` 的接续。
> **不是 READY 声明。** 仓库侧本轮仍只新增文档，未修改任何生产代码。

## 1. 一句话现状

- **CPU 路径：PASS，且与 PC 参考逐位相同** —— 在测试设备（vivo V2545A / MT6993 / Android 17）上 4 步出图，最终 latent SHA-256 `24b478212904…4abdb884` 与 PC 参考**完全一致**，PNG 79,714 B，`imageHealth=HEALTHY`，耗时 293 s。
- **NPU 整图：FAIL（又黑图）** —— 同设备同参数跑 NPU 轮：`animaVerdict=FAIL reason=latent_non_finite,png_bytes_2227,image_std_NaN`，`pngBytes=2227`，latent 全 NaN，耗时 604 s（是 CPU 的 **2.06×**，不是加速）。
- **失败链已完整定位**：step 0 的 block 0–21 在 NPU 上数值健康；block 22–27 的 NPU 输出为 **Inf**；`hidden[27]` 的 std 已达 **2795.9**（残差流炸了 4000 倍）；`final.tflite` 的 NPU 输出 **全部 Inf**（65,536 个）→ latent NaN → VAE → 纯黑。
- **根因**：插件把 fp32 计算松弛成 **fp16**（本轮再次被 `hidden[27]` 量级佐证），单块 ~7e-4 的误差在 28 层残差链上被放大到溢出；而我们自己的**门禁太粗 + `final` 没门禁**，让发散逃到了出图。

## 2. 设备基线（**两台设备，不要混用结论**）

| | 测试设备（当前可用） | 最初黑图设备 |
|---|---|---|
| SoC | **MT6993（天玑 9500）** | MT6899 |
| 机型 | vivo V2545A（`board=k6993v1_64`, `device=PD2545`） | HONOR SER-AN00 |
| Android | **37 / API 37** | 36 |
| Neuron SDK | **9.0.11**（bytecode 与 runtime 一致） | 未知 |
| 插件 | `libLiteRtCompilerPlugin_MediaTek.so` bytes=429,112 sha256=`bc05ea78…9ea8c51d` | 同版本 AAR |
| Dispatch | `libLiteRtDispatch_MediaTek.so` bytes=330,960 sha256=`13cc632a…fb4984b129` | 同上 |

**MT6993 在插件 16 项 SoC 白名单内；MT6899 不在。** 但要注意：编译日志一直是
`Starting MediaTek Compilation for 1 subgraphs, soc_model=(null)` —— **插件根本拿不到 SoC，白名单只在"查询支持型号"接口里生效，不参与编译放行**，所以"MT6899 被白名单拦掉"这个解释不成立。MT6899 的黑图至今**没有被复现或解释**，而该机是否还在测试者手上未知。

## 3. 工具链与交付物（当前定版）

| 项 | 值 |
|---|---|
| 探针/测试 App | `C:\mca-mtk-spike\mediatek-npu-smoke`（非 git 工程） |
| 当前 APK | `D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.6.apk`，**646,698,793 B**，SHA-256 `35c49227b7bb778d1fd562d460b8fd40558fb6272f0a6370d2191a8bf2469637`，versionCode 18 / versionName 1.0.6 |
| 签名 | 证书 SHA-256 `ad1169233fb720a049aee894ab8b072f340ed80a13dbb5264fd5f32c23d7c16a`，与 1.0.3–1.0.5 逐字节相同 → **可覆盖安装，不丢 `filesDir/anima-pack`** |
| 图包 | int8 weight-only（`weight_only_wi8_afp32`），2.09 GB，`block00.tflite` sha256=`fb3147b3…fffd6c`；分发用 5 个分片 `D:\anima-spike\dist\anima-pack-0.6.1-part{1..5}.zip`（375/368/368/368/519 MiB，逐片 SHA 见 `anima-pack-0.6.1-parts.json`） |
| 测试者说明 | `D:\anima-spike\dist-probe\TESTER_MESSAGE-1.0.6.md`、`FORWARD_TO_TESTER-1.0.6.txt`、`HANDOFF-1.0.6.txt` |
| 仅本地 | `D:\anima-spike\`（转换脚本、探针模型、PC 金标准、构建日志） |

## 4. 本轮设备证据

### 4.1 CPU 轮（PASS，2026-10-29）

```
backend=cpu_only   runParams=animaPack steps=4 cfg=6.0 seed=42 allowNpu=false
packSource=files   （说明 5 个分片导入成功）
step=0 latent std=0.8781  step=1 std=0.7077  step=2 std=0.8595  step=3 std=0.8621   （全部 nan=0 inf=0）
latentFileSha256=24b478212904a10a77e420d519c887ebe1e0c631757d88612af775470abdb884
pngBytes=79714
imageHealth=HEALTHY nan=0 inf=0 std=0.14347132 maxAbs=1.0
animaVerdict=CPU_FALLBACK_OK reason=cpu_only_requested     ← CPU 模式的正常判决
blocksCpuOnly=28   blockRunsTotal=112 blockRunsCpuOnly=112
四步耗时 73.4 / 72.0 / 71.9 / 75.9 s，合计 293 s
```

latent SHA 与历史 PC 参考（HONOR 那轮 CPU PASS）**完全同一串**，PNG 字节数也相同 → 管线、图包、VAE、采样器**没有跨设备/跨版本漂移**。

### 4.2 NPU 轮（FAIL，2026-10-29）

```
backend=npu_preferred   allowNpu=true
compileCacheDir=…/cache/mediatek-compiler-cache requested=true
blocksNpu=22   blocksCpuNumericFallback=28   blocksCpuOpenFallback=0
blockRunsTotal=112 blockRunsNpu=22 blockRunsNumericFallback=90
compileProfile step0MsTotal=73127 step1MsTotal=71127 step2MsTotal=73886 step3MsTotal=77072
               cacheVerdict=INEFFECTIVE note=plugin_may_not_implement_GetCacheKey
hidden[0]  mean=-2.719e-4 std=0.65372 min=-18.719 max=20.703 nan=0 inf=0   ← 与探针一致，健康
hidden[27] mean=-116.087  std=2795.898 min=-118228.086 max=3393.904 nan=0 inf=0   ← 已发散
step=0 finalPos inf=65536  finalNeg inf=65536
step=0..3 latent nan=65536, sha256=bf4043f30c7e178be8c26f82b6e38ab62f9348fbc8f9a1e122f2cee345fb2745（四次相同 = 完全退化）
pngBytes=2227   imageHealth=NAN nan=786432 inf=0 std=NaN
animaVerdict=FAIL reason=latent_non_finite,png_bytes_2227,image_std_NaN
四步耗时 126.7 / 154.9 / 158.7 / 164.3 s，合计 604.6 s（CPU 的 2.06×）
```

逐块失败明细（step 0）：

| block | 观测 |
|---|---|
| 0–21 | `backend=npu health=HEALTHY/HEALTHY`，`runMs` 217–278 ms，`compileMs` 2.1–3.0 s |
| 22–25 | NPU `status=UNHEALTHY(pos:INF,neg:INF)` → CPU 回退 → `HEALTHY` |
| 26 | NPU `INF` → CPU 重跑 **也 `EXPLODED/EXPLODED`**（`note=cpu_retry_also_unhealthy`，但被接受） |
| 27 | NPU `INF` → CPU 回退 |

## 5. 根因（分层，含置信度）

| 层 | 结论 | 置信度 | 依据 |
|---|---|---|---|
| 外部（不可改） | 插件把 fp32 计算**松弛成 fp16** | 高（已证实） | ①同一图，fp32 权重与 fp16 权重的 NPU 输出 7 位有效数字相同（7.225934e-4 vs 7.225945e-4）；②真实输入 ×1e3 / ×4096 时输出**全部 Inf**（fp16 上界 65504）；③插件串含 `NeuronModel_relaxComputationFloat32toFloat16` / `Failed to relaxFp32ToFp16` |
| 外部（不可改） | fp16 的 ~7e-4 单块误差在 28 层残差链上**被放大到溢出** | 高 | `hidden[0]` std 0.6537 → `hidden[27]` std 2795.9（4000×），block 22 起 NPU 输出 Inf |
| 我们 | `final.tflite` / `prelude.tflite` **没有数值门禁** | 已证实 | final 输出 65,536 个 Inf 直接进 latent 更新 → NaN |
| 我们 | 块门禁**阈值太粗**（只拦 NaN/Inf/全零/常数/>1e5） | 已证实 | 发散过程中 std 从 0.65 涨到 2795 期间一直被放行 |
| 我们 | CPU 重试也不健康时**仍然接受** | 已证实 | block 26 的 CPU 重跑 `EXPLODED` 被当作结果，随后 3 个 step 全 NaN，白跑约 480 s |
| 我们 | 编译缓存**无效** | 已证实 | `cacheVerdict=INEFFECTIVE`，每步编译 71–77 s，NPU 比 CPU 慢一倍 |

**"NPU 单块能算对"与"NPU 整图能出图"完全不是一件事** —— 这是本次最重要的认知修正：探针（block00 单块、真实 step0 输入）全 PASS，但 28 块串联必然发散。

## 6. 已排除（不要再查）

1. 图包/管线/VAE/采样器：CPU 轮 latent SHA 与 PC 参考逐位相同（本轮再次确认）。
2. dispatch 回写缺失：探针每块 `outputVerdict=VALUES`、`unchanged=0`、`paddedTail=0`。
3. 缓冲区/padding 契约：`packedBytes=bufferBytes=readbackBytes`，无 padding。
4. int8 权重不可用：`block00_int8` NPU PASS（`relL1=7.397615e-04`）。
5. 算子覆盖（除 softmax 形状特例）：transpose / slice / gelu×2 / rmsnorm / batchmatmul / concat_sub / reshape_mul_add 全 PASS（1.7e-4 ~ 4.2e-4）。
6. int8 **张量**：`mobilenet`（uint8 IO）NPU 编译 `504` —— 被拒的是 int8/uint8 **IO 张量**，不是权重。
7. 插件/runtime ABI 不匹配：17 个 compiler-plugin C API 两侧完全吻合。
8. SoC 白名单拦设备：`soc_model=(null)`，白名单不参与编译放行。
9. 同步/事件缺失：`LiteRtRunCompiledModel` 本就同步，`Lock` 内部会 sync event。

## 7. 下一步（按性价比排序）

### 7.1 必做：1.0.7 诊断版（约 5–10 分钟设备时间，能一次定生死）

核心未知只有一个：**误差是从第几块开始放大的？**

- 若**从第一块起均匀放大** → fp16 精度是硬限，NPU 全链不可行，天玑线降级为"仅实验"，交付走 CPU（已 PASS）。
- 若**某几块突然跳变**（例如含 cross-attention/softmax 的块命中 `Softmax::GenKernels: Unsupported build combination! Run empty kernel!`）→ 把那几块 pin 到 CPU，其余仍走 NPU，加速仍有空间。

1.0.7 需要四处改动（都很小）：

1. **记录全部 28 块 × 4 步的 hidden 统计**（std / maxAbs / mean；现在只记 block 0 与 27）。
2. **门禁加漂移带**：`out.std ∈ [0.3, 3] × in.std` 且 `maxAbs ≤ 1e3`，越界记 `drift=...`。
3. **`final` / `prelude` 也加同款门禁**（这是"永不再黑图"的必须项）。
4. **CPU 重试仍不健康 → 立即中止本轮**，写 `animaVerdict=FAIL reason=diverged_at_block_N`，不再空跑。
5. 顺手修 `latentVsPrevious`：跳过非有限的旧 latent（本轮 NPU 轮 `relL1=NaN maxAbs=0.0` 就是因为拿了别轮的 NaN latent）。

### 7.2 若诊断判定"某块跳变"：pin 到 CPU

在 `AnimaPackRunner` 里维护一个 `cpuPinnedBlocks: Set<Int>`，命中的块直接 `preferNpu=false`；期望收益 = 其余块的 4–5× 单块加速。

### 7.3 若诊断判定"均匀放大"：判死 NPU 全链，转交付

- 天玑 NPU 记 `NOT_VIABLE_WITH_LITERT_2_3_0`（原因：fp16 松弛 + 无精度档开关）。
- 产品交付走 CPU：**本机 293 s/4 步已与 PC 参考逐位一致**。
- 若要提升 CPU 速度，方向是 int8 静态量化的 XNNPACK 或换 runtime，而不是继续调这个插件。

### 7.4 被堵死/待上游的路线（不要浪费时间）

- **关闭 fp16 松弛**：插件里确有 `NeuronModel_relaxComputationFloat32toFloat16`、`--opt-accuracy --opt 3`、`gemma-*-accuracy` 等精度档字符串，但 `Lrt*MediatekOptions`（`litert_mediatek_options.h` 的 C API）在 `litert-npu-runtime-mediatek:2.3.0` 的 AAR 里**没有导出**（AAR 只有两个 `.so`），App 侧无法设置；opaque options 的 identifier 也无法获得。**只能等上游提供 API 或较新版本。**
- **编译缓存**：`LiteRT` 的 `CompilerCacheDir` 机制对该插件无效（插件未实现 `GetCacheKey`）。想省编译只能常驻 28 个已编译模型（估 ~1.8 GiB，真机内存未测）或等插件支持。

## 8. 顺带发现、尚未修的缺陷

1. **1.0.6 探针矩阵入口与整图入口容易混淆**：测试者第一次交了探针报告（`reportKind=probe-matrix`）当整图结果。建议 1.0.7 加护栏：启动就显示 `Pack: PRESENT/ABSENT`；探针矩阵结束时追加一行 "This is NOT image generation"。
2. **`latentVsPrevious` 会挑到 NaN 的旧 latent**（上条）。
3. **`ProbeMatrix` 里硬编码 `appVersion=1.0.4 versionCode=16`**，与封套 1.0.5/1.0.6 不一致，容易误判版本。
4. **`ZipSource` 内嵌包路径仍坏**（`/proc/self/fd/<apk fd>` + `offset=0`），当前被 `packSource=files` 掩盖；单文件内嵌包不可用。
5. **`op_softmax` 探针用的是缩小形状 `[1,16,256,256]`**（生产是 1024），命中了 MTK `Run empty kernel!`；结论不可外推，若要覆盖生产形状需 `[1,2,1024,1024]` 级别的探针。

## 9. 未验证 / 阻塞

| 项 | 状态 |
|---|---|
| MT6899 上的黑图根因 | `UNKNOWN`（设备不可用；`soc_model=(null)` 说明白名单不是原因） |
| fp16 松弛能否关闭 | `BLOCKED`（上游未导出 options API） |
| 28 张图常驻内存的真实峰值 | `NOT_RUN`（真机内存未测前不引入） |
| APK 本地安装预检 | `BLOCKED`（本地两台 HyperOS 的「USB 安装」物理开关未开） |
| MT6993 上 NPU 整图出图 | **`FAIL`**（本轮结论） |

## 10. 证据索引

仓库内（本轮新增 / 归档）：
- `docs/MCA_DIMENSITY_NPU_ANIMA_HANDOFF_2026-10-29.md`（本文）
- `docs/MCA_DIMENSITY_NPU_MT6993_REPORT_ANALYSIS_2026-10-28.md`（探针报告分析）
- `docs/MCA_DIMENSITY_NPU_ROOTCAUSE_2026-10-28.md`、`docs/MCA_DIMENSITY_NPU_REPAIR_PLAN_2026-10-28.md`
- `artifacts/architecture/2026-10-26-dimensity-anima/2026-10-29-MT6993-anima-{npu,cpu}-journal.txt`
- `artifacts/architecture/2026-10-26-dimensity-anima/2026-10-28-MT6993-probe-{report,journal}.txt`

仓库外（`D:\anima-spike\`）：
- `analysis/01-cpp-litert-audit.md` … `10-cookbook-ui.md`（四路取证 + 阶段 1 交付 + 编译复用/门禁 + 界面收尾）
- `probe/`（探针模型、真实 step0 输入、PC 金标准、`EXPECTED.json`）
- `dist-probe/`（1.0.4/1.0.5/1.0.6 APK、构建日志、测试者说明、liblog 自检）
- `dist/anima-pack-0.6.1-part{1..5}.zip`（分发给测试者的图包分片）

## 11. 复现与构建

```powershell
# 构建（JDK 21；不要与其它 Agent 并发跑 Gradle，会抢 build 目录）
cd C:\mca-mtk-spike\mediatek-npu-smoke
.\gradlew.bat --no-daemon --offline testDebugUnitTest assembleDebug
#   → app\build\outputs\apk\debug\app-debug.apk
# 单测基线：BlockHealthTest 10 + ProbeVerdictTest 17 + EvidenceJournalSelfTest 1 = 28，failures=0

# 签名红线（必须与 1.0.3 一致才能覆盖安装）
apksigner verify --print-certs app-debug.apk    # 期望 ad116923…d7c16a

# 设备侧（测试者，无 adb）
#   1) 覆盖安装 1.0.6 → 首行应为 "MediaTek NPU smoke v1.0.6 (18)"
#   2) Import Anima pack (.zip) ×5 片 → 直到 "Anima pack complete"
#   3) Run full Anima pack (CPU) → "Anima pack finished (CPU)."
#   4) Run full Anima pack (NPU) → "Anima pack finished (NPU)."
#   5) 各轮分享：Share last Anima PNG / Share last latent (.f32) / Share Anima run journal (.txt)
```

设备上判定只看这几行（都在 journal / evidence 里）：
`animaVerdict=` · `blocksNpu=` / `blocksCpuNumericFallback=` · `blockRunsNumericFallback=` ·
`compileProfile … cacheVerdict=` · `pngBytes=` · `imageHealth=` · `latentVsPrevious` · `hidden[27]`

## 12. 回退

- 代码：1.0.6 的改动集中在证据/门禁/缓存三层，`values[0..19]` 契约未变；回退直接覆盖安装 `dist-probe\mediatek-npu-smoke-1.0.5.apk`（或 1.0.4）即可，同签名。
- 数据：无 schema/持久化变更；`anima-pack` 目录不要删（2.09 GB，重导需 5–20 分钟）。
- 仓库：本轮只新增/更新 `docs/` 与 `artifacts/`，生产代码与子模块未动；删除新增文件即可回退。
- 未执行 `git reset --hard` / `git clean`；未卸载任何设备应用；两台本地测试机仅改过两个开发设置（`install_non_market_apps=1`、`verifier_verify_adb_installs=0`），可随时改回。
