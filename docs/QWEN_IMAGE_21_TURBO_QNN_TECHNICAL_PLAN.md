# Qwen-Image-2.1-Turbo 官方权重 QNN 技术方案

- 日期：2026-10-28
- 性质：实施设计，不是转换完成报告。本文没有附带已生成的 QNN 模型包。
- 配套文档：[实际进展与交接](QWEN_IMAGE_21_TURBO_QNN_HANDOFF_2026-10-28.md)
- 工程约束：[MCA 项目架构与可维护性硬锁](architecture/MCA_PROJECT_HARD_LOCK.md)

## 1. 决策摘要

**推荐路线：用 MI300X 建立官方 Turbo 的参考推理和校准基线，适配已有社区 Qwen 图像导出工程，在 Linux x86-64 CPU 上完成 QAIRT 转换与 HTP context 编译，最后用 Android 真机验证。**

第一版采用混合执行：官方语义一致的 Qwen3-VL-8B conditioning → QNN/HTP Turbo DiT → 官方 VAE。文本编码器和 VAE 可以先使用通过数值验证的 CPU/GPU 实现；不将“所有阶段都在 NPU”设为初版前提，也不把这个混合管线宣传为全 NPU。

实施顺序必须是：来源与环境锁定 → 官方参考 → 浮点语义等价 → 最小 QNN 图验证 → 量化与全链编译 → 真机完整生图 → MCA 集成与回归。不要先批量转换全部权重，最后才检查 tokenizer、隐藏状态和采样器是否一致。

三个交付层级不能混淆：

| 层级 | 最低含义 | 不能声称的内容 |
| --- | --- | --- |
| 编译产物 | converter/context generator 成功，文件与元数据可检查 | 尚不能声称 NPU 执行或生图成功 |
| 可用模型包 | 来源可追溯、可复现构建、完整组件闭包、目标设备实时提示词到图片、数值及质量证据 | 尚不能声称 MCA 生产 UI/API 已接入 |
| MCA 产品接入 | 同一包在生产业务链经 UI、认证 Local API、取消、资源释放和兼容回归验证 | 不能以独立 harness 或 debug Activity 替代 |

当前状态：技术资料与源码已调查；官方权重下载、参考运行、导出、量化、QNN 编译、目标设备执行均未完成。MI300X 远程执行通道曾部分可用，后续未稳定恢复。

## 2. 事实、假设和目标范围

### 2.1 模型身份

| 对象 | 已核实的性质 | 本方案中的角色 |
| --- | --- | --- |
| 官方 Qwen-Image-2.1-Turbo | 官方 README 公布于 2026-10-09；完整加速 checkpoint；7B、32 层 Single-Stream DiT；推荐 8 步、CFG=1 | 唯一目标权重身份 |
| Qwen-Image-2.1 base + Viggle v0.2.1 | LoRA 蒸馏方案，含 4–8 步调度 | 独立路线，不冒充官方 Turbo |
| 社区 image21-qnn-8elite | 已公开导出、分图及运行工程；其既有包以 base + Viggle 4-step 路线为基础 | 复用工程方法，不直接作为官方 Turbo 成品 |
| 现有 scsonic/evankuo MNN 路线 | MCA 已有独立 MNN worker；调查中的 Turbo 派生不能等同于官方全权重 | 旧能力保持，必要时作为实现参照 |
| Qualcomm Qwen3-VL-8B-Instruct 包 | 公开用途是 Genie/GenieX 聊天；本次没有检查下载后的 context graph I/O | 有限范围评估 reader 复用，不设为硬依赖 |

官方 README 和可变 `main` 页面只能作为调查证据。真正构建前必须锁定模型仓库 revision、逐文件 SHA-256、配置和许可证原文；当前没有完成这一步。

### 2.2 首个可验证切片

- 文生图，batch=1，512×512，官方 8 步采样，CFG=1。
- 不使用负面提示词、LoRA、textual inversion、编辑输入、ControlNet、UltraFix 或自定义 flow shift。
- 使用官方一致的 tokenizer、文本模板、Qwen3-VL-8B 隐藏状态语义、文本投影和 VAE。
- 支持实时输入提示词，不依赖开发机预计算 embedding 才能完成最终验收。
- 512×512 是降低导出和调试成本的工程切片，不是对官方约 2K 输出能力的完整复现，也不代表所有尺寸已可用。
- 官方模型的其他能力留待独立扩展；不得因此缩减 MCA 已有其他模型和后端的功能。

初版不做：小 reader 训练、2B 替代 8B、轻量 VAE 替代官方 VAE、Viggle schedule 混用、全局模型硬链接/去重重构、全应用 QAIRT 升级、无依据的自动调优。

### 2.3 成功的核心定义

同一份可追溯官方 Turbo 权重，经已验证的改写、量化和编译，能在目标 Android 设备上用**当次真实提示词**产生通过质量检查的图片，并证明 Turbo DiT 图确实完成 HTP 执行；记录每阶段实际后端、调度、输入绑定、输出和资源释放。PNG 存在、后端选择为 QNN、库中存在 QNN 符号，都不够。

## 3. 最重要的数值契约

### 3.1 固定官方参考实现

已核验的 Diffusers 源码锚点：

- revision：`f23eedf6029aa52c6e578734afd9dc395a39c2d0`。
- [pipeline_qwenimage21.py](https://github.com/huggingface/diffusers/blob/f23eedf6029aa52c6e578734afd9dc395a39c2d0/src/diffusers/pipelines/qwenimage21/pipeline_qwenimage21.py)。
- [transformer_qwenimage21.py 的文本投影](https://github.com/huggingface/diffusers/blob/f23eedf6029aa52c6e578734afd9dc395a39c2d0/src/diffusers/models/transformers/transformer_qwenimage21.py#L169-L200)。

这个 revision 是已读源码的事实锚点，不代表已经在 MI300X 安装运行。还须锁定兼容的 Transformers、PyTorch、tokenizer/config 版本；不能只固定 Diffusers 而让依赖漂移。

### 3.2 文本编码不是“让聊天模型返回向量”

已核验 `_get_qwen_prompt_embeds` 的关键行为：

1. 使用官方模板构造 system/user/assistant 序列；调查中的 system 文本为 `Comprehend and analyze the provided prompt.`。
2. 输入包含 `input_ids`、`attention_mask`，请求 `output_hidden_states=True`。
3. 管线通过 hook 绕过文本模型最终 RMSNorm，取 `outputs.hidden_states[-1]`。
4. 因此目标是**最后 decoder 层之后、最终 RMSNorm 之前的完整 token 隐藏状态**，不是 logits、生成文字，也不是任意库定义的 `last_hidden_state`。
5. 先按 attention mask 去 padding，再按动态 `_drop_idx` 去系统前缀；之后右侧补零，并生成 `encoder_attention_mask` 和 `image_pad_mask`。
6. 文本隐藏宽度为 4096；序列长度、前缀裁剪、mask 和布局必须取自实际 trace，不能因为形状看似相同就判定等价。
7. 调查源码没有提供可以照搬的固定 128/512/704 token 产品截断规则；导出固定长度图必须显式设计长度分桶与越界处理。

参考实现的关键片段如下；这是语义说明，不是已经验证的独立导出脚本：

```python
forward_kwargs = {
    "input_ids": model_inputs.input_ids,
    "attention_mask": model_inputs.attention_mask,
    "output_hidden_states": True,
}
text_model = getattr(self.text_encoder.model, "language_model", self.text_encoder.model)
handle = text_model.norm.register_forward_hook(lambda module, args, output: args[0])
try:
    outputs = self.text_encoder(**forward_kwargs)
finally:
    handle.remove()
hidden_states = outputs.hidden_states[-1]
```

不要将这段 hook 无条件移植到不同 Transformers 版本。必须用中间张量确认该版本是否已在内部处理 final norm，避免重复去除、遗漏或捕获错误层。

### 3.3 文本投影、DiT 和缓存

官方 `QwenImage21TextProjection` 包含 zero-centered RMSNorm（权重使用 `weight + 1`）→ Linear → GELU → Linear。导出时应逐项核验 norm epsilon、权重偏置、GELU 形式、layout 和精度，不要用“普通 RMSNorm + 同维度 Linear”近似替换。

必须区分两类缓存：

- **reader/chat KV cache**：文本 decoder 的缓存策略。
- **DiT 文本前缀 KV cache**：图像去噪网络缓存不随每步变化的前缀部分。

后者的生成、token 顺序、mask、RoPE、返回长度及每步使用时机都属于模型 ABI。社区或 MNN 的缓存名字相似，不证明语义相同。先比较无缓存的完整前向，再比较缓存路径，防止把缓存错误误判成量化损失。

对于 32 层 DiT，还须锁定 latent pack/unpack、图像与文本位置编码、attention mask、timestep embedding、调制与残差路径、最终输出投影。具体 tensor 名称和尺寸从 pinned reference 导出，不能由现有 SD/SDXL runner 猜测。

### 3.4 官方 Turbo 调度

官方 checkpoint 保存推荐 sigma 配置，支持该配置的 Diffusers 会读取。仅指定 `num_inference_steps` 不代表已经覆盖或重现保存的 Turbo schedule。

必须从实际参考运行保存：

- scheduler 类、完整配置及哈希；
- 实际 timesteps、有效 sigmas、终止项和它们的长度；
- 分辨率相关 shift、时间缩放及其他实际生效参数；
- 每步模型输入、预测结果和 scheduler 更新后的 latent；
- CFG 是否实际关闭、是否发生无条件分支、最终执行次数。

CPU 调度实现必须与这些实际序列逐步比对。本文不提供未经运行核实的“官方八个 sigma 数字”，也不从 Viggle 的 4/6/8 步数组推导它们。模型、scheduler config 与实际 sigma 序列应一起绑定进包和运行证据。

跨 ROCm、CPU 和 Android 的比较要共享**同一个初始噪声张量**，而不只是同一个 seed；不同 RNG 和 dtype 可能生成不同噪声。

## 4. MI300X、CPU 与手机的分工

```text
官方模型与配置 ───────────────→ 来源/许可证/哈希锁定
                                      │
MI300X + ROCm：官方参考、真实轨迹、误差及质量评估
                                      │
x86-64 CPU：图改写、ONNX、QAIRT 转换、HTP context 编译
                                      │
Android：context 加载、真实 HTP 执行、内存/耗时/取消验证
                                      │
MCA：共享执行 owner → UI / 认证 Local API / 生命周期回归
```

MI300X 单卡官方规格为 192GB HBM3；1.5TB 不是单卡显存。它最有价值的地方是容纳大模型与参考张量，减少 offload，并提高参考/校准/评估吞吐。

不能承诺它让所有阶段变快：社区构建指南明确标准 exports 在 CPU 上运行；converter、context generator 往往受 CPU、RAM、磁盘和 SDK 实现约束。本次没有测量本机基线、MI300X 吞吐或编译耗时，因此不提供速度倍数和保证工期。

### 4.1 环境隔离

| 环境 | 用途 | 锁定要求 |
| --- | --- | --- |
| reference 环境 | ROCm/PyTorch、Diffusers、官方权重参考与轨迹 | ROCm、torch build、Python、Transformers、Diffusers、attention 实现、dtype |
| export 环境 | 改写、ONNX、量化实验与 QAIRT Python 依赖 | SDK 支持的 Python/OS/依赖矩阵，不默认与 reference 共用 Python 3.12 |
| compiler 环境 | qairt-converter、context generator、host 工具 | QAIRT 精确版本、工具哈希、目标参数、glibc/编译依赖 |
| Android runtime | System/HTP、transport、native runner | 与编译产物相容的一套实际 runtime、ABI、来源和库哈希 |

调查中的社区 QAIRT 版本为 `2.50.40`，只作为候选起点。不能因为社区使用该版本，就覆盖 MCA 已有其他 QNN/LiteRT runtime；先验证独立组合，再讨论受影响范围。

AIMET 不是强制前置。已读安装资料以 CUDA 验证为主，没有取得完整官方 ROCm 支持保证；也不能反向断言其所有纯 Python/PyTorch 路径都不可能运行。首轮优先采用可复现的社区/QAIRT 支持流程，需要 AIMET 时再建立单独兼容性实验。

### 4.2 资源建议与当前实例

建议工作盘 500GB–1TB，RAM 128GB 起、并行较多时 256GB，CPU 约 32 可用核作为准备目标；这些不是测出的硬门槛。社区报告 Qwen 导出需要 64GB 以上 RAM 和约 100GB 临时 ONNX 空间，不能将该数字当成本方案完整磁盘预算。

当前实例曾实际输出 `nproc=23`、`free -g` 总量约 200；torch 识别 1 个可用 HIP 设备。实际 GPU 名称/显存、磁盘容量和永久挂载关系仍需重新采集。详细版本与访问问题见交接文档。

磁盘预算至少分开计算：原始 checkpoint、reader/VAE、参考输出、ONNX external data、量化中间文件、QNN contexts、验证样本及打包临时副本。按阶段检查剩余空间，禁止清理唯一原始证据来换取表面继续运行。

## 5. reader 复用的决策流程

### 5.1 Qualcomm 现成包

[Qualcomm AI Hub 模型页](https://aihub.qualcomm.com/models/qwen3_vl_8b_instruct)与[模型仓库](https://huggingface.co/qualcomm/Qwen3-VL-8B-Instruct)证明现成聊天包存在，但本次未下载并检查其二进制图 ABI。

复用前必须回答：

1. 哪一张图、哪个输出暴露 full-token hidden，而不只是最后 token 或 logits？
2. 输出在 final RMSNorm 前还是后？是否已经过额外变换？
3. decoder 权重、config、词表、模板和 special token 是否与官方图像管线相同？
4. 分块 prefill 能否重建完整 token 序列与 mask？量化 encoding 如何解码？
5. 可否在合法分发和 runtime 合同下把它作为图像依赖组件？
6. 与 pinned reference 在独立验证集上的 conditioning 和最终图像差异是否可接受？

若没有可访问且语义正确的中间输出，转为图像专用 reader 导出。公开文档未证明可用，不等于已经证明二进制绝对不可用；但不能无限期围绕一个未经验证的黑盒适配。

### 5.2 社区图像 reader

已调查的 [export_reader.py](https://huggingface.co/sebekblessing/image21-qnn-8elite/blob/main/build/qwen-image/export_reader.py) 和 [qimg_reader.py](https://huggingface.co/sebekblessing/image21-qnn-8elite/blob/main/build/qwen-image/qimg_reader.py) 提供图像所需的 reader 导出思路：36 层按 4×9 分片，末端反旋转，输出 `rx4/rex4` 的 `[T,4096]` residual，所见配置为 T=128/704，没有 final norm/lm_head。

[社区运行端](https://github.com/adiirfan/anima-android/blob/ee02f8ac198a5ef8722b9fffbcd19f86904932ce/app/src/main/cpp/qimg.cpp#L405-L419)确实裁剪 reader 输出形成图像 conditioning。这使它比“只知道存在聊天包”更值得先调查，但还不是官方 Turbo 数值等价证据。

复用策略：保留官方 8B 权重与模板；核验分片、反旋转和输出位置；优先做 float parity，再评估量化。2B reader + trained projection 是另一个模型近似，不作为本次官方一致方案的默认替代。

### 5.3 组件是否独立成包

逻辑上分离 reader/tokenizer、Turbo DiT、VAE；可以作为一个可安装 bundle 的三个组件交付。只有字节哈希、配置、接口和许可证都匹配时，才允许多个 bundle 引用同一 reader/VAE。

第一版不要求建立跨 bundle 内容寻址仓库或 Android 硬链接机制。优先完整、可校验、可卸载的组件闭包；避免为了省磁盘同时重构下载、删除和资源引用计数。

## 6. 分阶段实施与退出门槛

### G0：恢复可靠工作环境

工作：确认用户已授权实例、稳定执行通道、永久与临时目录、可用磁盘、SDK 合法来源与版本；不要先清盘。做小文件写入/读回/哈希及进程退出码验证，确认后台作业日志在页面重连后仍可获取。

最低证据：脱敏环境清单、挂载与容量、工具版本、脚本哈希、三次独立短命令的 sentinel/退出码、一次跨重连文件读回。终端对象存在、HTTP 200 或 `PROBE_DONE` 单行都不能替代这些证据。

停止条件：远程命令不能稳定执行或读回；SDK 无合法可用来源；工作盘不足且没有已识别可清理范围。此时不启动大模型下载和长作业。

### G1：锁定来源与构建输入

工作：下载官方 Turbo 完整依赖清单，保存 model index、scheduler、tokenizer、reader、VAE、DiT 配置；将可变仓库引用解析为 revision。为社区 exporter 建立独立版本化工作目录，记录上游 license 和本地 patch。

最低证据：`sources.lock.json`、文件名/大小/SHA-256 清单、许可证记录、依赖版本、下载完整性结果。这里的文件名均为计划产物，当前不存在已验证版本。

停止条件：混入 base/Viggle 权重；缺少 shard/config；镜像与官方来源无法建立可信关联；授权范围不清。不能用同名文件或 HTTP 成功代替来源核验。

### G2：MI300X 官方参考

工作：在锁定环境中执行 512×512、8 步、CFG=1 文生图，先单样本，再短/长、中/英文、文字绘制和多对象调试集。确认没有误触发其他 pipeline、默认调度或负面分支。

保存：token IDs、原始 mask、裁剪边界、pre-norm hidden、补齐后 conditioning、文本投影、位置与 mask 信息、初始噪声、有效 sigma/timestep、各步预测及 latent、最终 VAE 输入输出和 PNG。

大张量采用代表样本全量保存，其余流式统计和抽样；不无差别保存全部层的全部激活。记录 RNG、dtype、attention backend、确定性设置及其限制。

门槛：参考输出可复现到已定义的数值容差；所有关键张量有限；调度与输入身份可解释。此时取得的是参考基线，不是 QNN 结果。

### G3：改写/导出浮点等价

比较链：官方 PyTorch → 改写 PyTorch → 可用 host backend 上的浮点 ONNX。若某浮点 backend 不支持算子，明确记录该段未验证，用可比的逐层 harness 补证，不能写成已通过。

顺序：reader 单片与完整序列 → 文本投影 → 单个 DiT block → 全部 block → prefix cache 与无 cache 对照 → 一步去噪 → 完整 8 步 → VAE。

重点排除：norm 的 `+1` 丢失、GELU 不同、RoPE layout、mask sentinel、padding/crop、cache 顺序、转置、latent pack/unpack、timestep 归一化和残差精度错误。

门槛：每个边界都有 shape/dtype/layout/统计/误差证据；错误能定位到最早偏离处。浮点改写不等价时不进入全量量化调参。

### G4：最小 QNN 可行性切片

先运行 SDK 自带或自建最小模型，证明 host converter、context generator、Android loader 和 HTP execute 通道可用；随后选真实 Turbo 单 block、代表 conditioning/latent/timestep 做最小导出。

读取实际 graph metadata，验证 tensor names、dimensions、dtype、layout、encoding 和输出；再与 G3 对应浮点输出比较。若真实图需量化才被目标 HTP 支持，此处采用可说明的初始配置，不把它当成最终质量方案。

此步骤用于尽早发现算子、SDK 和目标运行障碍，不等待 32 层全量编译才试第一次手机执行。最小图通过仍不等于完整 DiT 或图片通过。

### G5：量化与分图优化

优先 PTQ；权重低比特、敏感算子更高精度作为候选。`W4A16` 必须明确是何种权重/激活 encoding，不能自动解读成 4-bit 权重 + FP16 激活。以实际 SDK、graph metadata 和 HTP 支持为准。

校准输入来自官方 8 步的真实分布，覆盖提示词长度、语言、timestep、latent 和 cache 状态；不能以随机张量代替完整校准。可用几十条提示词调试数据链，再扩展到数百条代表实验集；最终规模由统计稳定性和质量覆盖决定，保留独立验证集。

Hadamard rotation、block-of-32 权重量化、per-channel/group encoding 都是待比较策略，不是默认最优。注意 block-of-32 与模型的 32 层不是一回事。

按逐层敏感性调整：文本投影、norm、attention score/softmax、调制、残差和最终输出等位置重点观察；是否保留更高精度由误差与性能实测决定。保存饱和率、相对 L2、RMSE、cosine、最大误差及分步累计漂移，不以单个 max error 覆盖所有层。

门槛：量化配置、校准样本版本、encoding、图改写 patch 可复现；独立集的语义/文字/结构没有未解释退化。禁止只换一条好看的 prompt 就通过。

### G6：全量 context 编译与运行闭环

先以清晰分片建立可诊断版本，再测量合并收益。社区 `sp0..sp31` 是一种 32 层拆图实现，不要求最终包永久一层一文件，也不能假设单一巨图一定最快。

需要同时比较：

- context 数量、每个文件中的 graphs 数量、冷加载开销；
- 单步图执行、输入输出拷贝、quant/dequant、RPC 与 cache 搬运；
- CPU/native/HTP 共享内存峰值、权重驻留与恢复；
- 8 步内重复加载、图切换、总耗时及取消响应。

社区关于 graphs/file、内存和 converter bug 的报告是其特定版本经验，必须在所选 SDK 复现，不升格为所有 QAIRT 版本的通用规则。

完整执行路径：设备实时 tokenizer/reader → text projection/prefix → 8 步 QNN DiT + CPU scheduler → 官方 VAE → 图片。固定 embedding 输入只用于定位 DiT，不计作最终完整闭环。

门槛：QNN 返回值、实际 runtime、graph 执行次数、输出有限性、数值/质量、文件哈希及资源释放都可检查。必须能区分计划内 CPU/GPU 阶段与意外 fallback。

### G7：打包与重复构建

工作：生成完整 manifest、组件哈希、graph ABI、调度合同、构建来源、licenses、runtime 要求、复现脚本及验证报告。使用新的隔离输出目录从锁定输入重建至少一次。

如果编译器输出含非确定性字段，记录二进制差异原因，并验证 graph ABI 与运行结果等价；不能未验证就承诺 byte-for-byte 可复现。产物来源可复现和二进制逐字节一致应分开报告。

门槛：没有占位 SHA、绝对开发机路径依赖、私有 token、未声明模型组件；包在目标设备从完整安装状态完成端到端运行。外部发布另需许可与明确授权。

### G8：MCA 产品集成

按第 8 节接入独立官方 Turbo 身份和证据合同，完成生产 UI、认证 Local API、取消、故障恢复、模型切换和受影响旧后端回归。交付包与产品上线状态分别报告，不将 debug harness 通过升级为生产完成。

## 7. 包格式与复现工程设计

以下目录及脚本均为**建议结构，尚未创建或实现**。选定 SDK 后，实际命令行以该版本工具 `--help` 和官方文档为准；本文不编造尚未运行的 QAIRT 命令参数。

```text
qwen-image-2.1-turbo-qnn/
  manifest.json
  sources.lock.json
  SHA256SUMS
  licenses/
  tokenizer/
  reader/
  dit/
  vae/
  contracts/
    conditioning.json
    scheduler.json
    graphs.json
  provenance/
    environment.json
    build-inputs.json
  validation/
    summary.json
    cases.jsonl
```

大体积校准数据、完整中间张量和 SDK 安装包不必放入可安装 bundle；保存在受控 evidence storage，并在来源记录中给出哈希和受控定位方式。

### 7.1 manifest 必须表达的内容

| 字段组 | 需要表达的事实 |
| --- | --- |
| 身份 | schemaVersion、bundleId/revision、官方 Turbo 来源 revision、组件 modelFingerprint |
| 文件 | 安全相对路径、size、SHA-256、required/optional、组件归属 |
| 执行 | taskMode、精确支持的形状/长度桶、batch、步数、CFG、实际阶段后端 |
| conditioning | tokenizer/template/config 哈希、pre-final-RMSNorm 语义、裁剪与 mask、投影版本 |
| scheduler | 配置来源、实际 sigma/timestep 合同、dtype、终止与 shift 规则 |
| graph ABI | context 文件、graph name、输入输出名称/顺序、shape、dtype、layout、encoding、连接关系 |
| cache | reader 与 DiT cache 分别描述；生命周期、键、有效长度、容量与清理 |
| runtime | compiler/SDK 版本、Android ABI、实际依赖与相容性证据 |
| 许可证 | 模型、tokenizer、导出代码、runtime/SDK 各自来源及再分发限制 |
| 构建 | 上游 commit、本地 patch 哈希、工具版本、配置、原始输入与输出哈希 |

schema 名称和 profile ID 由 MCA 集成时定稿；不能提前声称已有某个新字段被当前 importer/API 支持。开发态未补齐值必须导致 release packaging 失败，而不是用空字符串或全零 hash 占位通过。

### 7.2 工程脚本边界

建议按 `环境检查 / 来源锁定 / reference capture / float parity / calibration / export / compile / pack / device validation` 分职责。每阶段输入是上一阶段的已校验 manifest，不隐式读取用户主目录中的“最新模型”。

每个长作业记录：开始/结束时间、job ID、PID、工作目录、输入版本、命令的脱敏形式、退出码、日志和输出清单。失败文件写临时区，不覆盖上一次完整产物；只有校验完成后才更新当前产物指针。

接收模型包时保留路径穿越、解压大小、完整性和安全路径边界。SDK/runtime 可否再分发应单独确认，不把私有 SDK 自动复制进公开模型包。

## 8. MCA 接入设计

### 8.1 当前代码边界

| 当前证据入口 | 事实与影响 |
| --- | --- |
| [LocalImageQwenImage21Operations.kt:33](../app/src/main/java/com/muyuchat/mca/LocalImageQwenImage21Operations.kt#L33) | 当前专用 MNN 管线要求 `dit.mnn`、MNN profile；不能把 QNN contexts 改名后塞进去 |
| [LocalImageQwenImage21Operations.kt:111](../app/src/main/java/com/muyuchat/mca/LocalImageQwenImage21Operations.kt#L111) | `qnn/mnn_qnn/qnn_htp/npu/htp` 映射为实验 MNN QNN DiT，不是官方离线 Turbo |
| [QwenImage21TurboSchedule.kt:31](../app/src/main/java/com/muyuchat/mca/QwenImage21TurboSchedule.kt#L31) | 现有调度仅对 sdcpp + 精确 Viggle v0.2.1 LoRA 字节和强度生效 |
| [qnn_executable_graph.hpp:105](../core/native/src/main/cpp/mca/qnn/qnn_executable_graph.hpp#L105) | 有 context、metadata、tensor binding、execute 和共享 session 基础，可评估复用 |
| [QwenImage21ExecutionEvidence.kt:80](../app/src/main/java/com/muyuchat/mca/QwenImage21ExecutionEvidence.kt#L80) | 当前是 `qwen_image_21_mnn_v1`，绑定固定 MNN runtime/profile |
| [ImageGenerationApiResponseEvidenceValidation.kt:273](../api/local/src/main/java/com/muyuchat/api/local/ImageGenerationApiResponseEvidenceValidation.kt#L273) | 现有 Qwen API schema v1 校验 512×512、2–40 步、CFG=1；不等于新离线图合同 |
| [QwenImage21WorkerService.kt:218](../app/src/main/java/com/muyuchat/mca/QwenImage21WorkerService.kt#L218) | 旧 MNN native 无安全中断时，取消通过终止自己的隔离 worker 完成 |
| [LocalImageQwenImage21Operations.kt:327](../app/src/main/java/com/muyuchat/mca/LocalImageQwenImage21Operations.kt#L327) | finally 清理身份、卸载/断连及临时输出；新路径不能绕过这些资源责任 |

既有 vendor README 是历史记录，可能与当前源码存在差异。例如历史尺寸网格不能覆盖当前实际 size contract/API 校验；实施时重读源码和测试。

### 8.2 身份、owner 与状态

- 新官方 Turbo bundle/profile 与旧 MNN、sdcpp Viggle 明确分离；保持旧模型记录和下载资产有效。
- 在现有业务入口下接入实际资源 owner，不由 UI 或 API 各建一套 reader/DiT/VAE runner。
- 复用 QNN 通用 loader 前，审查其 SD/SDXL 特定 tensor、scheduler 和 manifest 假设。Qwen 的 prefix cache、单流 DiT 和 flow schedule 必须有自身清晰实现。
- 模型加载、请求准入、共享内存竞争、取消、终态、进程死亡和释放要由明确 owner 负责；保留 requestId/epoch/lease 语义，旧请求不能污染新任务。
- 需要新增隔离进程或 SDK 组合时，先明确其生命周期及与现有 runtime 的冲突边界；不得为每个入口添加平行资源实例。
- 缓存身份至少覆盖权重、图、tokenizer/template、conditioning、调度和形状合同；不能只用文件总大小作为模型 fingerprint。

### 8.3 证据合同必须升级而不是换标签

[QwenImage21WorkerAudit.kt:39](../app/src/main/java/com/muyuchat/mca/QwenImage21WorkerAudit.kt#L39) 当前在 Kotlin 根据请求计算 prompt hash，再发布 `conditioning_consumed` 标签。它不能单独证明 native 独立计算过被消费 conditioning 的 receipt。

新路径建议区分三层：请求摘要、实际 conditioning 载荷摘要、执行与输出摘要。native 在真正消费张量的边界绑定 payload、shape、dtype、layout、mask 和 requestId，执行完成后提交对应 receipt；上层校验并映射协议，不能自行伪造 native 成功字段。

应至少记录：

- package/profile/graph/runtime fingerprints；
- 请求与实际 prompt 处理绑定，tokenizer/template/conditioning 版本；
- 实际 shape、steps、CFG、timesteps/sigmas 与初始噪声身份；
- encoder/DiT/VAE 分阶段 requested/effective backend；
- context 加载、graph execute 成功、完成次数及作用范围；
- 计划内 CPU/GPU 阶段与意外 fallback 的区别；
- 输出路径边界、PNG 格式/尺寸、bytes、SHA-256；
- 取消反馈、实际停止与资源释放三个时间点，以及失败原因。

该证据是可审计的软件执行记录，不是硬件远程证明。哈希也不自动消除提示词隐私风险；仅使用授权测试语料，生产日志避免写完整私人 prompt 和敏感中间张量。

### 8.4 接入的兼容性检查

- 模型下载、续传、导入、校验、加载、卸载、删除、中断恢复不退化。
- UI 与 Local API 对同一 profile 的参数、错误、终态一致；新证据 schema 有明确版本分支及旧 fixture 回归。
- 新官方多语言 conditioning 不能盲目继承旧路径的 English-dominant 准入/翻译假设；核验实际生效提示词，并保留其他模型现有语义。
- 固定图长度不意味着可以静默截断长提示词；依据现有产品合同选择可验证分桶或明确的格式/参数错误。
- runtime/图是否相容由具体资产、依赖和真实执行结果决定；构建目标和 transport 选择不能代替兼容性验证。
- 不借本功能更换界面布局或降低已有分辨率/质量；性能比较需同参数、同参考质量。

## 9. 验证矩阵与度量

| 层 | 代表用例 | 必留证据 |
| --- | --- | --- |
| 来源/包 | 缺文件、坏 hash、非法路径、配置混配 | 精确失败原因、无半成品激活 |
| tokenizer/reader | 中英文、空白、长提示词、长度桶边界 | token/mask/crop、pre-norm hidden 差异 |
| DiT | 代表 block、首中末步、cache/无 cache | 同输入逐层/逐步误差、有限性、encoding |
| scheduler | 同噪声完整 8 步 | 实际 schedule、每步预测与 latent |
| VAE | 同 latent 解码 | scaling/shift、layout、输出尺寸/像素与质量 |
| 端到端 | 构图、人物数量、关系、颜色、风格、中英文图中文字 | 成对图片、参数与输入 hash、独立集结果 |
| 真机 | 冷/热运行、连续多次、后台切换、内存压力 | 分阶段耗时、总延迟、温度/频率、总资源峰值 |
| 生命周期 | load/encode/denoise/decode 期间取消、worker 死亡、重试 | 单一终态、实际停止、释放与下一次成功 |
| MCA | 生产 UI、认证 API、模型切换、旧后端代表模型 | 当前构建与包绑定的日志、图片、请求和回归结果 |

数值阈值在 G2/G3 根据 dtype、层尺度、reference 重复误差建立；量化阈值用独立于校准数据的开发验证集确定，并在最终留出测试前冻结。最终测试集不参与调参或阈值制定，不能用同一批结果同时选择门槛和宣布通过。当前没有足够数据指定统一 `1e-3`，也不以几十张图的 FID 作为可靠总验收。

性能报告拆分 tokenizer、reader、projection/prefill、各步 DiT、scheduler、拷贝、VAE、编码保存、加载与释放。报告样本数、冷/热状态和波动；不能用社区 Viggle 4 步耗时外推官方 8 步，更不能忽略 CPU reader/VAE 后只公布 NPU kernel 时间。

## 10. 主要风险与决策点

| 风险 | 处置 | 何时停止扩大工作 |
| --- | --- | --- |
| 环境/会话不稳定 | 先恢复小命令与日志读回，保存可续作状态 | 不启动大下载和长转换 |
| 官方权重或 license 不完整 | 固定来源与许可；镜像逐文件核验 | 不打包、不外发 |
| reader 输出语义不符 | 检查 pre-norm 和完整序列；转图像专用导出 | 不靠投影“凑维度” |
| float parity 失败 | 定位首个偏离层、mask/cache/schedule | 不进入大规模 PTQ 搜参 |
| HTP 算子/图资源限制 | 最小图复现；合法等价改写或调整分片 | 不用空图/stub 冒充通过 |
| 量化质量退化 | 敏感层提高精度，补真实校准分布 | 不静默更换小模型或删难例 |
| 混合管线总延迟不理想 | 分解瓶颈，再决定 reader/VAE NPU 化 | 不只优化 DiT 指标 |
| runtime/其他后端回归 | 隔离相干库集合，按影响验证旧模型 | 不全局替换 SDK 试错 |
| 手机内存/热限制 | 测量实际峰值、图驻留和搬运策略 | 不把降低用户质量当等价加速 |

以 G0–G8 的证据门槛管理进度，不给未经基准支持的“几天必成”或“快几倍”承诺。最终工程可行性仍需要 G2–G6 实测；详细方案本身不能保证特定设备的性能结果。

## 11. 完成审计清单

### 模型包完成

- [ ] 官方 Turbo 权重/config/license/revision/hash 全部锁定。
- [ ] 官方 8 步参考实际运行并保存关键张量。
- [ ] reader、投影、DiT、cache、scheduler、VAE 的浮点语义验证完成。
- [ ] 量化配置、真实轨迹校准和独立验证集有结果。
- [ ] QNN 图 ABI 与完整 context 编译产物可审计。
- [ ] Android 实时提示词到图片闭环通过，DiT 的 HTP 执行证据完整。
- [ ] 每阶段实际后端、质量、总性能、资源与取消结果有记录。
- [ ] 完整 bundle、构建输入、脚本、licenses 和报告可交接并复现。

### MCA 产品完成

- [ ] 新身份和版本化 schema 与旧 MNN/Viggle 路径明确隔离。
- [ ] UI/API 共享执行 owner、状态与资源仲裁，无平行 runtime。
- [ ] 生产 MainActivity UI 与认证 Local API 使用当前包成功运行。
- [ ] 取消、断连、死亡恢复、模型切换、既有数据及旧后端回归通过。
- [ ] 当前工作区/子模块补丁、APK、包和测试证据绑定明确。
- [ ] 所有 FAIL/BLOCKED/NOT_RUN 都逐项列出；发布另经授权。

截至本文日期，上述转换、运行与集成清单均未完成，不得因文档交付而勾选。

## 12. 资料来源

以下是本次调查使用的来源。带 commit 的链接可定位当时源码；`main` 和产品页面在实施时必须再次固定 revision/内容哈希。资料可访问不等于模型文件已下载或实测。

- [Qwen 官方项目与发布说明](https://github.com/QwenLM/Qwen-Image-2.1)
- [官方 Turbo Hugging Face 仓库](https://huggingface.co/Qwen/Qwen-Image-2.1-Turbo)
- [官方 Turbo ModelScope 仓库](https://www.modelscope.cn/models/Qwen/Qwen-Image-2.1-Turbo)
- [Diffusers pinned pipeline](https://github.com/huggingface/diffusers/blob/f23eedf6029aa52c6e578734afd9dc395a39c2d0/src/diffusers/pipelines/qwenimage21/pipeline_qwenimage21.py)
- [社区 image21-qnn-8elite 构建说明](https://huggingface.co/sebekblessing/image21-qnn-8elite/blob/main/build/README.md)
- [社区 reader 导出](https://huggingface.co/sebekblessing/image21-qnn-8elite/blob/main/build/qwen-image/export_reader.py)
- [社区 reader 实现](https://huggingface.co/sebekblessing/image21-qnn-8elite/blob/main/build/qwen-image/qimg_reader.py)
- [社区 Android 运行端](https://github.com/adiirfan/anima-android)
- [Qualcomm Qwen3-VL-8B-Instruct](https://huggingface.co/qualcomm/Qwen3-VL-8B-Instruct)
- [Qualcomm AI Hub Models](https://github.com/qualcomm/ai-hub-models)
- [QAIRT Linux 模型准备](https://docs.qualcomm.com/doc/80-64748-1/topic/model_prep_linux.html)
- [QAIRT 工具说明](https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-10/general_tools.html)
- [QAIRT HTP backend](https://docs.qualcomm.com/doc/80-63442-10/topic/htp_backend.html)
- [AIMET 2.42.0 安装说明](https://github.com/qualcomm/aimet/blob/2.42.0/Docs/overview/install/index.rst)
- [AMD MI300X 产品规格](https://www.amd.com/en/products/accelerators/instinct/mi300/mi300x.html)
- [ROCm PyTorch 安装说明](https://rocm.docs.amd.com/projects/ai-ecosystem/en/latest/frameworks/pytorch/install.html)
