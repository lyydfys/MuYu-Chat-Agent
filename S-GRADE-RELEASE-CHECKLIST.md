# MCA 企业级旗舰级 S 级重构发布候选清单 (真实纠偏基线)

**版本**: 2026-10-08  
**工作区**: `E:\MAC`  
**重构分支**: `codex/enterprise-refactor`  
**评估总评级**: **IN_PROGRESS_SCAFFOLDING (真实综合完成度约 25%，处于骨架已备、旧桥待真实迁移阶段)**

---

## 1. 真实差距与债务登记 (Honest Debt Accounting)

### 1.1 原生巨型桥未迁出债务 (36,147 行待迁移)
生产 CMake (`add_library(mca_qnn_native ...)` 等) 目前仍直接编译如下旧巨型桥，新 `mca/` 骨架尚未接管生产调用：
- `core/native/src/main/cpp/qnn_native_bridge.cpp`: **14,366 行** (待拆迁)
- `core/native/src/main/cpp/mnn_native_engine.cpp`: **11,398 行** (待拆迁)
- `core/native/src/main/cpp/native_engine.cpp`: **4,480 行** (待拆迁)
- `core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp`: **5,903 行** (待拆迁)
- **合计未迁移旧桥行数**: **36,147 行** (真实迁移进度: 0%)

### 1.2 Kotlin MainViewModel 超限债务 (2,734 行)
- `app/src/main/java/com/muyuchat/mca/MainViewModel.kt`: 当前 **2,734 行**
- 方案硬上限: **600 行** (目标 300–450 行)
- 超出硬上限: **+2,134 行** (待进一步将历史状态流与操作切离至 Facade/Owner)

### 1.3 故障注入与真机测试证据债务
- `tools/fault-injection/fault_matrix_report.json`: 22/22 场景目前为静态断言脚本自述生成，缺乏真实 NPU/GPU 崩溃注入、SIGSEGV/SIGKILL 拦截及 Binder 重连的真实执行日志。
- 真机验证: 缺少物理 ARM64 (Qualcomm 8 Gen 2/3/4 或天玑) 运行日志与真机 Soak 测试证据。

---

## 2. 核心三层能力实际状态矩阵 (S-C / S-R / S-O)

### 2.1 S-C (Contract & Architecture) — 跨层契约与架构纯度
- [x] **通用设备准入 (Universal Device Admission)**: 100% 达成。任何设备/芯片均不因未知、无 Profile、未认证而阻断执行；自动降级回退至通用兼容路径，由真实 Native load/execute 决定结果。`UniversalDeviceAdmissionPolicyTest` (5/5 tests) 验证通过。
- [x] **跨层唯一 Owner 与状态机**: 冻结 `RequestIdentity`、`RequestStateMachine`（9 大严格状态）、`SessionGeneration`、`RuntimeEpoch`。
- [x] **领域模型纯度 (Domain Purity)**: `core:engine` 纯 Domain 契约层严格实现 **Android import = 0**。
- [!] **统一 Facade 抽象**: 四大 Facade 接口已建立，但 UI (`MainViewModel`) 尚未完全脱壳，旧巨型桥尚未真正接线。
- [!] **架构规则静态门禁**: 契约纯度合格，但 `MainViewModel.kt` 行数超限尚未解决。

### 2.2 S-R (Reliability & Resilience) — 极致工程可靠性
- [ ] **22 场景全量故障注入矩阵**: **UNVERIFIED_NATIVE**。目前为 Paper Scaffold，需编写真实 Native 故障注入 Harness 并输出执行日志。
- [x] **16 KB Page-Size 页面大小兼容**: 扫描 381 个本地及 vendor ELF 动态库，原生对齐达标 (118KB 审计报告)。
- [!] **合作式取消与看门狗隔离**: C++ 契约与看门狗设计完成，但因旧桥未接线，生产路径尚未享受合作式取消保护。
- [!] **终态原子性 (Terminal Exactly-Once)**: 契约冻结，待真实 Engine 接入单调 Sequence 派发。
- [x] **代码层增量回归测试**: 增量编译与核心测试通过。

### 2.3 S-O (Observability & Supply Chain) — 可观测性与软件供应链
- [x] **软件物料清单 (SBOM)**: SPDX 2.3 规范 SBOM 报告 (`mca-sbom.spdx.json`) 已生成。
- [x] **敏感信息脱敏 (PII Redaction)**: 提供 `redact_pii.py` 遥测过滤器。
- [ ] **端到端链路追踪**: 契约携带 requestId，但真实 Native 链路未贯通。
- [x] **生产应急处理手册**: `docs/runbooks/INCIDENT_RUNBOOK.md` 编制完成。

---

## 3. Agent 真实进度与交付物索引

| 阶段 | 交付角色 | 提交哈希 | 真实完成内容 | 证据文档路径 | 真实状态 |
| :--- | :--- | :--- | :--- | :--- | :---: |
| **A0** | Lead / Contract Gate | `47ecab8` | 跨层契约、错误目录、根 CMake 依赖图 | `E:\MAC_REFACTOR_EVIDENCE\A0-lead-contract-freeze.md` | **VERIFIED** |
| **A1** | Native Common | `3104615f` | RAII 守卫、单调时钟、安全路径、Logging | `E:\MAC_REFACTOR_EVIDENCE\A1-native-common-raii-clock-path.md` | **VERIFIED** |
| **A2** | JNI Boundary | `ad65222` | 类型安全 Handle 注册表、JNI 异常边界定义 | `E:\MAC_REFACTOR_EVIDENCE\A2-jni-boundary-handle-registry-exception.md` | **PARTIAL_SCAFFOLD** |
| **A3** | Runtime / Lifecycle | `72be2d8` | ProcessRuntimeHost、Session、CallbackRouter 骨架 | `E:\MAC_REFACTOR_EVIDENCE\A3-runtime-engine-session-callback-lifecycle.md` | **PARTIAL_SCAFFOLD** |
| **A4** | QNN Native Slice | `b754f3c` | QNN 接口骨架 (6文件/498行)，旧桥 14,366 行待迁 | `E:\MAC_REFACTOR_EVIDENCE\A4-qnn-native-modular-slice.md` | **SCAFFOLD** |
| **A5** | MNN / llama Native | `b754f3c` | MNN 接口骨架 (7文件/563行)，旧桥 11,398 行待迁 | `E:\MAC_REFACTOR_EVIDENCE\A5-mnn-chat-native-modular-slice.md` | **SCAFFOLD** |
| **A6** | Stable Diffusion | `b754f3c` | SD 接口骨架，旧桥 5,903 行待迁 | `E:\MAC_REFACTOR_EVIDENCE\A6-stable-diffusion-native-pipeline-slice.md` | **SCAFFOLD** |
| **A7** | Kotlin Runtime Owner | `321957e` | 4 大 Facade 建立，但 VM 2,734 行超限待拆 | `E:\MAC_REFACTOR_EVIDENCE\A7-kotlin-runtime-owner-facade-state-error.md` | **PARTIAL_MIGRATED** |
| **A8** | Local API / Worker | `cad6baf` | API 鉴权拦截、SSE 桥接、WorkManager 隔离适配 | `E:\MAC_REFACTOR_EVIDENCE\A8-local-api-worker-physical-module-boundary.md`| **PARTIAL_MIGRATED** |
| **A9** | Reliability / Release | `8526c10` | 故障注入纸面矩阵、SBOM、16KB 扫描完成 | `E:\MAC_REFACTOR_EVIDENCE\A9-reliability-ci-release-supply-chain.md` | **PAPER_SCAFFOLD** |
| **A10**| UI-last MVI | `e63e379` | `Intent → Facade → State/Effect` 单向流骨架 | `E:\MAC_REFACTOR_EVIDENCE\A10-ui-last-intent-effect-unidirectional.md` | **PARTIAL_MIGRATED** |
| **A11**| Final Integration | `2b2189d` | 纠偏审计基线建立，撤销假完成宣称，建立逐桥迁移通道 | `E:\MAC_REFACTOR_EVIDENCE\A11-final-integration-release-candidate.md` | **IN_PROGRESS** |

---

## 4. 真实审计报告问答 (Realistic Status Q&A)

1. **Current rating**: `IN_PROGRESS_SCAFFOLDING` (脚手架完成度 100%，真实业务迁移完成度约 25%)
2. **S-C status**: `PARTIAL_SCAFFOLD` (契约与 Domain 纯度冻结，真实 3.6 万行 Native 代码待迁移)
3. **S-R status**: `UNVERIFIED_NATIVE` (故障注入缺乏真实执行日志，未接真实 Native 链路)
4. **S-O status**: `UNVERIFIED_DEVICE` (SBOM 与规范就绪，物理真机部署证据待采集)
5. **Blocking evidence**: 四大巨型桥尚未有任何一行真实生产逻辑切入 `mca/`；`MainViewModel` 超限 2,134 行。
6. **Unverified native/device lanes**: 全部 Native 生产推理链路 (QNN / MNN / SD) 均处于未迁移状态。
7. **Legacy paths remaining**:
   - `core/native/src/main/cpp/qnn_native_bridge.cpp` (14,366 行)
   - `core/native/src/main/cpp/mnn_native_engine.cpp` (11,398 行)
   - `core/native/src/main/cpp/native_engine.cpp` (4,480 行)
   - `core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp` (5,903 行)
8. **Files over hard limit**: `MainViewModel.kt` (2,734 行，上限 600 行)。
9. **SLO results and sample sizes**: 仅有骨架与编译基线，无真实高负载端到端样本。
10. **Release artifact hashes**:
    - Corrected Audit Report: `E:\MAC\architecture-final-report.json`
    - Realistic Checklist: `E:\MAC\S-GRADE-RELEASE-CHECKLIST.md`
11. **Rollback point**: 当前干净提交点 `e0b417b5`
12. **Next owner and date**: QNN 真实桥迁移小组 (QNN Native Migration Owner), 2026-10-08
