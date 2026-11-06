# DSH 100% 补齐 — 当前状态与剩余工作

> 生成时间：2026-11-06。本文件是**交接单据**，不是宣传材料：它只写已验证的事实与仍未做的部分。

## 已完成并有真机/门禁证据

| 组 | 内容 | 证据 |
|---|---|---|
| ① 装机 | 内置 Ubuntu 24.04.5 rootfs + proot + proot-loader，六步安装流水线 | 真机 `step 1..6 ok` + `Installed(...)`；`DSH_CONTAINER.md §2.2` |
| ② 运行时 | proot 以假 `.so` 方式从 nativeLibraryDir 执行，`PROOT_LOADER` 指向随包的 loader | 真机 `PRETTY_NAME="Ubuntu 24.04.5 LTS"`、`aarch64`、`/usr/bin` 292 个程序 |
| ⑤ 终端 | 原生终端页：多标签、扩展键行（Ctrl/Alt 粘性）、双指缩放、ANSI 兜底 | 22 个 JVM 测试；`:feature:dsh-workspace` 全绿 |
| ⑥ Web UI | GeckoView 第二内核 + 控制台切换 + 不可用时回落系统 WebView | `DshWebKernelPolicy` 6 个测试；真机切换区块可见 |
| ⑦ 插件 | 控制台安装/删除，由随包 pnpm CLI 在官方 `profiles/web` 内执行 | 13 个测试；插件名/版本校验 + 输出脱敏 |
| ⑨ 安全 | `ApiKeyVault`：Android Keystore AES-256-GCM，`v1:` 自描述格式，旧明文迁移 | 既有测试全绿 |

全量门禁：`verify-code.ps1 -FullTests -Rerun` → **7197 测试 / 0 失败 / 13 跳过**。

## ⑧ 可靠性：已完成与未完成

**已完成**

- 安装修复页（运行自检 / 修复托管文件）
- 安全启动 profile（进入/退出安全模式）
- 导出诊断
- 应急恢复**决策层**（`DshEmergencyRecovery`，9 个测试）：按失败码给出有序修复计划
- 应急恢复**执行器**（`DshEmergencyRecoveryRunner`，7 个测试）：计划顺序执行、先停机、首个失败即停、汇总不泄露路径

**未完成（唯一剩余代码工作量）**

`DshEmergencyRecoveryRunner` 尚未接入 app 组合根。接入需要注入四个真实效果：

1. `stopHost` —— 停掉当前 DSH 世代并等待收敛
2. `resetHome` —— 把 `<dataRoot>/home` 改名为 `home.corrupt-<timestamp>`（**会动用户数据，控制台必须二次确认**）
3. `reinstallPayload` —— 用 APK 内置负载重装
4. `fallBackToPackagedPayload` —— 放弃已提交闭包，用自带负载启动

事实来源：`DshRuntimeSnapshot.phase` / 失败码、payload marker、`profiles/web` 是否存在、闭包是否已提交。

**设计决定（待用户确认）**

- 脚本验签：当前**没有** Ed25519 之类的独立签名。完整性来自 **SHA-256 清单**（`archiveSha256` + `criticalFiles` 逐文件哈希），而清单随**已签名的 APK** 分发。安全等级等价于 APK 签名。
- 若要"脚本增量热更新 + 独立验签"，需要引入密钥体系（签名私钥在发布侧、公钥内置），这是**新增信任链**，超出当前范围，需产品决策。

## 发布

- 正式 APK：`artifacts/releases/rc/MCA-v0.4.0-arm64-v8a.apk`
- `SHA256 = 86ac383fbb0d0675146bd1dec7344e294da4fdadf304373288fe22d507bad4b6`，620,871,640 字节
- **尚未** `git tag` / `push` / `gh release create` —— 等用户确认（对外不可逆动作）。

## 迭代效率（已改进）

```powershell
.\gradlew.bat :app:assembleRelease -PmcaIteration=true   # 跳过 R8/资源压缩/符号/lintVital，保留 release 签名
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am broadcast --include-stopped-packages -a com.muyuchat.mca.RUN_CONTAINER_INSTALL -p com.muyuchat.mca
adb logcat -s MCA-CONTAINER
```

- 迭代打包：40 分钟 → **3~8 分钟**（增量）
- 容器安装验证：6 步手动点击 → **1 条广播命令**
- 广播入口在正式包内恒关闭（`BuildConfig.MCA_ITERATION == false`）

## 已知小问题（不影响功能）

- 探针 stderr 曾有一行 `proot warning: can't canonicalize …/tmp`，已通过安装前 `mkdirs()` 消除（`b13e6f34`）。
- 平板 `/data` 曾达 99% 导致 `INSTALL_FAILED_INSUFFICIENT_STORAGE`，已清理 5 个遗留测试包（空出 37 GB）。后续验证前应检查 `df -h /data`。
