# 生图质量与链路审计（2026-09-07）

## 实测结果

### 真人风格

模型：CyberRealistic SD1.5 QNN 2.28  ；512×512、28 steps、DPM++ 2M、CFG 7、seed 901。

结果：面部、肤色、光影和皮肤纹理明显自然，适合真人肖像；这次是半身构图，因此没有完整验证手脚。图片位于 `artifacts/imagegen-evidence-fix-0906/qnn-image/compare-photo-adult-0907/`。

### 二次元风格

模型：Animagine XL v4 QNN 2.28；1024×1024、28 steps、DPM++ 2M、CFG 5、seed 901。

结果：保持动漫线稿和高光风格。长提示词版本出现了金属服装偏差；缩短提示词后能得到清晰的二次元上半身，但头发遮挡部分脸部。图片位于 `artifacts/imagegen-evidence-fix-0906/qnn-image/compare-anime-short-0907/`。

两类运行均报告 `completed`、真实 NPU 执行、28 steps 和 56 次 UNet 执行，PNG SHA256 与 worker metadata 一致。

## 已确认的链路问题

1. Animagine XL 是动漫模型。给它加入 `photorealistic` 或裸体摄影词不会把它变成真人模型，只会造成动漫先验与写实提示词竞争。
2. Animagine 默认负面提示词初版超过 CLIP 每段 77 token 的容量，尾部约束被截断。现在已压缩到 54 token（含 BOS/EOS），并把脸、眼睛、手指和肢体约束前置。旧 APK 或旧模型 profile 需要重新安装后才会使用新值。
3. QNN SDXL 当前是一次性双 CLIP → UNet → VAE 链路，没有 face restoration、局部重绘、姿势控制、区域提示或二阶段细节修复。脸、手和脚只占少量像素时，单次 1024 输出无法稳定修复这些局部。
4. CyberRealistic SD1.5 profile 固定 512×512。它的真人脸更自然，但分辨率不足以同时保证全身、手指和脚趾细节。
5. 目前没有证据表明 28 步调度、QNN worker 隔离、VAE 解码或 PNG 写出失败；这些阶段均有完整执行证据。量化误差和模型转换质量仍可能影响细节，但需要与原始 FP16/官方推理结果做同 seed 数值对照后才能确认。

## 建议的解决路径

- 真人细节：使用 CyberRealistic 或其他写实 checkpoint；优先半身/膝上构图，再做局部放大和重绘。
- 二次元细节：使用 Animagine；减少场景词，先保证单人、脸部和姿势，再做第二次局部修复。
- 产品能力：增加 image-to-image 局部重绘或 face/detail pass，并保留原始 seed、latent、mask 和最终 SHA256；不能用提示词替代细节修复器。
- 工程验证：增加同 seed 的官方 FP16、桌面 MNN、QNN 三方 latent/conditioning/UNet 输出对照；当前只能证明“执行完成”，不能证明“与参考实现数值等价”。

## 结论

当前生图失败并非运行时崩溃，而是模型选择、分辨率、单阶段架构和局部细节能力共同造成的质量限制。要生成更接近真人且细节丰富的裸体成年女性，应使用 CyberRealistic 类写实模型；要生成二次元角色，应使用 Animagine，并接受其动漫风格先验。两者不应通过互换提示词来替代。
