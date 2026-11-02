# 天玑 NPU S4 前置条件与 ABI 取证（2026-10-31）

**结论先行**：ABI 已由 MediaTek 公开 API 文档确定，S4 窄 adapter 已实现并通过构建/单测；**真机 restore/run 仍 BLOCKED/NOT_RUN**，唯一缺的是一台 MediaTek 设备来验证 `dlopen`、DLA 恢复、padding、执行与释放。本机只有高通设备，不能把任何 S4 结论写成已运行。

## 1. 已核实的事实

1. **上游 LiteRT 有 Apache-2.0 的 MediaTek 适配层。** `litert/vendors/mediatek/neuron_adapter_api.{h,cc}` 通过 `dlopen`/`dlsym` 加载厂商 NeuronAdapter，解析 `NeuronModel_create`、`NeuronModel_addOperand`、`NeuronModel_setOperandValue`、`NeuronModel_identifyInputsAndOutputs`、`NeuronCompilation_*`、`NeuronExecution_setInputFromMemory`、`NeuronModel_restoreFromCompiledNetwork`（及上游新增的 `V3`）等符号。该文件是 S4 的公开实现参考，不是厂商 SDK 的替代品。
2. **本地 AAR 不含厂商 runtime。** `litert-npu-runtime-mediatek:2.3.0` 只带 `libLiteRtDispatch_MediaTek.so` 与 `libLiteRtCompilerPlugin_MediaTek.so`；两者在运行时加载设备端的 `libneuronusdk.so` / `neuron_sys_util.mtk.so`。AAR 内可检索到 restore/execution 符号名和 `MTKNN_ADAPTER_DLA_*` 环境变量，但没有可独立执行的 Neuron 实现。
3. **LiteRT 的 MediaTek AOT 路径消费的是编译器生成的 LiteRT bytecode**，不是公开包里的裸 `.dla` + NPUG stub。把 `.dla` 直接交给 `CompiledModel`/dispatch 没有上游依据。
4. **参考实现的 DLA 恢复走厂商 NeuronAdapter**，与 LiteRT bytecode 路径是两条不同通道；这与分析报告的结论一致。
5. **ABI 已由公开文档确定，S4 adapter 已实现。** MediaTek 的 Neuron API Reference 给出了 `NeuronModel_restoreFromCompiledNetwork(NeuronModel**, NeuronCompilation**, const void*, size_t)`、`NeuronExecution_setInput/Output(..., const NeuronOperandType* type, ...)`（type 必须为 NULL）、`NeuronCompilation_getInput/OutputPaddedSize` 等精确签名。`MediaTekNeuronRuntimeAdapter` + JNI 桥已按此实现，构建期不链接厂商库，运行时 dlopen；arm64/x86_64 `.so` 构建通过，adapter 8 + VAE codec 4 + sigma 3 共 15 项 S4 相关 JVM 测试通过（core 总计 88）；`vaeOnly` 设备冒烟已编译。尚未在设备上执行。

## 2. S4 所需的最小前置

| 前置 | 当前状态 | 需要什么 |
|---|---|---|
| MediaTek 设备（目标 mt6993 / Dimensity 9400） | **NOT_RUN**（本机仅 SM8750P / SM8550） | 一台可 adb 的 Dimensity 设备 |
| 设备端 Neuron runtime ABI | **已由公开 API 文档确定**（`neuron-api-signatures.txt`） | 设备上确认实际库名/版本与符号可用性 |
| 厂商头文件 `neuron/api/NeuronAdapter.h` | **不再需要**（opaque handles + 公开签名） | 设备符号/版本核对；SDK 仅有参考价值 |
| 模型资产与 route | **ESTABLISHED** | 已锁定的 ditc1c stub + DLA + route（见 `reference-profile-lock.json`） |
| 单 chunk 输入基线 | **ESTABLISHED（DERIVED）** | 70/70 已绑定；#68/#69 为 rope 两半，#2 为参考零填充槽位（语义未定）；调用方字节包 #1–#69 共 1,315,328 B（`reference-input-pack.json`，SHA-256 `56d589e8…`）；真机未验证 |
| S4 adapter | **BUILD_OK / JVM TESTS PASS** | 真机 restore→bind→run→close 验收 |
| 完整链 AOT 资产 | **ESTABLISHED (FULL_RECOMPUTE)** | 四块 DiT DLA + VAE DLA 已全部下载并本地复算，size/SHA 与树一致 |
| VAE I/O | **ESTABLISHED (full graph)** | `[1,64,64,16]` int16 scale 1.901989e-4 → `[1,512,512,3]` int16 scale 4.065646e-5，NHWC；与 MCA v3 NCDHW 布局需转置 |
| sigma 调度 | **ESTABLISHED (DERIVED)** | `t=1-i/steps`，`sigma=3t/(2t+1)`，i=0..steps；见 `sigma-schedule-evidence.json` |

取证入口：`python tools/mediatek/probe_neuron_runtime.py`。脚本只做只读取证，不加载模型、不执行推理；无 MediaTek 设备时输出 `NOT_RUN`。

## 2.1 设备冒烟入口

```bash
# 只验证 runtime 可加载/版本/设备名（非 MediaTek 会记录 BLOCKED 并跳过）
python tools/mediatek/run_neuron_smoke.py --mode probe --install --build

# 单 chunk restore→绑定→run→close；需要 MediaTek 设备
python tools/mediatek/run_neuron_smoke.py --mode restore --install --build

# 独立 VAE DLA：零 latent [1,64,64,16] → [1,512,512,3]
python tools/mediatek/run_neuron_smoke.py --mode vae --install --build
```

脚本会用 `reference-input-pack-build.txt --write-pack` 生成 69 路 pack + synthetic hidden + config，推到测试 app 的 external files dir；`--install` 是显式安装开关，默认不安装。报告落在 `artifacts/device/mca-neuron-smoke/report.json`。`MediaTekNeuronSmokeTest` 在非 MediaTek 设备上会跳过 restore 并记录原因，不会伪造 NPU 结果。

## 3. S4 实现边界（不变）

- adapter 只暴露 `open→restore→bind→run→close` 与证据查询；不复制参考 App 的 Activity/下载器/全局单例。
- 一笔 attempt 只做一个后端；不允许 stub 回退 JIT，不允许填零冒充成功。
- 输出先写请求临时目录；通过结构/有限值/质量检查后才交给既有 `ImageGenerationOwner → worker → LocalImageProvider` 路径。
- 真机未跑通前，`npuAttribution` 保持 `UNVERIFIED`，设备类结论保持 `NOT_RUN`。

## 4. 明确不声称的事

- 未在真机装载/恢复/执行任何 DLA；
- 未取得厂商头文件或 SDK；
- 未验证 `restoreFromCompiledNetwork` 的 V1/V3 选择、输入 padding、量化 ABI；
- 未证明 `.dla` 与目标 runtime/驱动组合兼容。
