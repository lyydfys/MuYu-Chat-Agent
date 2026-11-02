# QNN/NPU 可行性审计：Wan2.2 TI2V-5B（2026-10-26）

## 结论

**BLOCKED（资产/转换链缺口，不是代码失败）**。当前工作区只有 Wan2.2 TI2V-5B 的 **GGUF**（sd.cpp 用 Q4_0/Q8_0），没有原始 PyTorch/safetensors/ONNX，也没有 Wan2.2 的 QNN context binary 或 QNN 模型包；现有 QNN 集成只覆盖 **SD1.5 图像**（`unet.bin`/`vae_*.bin`），不能直接迁移到视频 DiT。此时写 QNN 后端没有可验证的模型输入，不能宣称 NPU 加速。

## 已有条件

- QNN SDK 2.45.0.260326 完整：`qairt-converter`、`qnn-onnx-converter`、`qnn-context-binary-generator`、`qnn-net-run`；HTP V68/V69/V73/V75/V79/V81 stub。
- 设备：device-a `kalama`/SM8550（HTP V73）；device-b `sun`/SM8750P（vendor `libQnnHtpV79Stub.so`）。
- 现有 QNN 图像链路：`core/native` 的 `qnn_native_bridge`/`qnn_controlnet_execution`、`QnnRuntimeProfileSelector`（exact-arch 匹配）、SD1.5 QNN smoke 规格（VAE encoder context ~58MB 量级）。
- 设备 QNN HTP runtime 可由现有图像路径/探针验证；本审计不重复图像验证。

## 缺口（为什么现在不能做 Wan QNN）

1. **没有原始权重/ONNX**：QNN 转换器吃 ONNX/TFLite；GGUF 不能直接转 QNN。工作区未发现 Wan ONNX/safetensors（仅 SD turbo 的无关 safetensors）。
2. **没有 Wan2.2 QNN 模型/context binary**：QNN 图像包是 SD1.5 UNet/VAE，不是 Wan DiT/T5。
3. **算子/布局未验证**：Wan DiT 的 3D conv/attention/quantized matmul 在 HTP 的 op 覆盖、shape/dynamic 约束、量化布局没有证据；需要先做 op 覆盖与 context-size spike。
4. **规模风险**：5B DiT + 4.7B T5（Q8 约 6GB）+ VAE，远超现有 SD1.5 QNN context 量级；HTP context/权重共享/共享 DDR 的实际上限未验证。
5. **缺视频 QNN 执行适配**：现有 QNN 只在图像 adapter；视频 owner/worker/Room 契约不能改，QNN 必须作为新的执行适配/后端切片接入。

## 现成方案搜索（2026-10-26）

未找到 Wan2.2 TI2V-5B 的 QNN/HTP 现成实现或模型包。最接近的公开工作：

- **local-dream**（Android SD1.5，QNN HTP + MNN 混合）：QNN 只跑 SD1.5 UNet/VAE，不是 Wan DiT；issue #327 明确 `libggml-hexagon-htp.so` 的 Hexagon 后端没有 conv2d/group_norm，SD 类卷积仍需单独 QNN 栈。https://github.com/xororz/local-dream/issues/327
- **Qualcomm SD3.5 Medium 端侧 QNN 指南**（HTP v73+，transformer 约 2.3GB，32GB 内存 Windows on Snapdragon）：说明 transformer 扩散可在 HTP 跑，但模型规模/平台不是 Wan2.2 5B + 4.7B T5 + 12GB 手机。https://m.blog.csdn.net/csdnsqst0050/article/details/162511168
- **ggml Hexagon/QNN PoC**（kantv #121、llama.cpp hexagon 后端）：面向 LLM 算子，不是 Wan 的 3D conv/attention 图。https://github.com/kantv-ai/kantv/issues/121
- QNN 转换/执行路径本身成熟（qairt-converter、qnn-onnx-converter、ExecuTorch QNN、QNN ONNX Runtime EP），但都要求 ONNX/TFLite/PTE 输入，不能直接吃 GGUF。

结论：没有可直接改造的 Wan2.2 QNN 工程；只能自建，且先决条件是原始权重/ONNX 与转换环境。

## 恢复条件（按序）

1. 提供 Wan2.2 原始权重（safetensors）或固定 revision 的 ONNX 导出（DiT/T5/VAE，含动态 shape 导出脚本）。
2. 用固定 QNN SDK/AIMET 版本转换/量化出 DiT、T5、VAE 的 QNN context binaries，并记录转换 revision、量化配置与 sha256。
3. 在 device-a（HTP V73）与 device-b（HTP V79）上做最小 op/context/内存 spike：单 graph 加载、上下文大小、权重共享、峰值内存、单步耗时；不支持 op 必须显式失败，不能静默回 CPU 宣称加速。
4. 通过后再按现有 `VideoExecutionAdapter` 边界接入 QNN，保持唯一 owner、worker、Room、媒体发布与取消契约不变；QNN 仅作为后端选择，失败回 CPU 并明确记录。

## 证据

- SDK：`/e/tools/qairt-sdk/2.45.0.260326/{bin/x86_64-windows-msvc,lib/aarch64-android}`。
- 现有 QNN 图像规格：`core/download/src/main/java/com/muyuchat/core/download/ModelScopeCatalogQnnSpecs.kt`（SD1.5）。
- Wan 资产：只有 `MODEL_ASSET_SOURCES.md` 所列 GGUF；无 Wan ONNX/QNN。
- 设备：`getprop ro.board.platform` = kalama/sun；device-b vendor `libQnnHtpV79Stub.so`。
- 当前 CPU 稳定 APK：`614db669…`，device-a 9/9；JVM 690/0、架构 19/19（2026-10-26 16:45）。

**在恢复条件 1–3 完成前，QNN 状态保持 BLOCKED/NOT_RUN；不得用 SD1.5 QNN 或 CPU 结果替代 Wan2.2 视频 NPU 证据。**
