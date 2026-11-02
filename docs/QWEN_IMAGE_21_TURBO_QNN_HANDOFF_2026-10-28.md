# Qwen-Image-2.1-Turbo QNN 工作交接

- 记录日期：2026-10-28
- 配套方案：[官方 Turbo QNN 技术方案](QWEN_IMAGE_21_TURBO_QNN_TECHNICAL_PLAN.md)
- 规范入口：[MCA 项目架构与可维护性硬锁](architecture/MCA_PROJECT_HARD_LOCK.md)
- 用途：继续研发的事实交接，不是成品发布说明。包含环境与本地路径，外发前应再次脱敏。

## 1. 接手者先读的结论

1. **没有已完成的官方 Turbo QNN 包。**没有核验成功的官方权重下载、ONNX 导出、PTQ、QNN context 编译或真实设备生图结果。
2. 已完成的是公开资料调查、关键源码语义核验，以及对已登录 MI300X 实例的有限环境探测。
3. 远程 shell 曾返回 Linux、CPU/RAM、ROCm/PyTorch 信息；后续终端与文件读回不稳定，根因未确定。不能说远程开发环境已就绪。
4. 用户随后中断执行，要求“写一份详细的技术方案和交接文档”。本轮交付只创建这两份文档，不继续远程转换、清盘、安装、构建或真机测试。
5. MCA 工作树和多个子模块已有大量未提交修改。本会话没有修改生产源码；不能将当前源码状态等同于干净 HEAD，不能清理或覆盖既有修改。
6. 下一切片是恢复**可验证、可读回、可续作**的远程执行通道，然后核验存储和 QAIRT。不要直接从“量化/编译”开始，也不要把研究任务已完成当作模型目标完成。

### 状态术语

- `CONFIRMED`：本会话工具输出或已读源码确认的事实；注明时点和范围，不等于运行验收 PASS。
- `PROPOSED`：技术选择或计划产物，尚未实现。
- `UNKNOWN`：未取得可靠证据，不能根据预期填值。
- 验证结果使用 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`；一次命令失败仅描述该次检查，不泛化为整个模型不可行。

## 2. 目标、范围和授权边界

原执行目标是：在用户已登录的 MI300X 实例上，按前述方案完成官方 Qwen-Image-2.1-Turbo QNN 包。用户允许清空**该实例的永久存储**。

这一授权不意味着可以：

- 清理本地 MCA 工作树、其他工作区、手机数据或其他实例；
- 复制整个 MCA 私有工作树到公共模型仓库；
- 更改共享权限、公开发布模型/SDK/报告、创建远程 PR 或推送代码；
- 复用 release keystore 给 debug 包签名、关闭校验、跳过测试或修改规范来消除失败。

永久存储清空尚未执行。即使恢复执行，仍须先辨明挂载、实际路径、容量及其中数据用途，避免把 `/mnt/workspace` 和 `/mnt/data` 当成同一个存储。本文不附带一键清盘命令。

用户最新请求切换为文档交付。因此当前不启动新的远程实验；恢复实操以用户后续指令为准。既有制包目标仍未完成，不能标记完成。

## 3. 本地仓库基线

### 3.1 文档编写前重新核对的事实

| 项目 | 结果 |
| --- | --- |
| 工作区 | `E:\MAC` |
| 分支 | `codex/enterprise-refactor` |
| HEAD | `68b6eef1e67870b02bfef6afda2e625d36663da9` |
| 工作树 | 大量 tracked 修改及 untracked 文件；不是干净 checkout |
| `git diff --stat` | 本次快照为 370 files changed、13017 insertions、5959 deletions；不包含全部未跟踪文件及子模块内部 patch |
| 暂存差异摘要 | 本次 `git diff --cached --stat` 无输出；接手时需重查 |
| 本会话生产代码变更 | 无 |
| 本次文档变更 | 新建技术方案与本交接文档 |
| 构建、commit、push | 本次均未执行 |

已运行只读命令：

```bash
git --no-optional-locks status --short
git branch --show-current
git rev-parse HEAD
git diff --stat
git diff --cached --stat
git submodule status
git submodule foreach --recursive 'git --no-optional-locks status --short'
```

根工作树的既有改动涉及 CI、Gradle、manifest、app/feature、脚本等，不属于本次官方 Turbo 制包成果。摘要不能替代接手时的实际 diff 审查。

上下文中恢复过 `D:\ziliao\xuexi\Qoder` 下的旧文件路径；本次基线以 `E:\MAC` 实际文件为准，不把旧目录当当前源码或在那里执行构建。

### 3.2 子模块 revision 与脏状态

| 子模块 | revision | 实际状态 |
| --- | --- | --- |
| `third_party/llama.cpp` | `4ceb1719101f32637b841206c172f3f058ffc182` | 内部有未提交修改，涉及 ggml、量化、模型加载等 |
| `third_party/stable-diffusion.cpp` | `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0` | 内部修改、删除状态及未跟踪 Qwen 文件 |
| `third_party/tokenizers-cpp` | `acbdc5a27ae01ba74cda756f94da698d40f11dfe` | CMake、C/C++ 头和实现、Rust 有修改 |

`git submodule status` 的 revision 前缀不能证明内部干净。本次通过 recursive foreach 看到了内部状态；这些 patch 都是未来构建输入。不要恢复被标记删除的路径、清理未跟踪文件或将它们算作本次新增。

后续如需可复现构建，记录当前差异与未跟踪输入的受控清单、哈希和来源。不得把整个可能含私有文件的工作区自动上传远端。

## 4. 已核实的关键技术结论

### 4.1 官方 Turbo 与既有实现不是一回事

- 官方 Turbo 是完整 checkpoint，官方推荐 8 步、CFG=1，DiT 为 7B/32 层。
- 现有 MCA `QwenImage21TurboSchedule` 处理的是精确 Viggle v0.2.1 LoRA 的 sdcpp 调度，不是官方 Turbo 的身份识别。
- 社区 image21-qnn-8elite 提供有价值的导出和运行工程，但其现成包不是已经验证的官方 Turbo 包。
- 本次检查的来源没有提供可直接认定为 MCA 可用的官方 Turbo QNN 成品；这不是“整个互联网绝对不存在任何包”的证明。

### 4.2 conditioning 的精确锚点

Diffusers 已读 revision：`f23eedf6029aa52c6e578734afd9dc395a39c2d0`。

目标文本向量是**最后 decoder 层后、最终 RMSNorm 前**的 full-token hidden。管线通过 final norm hook 返回输入，再取 `outputs.hidden_states[-1]`；之后去 padding、按动态 `_drop_idx` 去系统前缀、右补零并构造 mask。

文本投影是 zero-centered RMSNorm（weight+1）→ Linear → GELU → Linear。reader/chat KV 与 DiT prefix KV 是两套不同语义。不能用同维度聊天 logits、任意 last hidden、固定 token 截断或 Viggle schedule 代替。

这是本次方案最关键的已核实接口结论；完整证据链接和实施约束见技术方案第 3 节。

### 4.3 reader 复用候选

- Qualcomm 现成 Qwen3-VL-8B-Instruct 聊天包没有被实际下载检查，graph I/O、是否暴露正确 hidden、norm 边界、完整序列和 encoding 都是 `UNKNOWN`。
- 社区图像 reader 源码有 36 层/4×9 分片及 pre-norm residual 输出；所见 `rx4/rex4` 为 `[T,4096]`，T=128/704，运行端裁剪后供图像网络使用。
- 源码存在只说明值得复用调查，没有证明它与官方 Turbo 权重、模板、量化和最终图片完全匹配。
- 建议先使用通过数值验证的官方一致 CPU/GPU reader，或适配社区图像 reader；不先训练小 reader，也不把黑盒聊天包当制包硬前提。

### 4.4 MI300X 的真实价值

适合官方 BF16 参考、真实轨迹采集和误差/质量评估；标准社区 export 主要跑 CPU。没有依据说 QAIRT context 编译会直接利用 MI300X，也没有本机对比数据支持加速倍数。

AIMET 的 ROCm 完整支持尚未验证；不设为必需依赖。社区 QAIRT `2.50.40` 是调查候选，不是当前实例已安装版本，也不代表允许全局升级 MCA runtime。

## 5. MCA 源码接手地图

以下关键入口在编写文档时已重新读取。行号是该工作树快照的定位，后续变更须重查。

| 文件/位置 | 已确认的边界 | 接手时的动作 |
| --- | --- | --- |
| [LocalImageQwenImage21Operations.kt:33](../app/src/main/java/com/muyuchat/mca/LocalImageQwenImage21Operations.kt#L33) | 当前 primary 必须是 bundle 内 `dit.mnn`；固定 MNN profile | 不把离线 QNN 包塞进现有 MNN 身份 |
| [LocalImageQwenImage21Operations.kt:111](../app/src/main/java/com/muyuchat/mca/LocalImageQwenImage21Operations.kt#L111) | NPU/QNN 别名走实验 MNN QNN DiT，encoder/VAE 保持 CPU | 区分配置选择、真实执行与官方 Turbo |
| [LocalImageQwenImage21Operations.kt:305](../app/src/main/java/com/muyuchat/mca/LocalImageQwenImage21Operations.kt#L305) | 取消、异常、finally 卸载/断连/清理路径 | 新路径保留相同业务责任与 request 隔离 |
| [QwenImage21WorkerService.kt:27](../app/src/main/java/com/muyuchat/mca/QwenImage21WorkerService.kt#L27) | 单线程 executor、单 active operation 和 native 状态 | 不从 UI/API 旁路创建第二套资源 |
| [QwenImage21WorkerService.kt:218](../app/src/main/java/com/muyuchat/mca/QwenImage21WorkerService.kt#L218) | 取消匹配 requestId；旧 native 无安全中断时终止自己的 worker | 不能只停止界面回调就报告停止 |
| [QwenImage21WorkerAudit.kt:39](../app/src/main/java/com/muyuchat/mca/QwenImage21WorkerAudit.kt#L39) | prompt hash 在 Kotlin 算，标签为 conditioning_consumed | 不把它说成 native 独立 payload receipt |
| [QwenImage21ExecutionEvidence.kt:80](../app/src/main/java/com/muyuchat/mca/QwenImage21ExecutionEvidence.kt#L80) | schema=`qwen_image_21_mnn_v1`、固定 MNN profile/runtime | 新 profile/schema 明确演进并保护旧 fixture |
| [ImageGenerationApiResponseEvidenceValidation.kt:273](../api/local/src/main/java/com/muyuchat/api/local/ImageGenerationApiResponseEvidenceValidation.kt#L273) | Qwen v1 API 校验 512×512、2–40 步、CFG=1 | 不能仅改标签而沿用不匹配的 ABI |
| [QwenImage21TurboSchedule.kt:31](../app/src/main/java/com/muyuchat/mca/QwenImage21TurboSchedule.kt#L31) | 精确 Viggle LoRA、强度1、sdcpp profile、4–8 步 | 保持独立；官方 schedule 另行绑定 |
| [qnn_executable_graph.hpp:105](../core/native/src/main/cpp/mca/qnn/qnn_executable_graph.hpp#L105) | load_in_session/load/bind_tensors/execute 基础 | 复用前审查实现中的 SD 特定假设 |
| [QwenImage21QnnDeviceSmokeTest.kt:17](../app/src/androidTest/java/com/muyuchat/mca/QwenImage21QnnDeviceSmokeTest.kt#L17) | 旧 MNN bundle、320×320、2步；缺资产/库时 assumeTrue 跳过 | 本次未运行，且不能覆盖官方8步Turbo |
| [vendor README:7](../vendor/qwen-image-2.1/README-MCA.md#L7) | 旧私有 MNN runtime 来源和许可证记录 | 历史文档不覆盖当前源码和实际二进制 |

此前调查还发现：旧 profile 的语言处理、size contract 与 API 声明并非可以无差别复用；QNN library 存在也不证明无 CPU fallback。实现阶段须检查 resolver、catalog、bundle contract、JNI 和对应测试，不将本地图谱当完整实现审计。

旧 runtime 的已记录上游锚点：

- scsonic/libQwenImage21：`93a6f603729505250c1c4bb5b1a6597a0b8b9304`。
- scsonic/MNN：`1965a2632b9d1197dff2ef364d307e40c150a5c7`。
- vendor 文档区分 Apache-2.0 代码与 Qwen 模型研究许可；不能把代码 license 推广到新官方 Turbo 权重。

## 6. MI300X 实例：实际观察与未知项

### 6.1 会话时点环境

下面是用户中断前的观察，不是文档交付时再次连接后的实时状态。

| 项目 | 观察值 | 证据边界 |
| --- | --- | --- |
| 平台 | ModelScope DSW AMD 实例 | 已登录父门户访问 |
| 数字实例号 | `2309692` | 只用于定位本次环境，不是认证信息 |
| 初始状态 | Running | 后续是否仍运行没有再检查 |
| CPU | Linux x86_64，`nproc=23` | 曾取得 shell 输出 |
| RAM | `free -g` 总量约200、available约199 | 当时快照，不是持续可用保证 |
| 界面 GPU | AMD/MI300X 实例，显示192G级显存 | 未拿到完整设备名称与实际显存字节核验 |
| ROCm SMI | vendor AMD；Card Model `0x74b6`、SKU `M3080202`、Subsystem `0x74a2` | 同时出现 libdrm get_name 错误 |
| Python | `3.12.13` | 曾取得解释器输出 |
| torch | `2.12.0+git6bbd260` | 曾取得 import 结果 |
| HIP | `7.2.53211` | torch.version.hip |
| GPU 可用性 | `torch.cuda.is_available() == True`，device_count=1 | ROCm 仍使用 torch.cuda 命名，不代表 NVIDIA |
| 镜像标识 | `1.40.1-pytorch2.12.0-gpu-py312-rocm7.2-ubuntu22.04` | 另一元数据表示含 rocm7.2.3；不是完整运行锁文件 |
| 局部 Python 包 | onnx1.22.0、onnx-ir1.0.0、onnxscript0.7.1、onnxslim0.1.96、transformers5.15.1 | 仅局部 pip 输出，不代表工具链完整 |
| diffusers、QAIRT、AIMET | 未确认安装 | 不得报告已就绪 |

实例部分元数据的 `GpuModel` 曾显示 `NVIDIA-V100-16G`，与 AMD 实例、ROCm 输出和192G界面矛盾。应视为不可靠元数据；下一次以实际设备查询核实，不使用这个字段选择工具链。

### 6.2 存储与联网

| 项目 | 当前认识 |
| --- | --- |
| `/mnt/data/` | 实例元数据显示 dataset RW 挂载；永久存储候选，尚未通过 findmnt/df 核验完整关系 |
| `/mnt/workspace` | 成功 shell 的工作目录；其持久性、容量及与 `/mnt/data` 关系未知 |
| ModelScope cache | 元数据为 `/mnt/workspace/.cache/modelscope`；实际占用未知 |
| 永久存储清理 | 未执行 |
| Hugging Face | 一次约8秒 curl 请求得到 http_code000；仅能说该请求失败 |
| ModelScope | 一次请求返回302；不代表大模型文件可下载、可续传或hash正确 |
| GitHub | 实例内检查没有可靠返回；联网状态未知 |

不要把一次 timeout 写成“HF 永久不可访问”，也不要把302写成“镜像下载成功”。长下载前用小配置文件验证重定向、TLS、访问权限、校验和及续传行为。

初始实例剩余时间约7小时56分；有限探测约消耗半小时。最后已知页面 reload 在 `2026-10-28T05:48:05Z` 附近。当前剩余配额没有复查，这些数字不能作为下一次运行预算。

## 7. 浏览器/IDE 接入：有效发现与失效边界

### 7.1 正确页面关系

入口：[ModelScope 工作区](https://www.modelscope.cn/code/workspace)。

页面结构是父门户 → 同源 notebook iframe → Hermes IDE → DSW remote connection，不是可以直接套标准 VS Code 快捷键或 Jupyter REST 的普通 code-server 页面。

父页面通过 `postMessage` 的实例/认证初始化，将远端信息传给 iframe。**把 iframe 的 notebook URL 单独导航成顶层页面，会丢失这条初始化链。**曾由此出现空 workspace、终端方法未实现和本地文件 provider 错误。

已连接时观察到 remote authority 的形式为 `2309692-proxy-9889.dsw-gateway-cn-hangzhou.data.aliyun.com`；续作应从当前环境服务读取，不能长期硬编码。

### 7.2 曾成功取得服务的方法

下面是本次 Hermes bundle 中曾有效的 DI 获取方式，不是平台承诺稳定的公开 API。需要在保留父门户、iframe 已经完成实例连接的上下文中运行；这不是建议的长期自动化接口。

```javascript
const frame = document.querySelector('iframe');
const cw = frame.contentWindow;
const workbench = cw.__hermes.workbench;
const services = await workbench.getServices();
const shell = await workbench.shell$;
const instantiation = shell.workbench.instantiationService;
const getService = name => instantiation.invokeFunction(
  accessor => accessor.get(services.serviceIds.get(name))
);
const terminalService = getService('terminalService');
```

关键细节：

- `getServices()`、`getCommandRegistry()` 是异步；command collection 是 Map。
- `serviceIds` 中是 DI 标识，不是服务实例。
- 给 `getCoreService` 传字符串或 decorator 的多种尝试曾报错；通过 instantiation accessor 才取得正确服务。
- `createTerminal` 曾创建 root bash，并返回 `SHELL_READY`、uname/nproc/free 输出。之后并非每次创建都成功取得 PID 或输出。

曾使用的终端参数为 name=`agent`、cwd=`/mnt/workspace`、shellPath=`/bin/bash`、shellArgs=`['-l']`。如果恢复后再用它，只保留一个 `onData` listener，保存其 disposable、terminal 标识和输出 buffer；不要每次探测叠加订阅。

**没有提供一段“粘贴就保证可用”的浏览器执行器。**已知服务访问方式不能替代 processId、sentinel、退出码及文件读回验证。

### 7.3 后续未稳定恢复的表现

- terminal 对象返回，但 processId/title/output 为空或不更新。
- `sendText` 后不能可靠读回结果；曾发生未保存 terminal 引用，后续调用 undefined 的问题。
- 多次 listener 共用 buffer 导致同一输出重复3–6次，不能据此断言命令执行了3–6次。
- file service 读取远端 URI 超时；provider 已注册不等于传输可用。
- 浏览器 evaluate 存在约15秒级调用超时，里面长等待会导致结果丢失。
- screenshot 返回 `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`，并显示 visible=false、attached=true、cdpAttached=true、visibilityState=hidden。
- 已清理当时由本 agent 创建的两个 terminal，并尝试重建；没有稳定解决问题。
- reload 会重建 iframe、清空 window 上临时对象和 listener。最后一次操作就是 reload 父页面，之后用户中断，没有检查恢复结果。

上述只能证明执行/读回链不稳定。隐藏标签页节流、PTY 容量、后端失效等原因均未被证明。此前“终端后端已经失效”的表述过强，应以这里的有限结论为准。

### 7.4 已失败或未证明有效的路径

| 尝试 | 结果 | 不应重复的误判 |
| --- | --- | --- |
| Ctrl+反引号、Ctrl+Shift+P、合成键盘事件 | 没打开预期终端/命令面板 | 不据此认为标准 VS Code 操作已经生效 |
| 独立打开 notebook iframe URL | 缺父页面初始化，空 workspace/方法未实现 | 不绕过父门户直接复用 |
| `/notebook/api/contents`、`/notebook/api/kernels` | 返回 SPA HTML200 | HTTP200不是Jupyter JSON API |
| `/api/v1/notebook/contents` | 404 | 没有找到可用的 Jupyter contents API |
| Jupyter裸URL、authCode登录URL、猜测token查询参数 | 重定向阿里云账户登录 | 魔搭登录不等于网关网页认证可直接复用 |
| `file://` 直接指向 `/mnt/...` | No file system handle registered | 指向浏览器本地 provider，不代表远端文件 |
| remote file URI | 可以构造，readFile曾超时 | 不能称为已跑通远端读写 |
| 很长的嵌套引号 shell 命令 | syntax error near unexpected token '(' | JSON字符串转义不是shell安全转义 |
| base64脚本后接 PROBE_DONE | 有完成marker，但脚本曾含错误且缺可靠退出码 | marker不等于所有检查通过 |

续作优先恢复可见、受支持的终端，或使用用户提供且授权的 SSH/Jupyter 正式接入。若仍只能通过 Hermes 临时接口，限定最小探测范围；不要无限猜 API、认证 URL 或反复刷新。

## 8. 实际文件与副作用清单

### 8.1 本地

| 路径/对象 | 状态 |
| --- | --- |
| `E:\MAC\docs\QWEN_IMAGE_21_TURBO_QNN_TECHNICAL_PLAN.md` | 本次文档交付新增 |
| `E:\MAC\docs\QWEN_IMAGE_21_TURBO_QNN_HANDOFF_2026-10-28.md` | 本次文档交付新增 |
| `E:\MAC\.tmp\qwen-npu\mca-test.apk` | 前半会话从设备拉取的 instrumentation APK；当时记录1370229 bytes；未据此证明DEX内测试存在，也未执行它 |
| `.tmp/qwen-npu` | 本会话创建的 scratch 目录；当前不清理、不上传 |
| 生产源码、构建脚本、测试、配置 | 本会话没有为官方Turbo任务改动 |

scratch APK 的当前存在性和大小未在本次文档阶段重查；这是前半会话记录，不是新一轮校验结论。它不是官方 Turbo 的构建产物，不应混入交付包。

### 8.2 远端

| 路径 | 实际证据 | 接手动作 |
| --- | --- | --- |
| `/mnt/workspace/env.log` | 已发送创建/覆盖命令，未可靠读取完整内容 | 恢复后只读检查存在性、size/hash与内容 |
| `/mnt/workspace/probe.sh` | 曾通过命令生成探测脚本；存在转义错误版本 | 不作为正式构建入口，先审核内容 |
| `/mnt/workspace/probe.log` | 有相关作业Done/marker输出，但完整日志和exitcode未知 | 不把日志名当成功证明 |
| `/mnt/workspace/agent_probe.txt` | IDE写入报 No file system handle registered | 没有成功创建证据 |
| `/tmp/pt` | 曾发送包含 pip download qai_appbuilder 的命令，但有shell语法问题 | 是否创建或下载任何文件未知 |
| 官方权重、QAIRT、ONNX、QNN contexts、manifest | 无已验证产物路径或hash | 不得报告存在成品 |

页面 reload 不证明远端进程被终止。本次没有取得完整进程清单，但也没有证据表明模型下载/转换任务已成功启动或正在后台运行。接手后检查本任务进程，不要因不认识就终止其他用户作业。

### 8.3 证据记录位置与保存限制

原始工具记录在本机会话目录：

`C:\Users\example\.qoder\tmp\E--MAC\tool-outputs\session-75a9d5df-4c4e-40fd-a6a1-3a0f1bd9c56c\`

其中：

- `b6km2zylt.output`：此前仓库/子模块状态输出。
- `bc4fmoq0l.output`：通过 gh 读取官方 README 的输出。
- `bd4yidle8.output`：文档阶段重新核对的仓库/子模块状态输出。

这些是本地工具记录，不是可移植交付依赖；未把整份原始记录复制到 docs。远端环境结论来自当时浏览器/终端工具输出，没有形成完整、经过校验的环境归档。

**敏感信息说明：**此前一次过宽的 localStorage/实例响应输出使会话类值、authCode 和 metrics ingestion key 出现在工具记录中。本交接不复制任何值，也不包含可用认证 URL。不要转发原始会话日志；根据这些凭据的权限、有效期和仍然有效的会话，评估注销/轮换。后续只输出白名单字段，避免完整环境变量、cookie、storage 或token API响应。

## 9. 接手后的执行顺序

以下是恢复实操后的 runbook，不是本次文档交付已经执行的命令。

### 第一步：重建事实基线，不重复大规模盲探

1. 阅读规范、技术方案与本交接；重查本地分支/HEAD/dirty/submodule。
2. 从已登录父门户确认当前实例、剩余配额、运行状态；不假定旧 iframe 全局对象仍存在。
3. 用一次最小命令确认 shell PID、cwd、sentinel和退出码；再做两次独立短命令，确认输出不重复、不丢失。
4. 在已确认的工作目录新建本任务小型探测文件，读回内容和hash；重连后再读一次。
5. 仍失败则先解决访问通道或取得用户授权的正式连接方式。不要把“重新发了命令”当作推进到下一阶段。

建议读取的非敏感环境信息：

```bash
pwd
uname -a
nproc
free -g
findmnt -T /mnt/data
findmnt -T /mnt/workspace
df -hT /mnt/data /mnt/workspace
```

GPU查询模板如下，尚未作为完整脚本在该实例成功执行；恢复通道后应单独运行、记录退出码，不再套多层字符串拼接：

```bash
python - <<'PY'
import json
import sys
import torch

available = torch.cuda.is_available()
result = {
    "python": sys.version.split()[0],
    "torch": torch.__version__,
    "hip": torch.version.hip,
    "gpu_available": available,
    "gpu_count": torch.cuda.device_count(),
}
if available:
    device = torch.cuda.get_device_properties(0)
    result["gpu_name"] = device.name
    result["gpu_memory_bytes"] = device.total_memory
print(json.dumps(result, ensure_ascii=False))
PY
```

这只检查 GPU 环境，既不安装依赖也不证明官方模型可运行。torch 的版本串和设备查询结果需要纳入后续环境锁文件。

### 第二步：存储与工具链门禁

1. 识别 `/mnt/data` 与 `/mnt/workspace`，核对是否永久、挂载容量、剩余空间及用户旧数据。
2. 优先在已确认的存储中新建独立任务目录；若需要使用清空授权，限定到确切目标并记录删除范围，不附带清理本地项目。
3. 核实合法 QAIRT 安装包/许可；建立独立 compiler/export 环境。确认 Python、OS、SDK host依赖，不直接改坏预装ROCm环境。
4. 保存 converter/context generator 的版本和帮助输出；运行最小工具链测试。
5. 检查手机侧可用 runtime 与编译目标组合；不要把社区版本自动部署进 MCA。

**本步骤交付：**脱敏 `environment`、storage、SDK版本与最小测试报告；任何未知都仍写UNKNOWN/BLOCKED。

### 第三步：来源与参考，先一张图

1. 固定官方Turbo仓库revision，下载并校验模型索引、配置、tokenizer、reader、VAE与DiT。
2. 网络不稳时先定位小文件失败原因；镜像文件要核验真实来源，不改拿Viggle包“先交付”。
3. 安装并锁定已选reference栈；使用官方8步、CFG1、512×512进行一次参考生图。
4. 保存同噪声、有效schedule、pre-norm hidden、projection、8步预测/latent和最终图片。
5. 初始样本跑通后才扩大调试集、校准集和导出量。

**本步骤交付：**source lock、license记录、首张参考图与逐阶段张量；不是QNN成功。

### 第四步：按技术方案 G3–G7 构建可用包

- 适配社区导出器而不是把旧成品改名。
- 先float parity，再真实单block QNN，再真实8步轨迹PTQ和完整分图。
- 每次编译绑定输入hash、脚本patch、SDK和输出；原始错误保留。
- reader/VAE可以先用已验证CPU/GPU版本，但最终包闭环必须实时接受设备prompt。
- 图I/O、encoding、cache、scheduler和实际后端都要在manifest/报告中明确。
- 目标设备的执行、质量、性能、内存及释放完成后，才报告“可用QNN模型包”。

### 第五步：MCA 集成另立验收记录

先定位共享执行owner和协议演进，再修改最小生产链路；遵守构建锁/JDK21和debug签名约束。不能在多个agent间并发写共享Gradle/native输出。

生产MainActivity、认证Local API、取消、worker死亡、模型切换、下载/导入/删除、旧后端代表模型分别验证。模型包能运行不等于这些入口自动完成。

如涉及安装、签名、手机数据、远端发布等超出当前明确范围的动作，按实际授权重新确认。不得用清应用数据或另换applicationId逃避升级兼容验证。

## 10. 后续任务分工建议

| 任务 | 主要责任 | 前置 | 验收输出 |
| --- | --- | --- | --- |
| A 环境/来源 | 实例通道、存储、SDK、source lock | 恢复实操授权 | 可复现小命令、版本、hash、license |
| B 官方参考 | reader/DiT/scheduler/VAE轨迹 | A的权重与reference环境 | reference tensors、cases、图片 |
| C 导出/量化 | 社区工程适配、float parity、PTQ、context | B接口合同；最小SDK通道 | 图ABI、encoding、误差与构建日志 |
| D Android验证 | loader、真HTP、完整pipeline、资源 | C可加载切片 | native执行及完整E2E证据 |
| E MCA集成 | profile/catalog/owner/API/UI | 已稳定组件ABI和D证据 | 生产入口与兼容回归 |
| 整合负责人 | 统一合同、状态、来源及完成判断 | 全阶段 | 不夸大范围的最终交付报告 |

可并行做独立源码/许可证审查、reference准备、SDK最小检查；不要在conditioning/schedule未稳定前让多个工位各写一套接口。社区研究结论由整合者核验，不能把代理摘要直接当PASS。

## 11. 当前验证账本

| 工作项 | 状态 | 说明 |
| --- | --- | --- |
| 官方/社区资料与关键conditioning源码调查 | CONFIRMED | 文献与源码层面，不是模型运行 |
| 文档阶段仓库/子模块基线 | CONFIRMED | 已重查，dirty状态保留 |
| 初始远程shell环境探测 | CONFIRMED（部分） | 有资源/torch输出，不含完整environment归档 |
| 稳定远程命令与文件读回 | BLOCKED | 后续未稳定恢复，根因未知；中断后未重试 |
| HF单次网络探测 | FAIL（单次） | http_code000；不能泛化成永久网络结论 |
| 实际GPU名称、显存、磁盘容量 | UNKNOWN | 待最小探测补齐 |
| QAIRT安装/版本/许可 | UNKNOWN | 没有可靠确认 |
| 永久存储清空 | NOT_RUN | 有过授权，但未执行 |
| 官方权重完整下载与hash | NOT_RUN | 没有成功证据 |
| MI300X官方BF16参考 | NOT_RUN | 未启动已确认的模型推理 |
| reader/DiT/缓存/scheduler浮点验证 | NOT_RUN | 只有设计和源码语义调查 |
| 校准/PTQ/ONNX/QNN编译 | NOT_RUN | 无产物hash与日志 |
| Android单图或完整官方Turbo生图 | NOT_RUN | 旧MNN smoke也没有在本轮运行 |
| MCA官方Turbo接线及生产UI/API | NOT_RUN | 未改生产代码 |
| 构建/签名/安装/发布 | NOT_RUN | 文档阶段不需要这些验证 |

不要将 UNKNOWN 自动转成FAIL，也不要因为NOT_RUN没有报错就写PASS。只有真实产物和对应执行证据可以推进账本状态。

## 12. 交付审查与下一切片

本文与技术方案是本次新增写集；不变更业务规则、执行owner、全局状态、协议、数据或界面。无需为文档启动完整Gradle/native构建；适用检查是链接、源码定位、证据一致性、diff与敏感信息。

文档级机械检查已通过：25处本地链接及源码行号范围、Markdown代码围栏、两文件的diff空白检查、5个代码块的离线语法检查；与文档编写前status快照相比，仅新增这两份文档。示例没有实际执行，语法通过不代表远端可用；外部资料链接未在本次文档阶段重新联网检查。

回退仅需处理本次新增的两份文档，不涉及模型、应用数据或既有源码；不得为“回退本任务”恢复整个dirty工作树。远端遗留探测文件和本地scratch APK另按第8节识别，不自动删除。

接手者的第一个可交付切片是：**稳定执行和文件读回 + storage/QAIRT实际清单**。在它完成前，不宣称环境就绪；其后依次完成官方reference、float parity、QNN最小图、完整编译与设备验证。

最终必须交付：可追溯完整模型包、构建脚本与输入锁、graph/conditioning/scheduler合同、数值与质量报告、设备执行和资源证据；若完成MCA接入，再附当前APK与生产UI/API回归。两份文档不替代这些产物。
