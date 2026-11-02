# 原生 Agent 执行任务卡与接手提示词

本文件与 [README](README.md)、[DESIGN](DESIGN.md)、[PARITY_ACCEPTANCE](PARITY_ACCEPTANCE.md) 共同构成交接包。当前是方案：以下任务均未因本文写成而完成。只实施用户明确交给本次执行者的卡；本轮没有实施授权。

## 1. 实施约定

### 1.1 开工与文件标记

每卡开始重新核对真实源码。下文简写仅用于文件定位，不是新模块要求：

| 简写 | 仓库内现有位置 |
| --- | --- |
| AC | `core/agent-contract/src/main/java/com/muyuchat/core/agent/contract` |
| AR | `core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime` |
| AA | `app/src/main/java/com/muyuchat/mca/agent` |
| UI | `feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace` |
| PR | `app/src/main/java/com/muyuchat/mca/presentation` |
| CR | `app/src/main/java/com/muyuchat/mca/composition` |

“现有”是先读入口，不表示整文件都能随意改；“拟新增”是职责建议，实施时核对是否已有等价实现。拟新增类型必须随生产调用者一起交付，不能先批量创建空接口。路径按当前仓库解析，不写机器相关常量进产品。

每卡在开始时填写：基线 SHA 与脏树文件指纹、子模块差异、负责人、读集/写集、依赖卡结果、唯一状态 writer、实际执行 owner、受影响旧功能、验收ID。合并前再次对照他人改动；共享写集串行租用。

### 1.2 共用完成门槛

一张实现卡只有同时满足下列条件，才能标为完成：

1. 按卡类型结算：基础/协议切片接入真实业务服务、旧管理预检或兼容调用者并通过其行为检查，只记“基础完成”；能力切片接通生产执行链；里程碑/整体验收要求真实 UI/触发→owner→执行→持久化→终态。不得因后置编辑器尚未交付而复制临时执行器，也不能把基础完成标成完整用户能力。没有生产调用者的空接口不属于基础完成。
2. 历史格式/旧数据、正常、失败、取消、重复与适用的重启路径得到行为验证。模型生成返回不等于用户任务正确。
3. 单一 writer、窄类型依赖、无平行 engine；遵守硬锁文件和接口预算。超过预算先做职责复核，不能改名/挪文件绕过。
4. 新状态、缓存、文件、native/平台资源均有创建、取消、释放、恢复负责人；未知停止保留 fence。
5. 检查真实执行数、退出码及日志；没有执行、不具备设备条件或只有 fake 的测试不能写成生产通过。
6. 输出变更文件、迁移、原始证据、未验证项、回退点及下一张可执行卡；不自动提交或发布。

卡内的“测试名”是拟补行为目标，并非声明已有。断言优先落在输出、账本、OS效果及数据不变量，不能只搜索源码字符串。

### 1.3 受控验证

实施时先读取当前脚本再使用，JDK/SDK/依赖按项目合同准备；本方案不执行这些命令：

```powershell
python tools/architecture/check_architecture_rules.py
.\scripts\agent-verify.ps1 -Tasks ':core:agent-contract:testDebugUnitTest' -KeepLog
.\scripts\agent-verify.ps1 -Tasks ':core:agent-runtime:testDebugUnitTest' -KeepLog
.\scripts\agent-verify.ps1 -Tasks ':feature:agent-workspace:testDebugUnitTest' -KeepLog
.\scripts\verify-code.ps1 -Tests '*实际测试类名*'
```

app过滤器必须替换为本卡实际测试名，并核实测试数不为0。设备 migration、isolated UID、Accessibility、真实 PTY、冷启动和模型行为另外运行；单测任务不能替代。新增模块核对是否被现有脚本覆盖。所有构建使用同一个项目构建锁，多个 Agent 不直接并发 Gradle；保留原始日志与 XML，不从旧缓存复制 PASS。

## 2. 依赖与整合顺序

```text
N00 → N01、N02 → N03 → N04
N04 → N05 → N06
N04 → N07；N02+N03+N04 → N08（按工具族分切片）
N04 → N09a静态导入 → N10 → N11 → N09b执行验证/启用 → N12
N02+N04 → N13 → N14 → N15 → N16
N01+N02 → N17；N03+N06+N17 → N18 → N19 → N20
N03+N04 → N21；N19+N20+N21 → N22
N07+N08+N12+N16+N22 → N23 → N24 → N25
```

N16 的前台手动提取可先验收；后台自动保存必须等 N21/N22。N21 可提前做生命周期审计，实际切换避开其它组合根/模型/前端共享写集。N24 的旧功能回归不应只在最后运行，以上每张相关卡都先执行其影响范围。

下表是上图必须补充的子切片依赖；M1–M4 是验收分组，不是强制串行发布列车。可先验收一张卡的基础切片，剩余切片保持未完成，不能用stub封卡。

| 子切片 | 附加依赖与执行顺序 |
| --- | --- |
| N07本地stdio | N08f实际进程owner；若宣称受限/离线执行，另需N10隔离证明。远端MCP部分可先交付 |
| N08h Chat/Memory/Workflow桥 | Chat/已有图片入口先接；Memory写接口等N13/N16；workflow/子frame接口等N18/N19；冷启动调用等N21 |
| N12e 模板 | 可先导入保存惰性模板；发布/运行workflow模板等N20，不能用旧recipe代替图模板行为 |
| N16后台自动保存 | 前台提案/CRUD先做；持久计划执行、冷启动及去重等N21/N22，再回到N16/M3最终封卡 |
| N18 Script节点 | N10/N11的真实脚本与host bridge；其它纯/模型/工具节点先交付，不依赖N12的UI/模板切片 |
| N18/N19共享存储 | N18先定义最小node attempt/输出事务与单步协议，N19增量加checkpoint/park/恢复；禁止N18先另写临时非持久执行器 |
| N09a/N09b | N10依赖N09a的固定内容/staging；N09b等N10/N11进行无副作用执行验证后才允许executable active。N09整卡等两段通过，inert v1保留原路径 |
| N17基础/N18/N20 | N17先接已有workflow导入预检/管理服务保存不可变定义，N18将手动launcher接入图执行策略；N20再补完整编辑/运行界面。不能要求N17提前完成N20后才开始N18 |

负责人最多同时交付三个独立领域切片，加一位整合者。AC、Room版本和 schema导出、AR owner/mailbox、CR、根构建、AndroidManifest 的改动由整合者合入。独立工作者只提交所负责实现/fixture及需要的契约差异建议，不抢写共享文件。

## 3. M0：修复基础闭环

### N00 — 锁定源码、能力与决策基线

- **依赖/所有权：** 无；整合负责人只写本卡记录和 ADR，尚不改运行行为。
- **现有读集：** 硬锁、ADR0002/0003、本方案、`AA/composition`、`CR/MainRuntimeAssembly.kt`、Room schema29、工作树和所有子模块内部差异、现有测试。
- **新增交付：** 任务证据索引、带来源的四域 capability manifest、migration ledger、五项增量 ADR 草案；编号先查冲突，不硬占现有 ADR编号。
- **步骤：** 记录 tracked/untracked 文件hash；标记前端/DSH/模型并行写集；逐条复核 DESIGN B01–B09；固定 third-party commit 和源码文件hash；登记工具实际注册、原生协议实际wire、MCP生产接线、包执行能力。
- **决策：** 确认统一调用流水线、同root frame/park、来源传播、隔离级别、执行根迁移与数据版本；每个决定列旧路径退出卡。
- **验证：** 链接/来源/写集可解析；列出已有测试实际路径和未来设备场景。能力状态默认 UNKNOWN/NOT_RUN，不从文件存在推导可用。
- **停止/回退：** 基线因并行改动漂移时刷新相关文件，不覆盖或重置他人工作；本卡回退只撤销自己的文档增量。

### N01 — 创建任务回执与幂等任务锚点

- **依赖/唯一writer：** N00；`AgentCommandProcessor` 与任务仓储事务，UI/workflow均只读回执。
- **现有写集：** `AC/AgentWorkspacePorts.kt`及实际命令结果定义、`AR/run/AgentCommandProcessor.kt`、`AA/data/`命令账本、`AA/workflow/AgentWorkflowLauncher.kt`、`UI/AgentWorkspaceViewModel.kt`及创建协调器、相应测试。
- **拟新增：** typed creation receipt与命令结果字段迁移/映射，优先扩已有契约，不额外造一套command bus。
- **步骤：** 生成稳定creationCommandId；任务、输入快照、commandId→taskId/workspaceId/resultRevision在同事务提交；重复同指纹返回原回执，不同指纹冲突；失败不留半个任务。调用者直接使用回执并删除unseen ID/goal/前200条扫描猜测。
- **兼容：** 旧命令结果可读；缺锚点记录返回明确不可恢复错误，不选同goal任务。分页、归档和同名任务不影响关联。
- **验证：** 同goal并发50条回执一一对应；提交前/后死亡再放送同命令只创建一次；不同payload同ID拒绝；UI和workflow真实入口可定位正确任务。
- **退出/回退：** 不允许残留猜测fallback；可暂停workflow新增入口，保留已创建ID与历史，不能删除任务回滚。

### N02 — Typed scope 与现有记忆数据边界修复

- **依赖/唯一writer：** N00；scope归一化由工具流水线执行；记忆数据只由现有仓储事务写。
- **现有写集：** `AC/AgentToolPort.kt`/invocation类型、`AA/security`冻结/审批、`AA/memory/AgentMemoryToolArgumentNormalizer.kt`、`AA/memory/data/RoomAgentMemoryPersistence.kt`/DAO、migration fixture、对应测试。
- **拟新增：** 分文件的`ResourceScope`与effect集合序列化/upcaster；不是无限扩张一个port。
- **顺序：** N02a先修space+item归属检查和限定子表删除；N02b修真实旧schema fixture的revision；N02c引入scope v2并迁移审批绑定；每段独立验证。
- **算法：** 同事务确认item属于space后，按父关系清理chunks/links；无此归属零变化。scope canonicalization稳定排序、去重并绑定版本/hash；Memory不再伪装workspace路径。
- **兼容：** 旧Workspace审批按已知规则upcast；旧memory伪路径审批失效重申请。不能把未知scope解释成全局workspace或空范围。
- **验证：** A空间+B条目ID删除后B的item/chunks/索引均完整；合法删除原子；29现状与旧版本迁移fixture真实执行；审批scope漂移、参数变更、撤销均不能dispatch。
- **停止/回退：** fixture失败先区分准备失败/迁移失败；schema已升级使用前向修复。不得destructive fallback或清用户数据。

### N03 — 单一 InvocationPipeline 与审批派发事务

- **依赖/唯一writer：** N01/N02；AgentRunOwner推进run，仓储原子写授权、invocation和outbox，单派发器认领执行。
- **现有写集：** `AR/run/AgentToolCallHandling.kt`、`AgentToolAuthorization.kt`、`AgentToolLifecycle.kt`、`AA/security`、`AA/data`与Room装配；整合者独占。
- **拟新增：** 纯Kotlin调用流水线、typed dispatch transaction port、持久dispatch outbox和recovery mapper；API只含真实propose/await/cancel职责。
- **步骤：** 先原loop等价迁移；freeze→policy→持久等待→重新校验→同事务consume+decision+invocation+outbox+run关联；工具执行在事务外且只读已提交intent；receipt和observation幂等提交。
- **约束：** 内存的“已批准”对象不能直接执行；旧broker不能继续独立consume后让owner另行提交。保留稳定invocationId，未知副作用不换ID重发。
- **恢复：** 按DESIGN §4.2崩溃矩阵处理；CONSUMED缺证据不等于已执行或可重发；外部动作无receipt按效果核账，UNKNOWN可见。
- **验证：** 双击审批、晚到旧epoch、事务rollback、consume/commit/dispatch/receipt/result五处死亡；断言真实执行次数、授权消费次数、配对结果和owner终态。至少一个真实文件原子写路径验证，不能只有fake计数。
- **退出/回退：** 原loop全部调用迁完删除旁路；可关闭新来源，保留旧数据读能力。不能同时留两条可执行pipeline供“保险”。

### N04 — 每run目录、权限快照与记忆UI选择闭环

- **依赖/唯一writer：** N03；catalog provider发布不可变run快照，owner持有/释放；页面只是选择意图。
- **现有写集：** `AA/composition/AgentRuntimeAssembly.kt`、`RunScopedAgentToolCatalogProvider.kt`、`AgentMemoryToolCatalogProvider.kt`、`CR/MainRuntimeAssembly.kt`、`UI/AgentMemoryCoordinator.kt`、创建task/policy路径。
- **拟新增：** snapshot manifest/revision与固定生命周期记录；复用已有provider，不建万能registry。
- **步骤：** start准入时读取启用的builtin/MCP/Skill/package/space版本；解决工具名冲突后固定catalogHash。memory选择传到任务创建与policy快照，仓储确认归属与授权；恢复使用固定内容hash，不跟随latest。
- **生命周期：** run引用旧内容，禁用阻止新run；撤销在dispatch再次校验。无活动引用后关闭session/GC版本；snapshot构建失败不创建半个run。
- **验证：** 同runtime内两run间启停生效、当前run schema不漂移；UI选择跨旋转/重入按既有草稿合同保留；不存在空间拒绝；run结束/取消/启动失败均释放引用。
- **回退：** 暂停新扩展来源，保留旧snapshot可读；不能清空catalog掩盖接线失败。

## 4. M1：工具调用与真实平台能力

### N05 — 结构化回合与真正原生工具协议

- **依赖/owner：** N04；仍由同一模型attempt gateway持lease，结构化结果不另建生成循环。
- **现有写集：** `AC/AgentModelPort.kt`、AR协议/loop、`AA/inference/AgentInferenceSupport.kt`、`AgentCloudModelAdapter.kt`、`AgentModelChoiceMapper.kt`及API wire适配测试。
- **拟新增：** AgentModelTurn、canonical transcript、provider终态映射和native call assembler，按协议分文件。
- **步骤：** N05a先让能力声明与实际wire一致；N05b升级结果契约并保留promptedV1；N05c逐一接通当前三种API格式的schema/calls/result pairing/终态；N05d再加独立promptedV2，不能宽松改V1 parser。
- **生产链：** 最终HTTP请求必须带tool schema；响应完整重建call ID/name/JSON；下一轮真实发送对应result。未完整终态、拒绝/截断/EOF产生typed失败，不提前执行partial JSON。
- **兼容：** 老transcript upcast有fixture；协议切换仅在未dispatch副作用前按明确策略发生；不能因参数错误自动换协议重做写入。
- **验证：** 受控wire server逐字节分片、重复call ID、不同call交错、usage缺失、工具终态与最终文本终态、断流；使用真实provider模型至少完成读取→运算→产物三步任务，单列服务条件与模型配置。
- **回退：** 能力协商关闭失败的native adapter，保留promptedV1；历史native transcript仍可读，不丢失配对。

### N06 — 批量调用、最终上下文预算与准确取消

- **依赖/owner：** N05/N03；owner推进批次，executor分别提供stop receipt，engine维持单模型仲裁。
- **现有写集：** AR/loop、budget/context、invocation ledger、模型序列化前context准入与停止适配。
- **拟新增：** bounded batch planner、ordinal result merger、versioned context checkpoint；不把本卡混成新模型服务。
- **步骤：** 全批验证落账；只读、无冲突、无egress且可信执行器最多3并行，其余串行；每项独立授权/终态，按原ordinal回传。模型schema/Skill/memory/result与预留输出一起算实际context。
- **长任务：** 先保留CONTEXT_LIMIT暂停，再独立验证摘要策略；摘要保留授权、未结调用和来源，不自动丢历史。工具完整输出存artifact，模型摘要有hash/truncated。
- **验证：** 逆序完成仍正确pair；一项拒绝不吞其他项；批中取消逐项停止；context边界含中文schema与超长结果；旧epoch回调不能改新run；stop超时保留fence而非释放lease。
- **回退：** 并发降为1是调度参数回退，不能删除批次语义；摘要策略可停，新checkpoint仍可读取。

### N07 — MCP 与 Skills 生产接线

- **依赖/owner：** N04/N03；MCP registry负责连接，run快照持session引用；Skill store负责内容版本。
- **现有写集：** `AA/extensions/mcp`、`AA/extensions/skills`、run-scoped provider、MainRuntimeAssembly的注入与管理服务。
- **新增交付：** transport/API兼容矩阵、session/auth错误映射、按需资源读取；按支持范围补resource/prompt实现。
- **步骤：** 移除生产mcpTools空集合的断链；固定协议版本与远端HTTP/SSE能力；再单独接本地stdio runtime与进程owner，不能拿DSH Node内部对象充当执行根。Skill每run读取启用版本，allowed-tools只收窄。
- **权限：** 远端MCP是egress；annotations不作为只读可信证明；服务端schema变化使旧审批失效。资源/Prompt内容属不可信输入，不能成为新增授权。
- **验证：** 从管理页导入到真实任务调用本地受控MCP和远端受控服务；分页listTools、会话过期/重连、auth撤回、schema drift、超时/取消/关闭；Skill资源越界/启停/更新固定快照。
- **回退：** 单来源禁用和错误可见，保留配置与凭据引用；未支持transport明确拒绝，不能静默显示连接成功。

### N08 — third-party 工具族与 Android 平台桥

- **依赖/owner：** N02/N03/N04；各工具只通过pipeline派发，各平台bridge拥有自己的可取消资源；不建一个巨型DeviceManager。
- **现有写集：** `AA/tools`、`AA/platform`、workspace port/实现和安全适配；Manifest与composition由整合者合并。
- **新增交付：** 上游逐工具API清单、MCA对应toolKey/schema/result/effects/权限/真实样本；逐族单独文件和窄port。
- **强制子切片：** N08a文件/SAF/压缩与artifact；N08b HTTP/网页/受控browser；N08c文档/OCR/媒体/FFmpeg能力；N08d标准Android应用/Intent/通知/剪贴板/位置/蓝牙与设置；N08e无障碍UI动作；N08f终端/PTY/脚本环境；N08g可选debugger/admin/root通道；N08h Chat/Tasker/Workflow/Memory/子frame及已有生图能力的窄桥接。每片先清单再最小真实链路，禁止一张卡一次性改全部族。
- **权限：** OS能力层与ALLOW/ASK/FORBID执行策略独立。每项记录最低系统依赖/用户可准备路径；未获具体权限返回可解释错误，不伪造成功。无关设备标签不作能力判断依据。
- **终端：** 真PTY、resize/stdin/进程组停止与后台子进程均需验证；sh不冒充bash。任意shell若没有fd/fs/network强边界，只能显式高信任模式，禁LOCAL_ONLY/无人值守；最终受限离线路径要由N10实际隔离证明，不能承诺Linux任意二进制都兼容Android。
- **验证：** 每个声明支持的API至少一个真实效果、一拒绝、一取消/失败样本；UI目标改变、锁屏、权限撤回、SAF授权过期、网络redirect/DNS、命令fork与daemon残留分别覆盖。多权限实现不得绕过用户审批。
- **回退：** 按单工具/transport暂停新调用，保留结果及已授目录；不整体封禁其他工具族。此卡未全验收不能用工具数量宣称全面对标。

## 5. M2：可执行工具包与兼容生态

### N09 — 版本化包安装与依赖事务

- **依赖/owner：** N04；包安装仓储是active pointer唯一writer，不持模型lease。
- **现有写集：** `AA/extensions/toolpkg` importer/parser/store/preview、`AC/AgentToolPackageContract.kt`、PR管理服务；保留inert v1。
- **拟新增：** executable v2 schema、dependency lock、content-addressed store、install journal与引用GC。
- **步骤：** staging扫描→格式/路径/大小/依赖/能力差异预检→无副作用解析测试→固定内容版本→原子切active。解析拒绝环、冲突、hash不符、未知执行物；离线bundle携全锁依赖，不运行时安装。
- **分段：** N09a只静态导入/预检、固定内容和安装事务，使用现有管理导入服务作真实调用者；可显示STAGED/WAITING_RUNTIME_VALIDATION，不允许可执行包进入run catalog。N09b在N10/N11完成后进行解析/导出与纯fixture验证，通过才切executable active；失败保留旧active。禁止为先做N09而在宿主直接eval脚本。
- **并发：** 旧run固定旧hash；更新/卸载只撤销新准入和未消费审批，GC等待持久引用归零。许可差异升级要重授权，不继承包名相同即同信任。
- **验证：** zip-slip/bomb/重复条目/符号链接；低磁盘；每个安装状态死亡；旧版本仍运行时更新/卸载；重启后active一致且staging可核账。真实UI导入ZIP与版本回退预览。
- **回退：** 切旧active只影响新run；已执行副作用不回滚；不能先删旧包腾空间再安装。

### N10 — 可中断、可核验的隔离脚本运行时

- **依赖/owner：** N09；隔离服务拥有JS实例与计算线程，宿主proxy拥有实例ID/死亡检测；不拥有MCA engine。
- **现有读集/拟写集：** 既有包契约和Android服务实现；拟新增`AA/platform/script/`客户端/服务、最小QuickJS实现/JNI模块及锁定依赖，构建/Manifest由整合者维护。
- **先做技术验证：** 固定来源、许可证、ABI、debug符号；实现isolatedProcess启动、只读脚本资源FD、窄RPC、无宿主路径/凭据；核实不同UID、实际权限和socket/fs探针。技术验证结论进入ADR后才铺全量API。
- **预算：** 显式heap/stack/CPU/墙钟/输出/并发实例上限；无限循环可中断，必要时隔离进程死亡可确证本实例静止；不把超时协程当JS已经停止。
- **沙箱：** 不暴露reflection/raw Context/raw Java对象/socket。WASM与JS共享受限能力边界；第三方原生扩展不能动态进主进程。无法达到边界必须停在技术验证，不静默fallback为同UID执行。
- **进程角色/停止：** isolated script进程的Application不得初始化MCA DB、数据容器或模型执行根。按一活动执行实例一隔离进程作为首版硬杀隔离策略；若池化必须先证明实例级中断和共享故障语义，不能为停A杀无关B后写“A准确停止”。
- **验证：** 真实设备运行同步/异步/Promise/异常/循环/大内存；读宿主私有路径、socket、FD写、线程/子进程逃逸探针；取消后无CPU残留；服务死后旧回调被instanceId拒绝。
- **回退：** 停脚本执行入口、继续保留inert包与数据；停止未知时不允许复用相关实例槽。不得把移到独立但同UID进程写成强隔离达成。

### N11 — Host API 与 third-party 导入转换

- **依赖/owner：** N10/N03及相应N08a–g已经交付的host能力；不等待N08h全部完成。memory/workflow/subframe等晚到API单独登记回填子切片；host broker落调用账本，脚本不能持executor引用。
- **现有写集：** toolpkg host catalog、provider与权限hook；拟新增第三方 manifest/METADATA转换器、API映射、session-aware host RPC。
- **步骤：** 实现JSON/HJSON、requires/main/subpackages、命名、CommonJS依赖解析与预编译JS；导入生成normalized包及SUPPORTED/TRANSLATED/REQUIRES_HOST_CAPABILITY/UNSUPPORTED逐API报告。
- **Host调用：** 带instance/packageHash/parentInvocationId/ordinal/argsHash；重复同号同参复用确定结果，异参拒绝。父脚本授权仅允许运行计算，每个内部副作用独立冻结与审批。
- **身份：** 宿主签发不可伪造的session capability，在server侧绑定runId/epoch、parentInvocationId、packageHash、allowed methods/scopes和revocation epoch；Binder来源与session映射双检。guest自报字段只作核对，不能改变身份。跨session/包重放和撤销后旧句柄均拒绝；不得把token打印到日志。
- **来源：** host将读取数据的provenance保守并入整次脚本输出；异步/错误/缓存不能洗标签。没有精细污点机制时用全输入label join。
- **验证：** 真实包读取→运算→申请写artifact；包尝试越权网络/删除被审批阻断；重放host调用无重复效果；取消父级拒绝新增子调用且已派发子调用核账；样本来源与许可证固定。
- **并发验收：** A/B两个真实session，A伪造B的run/package/parent身份不得读取或派发；停止A不停止B；隔离子进程冷启动不构造第二MCA执行根。
- **回退：** 可按兼容适配版本关闭导入/新执行；旧hash已安装包保留。未知API明确预检失败，不能返回假成功。

### N12 — 包上下文、WASM、资源、模板与扩展闭环

- **依赖/owner：** N11；main/UI/provider/hook各自按窄职责持资源，不共享一个万能host。
- **拟写集：** toolpkg resource/wasm/context/IPC/lifecycle adapters、UI包组件与相应fixture；feature只依赖显示契约。
- **内部顺序：** N12a main注册与资源/CommonJS完整性；N12b sandbox/provider上下文和IPC；N12c WASM计算/内存中断与host imports；N12d UI表单/隔离视图与hook贡献点；N12e workflow/workspace模板导入和导出。
- **兼容：** main注册阶段无副作用；四上下文有独立API暴露表，IPC不增权；WASM只可使用允许的imports；模板只创建draft不自动开trigger或授权。TS源须预编译JS或显式受控编译链，不谎称QuickJS能直接执行任意TS。
- **UI/Hook：** 操作产生同一命令/审批；WebView不获任意Java桥、文件或网络自由访问；hook不得修改授权/重入dispatch。不能等价映射的原始API保留兼容差异和待补项。
- **验证：** 固定合法/恶意样本覆盖每上下文、async/errors/stream、资源引用、WASM、IPC隔离、包更新后UI关闭及hook清理；空实现或只显示页面不计通过。
- **回退：** 分扩展停用；内容/模板与旧history可读；M2完成要求对照清单实际功能全交付，缺WASM/provider/UI时不能宣布工具包领域已完整对标。

## 6. M3：记忆体系

### N13 — 记忆 CRUD、版本、元数据与管理闭环

- **依赖/owner：** N02/N04；Room记忆仓储唯一writer，工具/UI都通过相同业务事务。
- **现有写集：** `AA/memory/`模型/store/persistence/DAO、PR/AgentMemoryAdminService、UI记忆管理；整合者分配Room增量版本。
- **拟新增：** item revision/metadata/source、folders/tags/typed properties/alias、link实体与draft proposal最小表；按迁移需要逐步增加，不一次空建所有表。
- **步骤：** 命令create/edit/get/move/delete/import/export及分页查询；revision CAS；源/目标space双授权；保留item/chunk ID、旧引用。UI选择由N04写进task，不可只存page marker。
- **迁移：** 从当前29和历史兼容fixture升级，非空默认明确回填；旧未知字段保留/明确错误。导入先preview/dedupe/source标记再事务提交，不合并同名空间即默认同权限。
- **验证：** 实际UI创建→编辑→任务检索→移动→删除；冲突编辑、重复导入、跨space错ID、全事务rollback；真实迁移检查内容/引用/版本不丢失。
- **回退：** 只关新写入口，旧数据可读；DB不可盲降级，备份/前向修复有版本和校验。

### N14 — Query-driven 词法与混合检索

- **依赖/owner：** N13；检索仓储只查询授权空间，派生索引由索引worker维护。
- **现有写集：** `AgentMemoryStore.kt`、lexical ranker/chunker/DAO与search tool；拟新增FTS或bounded倒排、融合器及检索诊断。
- **步骤：** 先修扫描上界；不再固定前缀分页后rank。用query驱动候选，分词/中文扩展有确定fixture；扫描量/topK/最终输出预算独立。授权范围内融合词法/向量，稳定引用与chunkHash。
- **查询：** space/folder/tags/properties/time/snapshot/threshold/limit；score含算法版本，跨算法阈值不直接通用。一跳关系扩展同时核验两端权限。
- **验证：** 目标在第1000条或最后新空间仍召回；重复短文、中文同义/英文缩写/空查询、topK边界、空间撤销/删除并发；以锁定相关性集合测Recall@K/nDCG与延迟，不只断言“有结果”。
- **回退：** 向量未就绪可显式词法回落；不能退回已知前缀缺陷。完整语义验收等N15，不把词法结果冒充。

### N15 — 本地 embedding、索引作业与资源仲裁

- **依赖/owner：** N14；辅助模型经MCA已有资源owner申请，index job只有checkpoint与outbox职责。
- **现有读集/拟写集：** AA/inference实际能力和lease、本地模型资产合同、AA/memory索引实现；新增embedding capability adapter/索引generation/任务调度实现。当前未证实存在生产文本向量契约，不能假定只写adapter就能完成。N15a由整合者显式租用`core/inference-contract`、`core/engine`、必要runner/native/资产与构建写集，增量实现文本embedding请求/向量结果、共享owner调度、取消/stop settlement和格式能力；不一次升级全部后端。
- **先验证：** 选真实支持输出向量的本地后端/模型，锁定资产来源/格式/分词/pooling/normalize/dim/许可证；不得文本hash代替embedding，不为每job new native engine。
- **分片门禁：** N15a先在真实设备用固定文本probe取得可重现的向量、正确shape/normalize、语义近远关系及实际停止/资源释放，并覆盖受影响后端旧生成回归。N15b索引/generation集成必须等该probe通过。图像CLIP或token表中间张量不自动等于句向量；其他后端明确能力状态，不能把一个后端通过推广为全部支持。
- **算法：** item事务写outbox；按chunkHash+fingerprint幂等索引；先小规模bounded exact search，规模证明需要再ANN；新generation完全提交后切换，旧generation保留到引用释放。索引可重建，Room内容是权威。
- **资源：** 单模型条件下先释放生成lease再分批索引；排队可见、有界公平、取消可证实；不能强迫两个大模型同时驻留。
- **验证：** 飞行模式冷启动语义检索；fingerprint/维度切换、重建一半死亡、损坏索引回退、删除竞争；与主生成/生图/API并发时不双engine、不抢错lease、不残留native计算。
- **回退：** 暂停索引job、回到词法并显示状态；保留item与原generation；未取得本地向量能力时M3语义项维持BLOCKED，不能偷偷用云embedding验收。

### N16 — 提取、合并、关系、自动保存与删除/外发

- **依赖/owner：** N13/N14/N15；提取只产proposal，仓储CAS决议；后台自动保存另需N21/N22。
- **拟写集：** AA/memory提取/整理/links/alias/profile策略、删除与重建作业、egress label传播及审查UI；不直写聊天/助手profile表。
- **顺序：** N16a手动记住与来源；N16b提案提取/冲突/merge/alias/link；N16c窄策略自动保存与profile文档；N16d墓碑删除/派生清理/任务副本选项；N16e外发追踪验证。逐段独立验收。
- **策略：** 默认确认proposal；明确空间/来源/类别/期限后才能自动保存，SECRET不自动写入。模型推断不当事实，网页提示不能扩权；覆盖/删除/冲突需决议，按revision CAS提交。
- **自动化：** 持久游标、分组节流、失败退避、同批去重，暂停/撤销立即阻止新写；定时任务与图触发共用根和inbox，不能另造后台engine。
- **来源/外发：** 所有变换输出保守join输入provenance；cloud/网络/MCP/分享/导出统一检查。只允许明确用户declassification降级；任意字符串拼接/摘要不降级。
- **删除：** 先同事务核归属并tombstone、立即拒绝新查，再可恢复清chunks/词法/vector/link/proposal/缓存；关联任务副本另给明确范围，不承诺闪存物理擦除或收回远端数据。
- **验证：** 会话提取盲评precision/冲突误写、注入攻击、双端link越权、merge回滚、提取一半死亡与重复保存、delete时索引在跑、粗粒度标签经脚本/模型/Hook/错误不丢；自动任务飞行模式恢复。
- **回退：** 停自动化和新提案，手动CRUD/旧索引保留；已提交合并按历史revision生成反向提案，不静默覆盖。

## 7. M4：Workflow、恢复与触发

### N17 — 图定义、版本、表达式与静态验证

- **依赖/owner：** N01/N02；definition repository唯一写draft/publish，执行只读不可变version。
- **现有读集/拟写集：** AA/workflow旧recipe/parser/preflight；AC分文件图契约、AR/workflow纯validator/受限表达式、AA图存储。
- **定义：** 输入/输出、Trigger/Tool/AgentTask/Model/Script/Condition/Logic/Extract/Wait/Approval/Subworkflow/Output，显式bounded loop是MCA增强。第三方五类映射与字段转译表逐项给出。
- **算法：** ID/边/类型/required/可达/终结/拓扑无环/分支完整/依赖hash/权限上界；禁止任意eval。Condition十运算符、AND/OR、Extract六操作有确定性fixture；random依赖注入种子并持久结果。
- **版本：** draft CAS修改；publish固定definitionHash及依赖lock；旧run不跟随draft/latest。初始规模限制只是待测配置，超界typed错误，不静默裁节点。
- **基础生产调用者：** 先接既有workflow管理服务的导入预检/定义保存，N18再扩现有手动launcher选择图策略；完整节点编辑器由N20完成。不得只新增validator和fake测试就标基础完成。
- **验证：** 无效图指向具体node/field；null/缺字段/类型/环/递归/secret绑定；旧recipe保留且转换生成新version不改旧task。
- **回退：** 关闭新publish，历史版本与recipe可读；不重新解释旧definition改变已开始任务。

### N18 — 共享工具/模型节点与同root执行 frame

- **依赖/owner：** N17/N03/N06；AgentRunOwner唯一推进run，graph策略产生局部node事实，不能拥有平行model/tool executors。
- **拟写集：** AR/workflow graph stepper、execution frame协议、原目标loop单步适配、owner调度；AA节点存储适配。
- **步骤：** 先纯节点拓扑；再Tool走pipeline、Model走同gateway、Script走包host；AgentTask复用目标loop作为同root frame，Subworkflow固定子version。禁止start一个排在父后方的子run再等它。
- **数据流：** typed binding读上游已提交输出；未激活分支SKIPPED；fan-in只等激活上游；foreach稳定index和有界并发；父总预算覆盖子frame，输入输出保守继承来源。
- **验证：** 一slot+一模型场景下父→AgentTask→子流程→输出能结束；父取消正确传播；预算不能因子frame重置；假乱序结果不污染后继；工具审批和模型lease与普通任务相同。
- **回退：** 新图执行可停；已提交节点历史和普通目标任务不受损，不能复制一套简化loop作为fallback。

### N19 — 节点事务、park、checkpoint 与启动核账

- **依赖/owner：** N18；owner mailbox/独占恢复阶段唯一写run及调度元数据；仓储事务写node/output/ready后继。
- **拟写集：** workflow run/nodeAttempt/checkpoint表与DAO、AR owner admission/恢复策略、startup reconciler按executionKind/recoveryVersion分派。
- **步骤：** node输出+成功checkpoint+后继ready同事务；等待保存durable wait token，确认计算停止后CAS从ACTIVE到PARKED并释放active slot。wakeId去重，run/epoch/checkpoint CAS后重新排队，不绕过准入。
- **恢复：** startup fence先单飞分页核账全部非终态，旧goal保持不自动续跑；graph根据节点效果/receipt/wait token恢复。旧reconciler不能无差别把合法等待全部暂停；核账完成再放行UI/trigger dispatch。
- **未知：** 任意副作用无receipt转UNKNOWN，无法静止的计算留fence；纯节点稳定输入才可重放。补偿是另一受审批动作，不声称DB能保证远端exactly-once。
- **验证：** 每个node提交边界死亡；timer与取消同时、双wake、recovery与UI start同时；park任务不堵普通新任务且恢复不饥饿；超过第一页的nonterminal也恢复；陌生recoveryVersion安全停并保历史。
- **回退：** 暂停新图恢复/触发，历史保持只读并显示原因；不对UNKNOWN自动写成功/失败来解锁。

### N20 — 手机编辑器、运行详情、导入与旧recipe

- **依赖/owner：** N17/N19；页面仅draft投影与intent，仓储唯一保存。
- **现有写集：** UI/AgentWorkflowContracts/Coordinator/WorkflowsScreen、PR/AgentWorkflowAdminService；拟加节点表单、连接/画布投影与运行详情小组件。
- **步骤：** CRUD/clone/patch/发布差异/启停/导入导出；列表编辑与画布使用同一draft；选择模型/工具/包要求与输入绑定；错误定位到node/field。运行显示账本实际节点、等待、审批、日志/统计及产物。
- **兼容：** 旧recipe继续可用，转图保存新ID及sourceHash；第三方导入保留未识别字段和精确差异，未知执行类型不自动丢弃后运行。
- **验证：** 真实手机从零编辑“读取→条件→模型→产物”并运行；旋转/返回/键盘/大字体/浅深色/长图；两个编辑来源revision冲突；导入失败不覆盖旧draft。
- **回退：** 停新编辑入口，旧定义/运行只读；冻结导航与既有布局不改，不能借编辑器任务重做聊天/图片。

### N21 — 实际执行根迁移与冷启动 provider

- **依赖/owner：** N03/N04；整合者独占生命周期/组合根/共享模型写集；迁移的是实际owner实例，不是加一个空Application holder。
- **现有写集：** McaAppContainer/MainRuntimeAssembly、AppStartupViewModel/MainViewModel创建关闭链、AgentRuntimeAssembly/platform、共享engine/facade/FGS连接点；不做无关业务重构。
- **前置交付：** 画实例创建/引用/关闭/进程死亡图，列聊天/图片/API/Agent各入口取得的实例ID；写独立ADR与迁移时序。
- **步骤：** application-only执行根单飞初始化；页面attach/detach只观察/绑定，不关仍有合法任务资源；Worker/Receiver经provider提交inbox，不能new engine或等Activity创建。保留idle释放、native隔离和stop settlement。
- **进程识别：** provider按已知process role初始化；脚本隔离进程、既有native工作进程只装配所需服务，不因Application.onCreate自动创建主MCA执行根。
- **切换：** 同次候选中不允许新旧两套owner同时活跃；旧资源停止确认/账本核对后才将准入切给新root。保存组件依赖不含VM/Activity引用。
- **验证：** 冷启动不打开页面可处理合法事件；页面退出/重建时任务按合同存续；聊天/图片/API/Agent交替和取消，真实本地模型请求共用仲裁且无双native实例；旧stop通知仍只停目标run。
- **回退：** 未变数据时按切片切回原作用域；有持久待执行任务先暂停新触发并核账，再前向修复。不可让旧APK自动忽略新任务继续执行。

### N22 — 六触发、持久调度与无人值守授权

- **依赖/owner：** N19/N20/N21；trigger inbox/slot仓储写入事件，owner创建/唤醒；WorkManager只负责wake。
- **拟写集：** AA/workflow trigger adapters/scheduler/slot/outbox、AA/platform Receiver/Worker/语音/Tasker适配、UI触发配置、Manifest受控注册。
- **顺序：** N22a manual/schedule；N22b tasker/intent；N22c speech/app_open；N22d补发/撤回/积压和授权策略。通知/MCP为额外增强，不替代任何六基线。
- **去重：** definitionVersion+triggerId+canonical event/slot唯一约束；create receipt与slot关联通过同事务或durable outbox；skip/queue/replace与最大积压明确，replace先等exact cancel结算。
- **时间：** wall clock+zoneId、DST、回拨、missed-slot策略和下一wake可解释；WorkManager周期限制与OS延迟如实显示，不承诺准点。权限撤回/force-stop/重启有可见状态。
- **授权：** 默认窄LOCAL_ONLY只读；扩大自动化需definition/tool/packageHash+参数/scope+次数/期限+来源的用户策略。每次动作生成一次授权落账；超界WAITING_APPROVAL。导入不能自动启用，Intent/Tasker payload不能自带批准。
- **离线语音：** 本地识别能力先验证；转录确认/命中分开；没有本地模型只标不可离线，不能云转录顶替。app_open只在冷启动事件生效，不在重组/旋转重复。
- **验证：** 六入口真实触发分别留证据；同事件100次只一slot；Worker重试/进程死/时区切换；未授权外部intent/超大payload拒绝；无人值守写/外发超scope不执行；UI进入后展示同run。
- **回退：** 先禁trigger/策略新准入，保留inbox与历史；运行中的动作按自身账本停止，不靠取消WorkRequest声明已停。

## 8. M5：逐项对标与回归交付

### N23 — 四域集成场景、真实模型与性能基线

- **依赖/owner：** N07/N08/N12/N16/N22；验证负责人读取生产链，不为通过而改规则或工具效果。
- **新增交付：** 固定fixtures/hash、受控服务、脱敏语料、任务oracle、离线包和模型配置；按PARITY_ACCEPTANCE逐项登记。
- **三层结果：** 确定性协议/状态测试；真实OS效果与恢复；真实模型选择参数与任务成功率。失败分别归因模型/模板/协议/工具/权限/调度，不能把离题全归小模型。
- **场景：** 用户资料→查记忆→脚本处理→工具审批→产物→下次引用；本地语音→触发图→子frame→条件分支→记忆提案；包升级同时旧run等待审批；杀进程后只恢复可安全节点。
- **指标：** 测试集、样本数、成功定义、冷/热条件、端到端时延、P50/P95、总进程内存/磁盘和取消四阶段在运行前固定；缺样本不外推统计优越性。对照第三方必须在相同任务/依赖条件下实际运行后才能说性能更好。
- **停止/回退：** 关键隐私/重复副作用/数据损坏出现一次即对应门禁FAIL；保留全部失败分母与日志，不能换题或降低上下文制造通过。

### N24 — 既有功能、迁移、视觉与交互回归

- **依赖/owner：** N23；整合者选择受影响后端，UI验证负责人独立审实际渲染。
- **读集/写集：** 当前聊天、图片、模型、Local API、设置、DSH与Agent既有行为清单；仅修复被证明的回归，独立记录，不借机改布局。
- **步骤：** 用真实兼容签名与既有数据升级；29及实施中所有中间schemafixture、旧任务/recipe/包/审批/凭据关联完整；检查MainUiState/Host未扩大、feature依赖无倒置、真实生产装配无重复实例。
- **视觉：** Agent新增页复用现有设计系统，冻结图片→Agent→DSH入口；受影响状态before/after两轮，键盘/返回/滚动/长内容/大字体/浅深/背景与可访问性。没有可信旧截图注明局限。
- **验证：** 聊天本地/云端/多轮/附件/取消，图片已有模式和产物，模型下载导入加载切换，认证API流式终态/断连，以及DSH入口和独立存储；按真实影响确定覆盖，不一机一模型代表全后端。
- **回退：** 新功能停用与前向修复；不清数据、不改applicationId、不刷全golden、不改旧测试定义。

### N25 — 最终证据、长运行与交接

- **依赖/owner：** N24及所有必需验收通过；发布/整合负责人负责事实核对，本卡本身不授权发布。
- **新增交付：** capability manifest最终版、逐项验收表、原始证据索引、迁移与恢复手册、用户兼容说明、依赖/许可证清单、精确剩余事项。
- **绑定：** commit+tracked/untracked内容清单+子模块revision/diff指纹、APK SHA256/签名/构建参数、设备/OS、模型资产/模板/参数、工具包hash及schema/recovery版本共同标识候选。
- **长运行：** 固定run数和时间窗覆盖多批任务、定时唤醒、自动记忆、脚本启停、模型切换、网络波动与热状态；比较起终总进程内存、持久队列、FD/进程/lease与未结副作用。次数在首测前登记，不将“过夜未崩溃”作为全部成功。
- **判定：** PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE逐项；缺必需能力或验证不能称完整对标。运行时未知显示为未知，文档设计完成不能冒充实现完成。
- **交接：** 列所有临时adapter、实际生产调用者、owner/关闭入口、下一步与回退；仅在用户另有授权时再做提交/安装/发布。

## 9. 给实施 Agent 的可复制提示词

```text
你接手 MCA 原生 Agent 补齐工作。本次只执行【填卡号与子切片】，这是实现任务授权，不授权发布。

先完整读取 docs/architecture/MCA_PROJECT_HARD_LOCK.md，再读取
docs/proposals/agent-native-thirdparty-completion-v1/ 下四份文档、适用源码与测试。
锁定当前分支、HEAD、tracked/untracked内容指纹、子模块revision及内部差异，保留所有既有改动。
不要把文档读取时HEAD当作当前完整源码，也不要将旧设备PASS复制为本次结果。

先核对本卡前置依赖与现状；如已有并行修复，读实际diff并复用，不重复覆盖。
输出本卡生产调用链、唯一业务writer、资源owner、读集/写集、迁移与第一可验证切片后继续实现。
你不是独自在代码库：不得revert他人改动，共享契约/数据库版本/owner/组合根/构建文件由整合者协调。
如果发现必须改写集外文件，给出具体依赖与最小扩展，交由整合者串行处理，不抢写共享文件。

禁止第二engine/平行Agent loop、UI/Worker持资源owner、万能Manager/Any注入、扩大MainUiState写权限或MainCoordinatorHost。
工具、workflow、脚本host-call必须走同一持久InvocationPipeline；旧审批/旧数据要有版本边界。
AgentTask使用同root frame；wait必须park并用epoch/checkpoint/wakeId CAS恢复；UNKNOWN停止保留fence。
脚本/模型/变换/摘要/Hook/错误/产物必须保守继承输入来源；包声明不能自行放宽权限。
同UID进程与argv审批不构成强沙箱；受限离线模式必须有真实fs/FD/network探针证据。
不要用manifest-only工具包、recipe、仅词法搜索或空生产catalog宣称完整对标。

按任务卡步骤小切片实现；保持聊天/图片/DSH既有能力、数据与冻结视觉。
复用已有行为测试，补真正覆盖竞态/迁移/副作用的测试；不堆镜像断言，不降低门禁。
使用现有构建锁和受控脚本，核实测试任务与过滤器真实执行；设备与真实模型验收不能由fake替代。
普通授权内的实现选择自主完成，不反复问确认；超过本卡授权时先完成可独立部分并说明具体缺口。

交付必须列：变更文件/职责、生产入口与writer/owner、数据协议兼容、已运行命令及测试数和证据、
NOT_RUN/BLOCKED/FAIL、已知风险与临时adapter退出条件、回退与下一卡。不得把未验证写成PASS。
```

## 10. 给整合/复核 Agent 的提示词

```text
审查【卡号】的实际diff与候选产物，不以实现者摘要替代源码。
逐项对照本目录四份文档及项目硬锁：
1. 真实生产入口是否使用新实现，管理页/假测试之外是否存在调用者？
2. 状态和实际native/平台资源是否仍单owner，组合根与页面生命周期是否连续？
3. 审批消费、dispatch intent、结果账本有无事务缺口，未知副作用会不会重发？
4. 旧记录/upcast/migration中断、park恢复与取消竞态是否有行为证据？
5. 来源标签经脚本/模型/缓存/错误/Hook后是否保留，实际隔离是否符合宣称？
6. 工具包/记忆/图工作流是否实现了宣称行为，是否偷换为alias/recipe/前缀词法？
7. 测试数、日志、SHA/脏树/子模块/APK/模型配置是否对应同一候选？
按具体文件、触发条件、用户影响给出缺陷；缺项保持FAIL/NOT_RUN，不调整分母。
通过后迁移全部调用者、删除已无用旧可执行旁路；文档和capability manifest同步更新。
```
