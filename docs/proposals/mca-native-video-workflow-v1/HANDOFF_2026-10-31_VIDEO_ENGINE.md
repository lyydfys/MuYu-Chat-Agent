# 交接文档：原生视频（Wan 2.2 TI2V-5B）引擎接入与对齐进度

日期：2026-10-31
范围：`core/sd-native` 视频后端、外部 sdcpp 引擎（`sdcpp_external`）、`/v1/video`、视频工作台开关
状态：**链路已跑通并验收；画质/性能对标仍在进行**

---

## 1. 一句话结论

- **能跑通**：App 内 `/v1/video` → owner → `:local_video` → native → 帧 → MediaCodec → 媒体发布，HTTP 200 且产物可校验。
- **480p 单帧画质达标**：清晰红球 + 蓝底。
- **480p 多帧结构上可跑完**（415 s / TAE 解码 9.4 s / 不 OOM 不越温），但**此前画面是噪声**；最新判断：主因是**漏掉了基线命令的采样参数**（CFG / 负面提示 / flow-shift / diffusion-fa），正在按基线契约复测（见 §7）。
- **App 进程内 NPU 不可用是平台限制**，非代码缺陷：`untrusted_app` 对 `/dev/fastrpc-*` 不可读写。

---

## 2. 已完成并通过真机验收

### 2.1 App 内端到端（已闭环，含字节级校验）

| 项 | 结果 |
|---|---|
| 请求 | `POST /v1/video`，`X-API-Key`，256×256×5 / 24 fps / steps 1 / seed 1234 |
| 响应 | **200**，`state=COMPLETED`、`stage=FINALIZE` |
| 产物 | MP4 **10,861 B**，`sha256` 与 API 返回**完全一致**；`ffprobe`：h264 / 256×256 / 24 fps / 5 帧 / 208.3 ms |
| 状态链 | `QUEUED→PREPARING→CONDITIONING→SAMPLING→DECODING→ENCODING→PUBLISHING→COMPLETED` |
| 证据 | `build/evidence/v1-26/E2E-VERIFIED.md`、`e2e-256x256x5.mp4` |

### 2.2 引擎选择（两条入口，默认零变化）

- **UI**：视频页「高性能视频引擎」开关；**2026-11-02 转正：去掉「实验性」并改名为「高性能视频引擎」、默认开启，内置 MCA 引擎保留为关闭后的回退**。提交时冻结 `engine=sdcpp_external`。
- **API**：`/v1/video` 支持 `engine`（`""` / `"sdcpp_external"`）与 `backend`（`cpu` / `htp`，默认 `htp`）。
- **默认请求体逐字节不变**（单测断言，含 UTF-8 字节比较）。

### 2.3 HTP 预检与回落（严格边界，用户指定）

- `htp_preflight_unavailable_reason()` 在**任何 context/权重创建之前**检查 `/dev/fastrpc-cdsp|sdsp|adsp` 的 F_OK + R/W。
- 仅当「请求 backend=htp 且预检失败」时**直接以 cpu 启动**；**生成开始后任何失败不重试、不回落**（旧重试分支已删除，`run_external_video_into_frames` 全文件仅 1 个调用点）。
- 响应回显：`external_backend`（实际）、`external_backend_fallback`（`htp->cpu`）、`external_backend_reason`（如 `htp_unavailable: fastrpc_not_writable`），顶层与 `execution` 内均有。
- 证据：`build/evidence/v1-26/htp-preflight-fallback.md`

### 2.4 阶段链修复（关键根因）

- 外部分支原先只写 `set_progress("sampling")`（无 step/steps），`VideoProgressProjection.samplerStep` 要求 `phase=="sampling"` **且** `steps==冻结 steps`，导致 SAMPLE 从不成立、owner 判定「阶段链不完整」。
- 修复：调用前 `progress_callback(0, steps)`、成功后 `progress_callback(steps, steps)`，复用内置引擎通道。
- 证据：`build/evidence/v1-26/video-stage-chain-fix.md`

### 2.5 命名清理（项目硬要求）

- 全仓源码 + 文档中旧别名 **0 命中**（含两份历史文档改写为 `sdcpp_external`，保留 commit 哈希可追溯）。
- 二进制内亦已清零（重编 `.so` 后 strings 校验）。

---

## 3. 关键工程结论（避免后来者踩坑）

### 3.1 正确的引擎调用契约（务必照抄）

1. DiT 必须用 `--diffusion-model`，**不能用 `-m`**；否则报 `get sd version from file failed`。
2. 骨架加载走 `file:///<name>-v<N>.so` 的**名字解析**；fastrpc **不接受任意绝对路径**（`file:///data/local/tmp/...` 同样 `0x80000406`）。
3. 与 MCA 自带 HTP 共存时，必须用**不同骨架名**（引擎支持 `MCA_HTP_SKEL_NAME`，默认 `libggml-htp`），不能靠换目录绕开。
4. 采样参数（基线 `docs/wan.md`）：`--cfg-scale 6.0 --sampling-method euler --flow-shift 3.0 --diffusion-fa`，并带**负面提示 `-n`**。

### 3.2 已排除的失败假设（都有实验）

| 假设 | 结论 |
|---|---|
| 多帧噪声 = 步数不足 | ❌ 20 步仍噪声 |
| 多帧噪声 = 分辨率 | ❌ 256² 与 480×832 都噪声 |
| 多帧噪声 = 解码器（Wan VAE vs TAEHV） | ❌ 两种解码器都噪声 |
| 逐帧图像路径解码可绕过图预算 | ⚠️ 能跑完（13.8 s），但**输出噪声**：Wan VAE latent 是时序编码，不能当单图解 |
| MCA 引擎（未打补丁）对照 | ⚠️ 未完成：缺 `libomp.so`/骨架、且不支持 `--params-backend disk`；去掉会 10.9 GB 全量入内存（11.6 GB 设备有重启风险），故放弃该对照 |

### 3.3 480p 多帧的资源现状

- TAE 解码路径：**能跑完**（480×832×5：采样 291 s + 解码 9.4 s = 415 s，未越温）。
- 真 Wan VAE 多帧：**图预算不足**（需求 8,548 MB > 2,048 MB），且引擎中「空间分块」与「流式 sink」互斥（`vae.hpp:329`）。

---

## 4. 各项能力现状对照

| 能力 | 对标基线 | 现状 | 性质 |
|---|---|---|---|
| DiT 跑 HTP | ✅ | ✅ shell 侧成立（480p 采样 128–291 s） | 已对齐 |
| App 内 DiT 跑 HTP | ✅ | ❌ `fastrpc_not_writable` | **平台权限** |
| VAE 跑 HTP | ✅ | ❌ 多帧图预算不足 | 引擎/图预算 |
| 480p 单帧 | ✅ | ✅ 画面正常（HTP 采样 118 s + TAE 5.6 s） | **已对齐** |
| 480p 多帧 | ✅ | ⚠️ 能跑完，画面待修（基线参数复测中） | **进行中** |
| 4-step Turbo 质量 | ✅ | ❌ 无 Turbo 权重；非蒸馏 4 步视频质量不足 | **资产缺失** |
| Turbo LoRA 加速 | ✅ | ❌ `--lora-model-dir` 无效（输出字节相同） | 引擎不支持该路径 |
| TAEHV 快速解码 | ✅ | ✅ 速度达标（9.4 s/5 帧），画质随 latent | 已对齐（速度） |
| 流式逐块解码 | ✅ | ✅ 自研 sink 在 128²/256² 验证 | 已对齐 |
| App 内端到端 | ✅ | ✅ 200 + MP4 + sha 校验 | 已对齐 |

---

## 5. 代码改动清单（未提交、未推送）

| 位置 | 内容 |
|---|---|
| `core/sd-native/src/main/cpp/sd_video_generate.cpp` | `engine` / `backend` 解析；`run_external_video_into_frames()`（dlopen `libmca_wan.so`）；`htp_preflight_unavailable_reason()`；阶段进度上报；回显实际后端/回落/原因 |
| `core/sd-native/src/main/jniLibs/arm64-v8a/` | `libmca_wan.so`、`libstable-diffusion.so`、`libggml-htp-mca-v79.so`（**二进制入库需评审**，若仓库不允许，应改为构建期生成） |
| Kotlin（子智能体交付） | `VideoRunSnapshot`/`VideoWorkflowPort`/`VideoWorkflowCompiler`/`VideoGenerationOwner`/`VideoRunStore`/`LocalVideoWorkerAdapter`/`VideoWorkerPipeline` 的 `engine`+`backend` 冻结与透传；`api:local` 的 `VideoGenerationApiContract`、`McaLoopbackServer` 路由、能力表与 golden |
| 视频工作台 | `VideoExperimentalEnginePreference`、`VideoWorkspaceViewModel/Route/Screen`（开关默认开启，内置回退） |
| 隔离树（**不在 MCA 仓**）`E:\MAC\.dsh-tmp\happyyzy\...` | `gguf.cpp` 5D 展平补丁；`vae_tiling.hpp` 流式 sink；`video.cpp` sink 接入 + `MCA_PER_FRAME_DECODE` 实验分支；`ggml-hexagon.cpp` 骨架名可配置 |

**测试**：`:api:local` 237 通过；`:app` 视频三测试类 28 通过；均 0 失败。

---

## 6. 复现命令

### 6.1 App 内端到端（device-b）

```bash
adb -s device-b forward tcp:11435 tcp:11435
curl -X POST http://127.0.0.1:11435/v1/video \
  -H "X-API-Key: <见设备 shared_prefs/mca_api.xml>" \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"a red apple on a wooden table","width":256,"height":256,"frames":5,"fps":24,"steps":1,"seed":1234,"engine":"sdcpp_external","backend":"cpu"}'
```

### 6.2 shell 侧引擎直跑（480p 单帧，已验证正常）

```bash
cd /data/local/tmp/hy98 && export LD_LIBRARY_PATH=.
./sd-cli -M vid_gen --diffusion-model /data/local/tmp/wan98/Wan2.2-TI2V-5B-Q4_0.gguf \
  --taesd /data/local/tmp/wan98/taew2_2.safetensors \
  --t5xxl /data/local/tmp/wan98/umt5-xxl-encoder-Q4_K_M.gguf \
  -p "a red ball on a blue table" -n "blurry, low quality" \
  --cfg-scale 6.0 --sampling-method euler --flow-shift 3.0 --diffusion-fa \
  --width 480 --height 832 --video-frames 1 --fps 24 --steps 4 --seed 42 \
  --backend diffusion=HTP0,vae=cpu,te=cpu --params-backend disk --offload-to-cpu --max-vram 2
```

### 6.3 构建

```bash
./gradlew.bat :core:sd-native:externalNativeBuildRelease --offline
./gradlew.bat :app:assembleDiagnostic --offline \
  "-PmcaQnnSdkRoot=E:\MAC\build\qnn-runtime-sdk\qairt\2.45.0.260326"
adb install -r -d app/build/outputs/apk/diagnostic/app-diagnostic.apk
```

> 注意：`assembleDebug` 用标准 debug key，与设备已装版本**签名不匹配**；必须用 `assembleDiagnostic`（同 applicationId + release 签名，可原地更新）。

---

## 7. 进行中 / 下一步

1. **（进行中）基线参数复测**：480×832×5 + `--cfg-scale 6.0 --flow-shift 3.0 --diffusion-fa -n "…" --sampling-method euler`，判定多帧噪声是否由缺参导致。
2. 若仍不合格：按 `docs/wan.md` 继续逐项对齐（`--video-frames 33` 默认值、UMT5 **Q8_0** 而非 Q4_K_M）。
3. **Turbo 权重**：获取 Self-Forcing Turbo DiT（或等价的 4-step 方案）用于画质对齐——这是 4 步出好片的必要条件。
4. **App 内 NPU**：需要具备 FastRPC/签名 PD 权限的环境（root / 系统签名 / QNN 签名 PD）。当前 device-b 无法绕过。
5. **二进制入库决策**：`jniLibs` 下的三个 `.so` 是否允许提交，需仓库所有者确认。

---

## 8. 环境与资产

| 项 | 值 |
|---|---|
| 设备 | `device-b`（SM8750P, 11.6 GB）、`device-a`（SM8550, 15.5 GB，当前离线） |
| 模型 | DiT `Wan2.2-TI2V-5B-Q4_0.gguf` 3,029,086,560 B；VAE `Wan2.2_VAE.safetensors` 1,409,400,960 B；UMT5 Q4_K_M 3,655,145,312 B / Q8_0 6,043,068,256 B；TAEHV `taew2_2.safetensors` 22,848,048 B |
| 模型落位 | `/data/local/tmp/wan98/`；App 侧经 `files/video_models/wan2.2-ti2v-5b/{dit,vae,text_encoder}/` **符号链接**指向（未复制 10 GB） |
| Local API | `127.0.0.1:11435`，key 在设备 `shared_prefs/mca_api.xml` |
| 设备安全线 | 结温 > 105 °C 立即停止；**不再重复 480p 多帧的已知掉线/超温场景** |

---

## 9. 硬约束提醒（接手者必读）

- `AGENTS.md`、`app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 为**本地专用**，禁止提交/推送/发布。
- 不得按芯片、型号、profile 隐藏或拒绝用户的下载/导入/运行入口。
- 本会话期间**无 commit、无 push、未发布任何模型/SDK/APK**。
- 不要用「低分辨率成功」替代 480p 验收，也不要把单帧成功说成视频成功。