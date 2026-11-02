# Wan2.2 TI2V-5B 基线资产来源清单（P0-A / V1-01）

固定日期：2026-10-25；2026-10-26 修正哈希字段；2026-10-28 复核下载来源形式（固定 commit，非 `main`）、传输主机策略与下载 owner 行为。本清单是 [主方案 §5](EXECUTION_PLAN.md) 与任务卡 V1-01 的资产事实记录。文件大小来自 HTTP `Content-Length`；`sha256` 来自 Hugging Face 仓库文件元数据的 **LFS oid**（文件字节的 SHA-256，安装/加载时由 `VideoModelAssetVerifier` 复算）；`xetHash` 是 HF Xet CAS 标识，**不是文件 sha256**。2026-10-25 版曾把 `xetHash` 误记成 sha256，导致正确官方文件会被校验器判为不符；2026-10-26 已通过 HF tree API 重新取 LFS oid，并用 ModelScope 同仓库下载的真实字节复核（VAE/DiT/UMT5 三个角色已在 device-a 设备侧复算一致，见 IMPLEMENTATION_LOG 的 2026-10-26 交接轮 §2 与 §5.1）。本表是来源声明而非最终判据。

## 1. 角色链（基础 TI2V-5B，非 Turbo）

| 角色 | 首选来源 | 格式/量化 | 大小（字节） | sha256（HF LFS oid） | xetHash（CAS） | 许可证（来源仓库标签） |
| --- | --- | --- | --- | --- | --- | --- |
| DiT（基础 5B） | QuantStack/Wan2.2-TI2V-5B-GGUF `Wan2.2-TI2V-5B-Q4_0.gguf` | GGUF Q4_0 | 3,029,086,560 | `fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463` | `f68930ef925cee2bc2ad68c07207d542d1fb37a5ff984665f55735732454c635` | Apache-2.0（仓库标签；声明 base_model: Wan-AI/Wan2.2-TI2V-5B） |
| DiT（备选） | 同仓库 `Wan2.2-TI2V-5B-Q4_K_M.gguf` | GGUF Q4_K_M | 3,433,116,000 | `95b19697b7f98e65b0a543640e9ca7b4dfec32e2a6e3731e8e10708be52655e2` | `9fc99dd5c3e564d54a18147ae40bc8c74afe07f393c27955c427d84d18f15b05` | 同上 |
| DiT（备选） | 同仓库 `Wan2.2-TI2V-5B-Q8_0.gguf` | GGUF Q8_0 | 5,400,179,040 | `57bece983817ab2f957546683bb670f13be7d99022d45674840cd999a050ea8f` | `d8c55de29f6698ac79b160f45488e3bac1a1c8435d165ad32a65a90d1fc86057` | 同上 |
| DiT（fp16 参考） | Comfy-Org/Wan-2.2 `split_files/diffusion_models/wan2.2_ti2v_5B_fp16.safetensors`（旧仓库名 Comfy-Org/Wan_2.2_ComfyUI_Repackaged 已重命名，HF API 重定向） | safetensors fp16 | 9,999,658,848 | `456f901338bd9eadbded3828b819109a9b68e8a525ca5cf8d0049a69fcfeca1e` | —（LFS oid 本身即文件 sha256） | Apache-2.0（仓库标签，2026-10-26 复核；仓库 revision `ee6f4a40737a995bf5818954cfce6d59443b0f04`） |
| 文本编码器 | city96/umt5-xxl-encoder-gguf `umt5-xxl-encoder-Q8_0.gguf` | GGUF Q8_0 | 6,043,068,256 | `2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc` | `6048e11fd9863de836bc014d9cdde1ac4a2b8ba9a3fa89beb08528400d27d828` | Apache-2.0（仓库标签） |
| 文本编码器（备选） | 同仓库 `umt5-xxl-encoder-Q4_K_M.gguf` | GGUF Q4_K_M | 3,655,145,312 | `17cf97a5bbbc60a646d6105b832b6f657ce904a8a1ad970e4b59df0c67584a40` | `34aac234db818530b0f7511073d178a53863337154ce6b5f03bae15134be8448` | 同上 |
| VAE（5B 专用） | QuantStack/Wan2.2-TI2V-5B-GGUF `VAE/Wan2.2_VAE.safetensors` | safetensors fp16 | 1,409,400,960 | `e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156` | `53b5f2941a82a2494fd4315151050f9022eefcea4bcc51e0e15b99079f92947f` | Apache-2.0（同 DiT 仓库） |

- 官方基础模型 Wan-AI/Wan2.2-TI2V-5B：仓库标签 **Apache-2.0**（2026-10-26 HF API 复核，仓库 revision `921dbaf3f1674a56f47e83fb80a34bac8a8f203e`）。
- 量化来源仓库 revision（2026-10-26 HF API）：QuantStack/Wan2.2-TI2V-5B-GGUF `57437632ddd08bdcbd1508c866aa22e126ed51d2`（Apache-2.0，base_model: Wan-AI/Wan2.2-TI2V-5B）；city96/umt5-xxl-encoder-gguf `b535255bee98c2b0a59ea7c0ae2dcd0c6657b3b7`（Apache-2.0，base_model: google/umt5-xxl）。
- **下载 URL 形态（2026-10-28 起）**：`Wan22Ti2v5bProfiles` 把每个组件的 `sourceUrl` 固定为 `https://huggingface.co/<repo>/resolve/<上述 commit>/<path>`，不再使用可变的 `resolve/main`。同一路径下的 `revision` 字段也是该 commit，manifest 的 `sizeBytes`/`sha256` 仍是唯一验收判据；上游之后改写 `main` 不会把健康下载变成 `MODEL_CHECKSUM_MISMATCH`。
- **传输主机（2026-10-28 起）**：下载时只替换 HTTP 前端主机（`huggingFacePreferredTransferUrl`，沿用应用既有的 `DEFAULT_HUGGING_FACE_ENDPOINTS`，当前首选 `https://hf-mirror.com`，回退 `https://huggingface.co`）；仓库、commit、路径、大小、SHA-256 一律取自上面的固定来源。镜像返回 302 跳到 HF Xet CDN，OkHttp 跟随且保留 `Range`/`If-Range`，续传语义不变；错误或陈旧的镜像只能让下载失败，不能装入不同字节。
- Comfy-Org 仓库的 `split_files/vae/wan2.2_vae.safetensors` 与 QuantStack VAE 同尺寸（1,409,400,960），按同一文件对待；以 QuantStack 仓库为主源。
- UMT5 tokenizer：本基线编译内嵌于 native（`t5_unigram_tokenizer.cpp` 词表），**不单设下载角色**；绑定 sd-native 构建 revision（`Wan22Ti2v5bProfiles.TOKENIZER_PROVISION`）。
- 所有权重运行期由用户下载，**不随 APK 分发**。

## 2. 明确排除（不得混入本基线）

| 排除项 | 原因 |
| --- | --- |
| Wan2.2 TI2V-5B **Turbo**（蒸馏权重/四步 schedule） | 与基础权重不同配方；许可链未澄清（NOTICE：代码 CC BY-NC-SA 4.0、权重页未声明） |
| TAEHV 近似解码器 | 独立质量/许可实验；基础 correctness baseline 使用完整 wan2.2 VAE |
| wan_2.1_vae | Wan2.1 专用；与 5B TI2V 不互换（上游文档明示 "for Wan2.2 TI2V 5B only"） |
| A14B high/low-noise 双专家、LoRA、CLIP Vision | 14B/LoRA 属 E-01 或 V3-02d 独立实验；TI2V-5B 不需要 CLIP Vision |
| 参考项目 Hexagon/QNN 定制 kernel 与加速结论 | 未随本仓库 SDK 验证；加速证据由 P0-D 独立产生 |

## 3. 基线采样配方（上游文档，非实测）

来源：`third_party/stable-diffusion.cpp/docs/wan.md` @ `16b3846`（子模块内部补丁见 V0-01 记录），"Wan2.2 TI2V 5B" T2V/I2V 小节：

- sampler `euler`、cfg 6.0、flow-shift 3.0、video-frames 33（=4×8+1，合法）、参考尺寸 480×832（均为 32 的倍数）、标准中文负面提示词。
- 桌面参考命令使用 `--diffusion-fa --offload-to-cpu`，属桌面内存策略，**不构成 Android 可行性证据**。
- `measuredPresets` 在 P0-C 产出真机数据前保持为空（`VideoModelManifest` 构造时强制三角色齐全、测试断言 presets 为空）。

## 4. 落地代码与验证

- 清单类型与固定 profile：`app/src/main/java/com/muyuchat/mca/video/model/{VideoModelManifest,Wan22Ti2v5bProfiles,VideoModelAssetVerifier}.kt`。
- 行为测试：`app/src/test/java/com/muyuchat/mca/video/model/VideoModelAssetVerifierTest.kt`（完整/缺件/截断/同名错件/仅尺寸 pin/磁盘余量/真实 pin 事实断言）。
- 生产下载入口（2026-10-27；2026-10-28 扩充）：`app/src/main/java/com/muyuchat/mca/video/model/VideoModelAssetOwner.kt` 复用 `:core:download` 的 `ResumableDownloader`，按 manifest 的角色、相对路径、大小和 LFS oid（sha256）下载；已合法组件不会重复下载，`.part` 会保留续传。`core:video-contract` 的 `VideoModelAssetPort` 由 `VideoHostAssembly` 注入视频页，页面只观察状态和发送下载/暂停/重查 intent。
  - **磁盘预检（2026-10-28 修复）**：预检只要求“本组件缺失尾部字节（`sizeBytes - 已有 .part 长度`）+ 256MiB 保留余量”可用，而不是整组件大小；否则 6GB 组件已下 5.9GB 时会被误判 `DISK_FULL`。真正的剩余字节不足仍如实报 `DISK_FULL` 且不发起网络请求。
  - 取消保留 `.part` 并报 `CANCELLED`；恢复后按 Range/If-Range 续传；每次组件落盘后立即按 pin 复算大小与 SHA-256，不一致报 `MODEL_CHECKSUM_MISMATCH` 并保留失败态；下载完成再对三角色整体复核一次。
  - JVM 证据（2026-10-28）：`VideoModelAssetOwnerTest` 10/10 PASS（含 `.part` 尾部预检、预检拒绝、续传、取消保留分片、固定 commit/镜像 URL），`HuggingFaceTransferUrlTest` 3/3 PASS。

## 5. 复核记录与未验证项

### 5.1 已复核（2026-10-26 本轮，真实字节与 HF API）

- **HF LFS oid 字段修正**：HF tree API 的 `lfs.oid` 才是文件 sha256；原表/原 profile 中的 `f68930ef…`、`6048e11f…`、`53b5f294…`、`9fc99dd5…` 是 `xetHash`。`Wan22Ti2v5bProfiles` 的 DiT Q4_0/VAE/UMT5 Q8_0 及两条备选 Q4_K_M 已修正为 LFS oid。
- **设备侧真实字节复核（device-a，2026-10-26 19:52:56 落盘）**：`run-as com.muyuchat.mca.devicetest sha256sum` 对 `files/video_models/wan2.2-ti2v-5b/` 三个角色文件实算，结果与 pin 完全一致：
  - DiT Q4_0 3,029,086,560B → `fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463`
  - UMT5 Q8_0 6,043,068,256B → `2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc`
  - VAE 1,409,400,960B → `e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156`
  - 原始输出：`build/evidence/v1-06/model-hash-device-a-20261026.txt`。
- **备选/参考资产 HF API 复核（2026-10-26）**：DiT Q4_K_M `95b19697…`、DiT Q8_0 `57bece98…`、UMT5 Q4_K_M `17cf97a5…` 的 `size`/`lfs.oid` 与表中值一致；Comfy-Org/Wan-2.2 的 `wan2.2_vae.safetensors` LFS oid 亦为 `e40321bd…`（与主 VAE 同字节）。
- **Comfy-Org fp16 参考资产**：已取到 sha256 `456f9013…`、大小 9,999,658,848B、仓库标签 Apache-2.0、仓库 revision `ee6f4a40…`；仓库已由 `Wan_2.2_ComfyUI_Repackaged` 重命名为 `Wan-2.2`。仍**不进入本基线可执行清单**（本基线用 GGUF Q4_0+Q8_0，不下载 fp16）。
- **固定 commit 复核（2026-10-28，HF API `?recursive=true&expand=true`）**：QuantStack `57437632…` 树上 DiT Q4_0 3,029,086,560B → `fcf40dd6…`、Q4_K_M 3,433,116,000B → `95b19697…`、Q8_0 5,400,179,040B → `57bece98…`、VAE 1,409,400,960B → `e40321bd…`；city96 `b535255b…` 树上 UMT5 Q8_0 6,043,068,256B → `2521d4de…`、Q4_K_M 3,655,145,312B → `17cf97a5…`。两个仓库当时的 `main` 也等于这两个 commit，即固定 revision 与 2026-10-26 记录的字节完全同一份。
- **下载链路（2026-10-28 真机）**：device-a 直连 `huggingface.co`/`cdn-lfs` 全部超时（DNS 174.37.243.85，TCP connect 超时、ICMP 100% 丢包），而设备直连 `hf-mirror.com` 实测 ~10 MB/s（Range 206），因此生产代码按上文的“只换前端主机”策略下载；真实下载结果与证据见 IMPLEMENTATION_LOG 的 v1-07 分节。

### 5.2 仍未验证 / 未接入

- 备选 Q4_K_M/Q8_0 权重**未下载运行**；上表 pin 只代表来源元数据与哈希字段正确，不代表真机可跑。
- 模型实际可运行性、内存峰值、耗时：P0-C（V1-03）真机测量；本轮已有 64×64×9×1 最小规格真实出片记录（见 IMPLEMENTATION_LOG），产品规格仍未完成。
- 模型下载链路（**2026-10-28 真机 PASS**）：经生产视频页驱动，device-a 真实下载三件套 10,481,555,776B（19m25s，≈12–13 MB/s，峰值 15.7 MB/s）；暂停→`CANCELLED` 保留 `.part`（1,155,031,720B）→续传从断点继续（未从 0 重来）；断网→`DOWNLOAD_FAILED`（保留 2.08GB 分片）→恢复后续传；最终设备侧三文件 SHA-256 全等 pin，页面进入 `三件套已就绪`，随后真实 T2V 245.2s 出片并发布。证据：`build/evidence/v1-07/device-b-*`、`device-c2-*`，逐项结论见 IMPLEMENTATION_LOG v1-07 §5。
- 传输主机证据边界：设备无 root / 无 tcpdump / 无 MITM，无法抓取 app 自身 TLS；已直接证明 app 自己完成传输（UI 进度 + 磁盘 `.part` + 设备侧哈希）、app 解析并尝试 `hf-mirror.com`（其自身错误串）、`huggingface.co` 在该设备不可达。302→`cas-bridge.xethub.hf.co` 链是同一 URL 的设备侧复现，**不是** app 报文抓包。

## 6. 4-step 蒸馏 LoRA（2026-10-29，v1-16 侦察）

- 候选（可用）：**Kijai/WanVideo_comfy** @ `8260d429d19fd7a72304cad059160b95d843913f`
  - 文件：`LoRAs/Wan22-Turbo/Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors`
  - 大小 **332,348,584 B**；LFS sha256 **`0ace5244e3d1256f884662c261b017249796cf5b95f05d5ed93cc02a478967b8`**
  - 张量命名（1099 keys）：`diffusion_model.blocks.N.{self_attn,cross_attn}.{q,k,v,o}.lora_down/up.weight`，另含 `diff`/`diff_b`（sd.cpp 的 `get_lora_weight_diff`/`get_raw_weight_diff` 支持这两类）
  - 许可：HF 仓库元数据无 `license` 字段；上游 `Wan-AI/Wan2.2-TI2V-5B` 为 Apache-2.0，本资产作为其蒸馏衍生物使用，分发时需同时遵守上游许可
  - 4-step 依据：`quanhaol/Wan2.2-TI2V-5B-Turbo` @ `c7cbd600f8ffa4a59bee2a3e742bdf58277a926c` 明确说明为 **4-step + CFG 蒸馏**（base `Wan-AI/Wan2.2-TI2V-5B`）；Kijai 仓库是该模型的 ComfyUI 单文件/LoRA 分发
  - 备选：`Wan22_TI2V_5B_Turbo_lora_rank_adaptive_quantile_0.15_fp16.safetensors` **198,043,768 B**，LFS sha256 `adc8416321febfe4edd9b379de0c5e96f98d8dac5fff57e4e5f2a6ec5824399b`，同一命名
- 不可用候选：
  - `lightx2v/Wan2.2-Lightning`：仅 A14B 的 T2V/I2V LoRA，无 TI2V-5B
  - `quanhaol/Wan2.2-TI2V-5B-Turbo`：只有 `model.pt` 19,999,421,147 B（全量 checkpoint，>3GB，未下载）
  - `inference-sh/Wan2.2-TI2V-5B-LightX2V-Diffusers`：仓库只有 `.gitattributes` + README，无权重
- 设备侧安装（2026-10-29 11:17/11:18）：device-b 与 device-a 的 `files/video_models/wan2.2-ti2v-5b/loras/` 下均有该文件，
  `run-as … sha256sum` 均返回 `0ace5244…`（两台一致，332,348,584 B）
- **运行时验证待补**：两台设备当时均锁屏，尚未跑带 LoRA 的探针；解锁后需在 128×128×9×4 上确认 `lora=` 回显与
  sd.cpp `apply_loras` 的实际命中（`applied tensor count`），若为 0 则说明 `diffusion_model.` 前缀与模型内部命名不匹配，需要做名称映射。

## 2026-10-29 追加：4-step 蒸馏资产候选（已定位，待下载与 sha 固定）

用户批准引入 4-step 蒸馏能力后，经 Hugging Face API 检索（models?search=TI2V-5B）找到**与已 pin 的 base 模型配套**的候选：

**首选（LoRA，与现有 base DiT 配合，无需替换 3GB 资产）**
- 仓库：hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF（likes 66、downloads 51,951、license apache-2.0、base_model quanhaol/Wan2.2-TI2V-5B-Turbo）
- 文件：**Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors**（Turbo 4-step 蒸馏 LoRA，rank 64，fp16）
- 配套：同仓库另有整模型 GGUF 量化（Q2_K…Q8_0）与 Wan2_2_5B_FastWanFullAttn_lora_rank_128_bf16.safetensors
- 接入方式：已实现的 loraPath/loraWeight 通道（sd_video_generate.cpp sha 1585E122…），日志 lora=<path>@<w> 可验证是否真的应用

**备选（整模型替换，>3GB，需重下并复核 sha）**
- hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF 的 Wan2_2-TI2V-5B-Turbo-Q4_0.gguf 等
- Green-Sky/FastWan2.2-TI2V-5B-FullAttn-GGUF（q6_k/q8_0，distill 变体）
- yetter-ai/Wan2.2-TI2V-5B-Turbo-Diffusers（diffusers 格式，不便直接喂 sd.cpp）

**已排除**：lightx2v/Wan2.2-Lightning 仅含 14B 的 T2V/I2V LoRA，无 5B。

**下一步**：取该 LoRA 的 size 与 LFS sha256 → 写成正式 pin（repo@commit+file+sha256）→ 下载到设备 → 跑 128×128×9×4 + LoRA@1.0 与 128×128×9×20 对照。
### 已固定（2026-10-29，用户批准）

| 项 | 值 |
|---|---|
| 用途 | Wan 2.2 TI2V-5B 的 **4-step 蒸馏 LoRA**（配合已 pin 的 base DiT，不替换主模型） |
| 仓库 | hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF |
| Commit | 9873ba175b4b204a81f4a43c9644e6c385ee4459 |
| 文件 | Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors |
| 大小 | **332,348,584 B** |
| LFS SHA-256 | ** ace5244e3d1256f884662c261b017249796cf5b95f05d5ed93cc02a478967b8** |
| 许可 | apache-2.0（base_model quanhaol/Wan2.2-TI2V-5B-Turbo） |
| 主机校验 | 下载后本地 sha256 **完全一致**（已核验） |
| 接入 | native loraPath + loraWeight（日志 lora=<path>@<w>） |

同一仓库另有 Wan2_2_5B_FastWanFullAttn_lora_rank_128_bf16.safetensors（660,874,456B，sha256 79290493711b022e1c6e655d803715cd8a91a75cdb139856cad46f354e2f681c）与整模型 Turbo GGUF（Q4_0 3,033,897,696B sha256 8aa3a98c254bd02a9cc7e4f1db37720fe291cef8b0c32fb78e7a2391125d6b28），留作备选。
## 2026-11-02 追加：全部视频资产的可下载链接与魔塔镜像

用户询问“视频链路所需的所有模型包是否都有下载链接”。结论：**五个资产全部有公开链接，且都不是我们改过的权重，无需自行上传魔塔**；
其中 DiT / 文本编码器 / VAE / Turbo LoRA 在 ModelScope 上有**同 sha256 镜像**，可直接使用魔塔链接。

| 角色 | 大小（字节） | sha256 | Hugging Face（来源记录） | ModelScope（下载源，固定 revision） |
|---|---|---|---|---|
| DiT Q4_0 | 3,029,086,560 | `fcf40dd6…` | QuantStack/Wan2.2-TI2V-5B-GGUF @57437632 | **lyydfys/mca-wan22-video-assets** @2372988c（`dit/…Q4_0.gguf`） |
| 文本编码器 Q4_K_M | 3,655,145,312 | `17cf97a5…` | city96/umt5-xxl-encoder-gguf @b535255b | **lyydfys/mca-wan22-video-assets** @42324c9d（`text_encoder/…`） |
| VAE fp16 | 1,409,400,960 | `e40321bd…` | QuantStack/Wan2.2-TI2V-5B-GGUF @57437632 | **lyydfys/mca-wan22-video-assets** @3ca684ee（`vae/Wan2.2_VAE.safetensors`） |
| Turbo LoRA rank64 | 332,348,584 | `0ace5244…` | hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF @9873ba17 | **lyydfys/mca-wan22-video-assets** @5ec39bef（`loras/turbo.safetensors`） |
| TAEHV 头 | 22,848,048 | `b84609b2…` | madebyollin/taehv @fa579a9a（GitHub，MIT） | **lyydfys/mca-wan22-video-assets** @7fedbb1b（`vae/taew2_2.safetensors`，逐字节镜像） |

用户决定：所有资产统一由自有魔塔账号镜像并下载。`lyydfys/mca-wan22-video-assets` 现包含上表五个文件，
外加两个备选 profile 文件（Q4_K_M DiT @60e70589、Q8_0 编码器 @4649b9c8），目录与 app 安装路径一致，
可作为整套模型根使用。上传时魔塔对已存在的上游 blob 直接复用（13 秒/文件级），没有重复转存 8 GB。
许可证：`LICENSE-APACHE-2.0`（Wan/编码器/VAE/LoRA）+ `LICENSE`（TAEHV MIT），README 记录逐文件来源与哈希。
TAE 额外说明：GitHub raw 在目标设备上不可达（SSL 000）；Kijai 的 ModelScope `taew2_2` 是
22,848,080 B / `885f6766…`、128 个张量内容都不同，不能当作同一文件，故采用 upstream MIT 文件的逐字节镜像。
七个下载 URL 已在 device-a 上逐个验证 HTTP 206（Range）。

落地：
- `Wan22Ti2v5bProfiles` 新增 `TURBO_LORA_FILE`（`loras/turbo.safetensors`，必需）与 `TAE_FILE`
  （`vae/taew2_2.safetensors`，可选）。视频页的“本地模型组件”卡片现在列出并下载全部五件。
- `VideoModelAssetOwner` 支持 HF / ModelScope 两种固定 URL；视频 profile 全部使用自有魔塔仓库直连
  （设备可达），HF 仅作为 provenance 记录。
- 就绪判定只看必需角色（DiT、编码器、VAE、Turbo LoRA）；TAE 头缺失或损坏不会阻塞全量 VAE 生成，
  但会在组件行如实显示（“可选组件，未下载”/“SHA-256 不匹配（可选）”）。
- 许可信息补入 `THIRD_PARTY_NOTICES.md`。
