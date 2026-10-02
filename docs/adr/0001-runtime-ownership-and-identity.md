# ADR 0001：运行时所有权、请求身份与取消语义

- 状态：已接受
- 日期：2026-10-01
- 基线：`c53f4b92`（独立环境 `E:\MAC`）
- 相关方案章节：第 6 节（状态与并发）、第 11.1 节（UI/API 同一业务链）、第 19.2 节

## 背景

MCA 同时提供本机 UI 和 OpenAI 兼容的 Local API，两者都可能触发同一份底层推理资源：
聊天生成、生图、模型加载/切换、调优任务。若 UI 与 API 各自持有一套运行时，就会出现
资源争夺、旧回调释放新请求、以及"页面销毁即释放别人还在用的资源"这类问题。

本 ADR 记录**当前已经存在**的所有权与身份机制，供后续切片（P3–P6）迁移时保持不变。
它不引入新抽象，也不替换任何安全内核；P1 只做映射与固定。

## 决策

### 1. 每个共享事实只有一个写入 owner

定位一律用**符号名**，不用行号：P2 的等值拆分改变了行号，而符号名在拆分、移动和后续切片中保持稳定。
括号内的行号是**基线 `c53f4b92` 的历史注记**，只用于回看当时的源码，不代表当前 revision。

| 事实 | 唯一 owner | 代码位置 |
| --- | --- | --- |
| 生图执行（UI 与 API 共用）| `LocalImageGenerationCoordinator` | `app/.../LocalImageGenerationCoordinator.kt`（类声明）|
| 聊天生成取消目标 | `GenerationStopToken` | `core/engine/.../McaInferenceService.kt` 中的 `GenerationStopToken`（基线行 58）|
| 引擎加载/卸载互斥 | `EngineLifecycleLease` | `core/engine/.../McaInferenceService.kt` 中的 `EngineLifecycleLease`（基线行 249）|
| 聊天 UI 生成所有权与 epoch | `UiGenerationOwnership` | 由 `UiGenerationOwnershipTest` 覆盖 |
| Local API 运行时 owner | `LocalApiRuntime.claimOwner` | `api/local/.../LocalApiRuntime.kt` 中的 `fun claimOwner`（基线行 20）|
| 图像资产生命周期 | `ImageAssetDeletionLease` | `app/...`（见 `ImageAssetDeletionLeaseTest`）|
| 前台服务租约 | `GenerationForegroundLeaseRegistry` | 见 `GenerationForegroundLeaseRegistryTest` |

### 2. 生图：UI 与 API 必须共用同一个协调器

`MainViewModel` 只构造 **一个** `LocalImageGenerationCoordinator`，字段名为
`localImageGenerationCoordinator`（基线行 2818）。
UI 路径（基线行 5382、6368、7873）与 Local API 路径（基线行 19843）都通过
`tryAcquireObservedImageGenerationLease(requestId)` 获取租约。

**核验方式（不依赖行号）：** 该函数在 `MainViewModel.kt` 中应只有**一处定义**
（`private fun tryAcquireObservedImageGenerationLease(`），且**恰好四处调用**；
`LocalImageGenerationCoordinator` 的构造点应**恰好一处**。
`LocalImageGenerationOwnershipContractTest` 以源码契约固定这两条。

- 租约绑定 `requestId` **与自增 epoch**。
- `release(lease)` 用**对象身份**比较（`active !== lease`），因此**陈旧的完成回调
  无法释放替换它的新请求**。
- 争用时不排队、不静默竞争，而是抛出类型化错误：
  `code = "image_generation_busy"`，`httpStatus = 409`。

Local API 侧通过 `LocalApiRuntime` 的可注入 bridge 接入，而不是自己持有运行时：

```kotlin
// api/local/.../LocalApiRuntime.kt 中的 imageGenerationProvider / generateImage
var imageGenerationProvider: (suspend (requestId: String, body: String) -> String)? = null
suspend fun generateImage(requestId: String, body: String): String? =
    imageGenerationProvider?.invoke(requestId, body)
```

bridge 签名携带 `requestId`，使身份在跨层传递时保持绑定。

### 3. 取消是针对某个请求的命令，不是"停止一切"

`GenerationStopToken` 通过 `activateGenerationStopTarget(requestId, runner)` 绑定到
具体执行目标；`stop` 只在 token 仍匹配时生效
（`McaInferenceService.kt` 中的 `activateGenerationStopTarget`，基线行 1629）。
任何资源释放前都要再次校验身份。

### 4. 跨进程不共享 Kotlin 单例

11 个进程（主 + 10 个 worker，见 `AndroidManifest.xml`）各自持有自己的 native handle。
`LocalApiRuntime` 的 owner 机制只在**进程内**有效，不能假定跨进程共享。

## 验证

| 不变量 | 固定它的测试 |
| --- | --- |
| 生图单 owner、陈旧租约不可释放、epoch 单调、同 requestId 可区分、空值拒绝 | `LocalImageGenerationOwnershipContractTest`（9 项，本次新增）|
| 生产源码中只存在一个 coordinator 实例 | 同上（源码契约）|
| UI 与 API 走同一获取路径，争用映射为 409 | 同上（源码契约）|
| 聊天生成所有权、后台化、显式取消优先级 | `UiGenerationOwnershipTest` |
| 前台服务租约 | `GenerationForegroundLeaseRegistryTest` |
| Local API 生命周期与 owner 替换 | `LocalApiLifecycleOwnershipContractTest` |
| API 侧图像取消 | `LocalApiImageCancellationContractTest` |
| 生图提示词取消契约 | `LocalImagePromptCancellationContractTest`（588 行）|
| 本地聊天取消状态 | `LocalChatCancellationStateTest` |
| native 取消闸门 | `NativeCancellationGateTest` |
| 图像资产删除租约 | `ImageAssetDeletionLeaseTest` |

`LocalImageGenerationOwnershipContractTest` 是本 ADR 新增的回归护栏：在它之前，
生图协调器的单 owner 规则**没有任何测试覆盖**（现有的 `UiGenerationOwnershipTest`
覆盖的是聊天，不是生图），P3–P6 的迁移有可能在不触发任何失败的情况下把 UI 与 API
拆成两条执行路径。

## 约束（P3–P6 迁移时必须保持）

1. 不得为 UI 和 API 各建一套执行路径；接入点只能是 `tryAcquireObservedImageGenerationLease`。
2. 不得用一个新的全局锁替换租约；争用语义（立即 409，不排队）必须保留。
3. 释放必须走身份校验路径，不允许任何调用方直接清空 active 租约。
4. 不得把 `requestId` 从任何跨层签名中移除。
5. 过渡 adapter 必须记录退出条件；确认调用全部迁移后删除旧路径，避免长期双实现。

## 后果

- 正向：生图并发语义可被测试固定，后续迁移有明确的不变量清单。
- 代价：`MainViewModel` 仍是这些协调器的持有者。P6 之前接受这一过渡状态，
  但**每个协调器只有一个实例**这一约束现在被测试强制执行。
- 未覆盖：本 ADR 未验证真机上的实际并发行为（需设备）。JVM 测试证明的是
  协调器逻辑与生产接线，不是运行时资源释放的真实时序。

## 未决事项

- 参数事务（desired/resolved/active/committed/LKG）的完整所有权映射尚未纳入本 ADR，
  应在 P5 前补充为 ADR 0002。
- Local API 的 `controlPlane` 与图像 bridge 的最终合并形态留待 P6 决定。
