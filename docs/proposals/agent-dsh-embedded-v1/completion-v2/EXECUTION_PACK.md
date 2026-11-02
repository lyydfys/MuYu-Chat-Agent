# DSH V2 执行包：28张任务卡与接手提示词

本文件只规定后续实施，不代表本轮已执行。配套：[总方案](README.md)、[架构](ARCHITECTURE.md)、[来源](SOURCE_MAP.md)、[验收](PARITY_ACCEPTANCE.md)。所有卡初始为待执行；当前已有实现先审查、复用、补验证，不另写一套。

## 1. 执行方法、写集和产物

每张卡先做一个可验证的最小业务切片，完成生产调用、失败结算和必要验证后再扩大范围。禁止按本文件批量生成未接线的类、接口和测试，禁止把路径建议当成必须新增的模块。

下表是相对仓库根的真实定位入口。卡内简称约束写集，**不是整目录任意修改权**。每次开工须登记具体文件；拟新增路径先检查同职责实现是否已存在。

| 简称 | 已存在的定位入口 | 所有权与写入约束 |
| --- | --- | --- |
| CONTRACT | `core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/` | 整合者单写公共契约，兼容增量；业务工作者提出diff建议 |
| RUNTIME | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/` | 生命周期工作者；`DshRuntimeOwner.kt`、service/IPC由整合者预约合入 |
| CONFIG | RUNTIME下`config/DshGatewayConfigSeeder.kt`及测试 | 配置工作者；D02、D03、D06、D07、D20不得同时改 |
| GATEWAY | `api/dsh-gateway/src/main/java/com/muyuchat/api/dsh/gateway/` | 协议工作者，保持仅模型职责 |
| ADAPTER | `app/src/main/java/com/muyuchat/mca/dsh/` | 模型工作者；保持模型业务owner与lease |
| COMPOSE | `app/src/main/java/com/muyuchat/mca/composition/DshModeAssembly.kt`、`DshGatewayAssembly.kt` | 整合者单写装配，不承载业务算法 |
| WEB | `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/` | 前端工作者；页面只读投影，不持有Node或设备执行owner |
| STAGE | `scripts/dsh/stage.ps1`、`payload-lock.json` | 打包工作者；锁住完整闭包，不拿APK解包结果代替来源账本 |
| NATIVE | 拟`core/dsh-runtime/src/main/cpp/`内按职责的小型原语，或已有同等来源 | native工作者；CMake/Gradle/nativeLib归整合者，源码必须可重建 |
| EXT | 拟`scripts/dsh/patches/`与受管扩展源码目录，D00确定实际存放路径 | 最小官方接点补丁、Android插件传输适配；不得只改不可复现bundle |
| PLATFORM | 拟`app/.../dsh/platform/`及按真实编译隔离需要建立的平台实现位置 | 各域工作者分文件，接口放窄契约；不得反向依赖feature |
| DATA | 拟RUNTIME下`maintenance/`、`backup/`、`session/`职责子包 | 数据工作者；官方存储仍由DSH拥有，宿主只做受控维护适配 |
| EVIDENCE | 实施时新建的独立`artifacts/dsh-verify/<run>/`与相应实施记录 | 原始证据不可改写；账户、消息及路径脱敏，机密不进仓库 |

所有卡均受以下完成条件约束：

1. 基线包括HEAD、dirty/untracked输入hash、子模块revision及内部patch、payload与工具链，不只写Git SHA。
2. 点名唯一writer、创建/使用/取消/释放/恢复owner；真实UI/服务/官方插件调用到了实现。
3. 至少覆盖一个失败、取消或进程中断分支；按改动风险验证，不为简单文案新增镜像测试。
4. 清理迁移完成的旧实现；临时adapter必须有调用者、退出条件和后续卡。
5. 新检查不修改历史PASS，不减断言、不接受0 tests、不用fake冒充真实工具或设备效果。
6. 交付当前精确diff、检查结果、剩余缺口、证据和回退点。未验证就保持NOT_RUN/BLOCKED。

普通工程选择可由整合者处理；本包不要求每张卡结束都向用户重复确认。需要破坏数据、取消功能、改变冻结全局视觉或外部发布时，先形成具体可审查结果，再按真实授权处理。

## 2. 子卡依赖：避免整卡循环

字母子片是可独立结算的边界，不代表仅有stub即可完成。基础片至少由真实服务入口使用并有行为证据；所属里程碑另验完整UI到效果。

| 子片 | 最低前置 | 可以先交付的结果 | 不可提前宣称 |
| --- | --- | --- | --- |
| D00 | 无 | 三基线、现状与写集、能力身份 | 运行完成度 |
| D01a身份与停止事实 | D00 | service/client/owner一致终态、未知态fence | 全部跨进程恢复 |
| D02a配置与账号独立启动 | D00 | 没有MCA模型时仍可打开官方前端 | 已登录、所有provider已工作 |
| D10a锁/原子原语 | D00 | 实际生产文件路径上的竞争与发布保护 | 数据维护全完成 |
| D03官方协议接线 | D02a | 官方认证配置与平台打开接口生产接线 | Android回调成功 |
| D04a可信打开接点→D04b浏览器回调 | D03、D01a | 官方attempt安全投影与pending handle真实关联，再完成手机往返 | 只有onCreateWindow事件就等于attempt认证 |
| D05凭据生命周期 | D04、D10a | 存储、竞态失效、退出可验证 | 模型任务正确率 |
| D06a来源与目录 | D02a | 官方与MCA来源同时可见、ID兼容 | 热更新或实际选择命中 |
| D07a身份/应用回执 | D01a、D06a | desired/applied和受管官方控制接点 | busy时能安全重启 |
| D07b热绑/目录闭环 | D07a | 新请求使用新revision，旧请求结算 | 完整quiescence替代热更 |
| D07c受控重连fallback | D07a、D01完整停止、官方活动证明接点 | 停止后安全重连或明确需用户停止 | timer猜空闲、自动重放 |
| D07m维护写屏障 | D07a、D01完整停止 | 全mutator在线屏障或已确认停止全部可写进程的维护准入 | 没有turn等于没有账号/配置/插件写入 |
| D08协议；D06b主辅路由 | D06a | 真实wire与资源语义；路由固定 | 所有后端/视觉都通过 |
| D09逐后端能力 | D08、D06b | 对应后端真实工具/视觉能力 | 一后端代替全部后端 |
| D11命令 | D02a、D10a | target36下完整dispatcher/bin闭包 | PTY、所有插件可安装 |
| D12终端 | D11、D01a | 真PTY及其停止/attach/resize | pipe就是PTY |
| D13插件 | D11、D10a、D02a；终端样本加D12 | 官方manager完整生命周期与实际执行 | 任意native包天然兼容 |
| D14完整内核 | D06b、D08、D13；PTY/平台样本再加关联卡 | 官方422行逐项闭环 | 422个名字存在即全通过 |
| D15a桥基础 | D07a、D10a | 认证/账本与首个真实SAF或通知动作 | 五域全完成 |
| D16–D19 | D15a、D11、D02a；各按具体权限/媒体依赖 | 独立平台域真实效果 | 仅工具注册成功 |
| D20a控制台框架 | D02a、D07a、D15a | 状态/intent有真实owner | 五域页面已验收 |
| D22a维护事务与快照 | D01、D07m写排他证明、D10a | 维护准入、journal、快照/恢复基础 | 会话修复、升级都完成 |
| D21会话 | D22a、D10a | 官方格式回收/恢复/精确修复 | 全量备份迁移完成 |
| D22b完整救援与备份 | D22a、D13、D21 | 安全模式/自检/备份恢复闭环 | 升级闭包已验收 |
| D23补丁/主题/悬浮 | D20a、D22a；设备动作再加对应D16–D19 | 受控自定义能力 | 主题授予脚本执行权 |
| D24升级 | D22b、D13、D07、D11；涉及PTY加D12 | 串行候选trial到提交/恢复 | 两宿主并跑或数据任意降级 |
| D25 UI/后台 | 从D01起分片；结案依赖D04、D07、D16–D24 | 按组件完成恢复与交互 | 页面恢复等于任务自动续跑 |
| D26–D27 | 所有声明完成范围对应卡 | 当前候选全矩阵与长期运行 | 缺证据仍称完全对标 |

D01未知态结算和D07身份契约按`D01a→D07a→D01后续→D07b/c`合入。D22a先交基础供D21/D24使用，D22b再验完整产品，不出现D21与D22整卡互等。D20只消费平台状态，不作为D16–D19基础实现前置。

建议并行泳道：账号D03–D05、模型D06–D09、执行D10–D14、平台D15–D19、数据D21–D24。每次只开启前置满足且写集不交叉的若干片；native、公共契约、CONFIG、COMPOSE、Manifest/根构建串行合入。前端优化继续独立推进；触碰同一WEB/共享主题/侧栏文件时先预约具体写集。

## 3. 任务卡

### D00：三基线、现状与责任锁定

**Owner/写集：** 整合者；只新增本轮实施基线、来源、差距与ADR记录。依赖无。

**步骤：** 完整读项目合同；读取真实入口到终态及一个取消分支。锁官方DSH、指定手机版、MCA三份来源和运行字节，记录source/archive/payload的对应关系。重查dirty/untracked、子模块内部diff与工具链。将422行旧identity和手机版每个tool/action提取到新账本，记录旧C卡的当前实现、证据适用性与剩余缺口。逐张分配具体文件和唯一owner，确定EXT/NATIVE实际可复现存放路径。ADR记录账号与MCA并存、官方业务所有权、维护屏障、target36执行路线。

**验收/失败：** 每行有来源/任务/生产入口或明确待接线；旧APK只证明其旧链路。来源不全时补取精确来源，不猜版本、不改旧manifest/PASS。**回退：** 仅撤本卡新记录；不动原数据和生产树。

### D01：停止事实、未知态、进程树与lease

**依赖：** D00；D01a先交，后续身份结算整合D07a。**Owner/路径：** 生命周期工作者；RUNTIME的service/stop policy/process/owner及对应测试，COMPOSE由整合者。

**步骤：** 先复核现有`DshServiceStopPolicy`、`DshStopSnapshotPolicy`、通知修复，避免重写。贯通用户stop→IPC→owner→Node及工具树→MCA取消/lease→回执。STOP_TIMEOUT/FAILED/UNKNOWN保留事实、通知和fence，不等于STOPPED。核对`DshModeAssembly` finally及gateway.stop时序，未知停止时不得提前关掉必要通道却宣告资源已释放。进程身份不能仅靠可复用PID；晚回调不结算新代。重复stop幂等，失联后由实际进程/lease回执结算。

**验收：** 正常停止、子进程不退、Binder断开、服务先死、main先死、超时后晚成功；真实残留树及lease归零证据，通知同事实。**回退：** 回退新增实现patch但保留“不明即未证实停止”的保护，禁止恢复失败stopSelf逻辑。

### D02：配置层、平台overlay与无MCA启动

**依赖：** D00。**Owner/路径：** 配置工作者持CONFIG与测试；RUNTIME startup/COMPOSE整合者接线；官方loader只做EXT最小适配。

**步骤：** 输出固定官方配置加载顺序和最终composed tree。保留已合并bash-local修复及sandbox服务；禁止误禁sandbox导致ptc-runtime pending。把MCA managed overlay、用户profile、官方账号、平台插件、短期binding分离。改现有强制`seed(endpoint)`链，使账号模式无MCA gateway或空目录仍可启动。用户路由不因来源暂失被删除；新用户显式选来源。旧生成overlay三方迁移，有冲突保留用户文件及上一有效值。保持审批，不继承手机版默认danger-full-access/never；明确bash-local的实际隔离界限。

**验收：** 无本地模型、云源失败、仅账号、旧overlay有用户编辑、语法坏、重复启动；实际默认preset可create/resume。**失败/回退：** 原子保留上一配置和typed来源错误，不空home、不整文件覆盖；回退仅受管层与匹配EXT版本。

### D03：官方DeepSeek账号协议接线

**依赖：** D02a。**Owner/写集：** 账号工作者持EXT官方平台打开接点及脱敏fixture；CONFIG与公共契约整合者合入。不自建账号数据库。

**步骤：** 从SOURCE_MAP固定版本确认账号平台、LLM provider、授权/credential服务、前端入口与配置。逐字段登记真实PKCE/state、callback、交换、状态、取消、失效及logout行为；源码无refresh就不添加refresh。保留官方请求协议和credential authority；MCA只替换需要的平台浏览器打开方式。账号provider与MCA provider同时启用，`llm-pi-ai`不误禁；账户/API Key分别显示。source不含某实现时以实际锁定payload补证并记局限。

**验收：** 官方设置入口生产调用可发起attempt、返回官方授权URL；假服务只验证失败/竞态，不算真实登录。官方服务异常不降级抓cookie。**回退：** 撤最小平台接点，保留私有credential与用户配置，不回退成自造接口。

### D04：Android外部浏览器与完整账号回调

**依赖：** D03、D01a。**Owner/路径：** 账号工作者持拟`WEB/auth/`及typed browser launcher；官方回调继续由DSH监听，Manifest/Activity接点整合者。

**步骤：** 先验证官方`window.open(about:blank)`预开→等待RPC/auth_init→`popup.location.replace(authorizeUrl)`的真实序列。当前MCA约1秒空白销毁/5秒popup硬寿命可能截断该流程。D04a必须先定位/实现最小可信受管client-host seam，利用官方安全状态投影关联pending handle/attempt/授权URL/deadline/终态，证明来源认证、HostIdentity与重放拒绝；现有onCreateWindow本身没有attempt证据，不可信任任意网页自报字符串。D04b再用有界pending handle移交系统浏览器/Custom Tabs，不只增大魔数；取消/终态/teardown释放，空白阶段不打开无关浏览器页。保留官方实际DSH Web server固定`GET /oauth/callback`与PKCE，不每次另开callback端口/路径。DSH WebView仍只加载本地origin，不注入密码/验证码/cookie，不注册未经官方支持的自定义scheme。后台listener保活与通知按系统规则实现；返回、旋转不重发attempt。cancel/timeout/重复callback/旧attempt/code重放/浏览器无handler/进程死均有确定状态；不可恢复attempt干净重开。

**验收：** 真手机从官方入口登录→浏览器→callback→官方状态登录成功→账号模型请求；auth_init人为延迟2秒、超过5秒及接近其请求超时分别验证，取消后迟到URL不能再开浏览器。固定官方`authorizeUrlWithTheme`追加/覆盖theme仍可打开；其他授权参数改变被拒，不能用整串相等误拒合法theme。另验attempt过期/错误state/path/重复回调及切后台。日志不含code/verifier/token。**回退：** 撤宿主打开适配、保留官方状态；不能放宽全WebView外链作为补救。

### D05：账号凭据、401、退出与存储迁移

**依赖：** D04、D10a。**Owner/路径：** 账号工作者持官方EXT凭据边界与脱敏fixture，私有路径/系统backup规则由整合者；不建Kotlin平行token store。

**步骤：** 验证官方`.credentials.yaml`唯一写入，App私有DSH home、目录0700/文件0600、真实锁及原子写/reload，排除普通备份、诊断和Android自动备份。首版保留官方存储，不宣称已有Keystore；若后续加密先证明可替换port与单权威迁移，失败不双写。401按官方lifetime/kind/token/issuer匹配失效，旧401不能删新登录；模型listing401不清登录，推理/profile/balance401或40003按官方处理。区分Web换票、MCA bearer重绑、账号失效。logout取消/等待attempt→本地删除→后台远端撤销→发布状态，删除失败不远撤销；只取消官方定义的deepseek-account运行Agent，不停MCA任务。账号退出不删除MCA来源/会话，本地cookie清除不冒充账号退出。

**验收：** 重启仍登录、存储失败、过期/401、先退出后晚响应、重新登录与旧401竞争、离线退出、普通导出无凭据。无账号条件记BLOCKED。**回退：** 仅可验证可读的存储版本回退，无法回退则前向修复/重新认证，禁止清全部home。

### D06：账号/MCA双provider、目录与主辅路由

**依赖：** D02a；D06b需D08协议。**Owner/路径：** 模型工作者持`DshModelCatalog.kt`及目录fixture；CONFIG/CONTRACT/官方路由EXT整合者单写。

**步骤：** 保留官方账号provider，MCA本地和云模型沿现有稳定ID注册；显示来源/网络依赖/有效能力与错误。目录为空合法，旧session模型引用不改写，移除模型后明确不可用。主模型、标题、压缩、子Agent等逐调用路径登记实际resolved snapshot。新操作固定revision，运行中选择变化只作用新操作。离线配置强制所有推理辅助路由本地，缺能力暂停；混合路由必须用户明确选。父任务在等待子Agent推理时不能占有导致死锁的独占native执行lease；由现有仲裁者按实际request分配。

**验收：** 三来源并存且真实下一请求命中，旧session恢复、主辅离线断网、子Agent资源排队/取消。**回退：** 恢复上个受管provider配置和稳定ID；不自动替换失败模型、不迁移账号token到MCA库。

### D07：绑定身份、目录热更新与重连

**依赖：** D01a、D06a；D07c依赖完整停止及活动证明。**Owner/写集：** 生命周期/模型工作者分窄实现；IPC、CONTRACT、CONFIG、owner整合者单写。

**步骤：** 明确HostIdentity/GatewayIdentity、commandId、expected identity、catalog/binding desired/applied revision及查询回执。用官方合法设置/credential接点支持内存binding，而非仅endpointHolder.set。新binding准备→认证探针→官方应用ACK→新操作快照→旧在途结算→撤旧token；失败可查并保留旧applied。目录事件覆盖模型装卸/删除和云编辑、能力/context改变，picker刷新后验证真实命中。D07m逐类证明turn/tools/jobs/子Agent/PTY/install/审批以及账号attempt/callback/committing/401凭据删除、settings/profile/插件/session等所有mutator准入和drain；仅agent空不算空闲。无法取得完整在线屏障则以D01确认Node与全部可写子树停止作为维护路径；未知残留BLOCKED，不猜空闲自动重启。

**验收：** main单死Node存活、端口/token改变、乱序目录事件、busy变更、晚旧ACK、旧401、新请求命中；不得自动重发旧turn。**回退：** 冻结已确认applied、撤未生效候选；unknown操作保留未知，不强杀busy掩盖问题。

### D08：最终模型wire与完整协议

**依赖：** D06a。**Owner/路径：** 协议工作者持GATEWAY、`DshTranscriptMapper.kt`、`DshPromptedToolProtocol.kt`及测试；共享inference/engine事件整合者按原owner修改。

**步骤：** 用脱敏fixture分别截取官方输入、gateway解析、MCA最终序列化、后端事件和官方回收。保持system/transcript/工具schema/图像，不二次附默认角色/时间/reasoning、不静默裁历史；窄typed策略默认不改聊天/API行为。贯通none/auto/required/named、parallel=false、完整JSON与call/result配对。容量不足输出固定pi-ai/DSH能识别的错误，证明其压缩/暂停路径。真实EOS/LENGTH/取消/错误/断连各自映射；required无call、named不匹配、malformed不返回成功，不执行reasoning中的半JSON。

**验收：** 各参数正负例、schema预算、超长历史、stream中途失败、重复Done、取消、usage；再以真实模型复核，合成fixture不当任务成功。**回退：** 新字段向后兼容、DSH专用策略整体回退，旧聊天默认与Local API wire保持原合同。

### D09：native tools、视觉与辅助能力逐后端补齐

**依赖：** D08、D06b。**Owner/写集：** 后端工作者按明确引擎分配，公共事件整合者；不把engine所有权移入DSH。

**步骤：** 建后端×模型×传输×工具模式×视觉×context能力矩阵；从实际runner到wire验证，而非仅配置supportsTools。已支持native模式直接用；prompted明确兼容模式与严格失败语义。逐一验账号模型、MCA云传输、本地相关MNN/llama等实际支持路径；未实现路径保留差距。图像包含真实解码/缩放/方向/多图及工具图像结果，辅助模型沿D06路由。能力变化发布新catalog revision，不改在途快照。

**验收：** 每个受影响backend至少代表模型真实schema call、结果回传、下一步完成；视觉用已知图任务与错误图，不用“文本回复了”结案。**回退：** 对应能力声明与实现同时回退，说明具体缺口；不能用型号名单限制入口。

### D10：真实锁、不覆盖发布与文件原语

**依赖：** D00。**Owner/路径：** native工作者持NATIVE及官方addon最小EXT；RUNTIME payload/后续DATA实际调用，构建整合者。

**步骤：** 核实payload锁和no-replace现状；真实flock绑定缺失不得no-op成功。选择实际内核可用的不覆盖原子发布原语；不能`stat→rename`假装原子。明确共享/独占、重入、锁释放、同/跨文件系统、errno、低磁盘、symlink策略。建立维护屏障和锁序，不持锁等待UI或长网络。补生产调用，让payload发布/数据维护真正使用，不只暴露helper。

**验收：** 独立进程竞争、持锁者被杀、重复发布、已有文件/symlink、跨FS/低空间/中断，原内容未被覆盖。**失败/回退：** 原语缺失typed失败并保留原目标；源码/ABI/payload匹配回退，不退回静默成功。

### D11：target36命令、包管理器与依赖闭包

**依赖：** D02a、D10a。**Owner/路径：** 工具链工作者持NATIVE/EXT/STAGE与运行清单；RUNTIME assembly与根构建整合者。

**步骤：** 复用nativeLibraryDir ELF入口，不降target。为Node/rg/curl/git/Python、npm/npx/pnpm、pip/venv及package bin列解释器/argv/env/cwd/库/CA/hash；JS/Python入口显式解释器，透明shebang/bin需求用最小native dispatcher或官方runCli接点。Bash是实装GNU Bash或明确sh兼容层；依赖Bash语法的插件必须真实验证。npm lifecycle、pnpm store、Python sys.path/venv、git credential与CA不能靠`--version`代替。划分纯JS/Python、Android addon/wheel与需编译包，固定Android可复现来源。

**验收：** 真正安装离线fixture与受控网络包、执行package bin/postinstall批准/拒绝、git clone/TLS正负例、Python import及venv/pip、含空格参数/路径、EACCES。**回退：** launcher/ELF/库/JS/payload闭包一起回退，保留home；禁止关闭TLS或chmod碰碰运气。

### D12：真实PTY、终端attach与job control

**依赖：** D11、D01a。**Owner/写集：** 终端native工作者持PTY原语与官方subprocess seam EXT；service树停止整合者。

**步骤：** 正确分配master/slave与对应关系、setsid/controlling tty/前台进程组、winsize/termios；不能扫描pts编号猜slave。保留官方attach协议，支持stdin/EOF、双向背压、resize→SIGWINCH、^C、退出码、后台jobs和重新attach。FD继承最小化，异常/页面关闭/服务死释放有owner。平台不可用明确pipe降级，完整对标PTY保持未通过，不把isatty单测当完成。

**验收：** 在`:dsh`真实Node运行交互shell、读取终端尺寸的程序、全屏程序、^C、后台/前台任务、断开重连、子树停止与FD稳定。**回退：** 回退seam/helper匹配闭包并保持明确降级状态，不丢用户session。

### D13：官方插件安装、启停、更新与失败恢复

**依赖：** D11、D10a、D02a；PTY插件再加D12。**Owner/写集：** 插件工作者持兼容样本/EXT最小接点/证据；官方manager仍唯一写profile/package/lock/node_modules。

**步骤：** 实测目录、包名、GitHub来源及版本依赖；保留信任确认、build scripts审批、version exemption各自入口。官方UI安装→加载→工具调用→结果→更新/禁用/启用/卸载→重启恢复。模拟网络失败、依赖错、构建拒绝/中断、取消及安装进程死亡，按官方事务恢复，未知先查询不换ID重装。记录恢复manifest/lock与plugin data/node_modules的真实边界，不承诺万能rollback。禁止Kotlin另写安装器。

**验收：** 纯JS、bin、postinstall、Python、Android addon、错误ABI、terminal、自带server各有样本；安装界面存在或npm退出0不算实际运行。**回退：** 走官方已支持卸载/版本恢复并保留用户plugin data；必要用D22快照，不直接删整个profile。

### D14：完整DSH内核422行、Skills/MCP/jobs/子Agent

**依赖：** D06b、D08、D13及各能力样本前置。**Owner/路径：** 内核兼容工作者持矩阵与受控样本；官方业务原样保留，只有平台seam入EXT。

**步骤：** 逐一映射422行identity到上游实现、有效配置、生产调用与可观察结果，不把family数当功能数。覆盖preset/loop/user questions/approval/文件工具/Skill发现与资源/安装、MCP stdio和HTTP及认证/重连、jobs持久状态、delegation子Agent与取消、workflow/PTC、压缩与session/记忆的实际官方能力。官方未启用功能登记启用profile和平台依赖，不能因旧V1 optional排除本次目标。子任务共享实际推理owner，父等待时避免lease互锁；活动都纳入D07维护屏障。

**验收：** 多步文件任务、审批拒绝、Skill实际读取资源、两类MCP、job断页/恢复/取消、子Agent汇总与停止、context压缩后任务继续。**回退：** 最小扩展接点回退，保留session/plugin数据；官方缺陷与Android适配失败分别记账，不在MCA重写loop。

### D15：认证手机能力桥、幂等账本、SAF与通知

**依赖：** D07a、D10a。**Owner/路径：** 平台基础工作者持现有CONTRACT capability提案、拟PLATFORM基础/server/ledger；公共契约、端点装配整合者。

**步骤：** 不把工具塞模型gateway。token/HostIdentity/允许操作与参数校验、大小/并发/速率/超时、错误与取消类型固定。官方invocation映射稳定operationId；参数fingerprint绑定caller/plugin hash/目标；先持久派发意图，后真实动作，receipt原子存储。重复同参返回同事实、异参拒绝；派发后无结果为UNKNOWN，不重放外部副作用。Android交互权限经resumed页面ActivityResult窄接点，后台返回USER_PRESENCE_REQUIRED。首个生产动作选受控SAF读写和通知，路径/授权过期与真实回执明确。

**验收：** 未认证/旧代拒绝、重复请求、动作后进程死、权限撤销、后台交互、文件写后读/通知实际可见、停止取消。**回退：** 撤端点/注册和凭据，保留账本和用户文件，不把UNKNOWN改失败后自动重试。

### D16：Android结构化工具及Shizuku/root

**依赖：** D15a、D11、D02a。**Owner/写集：** 系统平台工作者持PLATFORM android/shizuku与受管两插件传输适配；权限/Manifest整合者。

**步骤：** 从参照源码逐tool/action保留schema/result，覆盖包/应用/设置/输入/截图/通知/overlay及shell。采用App内Shizuku API服务，支持连接/授权/撤销/死亡重连；root单独通道、显式状态。标准API可做的优先标准API，不为普通动作强要高权限。结构化动作读取前后状态；通用shell明确广权限，不假装精细隔离。设备检测只供建议/transport，不做入口准入名单。

**验收：** 普通权限、拒绝、Shizuku未装/未运行/已授权/撤销、root可用/不可用；设置写前后核验、真实启动/输入/截图、shell超时和子进程终止。**回退：** 仅撤相关能力实现并说明具体依赖缺口；不禁整个DSH、不删除权限状态历史。

### D17：Accessibility工具完整行为

**依赖：** D15a、D02a。**Owner/写集：** 无障碍工作者持PLATFORM accessibility、服务及插件adapter；系统声明整合者。

**步骤：** 按参照逐一实现13工具：status/screen/tap/type/back/home/scroll/see，以及swipe/hold/touch/gesture/touch_status；以SOURCE_MAP实际名称为准，权限由用户真实启用。节点/坐标绑定window/package/tree generation，执行前复核bounds与前台；过期引用拒绝，不盲点。clickable ancestor、连续触摸/手势取消、输入、已到底/没移动、截图来源与能力分别返回。跨应用和敏感字段按授权范围处理；不缓存Activity，不在UI页面实现服务逻辑。

**验收：** 未开启/开启/被系统关闭、窗口切换/旋转、失效节点、滚动底部、文本输入、实际截图；目标应用画面证据加结构化回执。**回退：** 撤插件能力与服务接线，保留DSH和其他工具可用；不能把命令dispatch成功写任务完成。

### D18：VScreen、特权core与视频预览

**依赖：** D15a、D16特权通道、D11。**Owner/写集：** 虚拟屏工作者持PLATFORM vscreen/core/frame/preview和插件adapter；许可证、打包、Manifest整合者。

**步骤：** 固定参照及LGPL组件来源义务，不直接整包复制Activity。参照实际为ImageReader+JPEG帧泵/预览轮询，先对齐该链路；control/core/帧采集压缩/surface分别有资源owner，不把README的H.264当实现事实。动态认证端点替代固定8999，启动校验core hash且敏感token不公开在日志/命令行。create/status/launch/see/tap/swipe/key/close逐项连真实display；记录尺寸/dpi/orientation及坐标变换。first frame有限等待，no_display/not_rendered/no_frame区分；预览标帧时间及陈旧状态。悬浮预览权限可撤销，关闭释放display/ImageReader/缓冲/FD/socket/overlay/子进程。H.264优化另立测量切片，不阻塞JPEG能力对齐。

**验收：** 屏幕创建→目标App上屏→see真实帧→触控/按键效果→关闭；Shizuku与root-only单独验收，参照bridge实际上依赖Shizuku的缺口不得由ROOT_AVAILABLE布尔值掩盖。另验旋转、首帧超时、权限撤销、Shizuku死、主进程死和循环开关资源稳定；core启动token不出现在命令行/日志。**回退：** 停止并释放本域资源，保留明确不支持的具体App/系统行为，不误宣称支持安全内容截图。

### D19：独立AI Browser、多tab与稳定引用

**依赖：** D15a、D02a。**Owner/写集：** 浏览器工作者持PLATFORM browser独立进程服务、DOM协议及插件adapter；进程声明整合者。

**步骤：** 对齐caps/open/snapshot/find/click/type/read/nav/scroll/tabs十工具全部actions；不是只做网页打开。tab/document/frame/navigation generation绑定ref，DOM小改保留元素身份、导航/关tab失效。snapshot与真diff输出明确from/to及changed/deleted；点击/输入后核验实际URL/文本/元素，区分dispatched和observed。HTTP(S) URL/redirect/DNS/IPv6/local别名校验，禁止触达DSH/gateway/capability和file/content等scheme；不读账号credential/DSH cookie。下载受控staging，renderer死只恢复浏览器且不重发提交。

**验收：** 多tab切换、stale ref、DOM变化diff、iframe/SPA、表单真实效果、导航/下载、进程死、恶意本地URL与重定向。兼容浏览器方案按真实Web能力探测，不按设备白名单。**回退：** 关闭tabs/服务释放资源，保留用户DSH会话；不把DSH WebView改成任意JS执行器。

### D20：DSH专属控制台与权限投影

**依赖：** D20a依赖D02a/D07a/D15a，完整验收需D16–D19/D21–D24。**Owner/写集：** 前端工作者持WEB新增管理surface，typed ports整合者；不改聊天、生图和侧栏顺序。

**步骤：** 参照功能清单提供运行/账号来源/权限/插件/浏览器/虚拟屏/诊断/数据救援入口，但官方已有session/插件操作导航回官方UI。各卡供唯一权威状态，console仅投影和typed intent；不能用console preferences镜像实际plugin enabled状态。host READY/Web首帧/模型已绑/工具可用分别显示。按钮覆盖正常/禁用/等待权限/失败/取消/UNKNOWN；危险操作展示具体影响和预览，不把救援入口藏进主题。

**验收：** 从DSH页真实操作到owner到效果，长日志脱敏与有界内存、大字体/键盘/返回/深浅色；前端按合同做真实渲染复核。**回退：** 撤新管理UI仍可进入官方DSH，保留资源owner及用户配置；不在main VM添加万能写口。

### D21：session v4、回收站与精准附件修复

**依赖：** D22a、D10a。**Owner/路径：** 数据工作者持DATA session适配及历史fixture；DSH官方session仍唯一业务writer。

**步骤：** 优先用官方API；fallback先锁实际session.v4.jsonl.zstd等格式与reader/writer版本。运行session先settle或拒绝维护。回收是可恢复事务移动/索引同步，保留ID/关联；重复恢复幂等、同名冲突可见；永久删只对回收项显式确认。坏附件只修诊断命中的引用：官方reader读全→差异/备份→完整临时对象→原子提交→官方reader复读→ledger，保留正文/tool call/result/压缩和其他附件。

**验收：** 历史各格式、zstd损坏、未知格式拒修、附件缺一个但其他不变、半途崩溃、回收恢复ID连续、低磁盘。**回退：** 用本事务原件/索引journal恢复；不删整会话、不初始化新home掩盖解析失败。

### D22：维护事务、一致备份、安全模式与自检

**依赖：** D22a需D01/D07m写排他证明/D10a；D22b再需D13/D21。**Owner/路径：** 数据工作者持DATA maintenance/backup与journal；owner准入整合者。

**步骤：** 建maintenance单writer与准入屏障、文件锁序、进度/取消/恢复journal，真实数据流程调用而非空框架。热备份需官方一致快照或完整quiescence；普通导出仅用户数据/声明/锁与metadata，排除账号及所有token/自动备份机密、缓存、ELF和pid/socket。流式归档带大小/条目/hash/版本，恢复staging做路径/zip-slip/空间/冲突/格式校验→保存当前恢复点→受控提交→官方reader重开→commit。SAF引用需重授权。安全模式旁置用户patch/插件加载，保留数据/credential；自检只替换受管manifest明确文件，不删第三方数据。

**验收：** 一致快照、登录committing/profile401删除/用户settings写/插件安装与维护竞争、未遵在线屏障插件的停止后维护、导出无凭据、损坏ZIP/低空间/路径攻击/恢复中断、安全模式进退/坏插件恢复、自检只改目标文件、旧数据缺marker保持可救援。**回退：** journal恢复原快照；跨schema无法回退明示前向修复，禁止clear data。

### D23：用户patch、控制台主题和悬浮球动作

**依赖：** D20a、D22a；设备动作需对应D16–D19。**Owner/写集：** 自定义能力工作者持DATA patch/主题schema与WEB管理，资源和动作调用已有owner。

**步骤：** base源码/受管Android-MCA patch/用户diff三层记录hash；清用户patch只撤该层。同步后三方合并，冲突隔离救援，不字符串replace整树。用户patch不直接替换ELF/DEX/JAR等执行入口；配置的行为变化也做语义预检。主题JSON/ZIP/图片/文案/布局与按钮动作版本化、大小/深度约束、导入导出往返，坏配置回默认。悬浮球click/long/double通过typed action注册表，广权限shell动作单独确认，主题导入不授予执行权。救援面不可移除，定制仅限DSH，不全局换肤。

**验收：** 基线变化冲突、坏patch安全启动、清patch保数据、主题往返、恶意ZIP、动作权限撤销、悬浮开关资源释放。**回退：** 保留上一有效patch/theme与版本，撤本层即可；不覆盖受管升级或用户插件。

### D24：整闭包升级、串行trial与回滚边界

**依赖：** D22b、D13、D07、D11及变更涉及的D12/D18等。**Owner/路径：** 生命周期/打包工作者分RUNTIME payload与STAGE；owner和版本提交整合者。

**步骤：** 候选身份含源码/payload/Node/ELF/addon/依赖/patch/profile schema/锁，不只JS版本。verify→隔离数据trial→停止确认→真实home预检与备份→production health pending→commit。trial与生产同owner串行、任意时刻一个受管宿主；trial无真实账号/用户插件/外部副作用，其通过不替代真实home验证。APK替换nativeLibraryDir后旧JS不一定可回滚，需预打包匹配slot或证实ABI兼容。每一步可掉电恢复，失败不清数据不重放turn。

**验收：** trial启动失败/停不下、commit前后崩溃、插件不兼容、数据schema升级、旧ELF不可得、当前真实home重开；回退后会话/插件引用仍正确。**回退：** 仅兼容闭包及经验证快照，不能二进制能装就宣称数据可降级；不兼容进入RECOVERY_REQUIRED。

### D25：WebView、前后台、通知和恢复语义

**依赖：** D01起可分片，完整结案等D04/D07/D16–D24。**Owner/写集：** WEB工作者持容器交互与前端测试，service/后台生命周期工作者持RUNTIME，Manifest/lease整合者。

**步骤：** 保持官方前端、不二次写聊天状态；页面旋转/renderer death只重建WebView，同Node/session恢复且不重发turn。账号外链与普通外链分开，文件SAF、下载/blob、附件、剪贴板、键盘/返回/滚动/长内容逐项验证。前台服务和通知按真实任务/停止事实，wake lock有owner/期限。自动恢复区分系统死亡与用户停止，持久stop意图/冷却/失败上界；force-stop后不承诺后台自启动。存活Node重绑遵D07，数据损坏进救援不无限重启。

**验收：** 正常返回、旋转、renderer/main/service分死、息屏/后台/系统回收、用户stop后不复活、通知取消与实际树/lease一致；UI固定状态两轮渲染检查。**回退：** 分别回退容器/恢复策略，保留停止fence，不用强杀Node解决白页。

### D26：真实任务、全矩阵、离线与回归

**依赖：** 所有被声明完成的关联卡；从D00持续记账。**Owner/写集：** 独立验收者持新矩阵/fixture/原始证据，不改生产使其适配测试。

**步骤：** 按PARITY_ACCEPTANCE逐行绑定422官方identity+手机版增量+MCA增量；同一证据可服务多行，但每行都有行为断言。官方账号、MCA云、MCA本地分别测多步工具/修错/审批/子Agent/压缩/产物，记录提示、模板、参数和schema hash。协议正确性与模型任务成功率分开，不因离题直接归咎小模型。离线断网覆盖主辅推理和工具依赖；网络工具应明确失败，不偷偷用云。回归聊天本地/云/工具、图片、原生Agent、模型装卸/切换、Local API/取消及历史数据。

**验收：** 运行产物绑定精确候选，缺权限/账号记BLOCKED；“能力实现”与“代表模型质量”各有结果。**回退：** 验收文档不改变产品；失败定位到所属D卡小修后只重测相关及受影响集成，不重跑无关全量。

### D27：最终整合、长期运行与交接

**依赖：** D26和全部完整对标范围。**Owner/写集：** 整合者+独立复核者；最终源码、受控正式构建与新证据，根目录不混并行输入。

**步骤：** 冻结候选输入后串行使用项目构建锁和工具链做适用检查及正式APK，前后fingerprint一致；签名、升级、完整closure登记。真实生产MainActivity从侧栏进入，账号/MCA本地云、五能力、插件、PTY、维护/升级和长期资源运行。先完成至少2小时混合负载整合预检；完整交付再做验收规范REG-14的72小时受控耐久及后台/恢复循环，负载与次数运行前锁定。记录Java/native/Node/browser/帧采集及磁盘峰值/趋势、任务数和失败，不承诺一个数字代表性能；性能阈值以同条件基线和D00预算为准。各分层报告PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE，完整对标必需行无缺口。

**交付/回退：** 交源码diff、可复现来源、当前APK身份、矩阵/原始证据、操作手册、精确未验证项和经验证恢复方法。禁止自评10分替代证据；有缺口只声明对应阶段能力。回退严格按D24已验证闭包/数据路径，不重写历史记录。

## 4. V1成果的复用映射

旧卡是历史拆分，不要按标题重新执行一遍。D00对每一项判`已存在待复测 / 缺实现 / 实现但未接线 / 已有旧证据 / 当前候选已验证`。

| 旧C卡 | V2承接 | 迁移重点 |
| --- | --- | --- |
| C00 | D00、D26 | 保留旧422 identity、旧来源与历史证据 |
| C01 | D01、D25 | 当前停止policy已修，重点验证实际owner链与未知态 |
| C02a/b | D10、D22a | 真实锁/原子发布与实际生产调用 |
| C02c | D02、D11 | 已合并overlay复用；五手机插件按新桥接入，默认审批保留 |
| C03 | D07、D01、D15 | 身份/回执/重绑与完整活动屏障 |
| C04–C06 | D08、D09、D26 | 最终序列化/工具策略/真实终态，不能用旧测试标题判通过 |
| C07/C08 | D11/D12 | V2完整目标包括GNU Bash和真PTY，旧V1降级不是完整结案 |
| C09 | D13、D14 | 官方manager不替换，扩全生态样本 |
| C10 | D06、D07 | holder写入不当Node已刷新 |
| C11 | D15–D19 | 从原少数能力扩指定手机版每tool/action |
| C12 | D22、D24 | 一致维护、整闭包trial、真实数据边界 |
| C13 | D09、D14 | 逐后端/视觉/主辅路由及完整官方内核 |
| C14/C15 | D26/D27 | 新候选不继承旧APK PASS |

## 5. 可直接交给接手Agent的总提示词

```text
任务：实施MCA DSH补齐V2中的【Dxx/子片】，本次仅该写集。

先完整读取docs/architecture/MCA_PROJECT_HARD_LOCK.md及当前适用指令，
再读completion-v2的README、ARCHITECTURE、EXECUTION_PACK、SOURCE_MAP、
PARITY_ACCEPTANCE，并核实当前源码、测试、dirty/untracked与子模块内部diff。
这是一套目标设计，不是已实现报告；不要信旧摘要覆盖源码。

开始时输出：卡号/子片、前置证据、具体文件写集、现有生产调用链、
唯一writer与资源owner、一个失败分支、验证方法和回退点。
你与其他工作者共享代码库；不得覆盖或撤销他人改动。公共契约、runtime owner、
CONFIG、COMPOSE、Manifest、根构建只向整合者提交精确变更建议，预约后合入。

复用官方DSH loop/session/审批/压缩/插件/账号协议；只适配Android执行条件、
MCA模型来源和手机bridge。不得重做Kotlin DSH、另建engine或万能Manager，
不得扩MainUiState任意写口、MainCoordinatorHost、Any依赖注入或feature反向依赖。
保持图片→Agent→DSH及现有其他页面；新console只做DSH作用域的typed投影与intent。

按可运行小片推进。现有实现先复用，只有具体缺口才改；不要生成未接线接口。
不得降target、关闭TLS/审批、静默裁上下文/工具、伪造STOP或能力flag来凑通过。
静态/fixture、真实Android执行、真实模型任务、发布级证据分别结算。
按影响运行真实检查，核对执行数量、退出码与候选fingerprint；构建使用已有锁，
不并发抢Gradle目录，不改历史证据。账号/权限不可用如实记BLOCKED。

授权范围内持续完成本片，不为常规工程选择反复询问；超范围行为先形成可审结果。
交付：改动文件/生产调用者/owner与状态边界/数据协议视觉影响/实际检查和原始证据/
未验证项/已知问题/临时路径退出条件/回退点/下一可接手切片。
没有当前证据就不要写“完全对标”“全部生产可用”或自评满分。
```

仅调研/文档场景在上面第一行替换成“只读分析或文档，不实施”，并去掉构建设备授权。不要把本轮只写方案理解成已经授权后续Agent运行账号、改代码或发布。

## 6. 整合审查提示词与交接格式

```text
只读审查【Dxx】的实际diff与证据。对照项目合同和DSH V2设计，优先找：
生产调用未接线/重复owner/双writer、账号协议编造或token泄露、
旧代回调污染/UNKNOWN重放、MCA lease互锁、假原子/假PTY、
维护中写同home、target28假设照搬、官方插件状态被Kotlin复制、
真实wire丢字段/二次裁历史、旧APK证据复用为当前PASS。
逐问题提供精确文件/行、触发条件、用户后果、最小修复与必要验证。
不改生产、不弱化门槛；无问题时说明实际检查范围及未验证项。
```

每片交接使用：

```text
卡号/子片与状态：
起点HEAD + dirty/untracked fingerprint + 子模块revision/patch + payload：
前置证据与本次具体改动：
生产调用链、唯一writer、资源创建/使用/取消/释放/恢复owner：
功能/默认值/协议/数据/视觉变化：
实际检查命令、tests数量、退出码、范围与原始日志：
当前候选设备/APK身份；账号/模型/权限profile（无秘密）：
PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE逐项结果：
既有问题、新增问题、临时adapter退出条件和例外：
回退点、数据恢复限制、下一切片及未占用写集：
```

一个接手Agent无法由文字保证“完美执行”。本包通过固定边界、小片接线、独立审查和证据门槛降低偏航；最终质量以可重现的当前候选行为为准。

## 7. 验证入口和任务选择

本轮仅做文档静态检查，不执行下表运行任务。实施时先读取当前runner与锁合同，JDK21、SDK/native来源和受管构建输入齐备后再运行。

当前`scripts/verify-code.ps1`的`-FullTests / -AgentTests / -Changed`并不会自动覆盖四个DSH模块；新增目录尚未跟踪时，`-Changed`也不能代替人工写集核对。接手者应复用已存在、经核实的构建锁包装入口，显式选择所需任务；没有覆盖入口时由整合者给既有runner补精确DSH lane并检查真实测试数量，不创造一个不带锁的平行脚本。

| 改动 | 需显式覆盖的现有Gradle任务或检查 | 不能据此声明 |
| --- | --- | --- |
| 契约/capability DTO | `:core:dsh-contract:testDebugUnitTest` | 实际Android动作成功 |
| owner/config/payload/IPC | `:core:dsh-runtime:testDebugUnitTest` | native原语/Node在target36可执行 |
| gateway协议 | `:api:dsh-gateway:testDebugUnitTest` | MCA最终runner已保真、模型任务成功 |
| Web容器/策略 | `:feature:dsh-workspace:testDebugUnitTest` | WebView/登录/真实渲染通过 |
| app adapter/装配 | `:app:testDebugUnitTest`中的真实受影响类；如走`-Tests`须与其他module lane分开 | 所有app和历史兼容通过 |
| 公共inference/engine/云producer | 对应现有模块任务及聊天/Local API受影响行为 | 一个backend代表全部backend |
| 官方/受管JS或native seam | 固定包实际测试命令、Android Node独立进程行为与产物hash；命令从固定package脚本查证 | npm build或JVM fake等于目标设备执行 |
| UI与系统平台 | 真实生产MainActivity及认证API/插件路径，按验收用例记录原始效果 | Preview/stub/adb直接动作代替产品调用链 |
| 全闭包发布 | 项目受控正式构建、同签名升级、当前APK/数据/恢复与长期运行 | 旧APK或debug compile代表当前发布 |

架构检查使用项目已有`tools/architecture/check_architecture_rules.py`，并说明其实际覆盖范围；新增跨进程/模块边界要人工复核自动规则未覆盖的部分。每次运行前后比较完整fingerprint；测试reports须匹配当前run、任务、类和非零数量。命中0类、跳过、旧XML或源码在运行中改变均不能报PASS。
