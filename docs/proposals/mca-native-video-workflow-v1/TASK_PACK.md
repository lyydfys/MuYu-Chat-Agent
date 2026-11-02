# MCA 原生视频工作流：实施任务包

状态：待实施设计；本文件不表示任何视频功能已经完成。日期：2026-10-25。

配合 [主方案](EXECUTION_PLAN.md) 与 [参考实现审计](REFERENCE_AUDIT.md) 使用。工程规则以 [架构硬锁](../../architecture/MCA_PROJECT_HARD_LOCK.md) 和执行环境的适用指令为准。当前文档任务不授权修改生产代码；获得实现授权后，才按下面任务卡开工。

## 1. 执行方式与依赖

先完成一张卡、检查和记录结果，再推进满足前置条件的下一张卡。检查点用于审查和恢复，不要求每卡等待用户批准。涉及范围外功能变更、破坏数据或外部发布时另行处理授权。

带完整模块前缀的路径均相对仓库根目录。为减少重复，卡片中的应用 Kotlin 裸文件名以及 `composition/`、`presentation/`、`video/` 缩写均以 `app/src/main/java/com/muyuchat/mca/` 为基址；`app/schemas/`、`app/src/main/aidl/` 等已带前缀的路径不再拼接。花括号表示同目录文件清单，不是实际文件名。标记“拟新增”的文件、目录、类型和测试是实施落点，不代表目前存在。现有入口更名时先定位替代路径并记录，不按旧名盲建第二套实现。

| 卡 | 必需前置 | 可并行/结果边界 |
| --- | --- | --- |
| V0-01 基线 | 实现授权 | 只读核查，不构建 |
| V0-01b 契约与检查接线 | V0-01 | 固定 UI/执行/仓储共同契约 |
| V0-02 视频入口 | V0-01b | 可与 V1-00、V1-01 分工准备 |
| V0-03 编辑器切片 | V0-02 | 无模型也可完成真实 UI 验证 |
| V1-00 共享资源仲裁 | V0-01b | 与 V1-01 独立；先保护旧图片路径 |
| V1-01 模型清单 | V0-01b | 对应 P0-A；不承诺手机可运行 |
| V1-02a 持久任务 owner | V0-01b、V1-00 | 先建可恢复权威，再连 native |
| V1-02b worker | V1-02a | 对应 P0-B；fake 不算真实推理通过 |
| V1-03 JNI/native | V1-01、V1-02b | 对应 P0-C；P0-D 加速证据另列 |
| V1-04 编码与发布 | V1-02a；真实整合依赖 V1-03 | 合成帧可先测编码，不冒充模型输出 |
| V1-05 播放与媒体库 | V0-03、V1-04 | 可用脱敏测试 MP4 验证播放器 |
| V1-06 整合验收 | V1-03、V1-04、V1-05 | 唯一 V1 完成判定点 |
| V2-01 API parser | V1-06 | 先固定目标 ComfyUI revision/fixture |
| V2-02 图编译 | V2-01 | 每种映射分别证明语义 |
| V3-01 远端 | V2-02 | 显式选用，不能自动替代本地失败 |
| V3-02 扩展节点 | V3-01 | 每个节点是独立切片 |
| E-01 大模型实验 | V1-06，独立任务范围 | 不阻塞 V1，不计入 V1 完成 |

P0-A/B/C 是对应生产卡的验证关口，不是再造一套永久 spike。最小实验代码放在同一 adapter/worker 边界；验证后升级该实现或删除实验路径，禁止同时保留 debug executor 与生产 executor 两条链。

P0-D 记录 CPU 正确性以外的加速与移动实用性。缺少加速证据不妨碍无关 UI、媒体读取、契约或编码测试；它也不能被写成加速通过。CPU 产出正确视频，只能证明对应配置正确，不能自动得到“移动端体验可交付”的结论。

## 2. 全卡共同交付字段

每卡记录：起点 HEAD、既有差异摘要、子模块 revision 与补丁摘要、实际写集、生产调用者、状态 writer/owner、功能与数据影响、命令及执行数量、原始证据位置、未验证项、回退点、下一卡前置。结果统一为 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`。

模块名、任务名与测试类名以实施后的源码为准。验证命令参见主方案 §13；使用项目受控构建入口及共享构建锁，不能绕锁直接启动第二个 Gradle/native 构建，也不能在已经持锁的脚本外重复加同一非重入锁。

测试区分契约、fake 状态机、真实 native、真机播放和发布验收。新增测试覆盖真实失败风险；简单等值移动优先复用既有回归。零匹配、零执行或旧 XML 均不能作本卡通过证据。

## 3. V0：入口、契约和编辑体验

### V0-01 基线与边界冻结

- **前置/owner**：实现范围已授权；整合负责人执行。
- **写集**：仅本方案目录中的基线/切片记录；生产源码与构建输入只读。
- **读链**：导航 → composition/process root；图片 UI/API → lifecycle lease → worker 文件锁；Room/run → 文件发布 → 恢复；native 参数 → 取消/释放。
- **步骤**：记录 branch/HEAD、tracked/untracked 差异和各子模块内部 diff；核对 JDK 21、SDK/ABI、真实库和资产来源；为本次实现锁定保留能力、状态机、写集和所需原始证据。
- **出口/验证**：给出当前事实与计划差异表；没有把历史报告或旧 APK 当作当前通过。无需为这张文档卡编译。
- **回退**：删除本卡新增记录即可；不清理既有目录或用户数据。

### V0-01b 契约实装与检查接线

- **前置/owner**：V0-01；契约由整合负责人统一写入。
- **写集**：`settings.gradle.kts`、`app/build.gradle.kts`；拟新增 `core/video-contract/build.gradle.kts`、`core/video-contract/src/main/`、`src/test/`；拟新增 `feature/video-workspace/build.gradle.kts`；按需修改 `gradle/libs.versions.toml`。
- **检查接线写集**：`scripts/verify-code.ps1`、`tools/architecture/check_architecture_rules.py` 及其相关测试、`architecture/architecture-manifest.json`、`.github/workflows/android-ci.yml`，仅增加真实 video 模块覆盖，不改旧断言或扩大忽略。
- **步骤**：实现 versioned graph、typed ports、run/attempt identity、snapshot、错误、只读 projection 和窄命令；明确 Android-free 契约。feature 只取得 video contract/designsystem；运行时实现留在应用适配边界。
- **出口/验证**：编译契约与空 feature；执行图校验、序列化/版本和依赖方向测试。证明新模块确实进入验证入口，不能默认 `-FullTests` 已包含它。
- **旧路径/回退**：没有迁移旧业务；撤回新增模块接线与本卡文件，不动原聊天/图片契约。

### V0-02 视频入口壳

- **前置/owner**：V0-01b；导航负责人独占共享 shell 写集。
- **写集**：`app/src/main/java/com/muyuchat/mca/{AppTab.kt,MainActivity.kt,MainChatScreenActions.kt,LoadModelCompletionPublish.kt,ChatModelCancelAndSwitchOperations.kt}`；`feature/chat/src/main/java/com/muyuchat/feature/chat/{ChatScreenActions.kt,ChatViewModel.kt,ChatScreen.kt,ChatHistoryDrawerScreens.kt}`；拟新增 video Route/Screen 及导航测试。
- **装配写集**：`app/src/main/java/com/muyuchat/mca/composition/{MainRuntimeAssembly.kt,McaAppContainer.kt}` 中最小 typed client 接线；具体执行 owner 尚未建立时注入显式未接线能力，不创建默认真实 engine。
- **步骤**：追加 `VIDEO_WORKSPACE`，侧栏在 DSH 下方显示“视频”；完整传递窄导航 callback；独立 tab key/SaveableStateHolder、返回 CHAT；晚到聊天模型回调保留当前视频 tab。
- **出口/验证**：抽屉顺序、点击、返回、重复进入、状态保存、晚到加载回调均验证；浅/深色和无障碍实渲染。页面不因进入而启动 native。
- **旧路径/回退**：只追加入口；回退新枚举/回调/Route，保留原 enum 顺序和全部旧入口。

### V0-03 模板与可编辑原生节点图纵向切片

- **前置/owner**：V0-02；video feature 负责人。
- **写集**：`feature/video-workspace/src/main/`、`src/test/`、`src/androidTest/`；纯图编辑/校验契约确有变化时由契约负责人合入 `core/video-contract/`。不修改共享主题数值。
- **步骤**：手机默认“文生/图生模板 → 简化节点关系 → 参数 sheet → 输出预览 → 主动作”；高级画布补新增/删除节点、typed 连线/断连、平移/缩放、撤销/重做和保存 revision。
- **交互合同**：编辑命令产生新草稿；运行绑定冻结 revision，运行期间编辑不得改变进行中的请求。提供可访问的节点列表/连接选择器；不能只有手势连线。Back 依次关闭参数面板/退出图模式/离页，草稿未保存时有明确恢复处理。
- **出口/验证**：真实操作编辑、恢复、无效连接诊断、长 prompt、键盘、触达与读屏；至少两轮视觉 QA，每轮最多五项最高影响问题。状态 fake 必须清楚属于测试输入。
- **旧路径/回退**：编辑状态局限于视频领域；撤回新 UI 不影响 owner，不能用静态流程图宣称完成可编辑工作流。

## 4. V1：真实本地视频闭环

### V1-00 推广既有共享资源仲裁

- **前置/owner**：V0-01b；整合负责人独占锁/owner 公共边界。
- **写集**：`app/src/main/java/com/muyuchat/mca/{LocalImageExecutionGate.kt,LocalImageWorkerExecution.kt,LocalImageGenerationCoordinator.kt,RuntimeReleaseOperations.kt,ImageGenerationOwnerExecution.kt,ImageUpscaleOwner.kt}`；`presentation/LocalApiImageGenerationOperations.kt`、composition 中真实调用者及对应并发测试；审计发现其他物理调用者后先更新精确写集。
- **步骤**：先区分产品请求、chat engine lifecycle lease、worker 物理锁三层；优先等价推广现有文件 gate，保留 `native_image_execution.lock` 锁域，使图片 UI/API/放大和视频共同进入同一物理资源保护。
- **锁合同**：统一顺序为产品请求身份/准入 → chat lifecycle lease → worker 物理锁；释放反序。主进程不持 worker 文件锁再等待聊天 lease；owner/worker 不能重复获取同一非重入锁。暂停/取消等待有真实终态，进程死亡由 OS 释放文件锁。
- **出口/验证**：枚举 native generate/upscale 的实际生产调用者；覆盖 image/image、image/video、video/video、取消等待、死亡与旧 lease 释放；已有图片 UI/API/放大回归不变。
- **旧路径/回退**：迁入后清除平行 gate；保留的兼容 adapter 只转发同一 gate，明确本卡结束即清除或记录下一卡退出点。

### V1-01 基础 TI2V-5B 模型清单与资产校验（P0-A）

- **前置/owner**：V0-01b；模型资产负责人。
- **写集**：拟新增 `app/src/main/java/com/muyuchat/mca/video/model/`、相应模型 fixture/test 与主方案目录中的来源清单；需要复用下载能力时，只改现有 typed 下载/资产 adapter 的精确调用点，不另建下载引擎。
- **步骤**：固定基础 Wan2.2 TI2V-5B 的 DiT、UMT5、5B 专用 Wan2.2 VAE 各组件 revision、格式、量化、hash、许可证和 native ABI；本基线 UMT5 词表编译内嵌，绑定 native/词表摘要，不虚构另下 tokenizer 文件的要求。参考项目 Turbo/蒸馏/近似解码另记。
- **参数合同**：以固定模型+native revision 推导尺寸、帧数与采样规则；将 requested/resolved/nativeEffective 绑定 snapshot，模型/文件名相同不能替代 checksum/角色校验。
- **出口/验证**：完整/缺件/损坏/同名错件/磁盘满/中断恢复 fixture；许可证与分发边界可复核。只证明资产可用，不证明 native 能运行。
- **回退**：撤回新增推荐/manifest，不删除用户已有模型；已安装包保持可识别、可清理。

### V1-02a 持久 run、journal 与进程级 owner

- **前置/owner**：V0-01b、V1-00；状态/数据负责人，公共装配由整合者写入。
- **写集**：拟新增 `app/src/main/java/com/muyuchat/mca/video/{execution,data,workflow}/`；`ChatSessionStoreRoomDatabase.kt`、`app/schemas/com.muyuchat.mca.McaRoomDatabase/`；拟新增 `composition/VideoHostAssembly.kt`，并修改 `McaApplication.kt`、`composition/{MainRuntimeAssembly.kt,McaProcessRuntimeRoot.kt,McaProcessRuntimeOwner.kt}`，按实际关闭入口审 `ProcessActivityTracker`；相关 JVM/迁移测试。
- **步骤**：先加 workflow revision/run/journal 最小 schema 与可验证迁移，再接 `VideoGenerationOwner`；owner 使用 process root scope，唯一决定状态转换，repository 以 attempt/version 条件事务提交。
- **编译链**：本卡实现受限 MCA 原生 DAG→VideoRequest 的纯 Kotlin compiler，校验输出根、拓扑、typed 端口、模型/参数并冻结 snapshot；模板和手工图共用。V2-02 仅新增 Comfy→MCA 映射，不把首版原生 compiler 推迟到 V2。
- **生命周期**：Route/VM 仅发送命令和观察 projection；离页不取消。活跃视频 task hold 阻止最后 Activity 退出先清 holder：pending-close→同根复入→任务结算/停止确认→hold归零→closing barrier→关闭根→清 holder。真正关闭期间不能新建第二根；进程死亡仍由 journal 恢复。任务表和 journal 先于 worker 启动。
- **出口/验证**：fake adapter 验证原生图编译/参数一致、持久排队后死亡、排队取消、非法转移、重复提交、stale callback、取消/发布竞争、重试新 attempt、释放失败和恢复幂等；关闭最后Activity再重入不创建第二root，最终停止后正常关根；迁移保留旧行。fake 不出现在可生成能力判定中。
- **旧路径/回退**：不把新 run 塞入 `MainUiState` 或图片表；数据迁移后不靠旧 APK/清库回退，使用已定义的前向修复策略。

### V1-02b 独立 worker、前台执行与 IPC（P0-B）

- **前置/owner**：V1-02a；Android 执行适配负责人。
- **写集**：拟新增 `video/worker/`、`app/src/main/aidl/com/muyuchat/mca/ILocalVideoWorker*.aidl`、typed parcelable；`app/src/main/AndroidManifest.xml`、`app/build.gradle.kts` 中 AIDL/保留接线；前台通知资源及 process root 的 worker close；协议/死亡/FGS 测试。
- **步骤**：独立 `:local_video` service，非导出；begin/cancel/cancelAndExit/shutdown 携带 identity；输入、输出通过受控 token/PFD，Binder 不携带整段帧或 MP4；worker 只执行物理任务，不决定 Room 终态。
- **平台合同**：按 minSdk26/targetSdk36 核对前台服务类别、权限、subtype、启动时点、通知与后台启动限制。现有图片 `specialUse` 是参照，不是直接复制的结论；若选 `mediaProcessing`，实现适用时间限额/timeout 回调和中断收口，不能改用其他类别规避限制。
- **出口/验证**：绑定失败、binder death、主进程/worker 分别死亡、重复 cancel、超时强退、FGS 启动拒绝和通知取消均有测试；物理停止确认后再释放上层资源。
- **旧路径/回退**：删除未投入生产的 spike service/协议；若已经进入持久 run，先结算为明确中断，保留恢复记录。

### V1-03 真实 JNI、native 与正确性关口（P0-C/D）

- **前置/owner**：V1-01、V1-02b；native 负责人，构建由整合者串行运行。
- **写集**：`core/sd-native/src/main/java/com/muyuchat/core/sdnative/NativeStableDiffusionBridge.kt`、`core/sd-native/src/main/cpp/`；按实际需要改 `core/sd-native/build.gradle.kts`；上游修改限明确补丁/子模块 diff，保留 revision 与来源；native/JNI fixture 和设备测试。
- **步骤**：按当前 `bool generate_video(...frames_out,num_frames_out,audio_out)` 映射；初始化参数、逐字段回显、管理输入缓冲生命周期、逐帧/数组/context RAII；同 DSO callback 作用域串行并隔离 JNI 异常。
- **内存/取消**：先测原生整段 float decode 与 RGB 输出同时存在的峰值；编码时逐帧读取不能被当作 decode 已流式化。P0 若需 sink/分块解码，先证明时域上下文等价，再改 native；不可中断阶段记录边界并使用独立 worker watchdog 收口。
- **出口/验证**：代表 ARM64 真实文生与图生各产出已校验、可辨识的帧序列；正确帧数/尺寸、采样取消、解码中断、shutdown、重复运行和释放有证据。完整 MP4/播放是 V1-04/V1-06 联合出口，不能因 codec 尚未实现而用 fake 视频补齐。CPU、加速、可用内存/耗时分别记结果；形成实测 execution profile 与候选产品预算，不能只报“能跑”。
- **旧路径/回退**：不改图片 bridge 既有语义；晋升同一视频 adapter，移除实验双链；native 变更失败回到本卡前完整构建输入。

### V1-04 编码、校验和媒体发布

- **前置/owner**：V1-02a；真实整合还需 V1-03；媒体数据负责人。
- **写集**：拟新增 `video/{codec,media}/`、VideoExecutionJournal 的发布阶段；扩展视频 media/link DAO/schema；必要的 FileProvider XML 与备份规则；codec、crash-point、Room 迁移测试。
- **步骤**：受控 staging → H.264/MP4 EOS finalize → 文件同步/媒体探测/解码 smoke → 同根原子发布 → Room 条件事务 → journal 清理；执行主方案中的逐崩溃点恢复表。
- **合同**：V1 无音轨；真实颜色/stride/PTS/编码器能力显式验证。给有界超时、背压、取消、空间预留和 spool 上限；不能把 MediaExtractor 元数据读取当作可播放证明。
- **出口/验证**：合成帧颜色/顺序、首中末帧解码、硬件编码停滞、磁盘满、每个发布窗口杀进程与重复 reconcile；已提交成功产物不会被晚到 cancel 删除。
- **回退**：取消/失败清理仅作用本 attempt 临时文件；已发布资产采用可恢复账目及前向修复，不删除其他 run 输出。

### V1-05 播放器、媒体库与导出

- **前置/owner**：V0-03、V1-04；video feature 与媒体 adapter 分工，版本依赖由整合者合入。
- **写集**：`feature/video-workspace/` 的播放器/列表/详情；`gradle/libs.versions.toml`、feature build 中固定 Media3 依赖；`video/media/` 的导出、分享、删除 adapter；FileProvider XML 与设备测试。
- **步骤**：content URI 播放/暂停/seek、缩略图、错误重试、前后台 pause/release；分享用受控只读 URI；API29+ MediaStore `IS_PENDING` 导出，API26–28 使用 SAF 路径，拒绝覆盖不属本任务的文件。
- **删除合同**：先 tombstone 与引用/播放 lease，再幂等 GC；已删字节不能宣称可直接回滚。导出失败与私有已完成原片状态分离。
- **出口/验证**：重进页面、进程重启、坏文件、seek、分享接收方、系统播放器、API26–28/29+、权限拒绝、删除中断恢复；播放器销毁不能取消生成任务。
- **回退**：撤回 UI/导出 adapter，不破坏可读取的既有视频记录；必要 schema 修复向前进行。

### V1-06 当前候选版本整合验收

- **前置/owner**：V1-03、V1-04、V1-05；整合负责人负责证据与最终结论。
- **写集**：本方案目录中的验收记录；对应自动化回归与已确定测试夹具；生产修复按故障另开精确切片，不混入报告写集。
- **步骤**：绑定源码输入指纹、dirty patch、完整 native 输入、APK hash/signature、模型 manifest 与参数；从生产 MainActivity 的“视频”入口完成文生/图生/取消/重试/重启/播放/分享/删除。
- **回归**：图片 UI/API/放大与视频竞争、聊天加载/生成、DSH、导航、旧 Room 数据升级；运行多次并记录 Java/native/worker 总资源趋势及热状态，数量和时长写明。
- **出口/验证**：主方案 V1 完成定义逐项有原始证据；候选规格、资源/耗时/停止/热/重复运行预算在验收前冻结并满足，未定或未满足只能称实验闭环。CPU 正确、移动实用、加速、UI、数据与发布分别结论。没有新视频 HTTP 路由时不伪造 API 通过；未来新增路由仍调用同一 owner。
- **回退**：使用已记录候选构建与迁移前后边界；验收通过本身不授权提交、推送或发布。

## 5. V2/V3：受控互操作与扩展

### V2-01 ComfyUI API-format parser

- **前置/owner**：V1-06；纯图/导入负责人。
- **写集**：拟新增 `app/src/main/java/com/muyuchat/mca/video/workflow/importer/` 的纯 parser/normalizer 与 fixture；稳定诊断/导入命令类型进入 `core/video-contract/`；`feature/video-workspace/` 导入预览；固定 ComfyUI revision API fixture 与 compatibility matrix。不让 feature 取得解析/运行时实现。
- **步骤**：区分 UI/API 格式；限制字节数、深度、节点数/边数、字符串长度；保留原始 JSON 与 canonical graph；模型 role 显式绑定；显示准确 node/port/path 错误。
- **出口/验证**：重复 key/ID、环、缺引用、数值越界、类型不符、未知节点与模型错件 fixture；不静默丢节点。部分导入可保存不可运行 revision，原始文件不具有执行权限。
- **回退**：停用新 parser 版本并保留 raw/revision；不重写已经运行过的 snapshot。

### V2-02 支持子图的 compiler/lowering

- **前置/owner**：V2-01；编译器负责人，native 字段映射由原 owner 复核。
- **写集**：`app/.../video/workflow/` 中 Comfy→MCA lowerer、节点版本表与测试，复用 V1-02a 已完成的原生 compiler；app video adapter 的唯一接线点；不新增 engine/context。
- **步骤**：固定支持节点、输入默认值、采样数学与输出语义；lower 成单次受控 native pipeline；节点进度映射到真实阶段；冻结有效 seed/模型 fingerprint 和图 revision。
- **出口/验证**：固定 fixture 对照 requested/resolved/nativeEffective、文本条件、尺寸/帧数/seed/采样语义；可见地拒绝不等价映射，不猜调度器或自定义节点行为。
- **回退**：回退 compiler 版本和可执行能力映射；既有 raw/revision 可继续查看，不伪装为旧编译结果。

### V3-01 远程 ComfyUI endpoint 与任务客户端

- **前置/owner**：V2-02；远端协议负责人；Room/凭据/装配变更由各 owner 合入。
- **写集**：拟新增 `video/remote/` client、endpoint/credential adapter 和 HTTP/WS fixture；契约 remote backend 类型、video run 的远端关联字段及迁移；视频 endpoint UI。
- **步骤**：固定服务端 revision/capability；连 WS、持久提交意图与 nonce/clientId 后提交；处理响应前断线、queue/history 对账、下载/媒体发布及端点认证。未知 `prompt_id` 不能凭空查询具体 history，也不能自动重发。
- **取消合同**：排队删除按准确 ID；运行中仅使用已证实针对该任务的 cancel，旧全局 interrupt 不影响共享服务器其他任务；本地停止观察与远端真正停止区分记账。
- **出口/验证**：丢响应、WS丢事件、服务端重启、认证失效、重定向/下载越界、重复提交、未知任务与下载后崩溃；凭据不进入图/日志/分享；无静默远端回退。
- **回退**：断开新端点而不删除远端任务事实；无法确认的任务保持可对账记录，不能标“已取消”。

### V3-02 更多节点与后端

- **前置/owner**：V3-01；每个节点指定一名能力 owner。
- **写集**：该节点契约/编译器映射、adapter、模型 manifest、compatibility fixture 和 UI 参数面；公共 ABI/图 schema 由整合者统一管理。
- **步骤**：按主方案 §11.4 分成 V3-02a 输入处理/提帧、02b 裁切/连接、02c 末帧续写、02d LoRA/profile；每个切片补精确写集、类型、版本、有效参数、资源、取消、输出、许可与升级规则；先做真实闭环，再加入节点菜单。
- **出口/验证**：单节点及组合图真实执行、失败/取消/恢复与旧图兼容；不能用“支持 ComfyUI”替代逐节点证据。
- **回退**：回退对应映射版本；保留受影响图的诊断与原始内容，不自动改写图。

### E-01 Wan2.2 14B / MiniMax H3 独立实验

- **前置/owner**：V1-06，实验范围另行明确；独立 backend owner。
- **写集**：独立实验记录、来源/模型清单、隔离 adapter 与专用测试；不改 V1 默认模型、参数和完成定义。
- **步骤**：分别核对模型各组件许可证、量化、转换链、完整磁盘/峰值内存/热预算与可用 backend；本地与远端结果分开记录。
- **出口/验证**：真实模型 load/graph/输出/取消/释放证据；无法运行时保留具体阻塞与最小下一实验，不以另一个模型结果替代。
- **回退**：删除/撤销实验接线，保留合法实验记录；不回退 V1 已验证闭环。

## 6. 协作与整合顺序

契约、Room schema/版本、公共 gate、根构建、版本目录、MainActivity/composition 由一名整合负责人协调写入。可并行准备模型来源审计、纯图测试、独立 feature UI 与媒体 codec，但先冻结共同输入类型；共享文件不能由多名 Agent 抢改。

每个子任务报告实际文件、调用者、行为影响、测试数量和未验证项。整合者审查完整 diff，再在唯一受控构建槽验证合并结果。没有必要反复 clean/full build；真实 native 改动仍须完成相应完整构建和设备验证。

## 7. 阻塞与恢复检查点

| 发现的问题 | 依赖该结果的卡 | 可继续的独立工作 | 恢复条件 |
| --- | --- | --- | --- |
| 真实模型或合法资产暂缺 | V1-03、V1-06 | 已授权的 UI、契约、迁移和合成帧编码 | 完整 manifest、资产校验与来源明确 |
| native 正确输出但耗时/内存不适于日用 | 移动实用性与加速结论 | 媒体恢复、播放、编辑器和诊断 | 同配置实测完成，并明确实用 preset/限制 |
| P0-D 加速未验证 | 加速能力结论 | 已通过配置的 CPU 正确性和媒体链 | 真实 runtime、graph、输出、取消/释放证据 |
| Room 迁移失败 | V1-02a 及后继真实任务 | 不触库的图/编码单测 | 既有数据升级 fixture 通过，失败可恢复 |
| 共用锁仍有绕过入口或反向顺序 | V1-02b/03 并发执行 | 模型来源、UI和纯契约 | 全部实际调用者同域、锁序及故障测试通过 |
| 编码器持续无 EOS | 当前 attempt 发布 | 可访问旧媒体和不相关编辑 | 有界终止/释放，坏 part 隔离，真实重试通过 |
| 远端提交丢响应且无法关联 | 该 remote attempt 重发/结算 | 本地闭环、旧媒体和导入诊断 | 服务端可靠对账；未确认时保持 UNKNOWN |

阻塞报告应包含最小复现、最后可靠阶段、预期/实际结果、运行输入和缺失条件；它不自动成为新增后端、换模型、改参数或扩大写集的授权。

检查点的最小记录模板：

```text
card / start_revision / workspace_input_fingerprint:
existing_changes / actual_write_set / production_callers:
owner / state_writer / lease_and_release_path:
behavior_data_protocol_visual_impact:
commands / test_count / exit_code / raw_evidence:
result_and_scope / unverified_or_blocked / rollback_boundary:
next_card / prerequisite_status / temporary_adapter_exit:
```

## 8. 可复制开工提示词

```text
在 MCA 仓库中实施 docs/proposals/mca-native-video-workflow-v1 的视频方案。
首先读取执行环境适用指令、docs/architecture/MCA_PROJECT_HARD_LOCK.md、
EXECUTION_PLAN.md、REFERENCE_AUDIT.md 和 TASK_PACK.md，再核对真实源码与基线。
本提示词只有在用户已授权实施时使用；若当前仅授权方案，不修改生产代码。

从 V0-01 开始，按依赖表选择第一张尚未完成且前置满足的卡。
执行前列出精确写集、owner、唯一状态写入者、保留行为、验证方法和回退边界。
这是共享工作区：保留既有改动；不 reset/clean，不覆盖他人文件，不静默换基线。
新增视频沿用现有产品与运行时语义，不增加可用性限制。
页面只发 intent；process owner 统一提交、取消、结算和恢复。
共享媒体执行先推广既有 gate，保持统一锁域和固定锁顺序；不造第二套运行时。

一次完成一个可验证切片。对真实并发、迁移、资源释放风险补行为测试；
通过受控入口串行编译/测试，记录真实测试数量与退出码。
fake/stub 结果与真实 native/设备/媒体播放证据分开。
不运行会覆盖既有证据的聚合脚本，除非本卡已明确准备好它的输入和输出位置。
不复制参考仓库受限代码、二进制或品牌；不静默替换模型/参数/节点；
不把原生整段帧输出写成流式零峰值，不用停止UI收集冒充native停止。

每卡结束形成检查点：实际写集与职责、调用链、owner/状态边界、
命令/数量/结果、原始证据、PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE、
未验证项、回退点和下一卡前置。前置满足后自主继续，不逐卡索要确认。
阻塞时只暂停依赖该结果的分支；继续已授权且独立的工作。
不降低门槛、不删测试、不把缺证据写成通过；不擅自提交、推送或发布。
```
