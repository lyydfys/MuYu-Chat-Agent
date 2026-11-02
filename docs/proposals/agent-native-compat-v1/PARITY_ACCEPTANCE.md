# 第三方工具包兼容能力对照、逐项验收与交付证据

本文件属于 [原生 Agent 补齐方案 V1](README.md)，与 [详细设计](DESIGN.md)、[执行任务卡](EXECUTION_PACK.md) 一起使用。日期：2026-10-20。本文规定未来实施验收，**本轮所有新增项目均为 `NOT_RUN`**；没有因写完设计而获得的功能 PASS。

## 1. 对标对象、证据级别与边界

参照基线是一个外部只读快照（提交 `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`，2026-09-18）。
该参照只用于语义核对，本轮没有运行该参照，也不代表其每项功能已在设备上验证。
本文件不再复制参照项目的源码清单；只保留 MCA 自己的可观察验收项。


对标采用可观察语义：输入、输出、执行效果、错误、权限、生命周期和用户能否完成任务。无需复制参照项目的内部类、页面布局、存储格式或宽泛 Java bridge。采用第三方代码前另做许可证与依赖审查；本轮不提供法律结论。

以下属于 **MCA 增强目标**，不得倒推成上游已证实能力：本地 embedding 全离线闭环、跨进程受控脚本沙箱、来源级外发约束、持久节点恢复、明确的 bounded loop/foreach、固定版本子流程、精确资源停止证明。

## 2. 四域能力矩阵

“当前”是 2026-10-20 源码审查结论，不能替代执行结果。完整对标要求 `SUPPORTED/TRANSLATED` 均有实际样本，`REQUIRES_HOST_CAPABILITY` 提供真实 host 后才能 PASS，`UNSUPPORTED` 必须留在差距表。

### 2.1 工具调用与平台工具

| 能力 | 第三方 参照 | MCA 当前 | 本方案完成标准 | 任务卡 / 主要验收 |
| --- | --- | --- | --- | --- |
| 模型工具回合 | 注册、工具协议、结果回传 | prompted 单调用已有；NATIVE 标签与实际请求不一致 | native wire 真正携带 schemas/calls/results；本地 V1 兼容 | N03/N05；TOOL-01–05 |
| 多步、结果/产物、上下文 | 工具 handler 及聊天循环 | 既有 loop/产物/上下文 | 保留完整配对事实，模型侧有界摘要；多步有真实完成判据 | N05/N06；TOOL-05–07 |
| 权限与平台能力 | S04 两套权限 | 冻结审批/ledger 已有 | OS capability、用户策略、来源外发分别检查；所有入口共用 pipeline | N02/N03；TOOL-08–10 |
| 取消、超时、并发 | 生命周期与中断入口 | typed stop settlement 已有 | 有限只读并行；每项独立终态；未证明停止保留 fence | N06；TOOL-11–13 |
| 文件/文档/网络/设备/UI/终端 | S03 | 工作区基础工具；平台面不足 | 每个上游公开工具/API 映射到 MCA 行为与实际权限层，不以工具数量替代 | N08；TOOL-14–17、23–25 |
| MCP | S05 远端与本地 bridge | 管理/registry 存在，生产未接线 | 真实管理→run snapshot→调用→结果→关闭；明确 transport 支持范围 | N07；TOOL-18–20 |
| Skills | 作为 MCA 现有扩展保留 | 构造时 snapshot | 每 run 固定版本、资源按需、allowed-tools 只收窄 | N04/N07；TOOL-21–22 |

N08 必交 `thirdparty-tool-surface.json`（拟新增验收产物，非运行时万能注册表），逐项字段：`upstreamCommit/sourcePath/symbol/behavior/input/output/error/permissionTier/hostDependency/mcaToolKey/status/fixtureIds/reason`。枚举源必须从固定提交实际导出面生成并人工去重；后续源码 API 增减要显示 diff。不能用上表的工具族名称代替此逐项清单。

该清单至少覆盖：Files 与 SAF/搜索/压缩；Net 与 browser 自动化；设备/应用/通知/剪贴板/位置/蓝牙/设置；Intent 与 Tasker；accessibility 树/截图/点击/输入/滚动；shell/会话/真 PTY；FFmpeg/文档/OCR/媒体；Memory；Workflow；Chat/subagent 与已有图片/模型能力。各权限实现使用同一语义，不暴露 raw Context/DAO/engine。root/debugger 不是默认依赖；缺少 OS 授权是具体运行能力缺口，不能改写成成功。

### 2.2 工具包与生态

| 能力 | 第三方 参照 | MCA 当前 | 本方案完成标准 | 任务卡 / 主要验收 |
| --- | --- | --- | --- | --- |
| 容器与 manifest | S01 | 安全 ZIP、预览和存储 | 支持旧 inert v1、新可执行 v2；第三方 JSON/HJSON 导入有转换报告 | N09/N11；PKG-01–03 |
| 可执行子包 | S01/S02 | 未形成生产 executor | CommonJS、导出函数/metadata、Promise/错误、取消均真实运行 | N10/N11；PKG-04–07 |
| 依赖/资源/WASM | S01 | 未完整支持 | 锁定依赖、离线 bundle、限额资源、受控 WASM；不支持项预检阻断 | N09/N12；PKG-08–10 |
| main/ui/sandbox/provider | S01 | 未形成上下文运行能力 | 四种上下文分别有隔离、API 允许表、输入输出和生命周期 fixture | N11/N12；PKG-11–14 |
| hook/UI/模板 | S01 | 管理展示为主 | 受限贡献点、结构化或隔离 UI、流程/workspace 模板可用；不能借 hook 扩权 | N12；PKG-15–16 |
| 安装/更新/禁用/卸载 | 包系统生命周期 | 预览存储已有 | staging→验证→固定版本→active pointer，崩溃旧版本仍可用，运行引用不被删 | N09/N12；PKG-17–19 |
| 隔离与兼容证明 | MCA 增强边界 | 未运行隔离方案 | isolated UID/FD/网络/CPU/内存实测；声明支持的样本真实 host 效果 | N10–N12；PKG-20–23 |

未实现 WASM、provider、UI 或 hook 时可以交付阶段版本，但必须写具体缺口，不能称“工具包完整对标”。MCA 的兼容转译允许更严格的权限语义；若因此改变原包可见行为，要给出变更报告及替代授权流程，不能静默吞掉调用。

### 2.3 记忆

| 能力 | 第三方 参照 | MCA 当前 | 本方案完成标准 | 任务卡 / 主要验收 |
| --- | --- | --- | --- | --- |
| 空间、CRUD、分块 | S06/S08 | Room29 基础已有；UI写入不完整 | UI与工具共用版本事务；跨空间隔离；文档/chunk稳定引用 | N02/N04/N13；MEM-01–06 |
| 标签/属性/文件夹/移动/别名 | S06/S07/S08 | 未完整支持 | 类型化字段、检索过滤、CAS更新与迁移；别名保留来源和主条目关联 | N13/N16；MEM-07–08 |
| 词法、中文、向量、融合 | S06 | 固定前缀词法扫描有覆盖缺陷 | query-driven、中文/英文语义集、同授权空间向量候选、RRF及去重 | N14/N15；MEM-09–12 |
| 链接与图扩展 | S06/S08 | 未完整支持 | 有向/权重/类型链接、backlink、限深传播，端点重新授权 | N16；MEM-13 |
| 会话提取与自动保存 | S07 | 未完整支持 | 提案默认审核、窄自动策略、来源和冲突、取消/重启可核账 | N16；MEM-14–17 |
| 整理/profile | S07 | 未完整支持 | merge/dedupe、profile文档与显式更新策略、摘要可追溯 | N16；MEM-18–19 |
| 时序/快照查询 | S08 | 未完整支持 | 时间范围、有效/记录时间语义、快照版本查询；旧值不误作当前值 | N13/N14；MEM-20 |
| 导入/导出/重建/删除 | S06 | 基础存储为主 | 版本导出、索引派生重建、墓碑/GC/任务副本说明、跨空间错误零变化 | N13–N16；MEM-21–23 |
| 完整离线与外发约束 | MCA 增强 | 现有 LOCAL_ONLY gate | 本地embedding/提取/可选重排，来源级cloud/network约束 | N15/N16；MEM-24–25、OFF-01–06 |

### 2.4 Workflow

| 能力 | 第三方 参照 | MCA 当前 | 本方案完成标准 | 任务卡 / 主要验收 |
| --- | --- | --- | --- | --- |
| 定义/CRUD/patch/启停/日志 | S09/S10 | recipe/预检/手动创建任务 | draft与不可变published分离、可编辑图、版本运行历史 | N17/N20；WF-01–03 |
| Trigger/Execute/Condition/Logic/Extract | S09 | 无图执行 | 类型化节点、参数引用、条件/逻辑/提取运算逐项等价 | N17/N18；WF-04–08 |
| DAG/分支/错误策略 | S09 | 无节点调度 | 环检测、稳定拓扑、分支SKIPPED、fan-in不死锁、失败路径可见 | N17–N19；WF-09–11 |
| 子任务/子流程/循环 | MCA 增强 | 仅普通目标 loop | 同root frame、固定子版本、共享预算与取消、bounded loop | N18/N19；WF-12–14 |
| 节点恢复 | MCA 增强 | run journal已有 | checkpoint/outbox、未知副作用不重放、park与CAS唤醒 | N19；WF-15–17 |
| 六种上游触发 | S10 | 手动启动为主 | manual、schedule、tasker、intent、speech、app_open各有真实入口 | N21/N22；WF-18–23 |
| 调度与无人值守 | S10 + MCA 增强 | 缺真实进程级执行根 | 持久inbox/slot去重、权限绑定definition、时区与missed-slot策略 | N21/N22；WF-24–27 |
| UI与导入 | S09/S10 | recipe管理已有 | 节点编辑/绑定/发布/运行详情、旧recipe保留；触发导入不自动扩权 | N20/N24；WF-28–29 |

通知、MCP事件等可增补触发，不能替代上游六项。speech触发是否使用本地识别要作为具体实现证明，不能以联网服务证明完整离线。

## 3. 测试分层、状态与执行约定

测试层记号：`U`=纯逻辑/协议行为；`I`=真实实现集成（Room、文件、受控HTTP等）；`D`=真实 Android 主生产入口与OS效果；`M`=真实模型任务；`F`=故障注入/恢复；`V`=真实渲染与交互；`O`=断网冷启动。每行列出的层均为必需，不能用 `U` 代替 `D`，不能用模型声称“写好了”代替检查真实文件或设备效果。

每个用例单独记录：`PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`。BLOCKED 附缺少的确切依赖和最后尝试证据；NOT_APPLICABLE 仅用于确实不属于已批准目标/运行组合的项，需审查理由。不能把目标中的provider/WASM/触发/高权限工具统一标N/A后宣布完整对标。范围缩减必须形成产品决议及新的声明，不可改变本方案的原始结果。

以下用例是最低行为合同；任务卡可补，不可降低。fixture文件名、测试类名与自动运行命令由对应任务卡落地并登记到case registry，避免本文虚构当前已经存在的测试类。所有场景均从生产组合根接线后复测；fake只用于确定性边界。

## 4. 工具调用验收

| ID | 前置与输入 | 必须断言 / 判失败条件 | 层 | 卡 |
| --- | --- | --- | --- | --- |
| TOOL-01 | 旧PROMPTED_V1任务/记录；严格单调用fixture | 新版本可读并同语义执行；不把旧输出按V2批调用解析；旧非法输入仍拒绝 | U/I | N03/N05 |
| TOOL-02 | 声称NATIVE的云模型；受控wire服务捕获请求 | 最终HTTP带真实schemas、call ID和result；请求仍空tools或仅内部mapper标NATIVE即失败 | U/I | N05 |
| TOOL-03 | 本地显式V2与native批次各3 calls；参数分片流 | 完整终态后才派发；ordinal稳定；未知工具/extra字段/超界拒绝；半JSON零副作用 | U/I/M | N05/N06 |
| TOOL-04 | 正常工具回合、最终文本、EOF/错误/中途断线各fixture | 区分tool turn与final turn；无terminal proof不成功；截断参数不执行 | U/I/F | N05 |
| TOOL-05 | 读资料→选择→计算/写产物→引用的多步任务 | 每个call/result成对且ID贯通；产物hash/内容符合独立断言；仅“已完成”文字失败 | I/D/M | N05/N23 |
| TOOL-06 | 大结果、二进制产物、工具超时 | 原始结果有界持久引用，模型摘要标截断/hash；错误作为配对observation，不吞成空成功 | U/I | N06 |
| TOOL-07 | 接近context上限；schemas/skills/memory/工具结果同时启用 | 最终序列化处计数和输出预留；估计值标识；不静默丢配对历史，不削弱原参数 | U/I/M | N06 |
| TOOL-08 | 同一写工具从模型、workflow、script-host三入口调用 | 同pipeline/ledger/审批规则；没有executor旁路；拒绝时外部状态零变化 | U/I/D | N03/N18/N11 |
| TOOL-09 | 审批后改参数/文件版本/catalog/package/policy/epoch | 旧审批不能消费；同授权并发消费最多一次；拒绝陈旧回调，不写新run | U/I/F | N02/N03 |
| TOOL-10 | OS权限可用但用户FORBID；用户允许但OS拒绝；混合读+外发 | 分别给出真实原因；scope/effects覆盖读源和外发；OS授权不能覆盖用户拒绝 | U/D | N02/N08 |
| TOOL-11 | 三项安全读+一写+资源冲突批次，乱序完成 | 安全读受并发上限；写/冲突串行；每项独立状态；结果按输入ordinal | U/I | N06 |
| TOOL-12 | 批中拒绝一项、失败一项、用户取消其他项 | 每个call都有终态/未知状态；不丢result pairing，不全局取消无关请求 | U/I/F | N06 |
| TOOL-13 | native/tool/脚本停止超时，进程回调迟到 | 区分取消反馈/执行静止/释放；未知停止留fence；registry空不算Released | U/D/F | N06/N10 |
| TOOL-14 | SAF目录授权；读写/覆盖/移动/删除/压缩；路径逃逸与TOCTOU | 真实内容/原子提交/审批摘要正确；越界和撤销零变化；受限目录失败保留旧数据 | I/D/F | N08 |
| TOOL-15 | HTTP受控站点/redirect/私网/DNS变化；含私人输入 | 域与来源许可重新核验；限大小/超时/取消；无授权不发body；下载不是成功执行 | I/D/F | N08 |
| TOOL-16 | Android通知、Intent、应用信息、UI点击/输入；权限拒绝与撤销 | 系统真实效果可观测；selector/target派发前核验；敏感动作审批；锁屏/旋转不误点 | D/F | N08 |
| TOOL-17 | 终端行缓冲程序与真实PTY探针；子孙进程；网络/私有文件探针 | 真PTY行为与进程组停止可证；sh不冒充bash；未隔离shell标broad高信任，禁LOCAL_ONLY/无人值守 | I/D/F | N08/N10 |
| TOOL-18 | UI配置并启用远程MCP工具；真实生产run调用 | catalog非空且descriptor/result进入模型；真实transport/auth/分页；关闭run释放session | I/D/M | N07 |
| TOOL-19 | 本地stdio MCP server及可执行runtime | 从手机真实起进程/握手/调用/退出；不借DSH内部owner；只远端成功不能覆盖本地 | I/D/F | N07/N08 |
| TOOL-20 | MCP重连、schema漂移、超时/断线、凭据撤销 | 旧审批失效；schema/capability错误可见；不静默复用陈旧session或重放写动作 | U/I/F | N07 |
| TOOL-21 | 首run启用Skill A，运行中修改/禁用，次run启动 | 首run固定内容hash；次run见新状态；allowed-tools仅收窄，资源受预算与路径限制 | U/I/D | N04/N07 |
| TOOL-22 | Skill或工具输出包含“忽略授权/调用隐藏工具” | 当数据处理，不能扩权/访问未启用工具/绕过冻结scope | U/I/M | N07/N23 |
| TOOL-23 | 对照逐项API清单，执行位置/蓝牙/设置/Tasker等各权限fixture | 每项有对应tool、权限层、真实结果/具体缺口；仅列工具族或工具数不能通过 | I/D | N08/N23 |
| TOOL-24 | 文档、OCR/媒体/FFmpeg/浏览器样本与超大输入 | 真实解析/产物可核验；本地依赖来源固定；内存/输出限额和取消有效 | I/D | N08/N23 |
| TOOL-25 | 通过工具调用聊天子执行/已有图片/模型能力，与普通页面并发 | 取得共享业务owner；无feature内部调用、第二engine或父等待slot死锁；产物归属稳定 | U/D/F | N08/N18/N21 |
| TOOL-26 | 在approval consume、dispatch intent、派发、receipt及result提交各点kill；并发取消/恢复 | consumed+decision+invocation+dispatch outbox+run/node关联事务一致；无消费却无可恢复动作、无未消费授权先派发；receipt未知不重发 | U/I/D/F | N03/N19 |
| TOOL-27 | LOCAL_ONLY输入经模型生成、脚本计算、摘要、Hook、错误与artifact再尝试外发 | provenance合并保持最严格来源；无标签丢失/洗白；网络与云序列化处阻断；未知来源保守处理 | U/I/D/M | N03/N11/N16 |

## 5. 工具包验收

| ID | 前置与输入 | 必须断言 / 判失败条件 | 层 | 卡 |
| --- | --- | --- | --- | --- |
| PKG-01 | 旧inert v1、MCA可执行v2、第三方 JSON/HJSON三个合法样本 | 类型明确；旧包可读；导入normalized结果与兼容报告；不靠字段猜脚本类型 | U/I | N09/N11 |
| PKG-02 | zip-slip/重复条目/symlink/bomb/加密/DEX/未知JNI样本 | staging前或验证阶段拒绝；无越界写入；没有加载任何未许可可执行物 | U/I/F | N09 |
| PKG-03 | manifest缺字段、API不匹配、恶意schema、未知host方法 | 稳定错误定位；不得启用为可执行；兼容报告含具体symbol和替代条件 | U/I | N09/N11 |
| PKG-04 | CommonJS子包require、metadata/exports、两个互调模块 | 实际计算值一致，导出schema准确；无权限阶段不触发host副作用 | U/D | N10/N11 |
| PKG-05 | Promise/async、抛错、拒绝、嵌套异步host-call | 返回/错误/stack脱敏语义稳定；单call结算一次；async不提前标成功 | U/I/D | N10/N11 |
| PKG-06 | 无限循环、无限递归、heap增长、输出洪泛 | 时间/堆/栈/输出限额有效；可中断并回收；主UI与其他任务仍响应 | D/F | N10 |
| PKG-07 | 父脚本获运行许可，内部请求读私密+网络发送/删除 | 每项host-call再次走pipeline；父许可不扩大scope；拒绝后外部零变化 | U/I/D | N11 |
| PKG-08 | 锁定依赖图、冲突/环/缺依赖、离线完整bundle | 依赖hash确定、拓扑固定；错误拒绝启用；无运行时偷偷npm/pip下载 | U/I/O | N09 |
| PKG-09 | 包资源读取、路径逃逸/资源hash变化/超额 | 只读句柄只访问声明资源；变化撤销旧审批；无自由宿主文件路径 | I/D | N12 |
| PKG-10 | 最小WASM计算+受控host导入；无限运行/未知导入 | 真实结果、内存/CPU约束；导入按允许表；若不支持明确缺口，不能完整parity | I/D/F | N12 |
| PKG-11 | main注册UI/hook正常；注册阶段尝试网络/资源副作用 | 贡献点注册成功；初始化阶段副作用被阻止；不阻塞主线程 | I/D | N12 |
| PKG-12 | ui上下文表单/结果视图，恶意导航/桥接/本地文件样本 | 表单动作变显式命令；无任意Java bridge、导航/文件越界；UI状态完整 | D/V | N12 |
| PKG-13 | sandbox上下文用不同包/会话并发、尝试共享全局状态 | 只拿该session允许API/资源；隔离状态；跨包凭据/变量不可读 | U/D | N10/N12 |
| PKG-14 | provider上下文的声明入口与生命周期样本 | 配置/调用/关闭语义逐项实现；凭据引用不可日志化；未知API不伪造返回 | I/D | N12 |
| PKG-15 | before/after贡献点尝试改权限/重入派发/篡改账本 | 仅允许合同内贡献；权限/ledger不受hook修改；循环重入拒绝 | U/I | N12 |
| PKG-16 | workflow/workspace模板导入及运行；模板请求启用触发 | 生成可审draft与来源hash；不自动授权/注册外部触发；运行走同owner | I/D | N12/N20 |
| PKG-17 | v1包运行时安装v2；候选解析/验证失败/低磁盘 | 旧run固定v1，新run仅commit后见v2；失败旧active可用，无旧资源丢失 | I/F | N09 |
| PKG-18 | staging、pointer提交前后分别杀进程 | journal恢复到完整版本；不半安装；依赖/索引/权限状态一致 | I/D/F | N09 |
| PKG-19 | 禁用/卸载正在运行包；审批待处理；晚到回调 | 新准入立即撤销；旧动作核账；未消费审批失效；引用版本停止前不GC | U/I/D/F | N09/N12 |
| PKG-20 | 沙箱探测读取app DB/模型/Keystore、socket、任意JNI；A/B双实例与隔离进程冷启动 | 实际UID/权限/FD边界符合设计；越界拒绝有证据；token或same UID不能算隔离；停A不杀B，隔离进程不初始化MCA主数据/模型根 | D/F | N10/N21 |
| PKG-21 | script进程死亡，host外部动作已派发但回执未提交 | 标UNKNOWN/NEEDS_REVIEW，不自动重放；已知纯计算允许从固定输入恢复 | U/I/D/F | N11 |
| PKG-22 | 同stableCallOrdinal重复RPC且args相同/不同；父取消后新RPC；跨session/包伪造run/parent/epoch | 同参数仅一个逻辑动作；异参数拒绝；宿主session映射与Binder来源核验，guest自报身份不能扩权；撤销旧句柄/父取消后零新派发；旧调用独立结算 | U/I/D/F | N11 |
| PKG-23 | 每种声明兼容上下文/API/依赖至少一个固定第三方样本 | 真正安装→调用→host效果→取消→卸载；逐symbol报告，不用alias/fake代替 | I/D/M | N11/N12/N23 |

## 6. 记忆验收

| ID | 前置与输入 | 必须断言 / 判失败条件 | 层 | 卡 |
| --- | --- | --- | --- | --- |
| MEM-01 | UI选空间A建task；重启/次run | spaceIds随创建命令落库并冻结；scope/task读取一致；仅页面chip变化失败 | U/I/D | N04 |
| MEM-02 | 空间A+空间B条目ID执行删除 | 两空间item/chunk/index/link计数与hash完全不变；错误稳定 | I/D | N02 |
| MEM-03 | memory.create/update/delete从UI、工具、脚本分别调用 | 共用业务事务和typed Memory scope；审批显示真实空间，不借伪workspace路径 | U/I/D | N02/N13 |
| MEM-04 | 两端对同revision并发更新；删除与更新交错 | 仅合法CAS提交；冲突可审查；旧回调不覆盖新值 | U/I/F | N13 |
| MEM-05 | 文档导入分块、重复导入、截断/损坏文档 | source/chunk/hash稳定；完整度标识；失败无半条目/孤儿chunk；不重复污染 | I/D | N13 |
| MEM-06 | citation取回原文；取回前撤销space/删除item | 再授权并拒绝过期引用；不可凭citation读取任意文件 | U/I/D | N13/N16 |
| MEM-07 | tags/properties/folder增改/过滤/移动；别名冲突 | 属性类型/层级/范围一致；移动后权限重新判断；别名关联主条目不丢来源 | U/I/D | N13/N16 |
| MEM-08 | 批量导入旧值/未知enum/未知字段；旧item缺新字段 | 兼容默认或明确格式错误；不默默吞未知安全字段；ID/关联连续 | U/I | N13 |
| MEM-09 | 相关条目位于分页尾部及新建空间；总数据超过256chunks | query-driven召回可达；扫描预算与topK分离；不只扫描固定前缀 | U/I | N14 |
| MEM-10 | 中文分词/同义问法/英文缩写/无共同关键词语义对 | 词法与本地向量均贡献可解释候选；固定query与qrels，计算Recall/MRR，不靠演示挑样本 | I/D/M | N14/N15/N23 |
| MEM-11 | A授权B未授权；B有大量近邻向量/同名条目 | 索引查询空间受限，无内容/数量泄露；A召回不被未授权候选挤掉 | U/I/D | N14/N15 |
| MEM-12 | lexical+vector重复候选，RRF、rerank、token边界 | 去重/融合稳定；source/itemRevision/hash可溯；excerpt与最终context预算一致 | U/I | N14 |
| MEM-13 | directed links/backlinks、权重排序、跨授权端点、图环 | CRUD正确；一跳/限额生效；不可通过链接扩权或无限递归 | U/I/D | N16 |
| MEM-14 | 用户事实/工具事实/模型猜测/网页注入各提取样本 | 提案区分来源与置信；默认未确认不入正式检索；注入不改变授权 | U/I/M | N16 |
| MEM-15 | 明示窄自动保存策略，符合/超界/凭据样本 | 仅范围内自动保存并落账；凭据/SECRET阻断；冲突/删除需决议 | U/I/D/M | N16 |
| MEM-16 | 定时autosave：重复wake、长会话、多空间、取消/崩溃 | 稳定slot/source去重；授权持续有效；失败可恢复；不重复提取/跨空间写 | I/D/F | N16/N22 |
| MEM-17 | 自动提取过程中撤销来源访问或策略 | 阻止新读取/提交；已有候选标撤销；晚回调不能保存 | U/I/D/F | N16 |
| MEM-18 | dedupe/merge/过期/摘要提案，含矛盾事实 | 可预览diff与来源；用户决议/CAS；保留版本或冲突，摘要不伪装原文 | U/I/M | N16 |
| MEM-19 | profile文档初建、自动更新授权、矛盾/敏感候选 | 独立策略gate、版本和来源；不与角色/助手配置表混写；回退可追踪 | U/I/M | N16 |
| MEM-20 | validTime与recordedAt不同的记忆；范围/快照查询 | 边界含义固定；旧revision按指定快照返回；无越权历史版本泄露 | U/I | N13/N14 |
| MEM-21 | 导入/导出含文件夹/链接/别名/属性/大文档；损坏包 | 版本round-trip正确，ID冲突有策略；密钥不内联；失败无半导入 | U/I/D | N13/N16 |
| MEM-22 | item提交后索引失败/kill；切embedding模型/维度；rebuild | outbox可重放；Room真源完整；generation不混用；新索引提交前旧索引可用/词法回落 | I/D/F | N14/N15 |
| MEM-23 | 删条目/空间后进程死亡；任务中已有摘录；重新检索 | tombstone立即拒绝，GC可续；派生索引/link/proposal清理；UI解释任务副本删除范围 | I/D/F | N16 |
| MEM-24 | 记忆来源LOCAL_ONLY/PRIVATE/SECRET分别送云模型与网络工具 | 序列化边界拦截，PRIVATE限scope；无“本地失败转云”；实际网络日志零越权载荷 | U/I/D/F | N16 |
| MEM-25 | 生成与embedding/提取争用单模型资源，取消/热切换 | 同仲裁owner，无第二engine；请求lease顺序无死锁；真实停止前不抢占复用 | U/D/F/O | N15/N21 |

## 7. Workflow 验收

| ID | 前置与输入 | 必须断言 / 判失败条件 | 层 | 卡 |
| --- | --- | --- | --- | --- |
| WF-01 | CRUD/patch/复制/启停draft并发布v1；运行中发布v2 | run固定v1/hash，draft变更不影响；旧版本和记录仍可读 | U/I/D | N17/N20 |
| WF-02 | 相同goal同时create，超过200条任务，命令重放 | receipt精确commandId→taskId；无unseen+goal猜测；最多一个对应创建结果 | U/I/F | N01 |
| WF-03 | 旧recipe手动run、转换为新图、取消/导出 | 旧定义/任务不被覆盖；新图记录来源hash；旧手动行为保留 | U/I/D | N20 |
| WF-04 | Trigger→Execute工具，静态参数与节点输出引用 | 真实工具走统一pipeline；类型/必填/缺值错误定位到node/binding | U/I/D | N17/N18 |
| WF-05 | EQ/NE/GT/GTE/LT/LTE/CONTAINS/NOT_CONTAINS/IN/NOT_IN | 固定类型/空值/大小写语义；每运算真/假/非法输入有fixture | U/I | N17/N18 |
| WF-06 | Logic AND/OR，分支未运行值，短路输入 | 逻辑真值与短路规则明确；不能把缺值当任意false导致错误副作用 | U/I | N17/N18 |
| WF-07 | Extract REGEX/JSON/SUB/CONCAT/RANDOM_INT/RANDOM_STRING | 边界/非法表达式稳定；regex有执行上限；随机值落checkpoint，恢复不悄变 | U/I/F | N17/N18 |
| WF-08 | Model与Script节点、结构化outputs和secretRef | 统一模型gateway/脚本host；无raw密钥内联/日志；输出schema校验 | U/I/D | N18 |
| WF-09 | DAG中孤立节点/回边/无结束/错类型/子定义递归 | 发布前定位并拒绝；不启动部分图造成副作用 | U/I | N17 |
| WF-10 | 一分支选中，另一未选；fan-out→join乱序完成 | 未激活分支SKIPPED；join等待激活上游；无永等/重复执行 | U/I/F | N18/N19 |
| WF-11 | fail-fast、error边、继续策略、补偿失败各图 | 定义策略决定终态；补偿单独审批/可失败；不以有部分产物称成功 | U/I | N18/N19 |
| WF-12 | Workflow AgentTask节点等待多步子执行，已有其他排队任务 | 子执行同root frame，不排进被父占据的active slot；共享预算/取消；无父子owner死锁 | U/I/D/F | N18/N19 |
| WF-13 | Subworkflow固定v1，运行中子定义更新v2；层数超限 | nodePath与输出稳定；不追latest；层数/循环引用超限前拒绝 | U/I | N18/N19 |
| WF-14 | bounded loop/foreach含空集、上限、并行项失败/取消 | 稳定item index与预算；无任意回边；所有item有终态，父取消级联 | U/I/F | N18/N19 |
| WF-15 | 输出写入后checkpoint前/后kill；重复dispatch outbox | 输出+checkpoint事务；重复wake复用结果；节点effect无重复派发 | I/D/F | N19 |
| WF-16 | 外部非幂等动作派发后断电/回执丢失 | UNKNOWN/NEEDS_REVIEW；无盲目重发；用户看到已知事实，非伪exactly-once | I/D/F | N19 |
| WF-17 | Wait/User/Timer停1小时，期间启动普通Agent；重复唤醒 | 持久park释放计算lease及active准入slot；同checkpoint CAS唤醒一次；旧epoch不能复活 | U/I/D/F | N19 |
| WF-18 | manual从生产列表/编辑/运行入口触发同版本 | 精确receipt、输入快照、run历史；连点按幂等合同处理 | I/D | N20/N22 |
| WF-19 | schedule间隔/日历，时区/DST/时钟回拨、错过slot | 下一次wake与missed策略确定；WorkManager延迟如实；不声称精确cron/强停保活 | U/D/F | N22 |
| WF-20 | Tasker真实事件调用入口，合法/伪造/重复payload | 来源/权限/版本验证；slot去重；Tasker缺失标具体依赖，不用通知替代 | I/D/F | N22 |
| WF-21 | Android Intent/Broadcast显式契约、外部恶意extras | export/permission/source校验；输入大小/类型；未授权定义不能被任意应用启动 | U/D/F | N22 |
| WF-22 | speech声明短语真实触发，误识别/后台限制 | 识别来源/配置可见；高风险动作仍审批；离线声明必须本地识别实测 | D/M/O | N22 |
| WF-23 | app_open冷启动，Activity重建/前后台反复进入 | 只按定义的冷启动事件触发；不把每次重组/旋转当新event；无重复run | U/D/F | N21/N22 |
| WF-24 | inbox入库后kill、slot关联前kill、重复Receiver/Worker | durable去重与outbox恢复；任务不丢不重复；唯一约束冲突可归并 | I/D/F | N01/N19/N22 |
| WF-25 | 同定义高频触发，skip/queue/replace；积压超额 | 策略与限额真实生效；replace先settle旧资源；拒绝原因可见 | U/I/D | N22 |
| WF-26 | 无Activity/VM冷启动Worker，有已授权本地模型绑定 | 同一进程执行根/engine；不借MainViewModel不new第二套；任务/通知可恢复观察 | I/D/F/O | N21/N22 |
| WF-27 | 窄无人值守grant；definition/tool/package/args/次数/期限变化 | 仅匹配动作签发单次授权；超界WAITING_APPROVAL；撤销生效，不后台自动同意 | U/I/D/F | N22 |
| WF-28 | 手机节点列表/画布编辑同draft，长参数/键盘/大字体 | 两投影一致；错误定位、绑定、发布diff/取消可用；无第二个draft事实源 | D/V | N20/N24 |
| WF-29 | 导入第三方 workflow，含权限/触发/未知节点或不支持脚本 | 生成兼容报告与draft；不自动启用外部触发；不支持项阻止发布，不静默跳过 | U/I/D | N20/N22 |
| WF-30 | 启动恢复存在超过单页的全部nonterminal，混合旧executionKind/recoveryVersion、UNKNOWN资源与重复唤醒 | startup fence完成分页单飞核账后才新准入；未知版本不按普通目标loop执行；UNKNOWN保留fence，park恢复不复用旧epoch | U/I/D/F | N19/N21 |

## 8. 离线、兼容与架构回归

| ID | 前置与输入 | 必须断言 / 判失败条件 | 层 | 卡 |
| --- | --- | --- | --- | --- |
| OFF-01 | 准备模型/包依赖/资料，断网后杀应用冷启动 | 工具→可执行包→写记忆→语义取回→图执行全链完成；所有执行/索引在手机，无隐式云请求 | D/M/O | N23/N25 |
| OFF-02 | 无本地embedding或资产损坏；禁网 | 词法fallback有明确状态；不拿fallback称语义parity；不偷偷下载或转云 | D/O | N15 |
| OFF-03 | 离线包包含完整locked依赖与WASM/资源/模板 | 安装/执行无需联网；缺依赖在预检报具体项；不等运行时下载 | I/D/O | N09/N12 |
| OFF-04 | 本地提取/profile/重排等全部启用，监测网络 | 来源LOCAL_ONLY不外发；辅助模型也本地；网络遥测/异常日志不含内容 | D/M/O | N15/N16/N25 |
| OFF-05 | 云模型、远端MCP、网络工具断网与恢复 | 明确网络依赖和错误；不伪装离线可用；恢复不自动重放未知写动作 | I/D/F | N07/N08/N23 |
| OFF-06 | 单模型内存预算，索引/普通聊天/Agent/Workflow争用 | 总进程/native内存受控、无第二实例；公允准入/取消；不缩上下文掩盖性能失败 | D/F/O | N15/N21/N25 |
| REG-01 | 历史聊天/图片/模型/Local API数据，真实签名升级安装 | 数据ID/参数/默认值连续；各受影响入口可用；清数据/改applicationId不算升级 | I/D | N24 |
| REG-02 | Room28→29已知fixture及当前29→新增schema逐段迁移 | fixture含必填revision；Room校验与真实旧数据保留；不destructive fallback | I/D/F | N02/N13/N19/N24 |
| REG-03 | 旧审批/命令V1、memory伪路径记录、未知enum | 合法workspace upcast；旧memory重新审批；不可扩大授权或丢历史 | U/I/D | N02/N24 |
| REG-04 | 聊天/生图/原生Agent/DSH导航与返回，深浅色/大字体 | 侧栏图片→Agent→DSH；页面数据独立；冻结UI行为和草稿/键盘/滚动连续 | D/V | N24 |
| REG-05 | 页面关闭/VM clear/FGS重建/服务死亡/旋转/前后台 | 执行归属连续、无页面泄漏/双runtime；停止和模型卸载遵守真实lease | U/D/F | N21/N24 |
| REG-06 | 全部新增依赖与生产调用者审查 | 无feature反依赖/第二Agent loop/Any注入/全局写权限/大Host扩张；无空接口装完成 | 静态审查/I | N00/N25 |
| REG-07 | 高并发创建、审批/取消、触发、索引、安装混合长运行 | 无数据串属、重复副作用、未结资源丢失；每个终态可追原始receipt | I/D/F | N25 |
| REG-08 | 每domain低磁盘/配额满/导出中断/损坏索引 | 原数据可读；新写失败一致；索引可重建；不自动删模型/用户资料腾空间 | I/D/F | N09/N16/N19/N25 |
| REG-09 | 诊断导出含URI/凭据/私密文本/selector/通知内容 | 默认脱敏、有预览；无密钥和原文泄露；身份与阶段仍能定位 | U/I/D | N23/N25 |
| REG-10 | 全候选关闭新来源/触发与代码回退演练 | 数据保留/只读历史可用；升级DB有前向修复或版本备份，不盲装旧APK/清库 | I/D/F | N24/N25 |

## 9. 模型任务评测与性能门槛

确定性协议通过、真实工具执行通过、模型选对工具并完成目标，是三张独立成绩单。模型回答离题应计任务失败；不能因为HTTP有返回或工具名出现就算成功，也不能把模型错误全部归咎于runtime。

### 9.1 固定评测集

N23 在首次候选前冻结 `agent-parity-suite.jsonl`（拟新增）与 hash，至少 **80 个端到端任务**：工具25、工具包15、记忆20、workflow20。每项包括输入、模型可见资料、允许工具与预算、预期OS/文件/DB效果、禁止效果、独立判分脚本、人工判分规则。另保留不少于20项未用于prompt调参的保留集。中文为主，覆盖英文参数/文件名与多语言资料；至少三分之一为多步组合任务。

工具包集必须包含四上下文与异步host调用；记忆集包含跨空间诱导、无关键词语义取回、矛盾提案；workflow集包含六触发及恢复。仅纯算法样本不能占满端到端任务。安全/隔离/迁移用例作为独立必过门禁，不因模型总分高而抵消失败。

每个声明支持的模型/模板/协议组合至少固定种子或采样参数，跑3轮；记录全部尝试，包括拒答、超时、无效参数、重复工具、离题、外部失败。不得失败后只保留重试成功的一轮。基础分母为全部80项×3，保留集单列。外部环境故障可分层解释，但必须同时保留全分母成绩，不能无记录剔除。

| 指标 | 计算方法 | 候选准入规则 |
| --- | --- | --- |
| 确定性行为正确 | 本文各fixture的精确断言 | 必需项全部通过，零越权副作用/零跨空间污染；未运行不计PASS |
| 模型任务完成率 | 独立效果判分成功任务数 / 全部任务尝试 | 建议初始目标总集≥90%、各域≥85%、保留集≥85%；N23可依据预先记录基线提高，不可见失败后降门槛 |
| 工具选择/参数有效率 | 应调用工具的任务中，正确工具+schema+scope的比例 | 与完成率分别记录；协议有效不能替代任务完成 |
| 记忆检索 | 固定query/qrels的Recall@6、MRR@10、无关键词子集召回 | 建议Recall@6≥0.85、MRR@10≥0.80；未授权结果为零；展示词法/向量/融合各项，不能只报最好值 |
| 触发可靠性 | 接受的unique slots与对应run/终态逐项核账 | 重复创建/未知丢失为零；OS阻断单列并用户可见；不要求不现实的后台准点 |
| 恢复正确性 | 故障点×恢复策略预期结果 | 未知副作用自动重放为零；纯/幂等路径按已批准策略恢复 |

以上数字是实施前锁定的产品候选目标，不是目前已经测得的分数。若选用的小模型达不到，交付必须把“执行框架功能可用”与“该模型达到能力基线”分别说明；保留用户运行选择，不用某一模型/设备名单隐藏功能入口。

如果要宣称“达到或超过 第三方 的任务完成率”，必须在相同资料、同模型/采样/工具授权和等价host能力下运行固定上游版本，记录适配差异和全部分母。当前只审源码，最多声明覆盖经验证的行为清单，不能声称实测胜过上游。

### 9.2 性能与长运行

N00记录现有候选基线，N23冻结测量方案。平台差异不被伪装成统一极限；所有值记录样本量、分位数、温度/前后台/电池状态、模型有效参数和总进程内存。初始建议门槛如下，实际配置需在首次候选前锁定并说明理由：

- UI点击到取消反馈 p95≤250ms（不代表执行已停）；实际停止/lease释放分别按各执行器已记录timeout衡量，超时必须显示真实未知态。
- 纯本地只读工具调度开销、包RPC开销、1万条记忆检索冷/热延迟分别测量；同输入下p95相对基线回退超过10%需解释并获技术复核，不能删功能换分。
- 记忆指标至少在1千/1万items、多个空间、不同chunk分布运行；报JVM/native/隔离进程总内存、索引大小、增量更新与重建耗时。
- 至少4小时混合长运行，包含100次工具调用、20次包启停/更新、100次记忆写/删、20次workflow触发/恢复以及随机取消；另留24小时低频调度观察。每类覆盖不足则该类长运行保持NOT_RUN。
- 模型首token/解码、embedding吞吐、图片生成与调度开销分开；不降低上下文、精度、输出预算或画质形成性能PASS。

## 10. 验收证据包与完成规则

每次候选使用独立目录，例如 `artifacts/agent-native-thirdparty-verify/<timestamp>/`。它是验证产物位置建议，不是本轮已创建或可随仓库发布的内容。原始证据与脱敏报告分开，不把真实用户资料、凭据或大模型资产放进分发文档。

最小证据索引数据形状：

```json
{
  "schemaVersion": 1,
  "candidateId": "<unique-id>",
  "recordedAt": "<ISO-8601-with-offset>",
  "upstreamReference": {"repo": "AAswordman/第三方", "commit": "dbf71916fae9750cfdc9f9a774f5a0fee56633fb"},
  "source": {
    "branch": "<branch>", "head": "<sha>",
    "trackedDiffSha256": "<hash>", "untrackedManifestSha256": "<hash>",
    "submodules": [{"path": "<path>", "revision": "<sha>", "diffSha256": "<hash>", "untrackedManifestSha256": "<hash>"}]
  },
  "build": {"apkSha256": "<hash>", "signerFingerprint": "<fingerprint>", "variant": "<variant>", "toolchainRef": "<evidence-ref>"},
  "environment": {"androidVersion": "<version>", "abi": "<abi>", "deviceEvidenceRef": "<ref>", "permissionsRef": "<ref>", "networkMode": "<mode>"},
  "model": {"assetSha256": "<hash>", "backend": "<backend>", "runtimeRevision": "<revision>", "templateSha256": "<hash>", "protocol": "<version>", "effectiveParamsRef": "<ref>"},
  "extensions": {"catalogHash": "<hash>", "packageLockRef": "<ref>", "skillSnapshotRef": "<ref>", "mcpCapabilitiesRef": "<ref>"},
  "storage": {"roomSchemaVersion": "<actual-version>", "fixtureManifestSha256": "<hash>", "embeddingFingerprint": "<fingerprint>"},
  "case": {"id": "<case-id>", "taskCard": "<Nxx>", "level": ["<level>"], "status": "NOT_RUN", "attempt": 1, "expectedRef": "<ref>", "actualRef": "<ref>", "rawEvidenceRefs": [], "issueRef": null}
}
```

记录身份、hash与引用，敏感payload本身不进索引。设备信息用于复现和诊断，不成为用户功能准入名单。`effectiveParamsRef` 至少包含context、max output、模板、采样、tool protocol和本地后端有效参数；本地embedding另记tokenizer/pooling/normalize/dim，云端另记API格式与明确模型版本标识，不保存密钥。

脏树证据必须涵盖untracked新源码与子模块内部diff；只有父仓库HEAD不能复现本轮基线。构建后仍有人修改源码时，报告说明APK绑定的实际快照；不能拿“最新文件”的测试报告配旧APK。

每个任务卡交接附：实际写集、唯一writer、生产调用链、迁移fixture、命令和真实测试数量、退出码、原始日志、NOT_RUN/BLOCKED、临时adapter退出条件及回退。聚合脚本只读这些证据，不得凭页面存在/测试名存在自动升PASS。

最终准入顺序：

1. N00–N22对应能力与迁移门禁完成；源代码审查证明没有扩大禁止的架构债务。
2. 本文必需行为用例逐项有候选绑定结果；不存在未解释FAIL、NOT_RUN、BLOCKED；上游逐symbol清单无隐藏缺口。
3. 模型任务与记忆检索指标达预先锁定门槛；本地/云端/完整离线分别有成绩单。
4. 受影响旧功能、UI、升级、取消/恢复、长运行通过；真实host与隔离证据完备。
5. 输出明确声明范围：四域行为对标、第三方包兼容范围、MCA增强项和剩余限制。没有同条件上游实测，不写“性能/成功率超越”。

任何一项未满足，仍可发布经授权的阶段能力，但必须以真实差距表命名完成度。本方案不因交付压力降低门槛，也不承诺文档能让任意执行者无审查地完美实施。
