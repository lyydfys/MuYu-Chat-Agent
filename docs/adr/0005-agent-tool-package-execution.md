# ADR 0005：可执行工具包、隔离脚本与 third-party 兼容边界

- 状态：Proposed（N00 草案；N09–N12 实现中）
- 日期：2026-10-20
- 关联：README §5、DESIGN §5、EXECUTION_PACK N09–N12、PARITY_ACCEPTANCE PKG-01..23、OFF-03
- 参考：third-party `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`（只读行为参考）

## 背景

现有 toolpkg 支持 manifest/ZIP/预览/存储与声明式 host 绑定，但仍是 inert/preview：没有可执行子包、隔离运行时、host RPC 或 third-party 格式转换。上游 ToolPkg 是包含 CommonJS 子包、资源、WASM、模板以及 main/ui/sandbox/provider 上下文的可执行生态，不能以"工具别名列表"冒充。

同时，任意第三方脚本进入主进程会直接获得宿主 UID 的文件、网络与数据访问面，与来源级 egress 和 LOCAL_ONLY 约束冲突。

## 决策

1. **双格式共存**：保留 inert v1 原路径；新增 MCA executable v2（content-addressed 不可变版本、依赖 lock、安装 journal、active pointer 事务切换）。third-party 导入走独立适配器，产出 normalized 包和逐 API 兼容报告（SUPPORTED/TRANSLATED/REQUIRES_HOST_CAPABILITY/UNSUPPORTED），未知项预检阻断而非运行时跳过。
2. **隔离执行**：Android `isolatedProcess` 服务承载固定来源/许可证/ABI/hash 的 QuickJS 类引擎；一活动执行实例一隔离进程（首版硬杀隔离）。服务不初始化 MCA DB/模型/Keystore/主执行根，只收脚本、只读资源句柄、输入、限额和窄 Binder/RPC。
3. **Host 调用仍走统一 pipeline**：脚本内 fetch/文件/设备/shell 全部转换为 host-call 提议，父"运行脚本"许可只允许计算，不覆盖内部副作用。宿主签发不可伪造 session capability，绑定 run/epoch/parentInvocationId/packageHash/methods/scopes/revocation；guest 自报身份只作核对。
4. **资源与限额**：heap/stack/CPU/墙钟/输出/并发实例上界；实例级中断与服务死亡可证；同 UID 进程、token、QuickJS 本身都不构成强沙箱。
5. **上下文分层**：main/ui/sandbox/provider 各有独立 API 暴露表、输入输出和关闭顺序；注册阶段无副作用；UI 用结构化组件或隔离无桥接视图；hook 只能贡献 catalog/context/after-result 观察，不能改权限或重入 dispatch。

## 备选方案

- 把脚本放主进程执行：拒绝。无法提供来源级 egress 与 LOCAL_ONLY 证据。
- 池化多实例：首版拒绝；先证明实例级取消与故障语义，再考虑池化，不能停 A 杀 B 后写"A 已准确停止"。
- 逐字段猜测包类型：拒绝。v1/v2/third-party 由显式格式与版本判定。

## 后果

- 新增最小 `core:agent-script` 实现模块与 App 服务；构建/Manifest/ABI 由整合者维护，来源、许可证、校验和进入 ADR 与依赖清单。
- N09a 先交付静态导入/预检/安装事务，executable 只显示 STAGED/WAITING_RUNTIME_VALIDATION；N09b 在 N10/N11 无副作用验证后才允许 executable active。
- 未完成 WASM/provider/UI 时必须写具体缺口，不能宣布工具包领域完整对标。

## 退出条件

PKG-01..23 中每条声明兼容的样本完成"安装→调用→host 效果→取消→卸载"；隔离探针（fs/FD/socket/UID/进程死亡）有真实设备证据；失败/低磁盘/进程死亡/更新中旧 run 引用均有行为验证。
