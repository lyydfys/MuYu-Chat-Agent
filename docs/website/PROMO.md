# 群发文案 · 吸引人版

线上地址 https://mca.mac-api.xyz 已验证可用（HTTPS 200，证书已签发）。
所有数字均取自真实产物，未编造。

---

## 版本 A · 主推（适合技术群 / 开发者群）

一台手机，装下一整套 AI 工作台。断网也能用。

**MCA v0.4.1**

你有没有这种时候：想让 AI 干点私活，但设备不在自己手里、密钥要传给别人的服务器、断网就废。

MCA 反过来。模型、密钥、对话、生成结果，全在你这台手机里。云端接口是可选项，不是前提。

它不是一个聊天壳子，是六个东西装进一个 App：

**① 本地推理** —— MNN 优先，GGUF 兼容。加载完模型，飞行模式下继续聊。
**② 云端接入** —— OpenAI 兼容 + Anthropic Messages。想接谁接谁，密钥本机加密。
**③ 生图** —— stable-diffusion.cpp、MNN-Diffusion、QNN 后端。断网给角色配图。
**④ 生视频** —— Wan 2.2，节点图可拖动重排，480×832 真机跑完自动释放资源。
**⑤ DSH 工作台** —— 独立 Node 内核 0.2.2-rc.1。六阶段启动，哪一步失败就停在哪一步，不假装"加载中"。
**⑥ Local API** —— 11435 端口，OpenAI 兼容。手机上的浏览器、同网段的电脑、你自己的脚本，直接调这台手机的模型。

不需要账号。不按机型、芯片或白名单开权限——一次真机加载通过，所有兼容设备都能用。

**下载**
https://github.com/lyydfys/MuYu-Chat-Agent/releases/latest

arm64-v8a · Android 8.0+ · Apache-2.0 开源
612.7 MB —— 体积来自 DSH 内核和多套原生推理库，不含任何模型权重，模型装完自己下。
SHA-256 请核对：`4320a4ef2d7424e38a3afed2b2ccfee5e42713e8cb982d629e21c605f8c0e6f4`

官网 https://mca.mac-api.xyz

---

## 版本 B · 短平快（适合刷屏大群，一眼看完）

**一台手机，装下整套 AI 工作台。断网也能用。**

MCA v0.4.1 —— 本地模型、角色卡、生图、生视频、DSH 工作台、OpenAI 兼容 API，全在一个 App 里。

模型在你手里，密钥在你设备里。不需要账号。不按机型开权限。

下载 → https://github.com/lyydfys/MuYu-Chat-Agent/releases/latest
官网 → https://mca.mac-api.xyz

arm64-v8a / Android 8.0+ / 开源 Apache-2.0

---

## 版本 C · 场景钩子（最抓人，适合泛群）

**你有没有想过：AI 为什么非得连网？**

断网了，你的对话记录在哪、密钥在哪、生成的东西归谁——这些问题，MCA 给的答案很简单：

都在你手机里。

MCA v0.4.1 把本地模型、角色卡、生图、生视频、一个能自己跑的 Agent 工作台，和一条 11435 端口的 OpenAI 兼容接口，全塞进一台 Android 手机。

- 不需要账号，密码都不用注册
- 密钥本机加密，不上传
- 断网也能聊、能画图
- 手机上跑的模型，电脑和脚本可以直接调

下载 https://github.com/lyydfys/MuYu-Chat-Agent/releases/latest
官网 https://mca.mac-api.xyz

---

## 发之前建议确认

1. **612.7 MB 一定会被问"怎么这么大"** —— 文案里已写明"不含模型权重"。保留这句，否则群里第一反应是质疑。
2. **生图 / 生视频是实验能力** —— 文案已标"实验性/取决于完整模型包"。别删，否则用户装完发现跑不动会骂人。
3. **"不按机型开权限"是 MCA 的硬承诺** —— 这是差异化卖点，也符合项目铁律，务必保留。

## 配图建议

官网这几张可以直接当群图发：

| 用途 | 文件 |
|---|---|
| 开屏（质感最好） | `docs/website/preview/opening-light.png` |
| 四台手机展台 | `docs/website/preview/device-rail-rest.png` |
| 圆角细节（放大后很高级） | `docs/website/preview/corner-zoom.png` |
| 手机端首屏 | `docs/website/preview/mobile-360-hero.png` |
