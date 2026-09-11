# Animagine XL 面部质量优化（2026-09-06）

Animagine XL 使用独立的 `ANIMAGINE_XL_NEGATIVE_PROMPT`，包含脸部、眼睛、手指、肢体和水印约束。2026-09-07 使用模型包内 tokenizer 实测发现，初版长负面词有 84 token（含 BOS/EOS），超过每段 77 token 容量，末尾 7 个内容 token 被截断。现已删除重复约束并缩短；脸部和眼睛约束前置，水印约束保留。token 测量见 `artifacts/image-quality-compare-0907/validation.json`。

该提示词只绑定 `animagine_xl_v4_qnn228`，MeinaMix、SD15、CyberRealistic XL 及其他模型保持原有默认值。用户显式填写负面提示词时仍以用户输入为准；空字符串继续表示显式关闭模型默认负面词。

2026-09-06 的 seed 123 图片使用初版长负面词，已成功执行 28 步 / 56 次 UNet，但不能证明全部负面词生效，也不能凭单张图认定提升了普遍画质。2026-09-07 另用显式短提示词完成二次元和写实对照；这些图片不是缩短后的目录默认词验收。用户显式输入和模型包配置的优先级保持原样。

已确认的缺陷是目录默认词超出 token 预算。金属服装偏差不能单独归因于末尾截断：裸体要求及服装排除词仍在保留范围内。SDXL CLIP 输出层选择、模型转换与上游数值一致性仍需独立验证，现有 NPU 执行通过只证明计算完成。

产物：`artifacts/imagegen-evidence-fix-0906/qnn-image/animagine-face-optimized-negative-0906/`。
