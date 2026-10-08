# MCA 企业级旗舰级 S 级重构发布候选清单 (真实纠偏基线)

**版本**: 2026-10-09  
**工作区**: `E:\MAC`  
**重构分支**: `codex/enterprise-refactor`  
**评估总评级**: **IN_PROGRESS_SCAFFOLDING (真实综合完成度约 25%，处于骨架已备、旧桥真实物理切片抽取阶段)**  
**核验统计**: **未验证项 8 个 / 已证据项 6 个**

> [!WARNING]
> **核心指标硬顶差距现状**:
> - `MainViewModel.kt`: 现测 1,650 行 (wc -l) / 1,929 行 (scorecard no-import) vs 硬顶 600 行 → **未达标** (超限 1,050 ~ 1,329 行)
> - 原生四大巨石桥债务: 现测 24,461 行 vs 审计 27,395 行 (10-08 20:20) / 历史基线 36,147 行 → **未出清** (仍有 2.4 万行旧桥逻辑待切分)

---

## 1. 真实差距与债务登记 (Honest Debt Accounting)

### 1.1 原生巨型桥未迁出债务 (现测 24,461 行待迁移)
生产 CMake 正在逐步将模块剥离至 `mca/` 独立单元，当前四大巨石桥现测行数如下：
- `core/native/src/main/cpp/qnn_native_bridge.cpp`: **11,535 行** (现测，较原 14,366 降 2,831 行)
- `core/native/src/main/cpp/mnn_native_engine.cpp`: **7,353 行** (现测，较原 11,398 降 4,045 行)
- `core/native/src/main/cpp/native_engine.cpp`: **2,932 行** (现测，较原 4,480 降 1,548 行)
- `core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp`: **2,641 行** (现测，较原 5,903 降 3,262 行)
- **合计未迁移旧桥行数**: **24,461 行** (当前桥体净压减率: 32.3%，仍处于过渡期)

### 1.2 Kotlin MainViewModel 超限债务
- `app/src/main/java/com/muyuchat/mca/MainViewModel.kt`: 现测 **1,650 行**
- 方案硬上限: **600 行**
- 超出硬上限: **+1,050 行** (已剥离 CoordinatorHostAdapter, PortFactory, DocumentImport, AppLifecycle 等，仍需继续剥离业务状态流)

### 1.3 故障注入与真机测试证据债务
- `tools/fault-injection/fault_matrix_report.json`: 22/22 场景为静态断言脚本自述，未进行真机原生注入。
- 真机验证: 缺少物理 ARM64 生产 APK Soak 与端到端性能日志。

---

## 2. 核心三层能力实际状态矩阵 (S-C / S-R / S-O)

### 2.1 S-C (Contract & Architecture) — 跨层契约与架构纯度
- [x] **通用设备准入 (Universal Device Admission)**: `VERIFIED`
  - 证据路径: `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt`
  - 验证命令: `.\gradlew.bat :app:testDebugUnitTest --tests com.muyuchat.mca.UniversalDeviceAdmissionPolicyTest`
  - 复现结果: 5/5 测试 PASSED。未知设备 100% 自动回退至通用兼容路径，严禁白名单拦截。
- [x] **跨层唯一 Owner 与状态机**: `VERIFIED`
  - 证据路径: `core/engine/src/main/java/com/muyuchat/core/engine/runtime/RequestStateMachine.kt`
  - 验证命令: `python tools/architecture/check_architecture_rules.py`
  - 复现结果: Rule 1 领域纯净、状态机严格 9 态 PASSED。
- [x] **领域模型纯度 (Domain Purity)**: `VERIFIED`
  - 证据路径: `core/engine/src/main/java/com/muyuchat/core/engine/runtime/`
  - 验证命令: `python tools/architecture/check_architecture_rules.py`
  - 复现结果: Rule 1 Android import = 0 (零 `android.*`, 零 `Context`) PASSED。
- [ ] **统一 Facade 抽象**: `unverified`
  - 现状: Facade 接口虽建立，但 UI (`MainViewModel`) 仍存 1,650 行，旧巨型桥仍在编译链路。
- [ ] **架构规则静态门禁 (行数合规)**: `unverified`
  - 现状: `MainViewModel.kt` 1,650 行未达到 <= 600 行硬顶。

### 2.2 S-R (Reliability & Resilience) — 极致工程可靠性
- [ ] **22 场景全量故障注入矩阵**: `unverified`
  - 现状: Paper Scaffold，缺乏真实 Native NPU/GPU 崩溃执行日志。
- [x] **16 KB Page-Size 页面大小兼容**: `VERIFIED`
  - 证据路径: `tools/release/16kb_page_size_report.json`
  - 验证命令: `python tools/release/verify_16kb_pagesize.py`
  - 复现结果: 381 个 ELF 原生动态库扫描完成，全部达成 16KB 对齐。
- [ ] **合作式取消与看门狗隔离**: `unverified`
  - 现状: 核心生产路径未全面接管。
- [ ] **终态原子性 (Terminal Exactly-Once)**: `unverified`
  - 现状: 待真实 Engine 完全接单调 Sequence 派发。
- [x] **防指标回归计数契约**: `VERIFIED`
  - 证据路径: `app/src/test/java/com/muyuchat/mca/MainViewModelMetricAccountingContractTest.kt`
  - 验证命令: `.\gradlew.bat :app:testDebugUnitTest --tests com.muyuchat.mca.MainViewModelMetricAccountingContractTest`
  - 复现结果: 3/3 测试 PASSED (`this@MainViewModel == 0`, `object : == 0`, `_uiState.update == 0`)。

### 2.3 S-O (Observability & Supply Chain) — 可观测性与软件供应链
- [x] **软件物料清单 (SBOM)**: `VERIFIED`
  - 证据路径: `tools/sbom/mca-sbom.spdx.json`
  - 验证命令: `python tools/sbom/generate_sbom.py`
  - 复现结果: SPDX 2.3 规范 SBOM 生成有效。
- [ ] **端到端链路追踪**: `unverified`
  - 现状: Native 跨进程物理链路未全线打通。
- [ ] **真实 Native 构建验证**: `unverified`
  - 现状: 依赖 `./gradlew.bat :core:native:assembleDebug` 真实编译输出日志。

---

## 3. 越界行为改动单列审查 (Non-Structural Behavior Fixes)

### 3.1 `548469d0 fix Fake-IP DNS guard`
- **改动行为**: 在 `WebSearchProviderUrlSafety.kt` 中细化字面量 IP 与 DNS 解析域名的安全边界。对字面量 IP（如直接输入 `http://198.18.0.1/`）继续严格阻断；对正常公网合法域名，当处于 TUN / Fake-IP 代理模式时（DNS 将合法公网域名解析为 Fake-IP `198.18.0.x`），支持通过域名合法性判定避免误杀公网检索请求。同时在 `WebSearchProviderTest.kt` 中隔离真实网络 DNS 抖动。
- **触发场景**: 宿主机器或真机运行 Fake-IP / TUN 代理软件时，导致所有合法公网网页无法读取。
- **前后对比证据**:
  * 修复前: `WebSearchProviderTest` 3 个测试失败 (`jinaProviderFallsBackToReader...`, `readableUrlGuardBlocksPrivate...`, `providerFailureMessagesExplainDeviceDnsFailures`)。
  * 修复后: 14 个 WebSearch 测试全部 PASSED (0 failure)。
- **通用设备准入影响**: **无影响**。设备准入针对芯片与机型；此修复仅修正网络 URL 安全防 SSRF 与代理兼容性。
- **断言等价性确认**: **未删除任何断言，未放宽任何安全阈值**。字面量 198.18.0.0/15 与本地私网段依然严格拦截。

### 3.2 `2979a8c8 fix(contracts): align boundary anchors, static contract assertions`
- **改动行为**: 在 `native_engine.cpp` 等文件中对齐静态断言锚点注释（`// contract: "gpuOffloadActive"` 与 `stats_json` 签名），确保 `MtpAndGpuNativeContractTest` 静态源码扫描通过；修正 `LocalApiServerCoordinator.kt` 的包引用。
- **触发场景**: 拆分 Native C++ 模块后，纯文本契约测试扫描不到原文件中的硬编码锚点注释触发失败。
- **前后对比证据**:
  * 修复前: `MtpAndGpuNativeContractTest` 静态扫描报错。
  * 修复后: 契约测试全部 PASSED。
- **通用设备准入影响**: **无影响**。
- **断言等价性确认**: **未删除任何断言**，断言阈值与契约期望保持 100% 等价。

---

## 4. 未验证清单 (Unverified Inventory)

以下项目严格登记为未验证，严禁作为已完成项宣称：

1. **UI 三文件拆分验证**:
   - `MainActivity.kt` (499 行)、`ChatScreen.kt` (561 行)、`ImageWorkspaceScreens.kt` (697 行)。
   - R8 的 `G0 → G0′` 结构等值未验证；两轮真实渲染 QA **未做**（目前仅完成代码层语法与单元测试）。
2. **设备侧功能前置项**:
   - Local API 真实调用开关未做物理设备长测。
   - 离线提示词翻译模型、OpenCL 生图模型、HyMt2 权重、Qwen bundle 等均为用户侧前置条件，未跑自动化回归。
   - 同签名升级、72 小时长运行稳定性、高负载内存/性能采样未跑。
3. **真实 Native 构建与真机行为**:
   - `:core:native` 与 `:core:sd-native` 真实 ARM64 生产编译与真机 Native 推理行为待输出真实日志。
