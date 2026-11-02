# 视频工作流参考审计

核查日期：2026-10-25。本文是实施方案的来源说明，不是运行通过报告。主方案见 [EXECUTION_PLAN.md](EXECUTION_PLAN.md)。

## 1. 固定参考和核查范围

用户指定参考项目为 [nightmare-mobile](https://github.com/AbrahamPaulJ/nightmare-mobile)。本次用 `git ls-remote`、GitHub commit API 和源码内容核对到同一提交：

`42163d86e91fc2717a9d3673417d970f2998000d`

原草稿末尾为 `800d0` 的 SHA 无法解析；以上末尾为 `8000d` 的 SHA 才是本次参考基线。后续实现应使用固定提交链接，不能跟随默认分支变化后仍沿用本次结论。

本次只读查看公开源码、LICENSE、NOTICE 和指南；没有运行参考 APK、构建其 native backend、下载模型或复现作者性能数字。

| 资料 | 本次确认的事实 | 对 MCA 的意义 |
| --- | --- | --- |
| [BackendProcess.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/BackendProcess.kt) | 管理 native 可执行文件、模型参数、runtime 库准备、进程退出及诊断；包含 ggml Hexagon 和分块内存预算相关配置。 | 参考进程所有权、退出诊断和 runtime 一致性。MCA 使用自身 worker/lease 协议，不复制启动参数或整套 runtime 布局。 |
| [Backend.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/Backend.kt) | 本地 HTTP 使用 `127.0.0.1:8189`，显式绕过系统代理；请求带本次启动的 token，探活与推理阶段使用不同超时。 | 回环通信也需要认证、身份和分阶段超时；MCA 首版可以使用既有 AIDL，不必为参考 HTTP 再造一个服务。 |
| [WanVideoNode.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/npu/WanVideoNode.kt) | 编排提示词/输入图、模型、尺寸和帧数，调用视频后端；默认 5B 路径为 Turbo，使用四步采样相关配置；后端原始帧文件再送编码器。 | 可参考“节点输入 → typed 请求 → 阶段事件 → 帧文件 → MP4”闭环。不能把 Turbo 参数当作官方基础 TI2V-5B 的等价默认值。 |
| [ModelCatalog.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/ModelCatalog.kt) | 分别登记 5B Turbo、Fun Control、14B T2V、14B I2V；引用 DiT、umT5、TAEHV 以及部分模型的 LoRA。 | 同为 Wan 名称不代表资产可互换。MCA manifest 要固定变体、组件角色、精度、采样配方、来源和 digest。 |
| [VideoWriter.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/npu/VideoWriter.kt) | 使用 MediaCodec 输入 Surface 和 MediaMuxer 写 H.264 MP4；支持逐帧 provider，检查取消/停滞，编码停滞后可重试软件编码器；MediaStore 发布区分 API29+。 | 重点借鉴 EOS 停滞、取消可达性和 codec 释放；重试必须从同一冻结帧源重建编码器，不重跑模型。MCA 需独立完成探测、发布和恢复协议。 |
| [VideoGate.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/npu/VideoGate.kt) | 按 `versionCode` 保存 canary 探测状态，先写 `probing`；下次启动发现该状态时视为上次探测崩溃。它还影响参考项目的视频显示。 | 这是探测/崩溃缓存，不是跨任务 semaphore，也不能证明共享 native 资源互斥。MCA 不移植其可见性决策，只评估隔离探测和崩溃记录的诊断价值。 |
| [DitEngine.kt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/java/com/abrah/nightmare/DitEngine.kt)、[CMakeLists.txt](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/app/src/main/cpp/CMakeLists.txt) | 图执行之外还有定制 C++ 后端、构建依赖与补丁；并非完整 ComfyUI Python 服务。 | API 外观和节点图不能证明 MCA 当前 native 已具备相同 kernel、分块或加速能力。逐项追溯许可友好上游和补丁后再独立实现。 |
| [视频指南](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/guide/pages/flows/video.md) | 展示文生、图生、控制、视频连接等产品能力，并给作者测得的时长和资源说明。 | 用于行为与范围对照；作者测量不是 MCA 的 benchmark、预设或交付承诺。 |

## 2. 基础 TI2V-5B 与参考 Turbo 的区别

首期 MCA 目标仍为 Wan2.2 TI2V-5B。必须先决定资产配方并写进 manifest，不得只写一个 `wan2.2-5b` 名称，然后混用以下两种实现：

| 项目 | MCA 首期正确性基线 | 参考项目默认 5B 视频路径 |
| --- | --- | --- |
| DiT | 官方基础 TI2V-5B，或可追溯且语义明确的基础模型量化版本 | TI2V-5B Turbo 的 Q4_0 GGUF |
| 采样 | 与选定基础模型配方一致，实际有效参数有回显 | Turbo 四步相关 schedule；不能直接复制给基础模型 |
| 文本编码 | 配套 umT5 与 tokenizer，固定版本和转换来源 | 模型目录引用量化 umT5 encoder |
| 视频解码 | 先验证该 TI2V 变体的配套 VAE | 使用 TAEHV 相关组件；属于另一个需要单独证明的近似解码选择 |
| 加速/内存 | 当前 MCA Android 构建与实际 kernel 需要 P0 验证 | 定制后端、Hexagon 路径与权重/计算分块预算 |
| 性能/画质 | 本机重新测量 | 仅为参考项目特定构建、资产与设备的结果 |

更换基础模型、蒸馏模型、采样 schedule 或近似 decoder 都形成新的 execution profile；输出、画质、许可证与资源结果分别记录。不能用其中一个 profile 的通过结果替代另一个。

参考项目的 `NMV1` 是自定义帧文件：magic 后跟小端 width、height、frameCount、fps，随后是帧数据。MCA 可以设计自己的有版本帧容器或采用 PFD/受控文件协议；没有必要承诺兼容 `NMV1`，更不能只检查 magic 就信任文件长度和尺寸乘积。读取前要做 checked arithmetic、完整长度和资源限额校验。

## 3. 许可证和分发边界

以下是源码中记录的许可事实，实施时仍须检查原始权利来源和所选文件 revision：

| 内容 | 证据与结论 | 实施动作 |
| --- | --- | --- |
| 参考项目整体 | [LICENSE](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/LICENSE) 为 CC BY-NC 4.0；[NOTICE](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/42163d86e91fc2717a9d3673417d970f2998000d/NOTICE) 说明来源及单独组件许可。 | 不将整体源码或其构建产物当作 MCA MIT 代码直接复制。借鉴行为，独立实现；采用组件时逐项确认原始授权及归属。 |
| 官方 Wan2.2 基础模型 | NOTICE 将官方 Wan2.2 记为 Apache-2.0。 | 核验实际下载文件、模型卡、许可证、转换过程和 digest；代码许可不能自动覆盖全部权重。 |
| 5B Turbo | NOTICE 明确区分：Turbo 代码 CC BY-NC-SA 4.0、权重页面未声明许可证、量化 GGUF 页面声称 Apache-2.0。 | 这是一条需澄清的来源链，不能归纳为“Turbo 全部 Apache-2.0”。未澄清时不作为默认可分发模型配方。 |
| TAEHV | NOTICE 记为 MIT，并固定使用相关 decoder 文件。 | 仍核对该文件和权重授权；记录与官方 VAE 的兼容/质量差异，不能无记录替换。 |
| umT5、14B 及 LoRA | 不同来源、不同变体与重打包文件分别列出。 | 每个组件独立固定 URL、revision、size、sha256、许可、转换和使用方式；14B 不进入首期完成定义。 |
| stable-diffusion.cpp、ggml、QuickJS | NOTICE 分别记录 MIT；参考项目使用带 Hexagon 优化的 stable-diffusion.cpp fork。 | 追溯到明确许可的上游提交与独立补丁；MIT 组件不会把整个参考项目自动变成 MIT。首版无需引入 QuickJS。 |
| QNN/Hexagon SDK 与 runtime | 不能由 Kotlin 工程或模型许可证推断其可分发权利。 | 使用 MCA 既有合规来源并逐项记录，不搬运参考 APK 内二进制。 |

## 4. 当前 MCA 的准确对照

核对基线：父仓库 HEAD `68b6eef1e67870b02bfef6afda2e625d36663da9` 加现有工作区差异；stable-diffusion.cpp revision `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0`，该子模块另有未提交补丁。父仓库 SHA 不能独立复现这套 native 输入。

### 4.1 API 已有，Android 视频接线尚缺

[stable-diffusion.h](../../../third_party/stable-diffusion.cpp/include/stable-diffusion.h) 当前第 705–710 行的 ABI 为：

```cpp
void sd_vid_gen_params_init(sd_vid_gen_params_t* params);
bool generate_video(sd_ctx_t* ctx,
                    const sd_vid_gen_params_t* params,
                    sd_image_t** frames_out,
                    int* num_frames_out,
                    sd_audio_t** audio_out);
```

函数返回成功与否，帧通过输出参数返回。它不是“直接返回 MP4”或“直接返回帧指针”的 API。适配器必须初始化参数与所有输出，Wan 无音轨路径明确不请求音频；每帧像素 buffer、帧数组、可能的音频以及部分失败输出均有明确释放者。`free_sd_ctx()` 不代替结果释放。

[NativeStableDiffusionBridge.kt](../../../core/sd-native/src/main/java/com/muyuchat/core/sdnative/NativeStableDiffusionBridge.kt) 当前只有图片生成/放大、查询、取消和关闭方法，尚无 video JNI。不能把上游 ABI 存在写成 MCA Android 视频可运行。

### 4.2 全量解码是独立的峰值风险

[stable-diffusion.cpp](../../../third_party/stable-diffusion.cpp/src/stable-diffusion.cpp) 的 `decode_video_outputs` 在当前第 7047 行先得到完整 float 视频 tensor，第 7067–7076 行再分配并转换全部 RGB 帧。二者可以同时占内存。

因此，JNI 返回后逐帧编码、逐帧读取磁盘或只保留一个 Bitmap，能降低后续阶段的内存，不能消除 native 解码峰值。若需要真正减峰，必须评估 decoder/sink 接口，保留时域上下文和输出等价性，再测整个进程与加速器资源；不能只测 Java heap。

### 4.3 参数与回调有具体边界

- 当前 Wan 帧数处理会向下对齐到 `4k+1`。请求 6 帧不能在 UI 中继续标为 6 帧而实际输出 5 帧；编译阶段验证或明确展示 resolved 值。
- 当前 TI2V VAE 空间 scale 为 16、Wan DiT down factor 为 2，合法尺寸倍数因此为 32；此值来自本基线的模型实现，不应推广成所有未来视频模型的常量。见 [vae.hpp](../../../third_party/stable-diffusion.cpp/src/vae.hpp) 与上述 native 源码的 request 构造。
- cancel/progress callback 的作用域是 DSO 全局状态。同进程多个 context 不能各装回调后假定互相隔离；需要串行所有权或进程隔离，并验证停止回调覆盖哪些实际阶段。

### 4.4 已有资源锁应推广，而非绕过

[LocalImageExecutionGate.kt](../../../app/src/main/java/com/muyuchat/mca/LocalImageExecutionGate.kt) 使用文件锁 `native_image_execution.lock` 保护跨进程执行；[LocalImageWorkerExecution.kt](../../../app/src/main/java/com/muyuchat/mca/LocalImageWorkerExecution.kt) 的图片生成和放大已经接入。另有 [RuntimeReleaseOperations.kt](../../../app/src/main/java/com/muyuchat/mca/RuntimeReleaseOperations.kt) 的聊天 engine 生命周期 lease。

视频接入前应把现有锁域、调用者和获取顺序纳入统一设计，保持图片/放大的真实调用覆盖。只给视频新建 semaphore 不会形成共享互斥；owner 与 worker 重复获取同一非重入 lease 还会自锁。

[Android sd-native CMake](../../../core/sd-native/src/main/cpp/CMakeLists.txt) 当前关闭 Vulkan/WebM/WebP 等开关。参考项目的加速、分块与帧文件协议不能由此自动继承。

## 5. P0 必须补出的证据

| 证据 | 判定要求 |
| --- | --- |
| 资产与参数映射 | 首期固定基础 TI2V-5B profile；DiT/encoder/词表/VAE 的来源、许可、hash、ABI 和实际有效参数完整。Turbo 留在独立 profile 实验，不能替代基础模型验收。 |
| 文生/图生真实执行 | 代表性 ARM64 环境分别完成 load、condition、sample、decode，输出可辨识片段；不以 stub 或静态 API 代替。 |
| 内存与磁盘 | 记录模型驻留、采样、float 解码、RGB 帧、codec 和临时 spool 的阶段峰值及总峰值。 |
| 取消与释放 | 分别记录取消受理、native 停止、worker 退出/复用、资源释放；覆盖解码和 codec EOS 停滞。 |
| 媒体闭环 | MP4 容器检查加真实解码/播放器 smoke；发布与 Room 对账，进程死亡后的清理和恢复幂等。 |
| 重复与竞争 | 第二次执行不复用失效 context；图片、放大和视频的竞争不绕过共享锁，也不发生锁顺序死锁。 |
| 加速 | 与 CPU 正确性分别报告；定制 fork/SDK/kernel 的存在、真实 graph 与资源释放有证据才宣称支持。 |

本次结果：固定参考提交、上述公开文件与当前 MCA 对照为已核查；模型下载、参考项目复现、MCA 视频 native 执行、画质、编码和设备性能均为 `NOT_RUN`。这些运行项由主方案的 P0 及后续任务卡完成。
