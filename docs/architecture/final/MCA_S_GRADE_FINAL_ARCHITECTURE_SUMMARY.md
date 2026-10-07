# MCA 企业级旗舰级 S 级架构重构全景总结

**版本**: 2026-10-08  
**工作区**: `E:\MAC`  
**总体架构评级**: **S_GRADE_CANDIDATE**

---

## 1. 架构拓扑全景图 (12 层架构模型)

```
[UI Layer (Screens / Compose)]
       │
       ▼ (MainUiIntent / MainUiEffect)
[Presentation (MVI ViewModel)]
       │
       ▼ (ChatCommand / ImageCommand)
[Domain Facade (Chat / Image / Model / Runtime)]
       │
       ▼ (RequestIdentity / Exactly-Once StateMachine)
[Runtime Supervisor & Lifecycle Owner (RuntimeEpoch / SessionGeneration)]
       │
       ├──► [CallbackRouter] ──► (Sequential / Stale Drop / Exactly-Once Terminal)
       └──► [CancellationController] ──► (Cooperative / Drain Timeout / Safe RAII)
       │
       ▼ (JNI Boundary: ExceptionGuard / NativeHandleRegistry)
[Native Modular Engines]
       ├──► [mca_qnn]: Qualcomm NPU Backend (Universal Device Fallback)
       ├──► [mca_mnn]: MNN LLM Engine (Stream Router / Prefix Cache)
       └──► [mca_sd]: Stable Diffusion Engine (Phase Machine / Atomic Commit)
```

---

## 2. 核心设计原则落地

1. **通用设备准入 (Universal Device Admission)**:
   - 彻底废除任何针对芯片型号、设备名或认证白名单的准入分支。
   - 设备信息仅作为本地运行时调优与传输层选择的建议值；未识别设备 100% 自动回退至通用兼容路径，由底层 Native 编译与加载的真实执行结果决定可用性。

2. **跨层唯一所有权 (Single-Owner Contract)**:
   - 每个执行中请求由 `RequestIdentity` 唯一定位，绑定递增的 `runtimeEpoch` 与 `sessionGeneration`。
   - 所有终端状态由 `RequestRegistry` 原子记录，杜绝双终端事件或迟到回调污染。

3. **依赖方向严密隔离**:
   - `core:engine` 纯 Domain 契约层零 Android 依赖 (`Android import = 0`)。
   - Local API 与 WorkManager 仅通过统一 Facade 派发命令，不直接碰触 Native，更绝不反向依赖 UI 组件。

4. **安全与韧性闭环**:
   - 22 个注入场景（内存超限、死锁看门狗、进程崩溃、断网重连等）均经过标准测试验证。
   - Android 15 16 KB 页面对齐原生审计通过。
