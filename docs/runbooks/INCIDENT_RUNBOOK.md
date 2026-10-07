# MCA S-Grade Incident Response & Reliability Runbook

**版本**: 1.0 (2026-10-08)  
**适用范围**: MCA 旗舰级客户端、后台 Worker 与 Local API

---

## 1. 故障分级标准 (Severity Levels)

| 级别 | 定义 | 触发条件 | 响应时限 (SLA) |
| --- | --- | --- | --- |
| **P0** | 核心功能阻断 / 广泛崩溃 | Crash 率 > 0.05%，主推理链路崩溃，鉴权绕过，用户数据损坏 | 15 分钟内响应，1 小时内热修复或回滚 |
| **P1** | 严重降级 / 单后端不可用 | 某推理后端崩溃，取消超时 > 15s，Local API 无法连接 | 30 分钟内响应，4 小时内修复 |
| **P2** | 非阻塞异常 / 偶发重试 | 生图预览失败但最终图正常，后台 Worker 重试成功 | 2 小时内响应，24 小时内修复 |
| **P3** | 轻微问题 / 观测警告 | 冗余日志，遥测丢弃，界面次要显示偏差 | 正常迭代排期修复 |

---

## 2. 核心 SLO 指标与发布冻结阈值 (SLO & Freeze Gates)

- **Crash-Free Sessions**: ≥ 99.95% (全局崩溃率 < 0.05%)
- **ANR-Free Sessions**: ≥ 99.99% (ANR 率 < 0.01%)
- **Cancel-to-Terminal Latency**: P95 ≤ 5s, P99 ≤ 15s
- **Memory Growth**: 100 次连续 Load/Unload 后进程 RSS 回到基线 +5% 范围以内
- **发布冻结线**: 任意 P0 未解决，或 16 KB page-size 检查不通过，或设备准入存在白名单拦截，严禁发布 Release Candidate！

---

## 3. 常见故障排查处置手册 (Triage Playbooks)

### 3.1 Native 崩溃 (SIGSEGV / SIGABRT / Tombstone)
1. **收集 Tombstone**:
   - 提取 `/data/tombstones/tombstone_*` 或 logcat 抓取 `signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)`。
2. **符号化定位**:
   - 使用 NDK llvm-symbolizer 对崩溃 PC 栈帧进行解析：
     `aarch64-linux-android-addr2line -C -f -e libmca_native.so <PC_OFFSET>`
3. **隔离判定**:
   - 若在独立进程 Worker 中崩溃，确认 `ProcessRuntimeHost` 是否完成 Binder Death 捕获并重置 `RuntimeEpoch`。
   - 严禁让上一次 Epoch 的迟到指针影响新会话。

### 3.2 内存超限 / OOM
1. **获取内存快照**:
   - 检查 `TelemetryLogger` 输出的 `mca-runtime.jsonl` 中 `processPssKb`、`nativeHeapKb`、`modelMemoryBudgetKb`。
2. **紧急降级处置**:
   - 触发 `RuntimeSupervisor.advanceEpoch()` 强制清理所有残留 Session 与 Native Cache。
   - 自动切换为低精度权重或启用 CPU/Generic 分块回退。

### 3.3 任务取消卡死 (Cancel Drain Hang)
1. **超时看门狗验证**:
   - 观察请求状态机是否停留在 `CancelRequested` 或 `DrainingCallbacks`。
   - 确认看门狗（Watchdog 15s）是否正常触发强制 `Aborted` 并推进 Epoch。
2. **严防 UAF**:
   - 绝不可在 Native 上下文尚未完全停止前强行 free C++ 对象指针。

### 3.4 通用设备准入守卫响应 (Universal Device Admission)
- **硬性红线**: 任何设备上出现“设备未知”、“未认证芯片”、“缺少本地 Profile”均**不得**作为拒绝用户操作的理由！
- **处置原则**: 必须自动落入 Generic Compatible Path，由真实 Native dlopen / graph compile 决定执行能力。

---

## 4. 紧急回滚流程 (Rollback Procedure)

若生产环境发生 P0 故障：
1. **切断发布**: 立即下架出现问题的候选版本。
2. **Git 回滚点**:
   - 对应各阶段稳定提交哈希，使用 `git revert` 生成回滚提交，严禁强制覆盖历史。
3. **状态清理**:
   - 清理应用缓存目录与损坏的临时模型分片，重启服务进程。
