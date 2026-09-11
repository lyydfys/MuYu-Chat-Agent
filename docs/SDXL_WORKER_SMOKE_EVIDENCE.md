# SDXL worker 生图证据修复（2026-09-06）

## 问题与结论

`LocalImageSmokeActivity` 的主进程预检对 SDXL 刻意只执行 1 步，使用独立请求副本和 seed 1234。它不修改最终 worker 的生成请求。

此前测试入口丢弃了 `LocalImageResult.executionMetadataJson`，将预检的 NPU 状态用于最终 `completed` 结果。报告因此缺少最终图片的 `nativeEffective`，且可能将预检的 1 步误读为最终采样步数。旧 seed 123 记录已有 56 条 worker UNet 执行进度，对应 28 步、正负两个 CFG 分支；另有 2 条主进程预检 UNet 进度。

**“请求 28 步实际只执行 1 步，导致脸歪”没有证据支持。此次修复纠正证据传递，不改变调度器，不宣称改善面部质量。**

## 修改

- QNN worker smoke 从最终 `executionMetadataJson` 构造结果，保留 nativeEffective、SDXL phase proof、timesteps、sigmas、实际 seed、计数及 transport 信息。
- 最终 NPU 结论必须由 worker 自身的 `npuActive`、`qnnGraphExecution`、`nativeExecution`、`fallback` 证明。预检只保留在 `mainProcessVerification` 内。
- 校验请求步数、最终报告步数与 nativeEffective 一致；保留各调度器自身的 timetable/UNet 次数，不假定所有调度器都有相同计数公式。
- 校验最终图片字节数和 SHA256 与 worker 元数据一致。缺失或冲突的证据使 smoke 失败。
- completed 事件提供 `nativeStats`；PowerShell summary 分别提供 `requestedSteps`、`nativeSteps`、`timetableCount`、`effectiveDenoiseSteps`、`unetExecutionCount`、`outputSha256`。原数据缺失时保持 null。

既有 CLIP projected sequence EOS 提取修复保留；CPU high precision、OpenCL 和 vendor MNN patch 不变。没有修改设备准入策略。

## 回归验证

`QnnWorkerSmokeResultTest` 增加 9 个行为测试，覆盖 1 步预检与 28 步最终结果隔离、真实计数/phase proof 保留、缺失 metadata、冲突步数、非整数计数、失败 NPU/fallback、错图 SHA/长度、未提供请求步数和不同调度器计数。

复验使用同一 Animagine SDXL 包、1024×1024、28 步、CFG 5、DPM++ 2M、seed 123，核对最终图片与旧 seed 123 产物。测试日志、APK 身份、运行 JSON、PNG 和 logcat 保存在 `artifacts/imagegen-evidence-fix-0906/`。

该复验通过 smoke 调用生产 provider/worker 链路，不代表 UI 触摸遍历或全项目回归验收。
