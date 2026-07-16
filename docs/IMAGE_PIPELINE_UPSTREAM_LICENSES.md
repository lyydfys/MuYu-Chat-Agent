# MCA 生图管线许可友好上游清单

- 冻结日期：2026-07-17
- 适用范围：MCA 生图 scheduler、Qualcomm QNN 参考管线、MNN runtime、标准 tokenizer 与 stable-diffusion.cpp adapter
- 原则：只允许下列许可友好上游的代码、公式、测试向量或接口语义进入产品树；本地画质对照应用及其身份、URL、源码和产物不进入本清单或可发布代码。

## 冻结版本

| 上游 | 版本或标签 | 完整 commit | 许可证 | MCA 用途 |
|---|---|---|---|---|
| Hugging Face Diffusers | v0.35.1 | `0f252be0ed42006c125ef4429156cb13ae6c1d60` | Apache-2.0 | EulerDiscrete、DDIM、PNDM/PLMS、DPM-Solver++ 的配置语义、公式和 golden vectors |
| Qualcomm AI Hub Models | 固定提交 | `db311c000378c7142fe32bd0c4aea25db873adcc` | BSD-3-Clause | Stable Diffusion 1.5、2.1、ControlNet 的组件连接、scheduler 选择、tensor layout 与 VAE export 语义 |
| Alibaba MNN | 3.6.0 | `cc20f672af9e177e2fa338c332dc097de2fc9264` | Apache-2.0 | MNN graph/runtime、MtokTokenizer 与 diffusion 执行接口 |
| mlc-ai tokenizers-cpp | 固定提交 | `acbdc5a27ae01ba74cda756f94da698d40f11dfe` | Apache-2.0 | Hugging Face tokenizer.json 的 Android/C++ binding；包含 tokenizers 0.21 AHashMap、Rust 1.95 与 Android arm64 链接兼容修复 |
| Google SentencePiece | tokenizers-cpp gitlink | `11051e3b73b3a6222a52acd720e39805dc7545ab` | Apache-2.0 | tokenizers-cpp 固定的 SentencePiece C++ 依赖；避免要求 Windows 符号链接权限 |
| msgpack-c | tokenizers-cpp gitlink | `092bc69b6e815980bce7808595c914dd3a29f905` | BSL-1.0 | tokenizers-cpp 固定的 msgpack C++ 头文件依赖 |
| stable-diffusion.cpp | 仓库固定提交 | `be65ac7511b30379b003626c15224798929e33d4` | MIT | GGUF/safetensors 生图 runtime 与原生 sampler adapter |

## 进入产品树的允许范围

### Diffusers

- 允许：scheduler 配置字段、公开数学公式、独立生成的 timesteps/sigmas/step golden 数据。
- MCA 实现：在自己的 C++ 数据结构和错误模型中重写，不复制 Python 框架代码。
- 归属：新增文件保留 Apache-2.0 来源说明；发布包保留许可证文本。

### Qualcomm AI Hub Models

- 允许：BSD-3-Clause 参考管线中的 text encoder → UNet → VAE 连接语义、ControlNet residual 角色、SD1.5/SD2.1 scheduler 选择和 graph-internal VAE scaling 事实。
- MCA 实现：适配自己的 QNN coherent session、worker、Local API 与 execution metadata。
- 归属：若复制或改写具有版权表达的代码段，必须保留 Qualcomm 版权头和 BSD-3-Clause 条款；仅采用接口事实和独立实现时仍在本清单记录来源。

### MNN

- 允许：仓库已固定 MNN runtime、diffusion engine 和 tokenizer API。
- MCA 实现：只在 MCA bridge/adaptor 中增加 profile 解析、真实参数回显和契约校验；上游修改保持可辨识。
- 归属：保留 `third_party/MNN/LICENSE.txt` 与既有版权头。

### tokenizers-cpp

- 允许：`Tokenizer::FromBlobJSON`、Encode/Decode binding 以及 Android arm64 静态链接配置。
- MCA 实现：包内 tokenizer.json 的安全读取、fingerprint、BOS/EOS/PAD/maxLength 和双 encoder 规则由 MCA contract 管理。
- 依赖锁定：主 gitlink 固定为 `acbdc5a27ae01ba74cda756f94da698d40f11dfe`；其嵌套 gitlink 固定为 SentencePiece `11051e3b73b3a6222a52acd720e39805dc7545ab`、msgpack-c `092bc69b6e815980bce7808595c914dd3a29f905`。该主提交包含 `e8964871dda750164787d8fba23e1451918677d4` 的 AHashMap 类型修复、Rust 1.95 修复和 Android arm64 链接修复，并停在 SentencePiece 引入 Windows 符号链接要求之前。
- 归属：发布包保留 Apache-2.0 文本；Rust tokenizers 与其传递依赖的许可证必须在最终依赖清单中复核。

### stable-diffusion.cpp

- 允许：现有子模块 API、component selection 和原生 sampler。
- MCA 实现：profile 到 native 参数的映射、unsupported 参数失败、requested/resolved/nativeEffective 回显。
- 归属：保留 `third_party/stable-diffusion.cpp/LICENSE`。

## Golden 数据规则

- Golden 生成器固定上游 commit、scheduler config、dtype、seed 和输入张量。
- 仅提交数值 fixture 与许可友好的生成脚本，不提交模型权重、下载包、外部应用产物或设备私有日志。
- 测试必须比较 timesteps/sigmas、scaleModelInput、prediction conversion 和每步 prev_sample；只比较最终 PNG 不足以证明 scheduler 对齐。
- 模型权重、QNN ZIP、tokenizer sidecar 和 scheduler_config.json 的许可证按每个推荐包单独记录，不能由代码许可证替代。

## 提交前检查

1. 确认本文件中的 commit 与实际依赖一致。
2. 确认新增第三方文件带正确版权头、LICENSE/NOTICE 和修改说明。
3. 确认产品代码、测试、注释、日志、UI、API、profile ID 和可发布文档不包含本地外部画质基准的身份或路径。
4. 确认 `.external/`、模型、APK、golden 临时输出和 `docs/experiments/` 未进入提交。
