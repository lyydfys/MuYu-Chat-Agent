# 推荐模型包检查与 Gen 5 QNN 配置修复（2026-09-21）

本次检查覆盖当前目录全部 48 个条目，其中推荐页可见 39 个。发现并修复了三个 Gen 5 QNN 生图包的图名称绑定错误，以及旧安装配置升级后可能丢失 ZIP 子目录路径的问题。文件清单检查没有发现必需组件缺失。

这是一份源码、上游包结构和主机回归报告。本轮没有构建或安装 APK，也没有新的手机运行、出图或 logcat 证据。用户截图没有附完整运行 JSON，不能据此断言其设备上的唯一故障已经实机排除。

## 1. 发现与修复

### 三个 Gen 5 QNN 包实际图名不是 `model`

从目录指定的发布 ZIP 中，通过 HTTP Range 读取图二进制头，得到以下名称：

| 推荐 ID | text_encoder.bin | unet.bin | vae.bin |
| --- | --- | --- | --- |
| `qualcomm_sd15_gen5_qnn` | `stable_diffusion_v1_5_text_encoder` | `stable_diffusion_v1_5_unet` | `stable_diffusion_v1_5_vae` |
| `qualcomm_sd21_gen5_qnn` | `stable_diffusion_v2_1_text_encoder` | `stable_diffusion_v2_1_unet` | `stable_diffusion_v2_1_vae` |
| `qualcomm_controlnet_canny_gen5_qnn` | `controlnet_canny_text_encoder` | `controlnet_canny_unet` | `controlnet_canny_vae` |

ControlNet 的额外图为 `controlnet_canny_controlnet`。

旧配置统一使用 `model`。当前 native 路径在这些阶段找不到请求名称时可选择实际首个图，返回的执行证据包含真实名称；Android 随后按旧配置比较，便会拒绝结果，产生 `QNN conditioning and runtime-session evidence differs from the selected worker strategy` 一类报错。UNet 也存在同样的名称偏差。

修复为按图文件传递真实名称，三个配置的 revision 从 2 升到 3，覆盖新下载配置和已有的、来源匹配的推荐包配置迁移。其他八个 QNN 生图 ZIP 的图名经检查仍是 `model`，保持原设置。没有关闭执行证据校验，也没有按设备或芯片添加准入限制。

### 旧安装升级后保留发布者子目录

旧 manifest 可能保存 `发布者目录/unet.bin`。revision 迁移使用目录模板后，路径会回到 `unet.bin`，此前仅在迁移前做路径修复，可能导致完整旧包被误判缺文件。

现在在最终配置解析后再次绑定安装内路径。仍只接受唯一、非空、位于 bundle 内的真实文件；歧义路径和无效文件不会被放行。修复只作用于本次请求的配置副本，不隐式重写用户的 manifest。回归覆盖三个包的实际 ZIP 包裹目录形态。

### 保留严格校验并提供具体错误

- QNN conditioning 异常现在列出真正不一致的字段及 expected/actual，不再输出一组无法区分原因的固定字段。
- 修正 shared SDXL 外部双 CLIP 路径的 mode 期望值为 native 实际返回的 `external_mnn_sdxl_embeddings`；仍校验编码执行次数、图/产物哈希、消费状态和 session mode。
- 执行结果与配置不一致时，提示更新应用并提供模型及错误详情，不再一律建议检查或重新下载完整包。
- CPU high precision、load signature、既有 OpenCL 路径未在本轮修改；本轮未修改 `third_party/MNN`，无需更新 vendor patch。

主要源码：

- `core/download/.../ModelScopeTypes.kt`：图文件到实际图名的映射。
- `core/download/.../ModelScopeClient.kt`：三个 Gen 5 包配置及 revision。
- `app/.../DownloadedImageExecutionProfile.kt`：将图名映射写入安装配置。
- `app/.../ImageExecutionProfileResolver.kt`：旧配置迁移及内置兼容配置。
- `app/.../LocalImageExecutionProfileIntegration.kt`：迁移后的安装路径重新绑定。
- `app/.../ImageExecutionProfileNativeContract.kt`：精确诊断与 shared SDXL mode。
- `app/.../MainViewModel.kt`：按原因提供错误提示。

这些文件原有其他未提交修改均保留；本报告不将此前的下载、后台运行、云端协议等改动算作本次修复。

## 2. 检查范围与证据边界

- 目录共 48 项，46 项提供下载；推荐页可见 39 项，其中 37 项提供下载。
- 两个无下载包条目是 LiteRT-LM E4B/12B NPU，占位条目原本没有发布文件。没有把未知设备当成禁用下载的理由。
- 140 个组件声明中，134 个必需组件全部存在，另外 5 个可选组件存在、1 个可选组件未发布。可比较的上游大小和 SHA 与 pin 未发现冲突。
- 唯一不存在的可选项是 `qwen35_2b_q4` 的 `embeddings_bf16.bin`。其 `llm_config.json` 使用 `tie_embeddings`，读取 `llm.mnn.weight`；生产下载解析会跳过不存在的可选组件，因此不是缺包故障。
- 11 个 QNN 生图 ZIP 均检查 central directory、必需图/内置配套文件及图二进制头；Gen 5 的独立 tokenizer/scheduler 配套也纳入下载清单和哈希检查。
- 38 个有 pin 的小配套文件实际读取内容，大小和 SHA256 全部一致。
- 6 个 MNN 聊天包读取真实配置：17 个显式路径引用存在；两个 Gemma 的独立 embedding 路径使用主权重内共享 embedding，不应误报缺失。PLE 文件已核对。
- 7 个 LiteRT-LM 容器读取真实元数据头，并使用项目生产 `readAndVerifyHeader` 配合远端实际长度检查，7/7 通过。保存的 `.header.bin` 仅为头部，不能当完整模型使用。
- QAIRT 的 24 个发布变体均核对目录、Genie 配置中的 context 分片/embedding/扩展配置引用，以及 tokenizer、metadata 和视觉包 encoder 的存在性；没有完整解析所有 metadata/pipeline JSON 内容。GGUF/safetensors 的补充检查见下面生成的逐项清单及本地 JSON。

上游文件存在、头部可解析，不代表所有模型都能在每台手机推理成功。本次未全量下载大权重，未重算所有大 ZIP 的完整 SHA/CRC，也未检查张量数值或执行原生模型。远程瞬时网络错误与包损坏分开记录。

## 3. 主机回归

最终 JUnit XML 合计 **281 tests，0 failures，0 errors，0 skipped**，不重复累计不同轮次的同一测试。

| 模块 | 测试数 | 重点 |
| --- | ---: | --- |
| core/download | 64 | 全图名映射、图/配套下载计划、其余模型默认行为、下载恢复 |
| app（8 个相关测试类） | 157 | 三个 Gen 5 配置、CFG 开/关证据、旧安装迁移和嵌套路径、错误提示、严格拒绝无效证据 |
| feature/modelhub | 29 | 推荐目录、展示状态和用户文案 |
| core/modelstore（3 个相关测试类） | 31 | MNN readiness、LiteRT 容器格式、QAIRT 必需文件与路径检查 |

新安装迁移测试第一轮使用空 scheduler JSON，正确地被生产解析器拒绝。随后将测试夹具补为发布者的有效 scheduler/tokenizer 字段，重跑通过；保留失败日志说明过程，没有放宽生产解析规则。

最终有效日志：`gradle-validation.log`、`gradle-final-recheck.log`、`gradle-readiness-recheck.log`；数字与类名来自 `validation-summary.json`。其中设备准入策略测试为本地守护文件，不随代码发布。

## 4. 本地证据与复查

证据根目录：`artifacts/recommendation-package-audit-20260921/`。

| 证据 | 内容 |
| --- | --- |
| `catalog-before.json`、`catalog-after.json` | 实际编译后的目录导出 |
| `catalog-change-review.json` | 本轮目录配置变化；模型 ID、下载来源、组件路径及 pin 保持不变 |
| `repositories.json`、`audit-results.json` | 上游清单与逐组件检查 |
| `companion-audit-results.json` | 小文件哈希、MNN 配置引用、QNN 图名 |
| `container-audit-results.json` | 首轮 LiteRT 头和 QAIRT 包目录 |
| `litert-production-parser-results.json` | 生产 LiteRT 解析器的 7 个结果 |
| `qairt-all-variants-results.json` | QAIRT 所有发布变体的目录及配置引用 |
| `weight-header-audit-results.json` | GGUF/safetensors 元数据和张量范围检查 |
| `validation-summary.json` | 最终不重复累计的主机测试结果 |

保留 `audit_packages.py`、`audit_companions.py`、`audit_containers.py`、`audit_remaining_formats.py` 和 Java 导出/解析脚本用于复查。它们采用有限 Range 读取，不能当作下载完成或手机验收证明。

## 5. 后续手机验收

构建完整 APK 后，优先在反馈设备上使用修复前已经安装的三个 Gen 5 推荐包，验证不删除、不重新下载也能解析到 revision 3、真实图名及真实子目录。再分别从推荐页新下载，执行普通文生图、CFG 开/关和 ControlNet 输入；收集版本、APK SHA、manifest、运行 JSON、PNG 和 logcat。对照一款原图名为 `model` 的 QNN SD1.5、一款 SDXL 及 MNN 生图，确认没有回归。

在上述证据取得前，本次只能标记“代码修复、包结构检查和主机回归通过”，不能标记“反馈手机已修复”或“所有模型真机通过”。

## 6. 全目录逐项清单

“通过”仅表示表中列明的结构或主机检查；本轮所有条目的手机执行状态均为未执行。

| 推荐 ID | 推荐页可见 | 格式/引擎 | 检查结果 | 备注 |
| --- | --- | --- | --- | --- |
| `gemma4_e2b_litertlm_cpu` | 是 | LITERT_LM | 必需组件存在；生产 LiteRT 头解析通过 | — |
| `gemma4_e2b_litertlm_gpu` | 是 | LITERT_LM | 必需组件存在；生产 LiteRT 头解析通过 | — |
| `gemma4_e2b_litertlm_npu` | 是 | LITERT_LM | 必需组件存在；生产 LiteRT 头解析通过 | — |
| `gemma4_e4b_litertlm_cpu` | 是 | LITERT_LM | 必需组件存在；生产 LiteRT 头解析通过 | — |
| `gemma4_e4b_litertlm_gpu` | 是 | LITERT_LM | 必需组件存在；生产 LiteRT 头解析通过 | — |
| `gemma4_e4b_litertlm_npu` | 是 | LITERT_LM | 未发布下载包，无可下载文件可核验 | — |
| `gemma4_12b_litertlm_cpu` | 是 | LITERT_LM | 必需组件存在；生产 LiteRT 头解析通过 | — |
| `gemma4_12b_litertlm_gpu` | 是 | LITERT_LM | 必需组件存在；生产 LiteRT 头解析通过 | — |
| `gemma4_12b_litertlm_npu` | 是 | LITERT_LM | 未发布下载包，无可下载文件可核验 | — |
| `qwen35_08b_uncensored_mnn` | 是 | MNN | 必需组件存在；真实 MNN 配置引用已核对 | — |
| `qwen35_2b_q4` | 否 | MNN | 必需组件存在；真实 MNN 配置引用已核对 | 可选 embedding 未发布；实际使用主权重内 embedding |
| `qwen35_2b_abliterated_gguf` | 是 | GGUF | 必需组件存在；GGUF/safetensors 头 2/2 通过 | — |
| `gemma4_e2b_iq4` | 否 | MNN | 必需组件存在；真实 MNN 配置引用已核对 | — |
| `gemma4_e2b_uncensored_gguf` | 是 | GGUF | 必需组件存在；GGUF/safetensors 头 1/1 通过 | — |
| `bitcpm4_cann_3b_tq2` | 否 | GGUF | 必需组件存在；GGUF/safetensors 头 1/1 通过 | — |
| `bitcpm4_cann_1b_tq2` | 否 | GGUF | 必需组件存在；GGUF/safetensors 头 1/1 通过 | — |
| `qwen3_vl_4b_qairt_w4a16` | 是 | GENIEX_QAIRT | 必需组件存在；QAIRT 发布变体 6/6 目录/引用通过 | — |
| `qwen3_4b_2507_qairt_w4a16` | 是 | GENIEX_QAIRT | 必需组件存在；QAIRT 发布变体 6/6 目录/引用通过 | — |
| `qwen35_4b_uncensored_mnn` | 是 | MNN | 必需组件存在；真实 MNN 配置引用已核对 | — |
| `bitcpm4_cann_8b_tq2` | 否 | GGUF | 必需组件存在；GGUF/safetensors 头 1/1 通过 | — |
| `minicpm_v46_q4` | 否 | GGUF | 必需组件存在；GGUF/safetensors 头 2/2 通过 | — |
| `gemma4_e4b_iq4` | 否 | MNN | 必需组件存在；真实 MNN 配置引用已核对 | — |
| `gemma4_e4b_uncensored_gguf` | 是 | GGUF | 必需组件存在；GGUF/safetensors 头 1/1 通过 | — |
| `qwen35_9b_uncensored_mnn` | 是 | MNN | 必需组件存在；真实 MNN 配置引用已核对 | — |
| `qwen3_8b_qairt_w4a16` | 是 | GENIEX_QAIRT | 必需组件存在；QAIRT 发布变体 6/6 目录/引用通过 | — |
| `qwen25_vl_7b_qairt_w4a16` | 是 | GENIEX_QAIRT | 必需组件存在；QAIRT 发布变体 6/6 目录/引用通过 | — |
| `glm47_flash_tq1` | 否 | GGUF | 必需组件存在；GGUF/safetensors 头 1/1 通过 | — |
| `qwen35_35b_a3b_iq2_xxs` | 是 | GGUF | 必需组件存在；GGUF/safetensors 头 1/1 通过 | — |
| `google_gemma4_26b_a4b_iq2_xxs` | 否 | GGUF | 必需组件存在；GGUF/safetensors 头 2/2 通过 | — |
| `gemma4_26b_a4b_abliterated_gguf` | 是 | GGUF | 必需组件存在；GGUF/safetensors 头 2/2 通过 | — |
| `cyberrealistic_sd15_qnn228` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | — |
| `realisticvisionhyper_sd15_qnn228` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | — |
| `dreamshaper_sd15_qnn228` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | — |
| `meinamix_sd15_qnn228` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | — |
| `sdxl_base_qnn228` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | — |
| `realismsdxl_dmd2_alt_qnn228` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | — |
| `animagine_xl_v4_qnn228` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | — |
| `cyberrealisticxl_qnn228` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | — |
| `qualcomm_sd15_gen5_qnn` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | 图名绑定、旧配置升级已修复 |
| `qualcomm_sd21_gen5_qnn` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | 图名绑定、旧配置升级已修复 |
| `qualcomm_controlnet_canny_gen5_qnn` | 是 | QNN_HTP | 必需组件存在；QNN ZIP 目录、图名已核对 | 图名绑定、旧配置升级已修复 |
| `sd15_mnn_512_quality` | 是 | MNN_DIFFUSION | 必需组件存在 | — |
| `sd_turbo_512_experimental` | 是 | STABLE_DIFFUSION_CPP | 必需组件存在；GGUF/safetensors 头 1/1 通过 | — |
| `mnn_sana_edit_v2` | 是 | MNN_DIFFUSION | 必需组件存在 | — |
| `z_image_turbo_q4` | 是 | STABLE_DIFFUSION_CPP | 必需组件存在；GGUF/safetensors 头 3/3 通过 | — |
| `flux2_klein_4b_q4` | 是 | STABLE_DIFFUSION_CPP | 必需组件存在；GGUF/safetensors 头 3/3 通过 | — |
| `qwen_image_2512_q2` | 是 | STABLE_DIFFUSION_CPP | 必需组件存在；GGUF/safetensors 头 3/3 通过 | — |
| `longcat_image_q4` | 是 | STABLE_DIFFUSION_CPP | 必需组件存在；GGUF/safetensors 头 3/3 通过 | — |

补充检查统计：

- QAIRT 发布包：24 个，结果 {'HEADER_OR_ZIP_DIRECTORY_PASS': 24}。
- GGUF/safetensors：28 个声明组件，结果 {'HEADER_PASS': 28}。
- 一次 QAIRT Range 请求返回 503，单独重试通过，原失败信息保留在 previousAttempt 和日志中。
- GGUF 检查元数据、分片声明和张量起始偏移；safetensors 检查 JSON 与张量字节范围。均未检查权重数值。
