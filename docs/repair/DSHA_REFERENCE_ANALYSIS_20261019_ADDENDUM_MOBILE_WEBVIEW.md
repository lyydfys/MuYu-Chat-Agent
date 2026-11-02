# 附录：DSHA 对 Android WebView 四个具体问题的处理，与 MCA 现状对照

记录日期：2026-10-20（Asia/Shanghai）。性质：**只读文档**，不构建、不装机、不改生产代码。
主文档：[DSHA_REFERENCE_ANALYSIS_20261019.md](DSHA_REFERENCE_ANALYSIS_20261019.md)。本文只补四个与本次真机故障直接相关的点，不重复主文档的总体对照与建议排序。

**证据边界**：DSHA 源码链接固定到提交 `810fb03b9575e1e816d086e6556ca1c1ae99b029`（build173 源码同步点），行号为本次下载原文后实测。MCA 侧行号为本次工作区实测；MCA 工作区仍在被并行修改，接手前需复核。**未运行任何 DSHA 代码，未在设备上验证任何一条修复。**

---

## 0. 四行结论

| 问题 | DSHA 的做法 | MCA 现在 | 结论 |
|---|---|---|---|
| `100vh`/`dvh` 解析为 0、弹层被裁 | 插件自己**持有 viewport meta**（`viewport-fit=cover`）+ 用 JS 维护 **px 自定义属性 `--dsh-web-mobile-vh`** 替代视口单位 + 用 `visualViewport` 计算可见带定位浮层 + 三条短屏/键盘/横屏的菜单夹具 | **已实现** html 显式 px + CSS `vh/dvh` 重写（[DshPageLayoutShim.kt:28-97](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshPageLayoutShim.kt#L28-L97)） | 主因已覆盖；**分层差异仍在**（见 §1.3） |
| 首次使用选工作区目录 | 启动 shell 里给 `DSHA_WORKSPACE_DOCUMENTS=/root/Documents`；网页端保留**官方 browse 目录选择器**，不做 Android SAF 树选择 | **已实现** `documentsDirectory` 固定到 MCA 私有 workspace（[DshGatewayConfigSeeder.kt:584-590](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L584-L590)）；`DshCapabilityPort` 只有契约 | 首次可用性已解决；“用户自选目录”仍是未实现能力 |
| 键盘/IME、安全区、焦点/缩放、状态栏/PiP | 原生 insets（含 IME）+ 页面 `env(safe-area-inset-*)` + `interactive-widget=resizes-content` + 不封禁缩放（用 ≥16px 输入框防聚焦缩放）+ 真 PiP | 容器级 `imePadding()`；无 PiP；无页面级 safe-area；`supportZoom=true` 与页面 `user-scalable=no` 并存 | 部分实现；键盘弹层与安全区仍是工作 |
| 本地模型上下文预算 vs 大 prompt / 工具 schema | **没有本地模型**：只用 DeepSeek API key（README:50,94），上下文交给官方 compaction，不存在本地裁剪策略 | **已有** adapter 限额（工具目录 24 KiB、schema 12 KiB、工具结果 32 KiB…）+ prompted-tool 协议 + `CONTEXT_LENGTH_EXCEEDED` 映射 | 这一项 **MCA 领先**，不应向 DSHA 看齐 |

---

## 1. 视口单位与弹层裁剪

### 1.1 DSHA 做了哪几层

**(a) 自己持有 viewport meta，且刻意不禁缩放。**
[client.js:9605-9612](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L9605-L9612)：

```js
/** Viewport content the plugin owns while the mobile branch is armed.
 *  Deliberately zoom-free: iOS 10+ ignores maximum-scale/user-scalable for user pinch ...
 *  the iOS focus-zoom fix is the >=16px field floor ... not a zoom ban (#45). */
const VIEWPORT_CONTENT = 'width=device-width, initial-scale=1, viewport-fit=cover'
```
并用 `MutationObserver` 在宿主重写/替换/晚注入时**重新断言**（[client.js:9625-9659](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L9625-L9659)），理由是“否则 `viewport-fit=cover` 会被静默丢掉，所有表面移到刘海下面”。

**(b) 用 px 自定义属性替代视口单位。**
[client.js:9613-9623](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L9613-L9623)：

```js
/** CSS custom property carrying the viewport height WITHOUT the soft keyboard (px),
 *  maintained by syncStableViewportHeight() ... Mobile cards that must not move when
 *  the keyboard appears size themselves with it instead of a viewport unit */
exports.STABLE_VIEWPORT_VAR = '--dsh-web-mobile-vh'
```
使用处保留 `dvh` 作为兜底（[client.js:3418](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L3418)）：

```css
max-height: min(800px, calc(var(--dsh-web-mobile-vh, 100dvh) - 24px - env(safe-area-inset-top, 0px)));
```
关键在**顺序**：先 `var(--dsh-web-mobile-vh, …)` 再兜底 `100dvh` —— WebView 里 `dvh` 解析为 0 时仍然拿到真实 px；MCA 的 shim 走的是另一条等价路线（把 `vh/dvh` 直接改写成 px）。

**(c) 弹层定位用 `visualViewport` 的“可见带”。**
[client.js:12848-12858](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L12848-L12858) 明确写：优先 `visualViewport`，“键盘弹起时它只缩视觉视口”，并点名这是“浮层停在会话中部”那一族真机报障的修法；[client.js:12987-13006](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L12987-L13006) 再把 `resize` + `visualViewport.resize` + `visualViewport.scroll` + `orientationchange` 的事件风暴**去重成一次 place**。

**(d) 短屏/键盘/横屏三条菜单夹具**（弹层被裁的直接对策）。
[client.js:1802-1834](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L1802-L1834)：

```css
/* Guard 1 — the panel's inner model listbox opens upward (bottom:58px inside the panel)
   with max-height:min(220px, 100vh - 96px). On a short screen that ceiling is taller than
   the room above the panel, so the listbox pokes past the viewport top (320x568: menu y=-41). */
@media (max-height: 700px) { [data-seat-model-menu] { max-height: min(220px, calc(50vh - 110px)) !important } }
/* Guard 2 — with the on-screen keyboard up (portrait, ≤505px tall) there is not even 208px ... */
@media (orientation: portrait) and (max-height: 505px) { … max-height: calc(50dvh - 45px) !important; overflow-y:auto … }
/* Guard 3 — landscape: ... Detach the panel into a bottom-docked sheet instead */
@media (orientation: landscape) { … position: fixed !important … }
```

> 这条正好解释了“`100vh`=0 → 弹层裁切”的机制：官方面板写的是 `max-height: min(220px, 100vh - 96px)`，`100vh` 一旦解析为 0，`max-height` 变成负值 → 菜单塌成一条/完全不可见。MCA 的 vh 重写把这类声明救回来；DSHA 则是“先给稳定 px 变量，再给短屏夹具兜底”。

**(e) 安全区与 `box-sizing` 的成对规则**（不是单纯 padding）。
[client.js:854-897](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L854-L897) 记录了一个与 MCA“整页看起来是空白”同族的真实故障：

```css
html [data-mobile-nav="frame"] {
  box-sizing: border-box !important;
  grid-template-columns: minmax(0, 1fr) 0 0 !important;
  padding-top: env(safe-area-inset-top, 0px) !important;
  padding-bottom: env(safe-area-inset-bottom, 0px) !important;
}
```
注释（860-870）：“the official frame is height:100% of a 100%-height body, and it is content-box by default, so the safe-area padding is **ADDED on top of** the full viewport height… Symptoms: the whole UI can be swiped up, the composer lifts off the bottom leaving a blank strip… With border-box the padding is taken out of the 100% height instead.”

**(f) 宿主侧窄屏布局**：DSHA 的抽屉/汉堡同样是补丁（见主文档 §3.4 与本仓库 payload `dsh-client-ui-layout`），但其副作用处理在 mobile 插件里（[client.js:899-907](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L899-L907)：“the sidebar column becomes a left drawer… Closed state: translateX(-110%) … A mere -100% leaves a sliver”）。

### 1.2 MCA 现在做了什么（已实现）

- **html 显式 px + CSS vh/dvh 重写**：[DshPageLayoutShim.kt:5-25](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshPageLayoutShim.kt#L5-L25) 记录的现象与 DSHA 的 860-870 注释几乎同源（“`100vh`/`100dvh` 解析为 0、`html, body, #root { height:100% }` 链塌陷、fixed 层仍绘制 ⇒ 看起来像空白主面板”）；实现见 [DshPageLayoutShim.kt:28-97](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshPageLayoutShim.kt#L28-L97)：`html.style.height = px`，并遍历同源 stylesheet 把 `vh/dvh` 改写成 px（原文保留在 WeakMap，旋转时按原始值重算）。
- **`loadWithOverviewMode`**：[DshWebViewFactory.kt:66-71](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewFactory.kt#L66-L71)（`useWideViewPort = true` 且 `loadWithOverviewMode = true`），与 DSHA 的 [WebPreviewActivity.java:673-674](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java#L673-L674) 完全一致的设置项。

### 1.3 仍然存在的差距（工作项）

1. **页面级 safe-area 完全缺失**：MCA 没有 `viewport-fit=cover` 的所有权，也没有 `env(safe-area-inset-*)` 规则。当前靠 Compose 容器 inset 兜住，但一旦官方页面出现 `position:fixed` 的顶/底条（弹层、悬浮条），就会顶到状态栏/导航栏下面。**建议**：学 DSHA 的“成对规则”（`box-sizing:border-box` + `padding-top/bottom: env(...)`），并且只在窄屏/edge-to-edge 命中时注入，不写死到全站。
2. **缺少 `--dsh-web-mobile-vh` 等价物**：shim 只在 `resize`/`visualViewport.resize`/两个定时器上重算，但**键盘**引起的 visual viewport 变化在 Compose `imePadding()` 下表现为容器变高/变矮，若 shim 重算时机晚于官方弹层定位，弹层仍可能用旧高度。**建议**：在 shim 里维护一个 `--mca-dsh-vh` px 变量并先于 `vh` 重写生效，供官方 `calc(100vh - …)` 规则自然吃到（比重写每条规则更稳），并在 `visualViewport.scroll` 上也重算。
3. **没有短屏/键盘/横屏的弹层夹具**：DSHA 的三条 Guard 是对官方弹层的**结构性**修复；MCA 目前完全依赖官方布局。**建议**：先验证（本次 `插件`/`更多`/工作区 `+` 的空白是否就是弹层 0 高度），再决定是否在“移动适配层”加最小夹具；任何夹具都必须单独截图验收（硬锁 §9 冻结合同）。

---

## 2. 首次使用的工作区目录，与原生目录选择

### 2.1 DSHA 的做法：换一个“OS 文档目录”，不做 SAF

- 启动 shell 直接注入环境变量：[WebLaunchCommand.java:17](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java#L17)
  `export DSHA_WORKSPACE_DOCUMENTS=/root/Documents && …`
  —— DSHA 跑在 Ubuntu rootfs 里，`/root/Documents` 是真实存在的目录，官方 workspace controller 首次使用时能直接创建 `deepseek-harness/default-workspace`，**不需要任何 Android 权限或原生选择器**。
  （`DSHA_*` 前缀说明这是 DSHA 侧的注入/补丁点；官方内核的读取位置我在本仓库源码中**未核对**。）
- 官方**浏览式**目录选择器被保留，而不是替换成 Android SAF：mobile 插件明确要求“The directory picker (dsh-client-ui-directory-picker-browse) must be excluded too”，并记录了一次真实事故——通用“sheet 弹窗”CSS 把它的 header/路径输入吞掉，用户无法输入路径（issue #12）：[client.js:3370-3379](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L3370-L3379)；宽屏 sheet 规则也把它单列：[client.js:5754-5756](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L5754-L5756)。
- 原生桥只用于**附件/文件**，不用于目录：`window.DSHA.pickFile` → 返回 `File[]` → 写回宿主 hidden input 再派发 `change`；桥不存在/抛错/脏数据时逐级回落到宿主 `<input type=file>`：[client.js:12648](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L12648)、[12684-12689](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L12684-L12689)、[12784-12821](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L12784-L12821)。
- 另外它的“文件交换”能力是**桥端点**（读目录/文本、导出到 `Download/DSHA`，凭据区拒绝），见 README:166；这不是 Android 选择器。

**要点**：DSHA 之所以能绕开“原生目录选择器”，是因为它的 DSH 工作区就在自己拥有的 rootfs 里；Android 的存储权限/SAF 根本不在链路上。

### 2.2 MCA 现在做了什么（已实现）

- 网关 overlay 里把官方 `workspace-controller` 的 `documentsDirectory` **固定**到 MCA 私有目录：[DshGatewayConfigSeeder.kt:584-590](../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt#L584-L590)，注释写清了原因：“Android has no OS Documents lookup, so it throws and the UI shows 无法创建默认工作区. Pin the MCA-owned workspace so first use creates `<workspace>/deepseek-harness/default-workspace` **without a native directory picker**.”
- 契约层已有 `DshCapabilityPort`：[DshCapabilityPort.kt:20-35](../../core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/capability/DshCapabilityPort.kt#L20-L35)（`status` / `pickFiles` / `saveFile` / `notify` / `performDeviceAction`，另有取消端口 42-44），鉴权在 [DshCapabilitySecurity.kt:91](../../core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/capability/DshCapabilitySecurity.kt#L91)（`dshc_` bearer + `X-MCA-DSH-Generation` 代次）。
  **两点必须说清**：
  1. 本次检索**没有发现生产实现**（只有 `FakeDshCapabilityPort` 测试替身与契约测试），即“原生选择器桥”仍是**未落地**；
  2. 契约覆盖的是**文件选择/保存**（`pickFiles`/`saveFile`），**没有“目录树选择”**（无 `ACTION_OPEN_DOCUMENT_TREE` 对应请求类型）。所以“用户自选工作区目录”不只是没实现，是**连契约都还没有**。

### 2.3 工作项

1. **首次使用路径已闭合**（P0 无需动作）；但要在真机确认 `default-workspace` 真的创建成功、且 DSH 侧边栏“工作区”能列出该目录（当前故障里工作区 `+` 是空灰面板，另属 §1 的弹层问题）。
2. **“用户自选目录”是未实现能力**（P2）：若产品要求，走 `DshCapabilityPort` 的 SAF 实现（`ACTION_OPEN_DOCUMENT_TREE` → 目录 fd/路径 → 传给 Node），并注意 DSH 的 sandbox 预设是 `workspace-write`，越界路径会被官方拒绝——这不是加个按钮就能用，需要先决定“用户目录是否在 DSH 的允许根内”。
3. **不要照搬 DSHA 的“浏览式选择器”**：它依赖 rootfs 内的路径空间；MCA 的 Node 只看见 `files/dsh/**`，官方 browse picker 会把用户带到无意义的路径。

---

## 3. 键盘/IME、安全区、焦点与缩放、状态栏/PiP

### 3.1 DSHA

- **原生 insets 一处收口**：[ModernAndroidUi.java:44-56](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/ModernAndroidUi.java#L44-L56) 统一监听 `systemBars() | displayCutout()`，并读取 `WindowInsetsCompat.Type.ime().bottom`；[ModernAndroidUi.java:28](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/ModernAndroidUi.java#L28) 设透明状态栏，[38-40](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/ModernAndroidUi.java#L38-L40) 控制浅色图标。
- **全屏/PiP 也走同一套 insets**：[WebFullscreenUi.java:26-46](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/WebFullscreenUi.java#L26-L46)，PiP 时早退（[WebFullscreenUi.java:30-32](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/WebFullscreenUi.java#L30-L32)），并单独处理 `captionBar()`（[40-42](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/WebFullscreenUi.java#L40-L42)）。
- **页面侧键盘重排**：[app-integration/client.js:631-638](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/app-integration/client.js#L631-L638) 重写 viewport meta，追加 `interactive-widget=resizes-content`（注释：“Gecko 132+ 默认只缩小 visual viewport；dsh 的整屏布局需要随键盘一起重新排版”）。
- **防聚焦缩放而不是禁缩放**：[client.js:9605-9610](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js#L9605-L9610) 明确“不写 maximum-scale/user-scalable”，改用 ≥16px 输入框下限（`data-mobile-nav-ios`）。
- **真 PiP**：[PictureInPictureActivity.java:194-256](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/PictureInPictureActivity.java#L194-L256)（`setAspectRatio`、`setSourceRectHint`、API 31+ `setAutoEnterEnabled`、`setSeamlessResizeEnabled(false)`、参数去抖），[258-269](https://github.com/DSH-APP/DSHA/blob/810fb03b9575e1e816d086e6556ca1c1ae99b029/app/src/main/java/com/deepseekharness/app/ui/PictureInPictureActivity.java#L258-L269)（进入前 `freezeViewport()`，失败 `resumeViewport()`）；`refreshPictureInPicture()` 在布局/比例变化时重建参数（225-256）。

### 3.2 MCA 现在

- **容器级 IME**：[DshWorkspaceScreen.kt:73-79](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/ui/DshWorkspaceScreen.kt#L73-L79) 对 WebView 容器加 `.imePadding()`，注释说明“adjustResize alone is not reliable on every OEM/edge-to-edge combination”。这保证**容器**不被键盘盖住，但不改变页面内部的 `vh`/弹层。
- **无 PiP**：DSH 页面没有 PiP（`DshWorkspaceRoute`/Activity 无相关调用）。V1 不要求，列为**拒绝/后置**。
- **缩放**：`DshWebViewFactory` 设 `setSupportZoom(true)`、`builtInZoomControls = true`、`displayZoomControls = false`（[DshWebViewFactory.kt:58-60](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewFactory.kt#L58-L60)），而官方页面 viewport 是 `maximum-scale=1.0, user-scalable=no`（payload `dist/index.html:5`）。两者并存时页面声明优先，但**没有实测**；若出现“双指把整页放大”，应按 DSHA 的思路关掉内置缩放而不是加 `textZoom`（MCA 已正确不设 `textZoom`，以保留系统 2.0x 字体，[DshWebViewFactory.kt:23-27](../../feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/web/DshWebViewFactory.kt#L23-L27)）。
- **状态栏**：由 MCA Compose 主题/edge-to-edge 统一处理，DSH 页无独立 insets 逻辑。

### 3.3 工作项

1. **键盘与弹层联动**（P1）：先按 §1.3-2 让 shim 维护 px 变量并在 `visualViewport.scroll` 重算；再看是否需要 `interactive-widget`（WebView 支持度需实测，DSHA 只在 Gecko 分支用）。
2. **页面级安全区**（P1）：见 §1.3-1。
3. **缩放回归**（P1）：真机做一次“双指缩放 + 输入框聚焦 + 旋转”的截图回归；若整页缩放成立，把 `setSupportZoom(false)`（与官方 viewport 一致）而不是改页面。
4. **PiP**（P2/拒绝）：除非产品明确要悬浮窗，否则不引入 DSHA 的 PiP 复杂度（它牵涉 `freezeViewport`、参数去抖、PiP 下 WebView pause/resume）。

---

## 4. 本地模型上下文预算 vs 大 prompt / 工具 schema

### 4.1 DSHA：不存在这个问题（因为它没有本地模型）

- 唯一模型来源是 DeepSeek API：README 第 50 行“填 API key（或跳过）”、第 94 行“「配置」页填 DeepSeek API key →「启动」页点启动”。没有模型下载/加载/上下文窗口配置。
- 长上下文与工具 schema 由**官方内核**处理（compaction / 工具呈现模式），DSHA 没有自己的裁剪策略可抄。
- 其 token 相关 UI 只是“计量显示”（mobile 插件 `pickTpsReadout`，client.js:6498-6555），不是预算控制。

### 4.2 MCA：已有显式的 adapter 预算（这一项领先）

[DshAdapterLimits.kt:11-44](../../app/src/main/java/com/muyuchat/mca/dsh/DshAdapterLimits.kt#L11-L44)：

| 限制 | 值 | 作用 |
|---|---|---|
| 整段 transcript | 1,000,000 字符 | 进引擎前的硬顶 |
| 单条消息 | 256 KiB | 超限**如实失败**，不静默截断 |
| 单条工具结果 | 32 KiB | 截断**带显式标记**，不让 loop 死 |
| 单条 assistant 工具调用块 | 64 KiB | 同上 |
| 工具定义数 | ≤128 | 网关也封 128 |
| 单工具描述 / JSON schema | 2 KiB / 12 KiB | 防止 schema 撑爆提示 |
| **整个 prompted 工具目录** | **24 KiB** | 超出的工具被**省略并标注不可用** |
| prompted 信封 | 64 KiB | 超限降级为纯文本 |
| 输出 token | 32,768 | 与云传输硬顶一致 |

配套：[DshPromptedToolProtocol.kt:9-31](../../app/src/main/java/com/muyuchat/mca/dsh/DshPromptedToolProtocol.kt#L9-L31) 明确“本地引擎不序列化原生 tools”时用**有界目录 + 严格 JSON 信封**，并在目录里标 `nativeToolCalling=false`；[McaDshGatewayBackend.kt:537-539](../../app/src/main/java/com/muyuchat/mca/dsh/McaDshGatewayBackend.kt#L537-L539) 把引擎的 `CONTEXT_LENGTH_EXCEEDED` 映射成如实失败（“The request does not fit the local model context.”）。

### 4.3 仍然存在的缺口

1. **预算不是“按模型上下文窗口”做的**：`contextWindow` 只在目录与 OpenAI wire 里传递（`DshModelCatalog.kt:54`、`DshOpenAiWire.kt:213-214`、`DshMcaPorts.kt:40-45`），adapter 用的是固定字符上限；超限靠引擎报错再映射。**建议**（P1）：在请求侧用 `contextWindow` 做一次保守预算（例如 `contextWindow*0.6` 折成字符，扣掉工具目录与输出预算），超限时**先裁工具结果/历史**再报错，并把“裁掉了什么”写进诊断。
2. **DSH 侧压缩与 MCA 预算谁负责没写清**：官方 compaction 会在 DSH 内裁历史；MCA 的 adapter 又裁一次。**建议**（P1）：在文档里明确“compaction 在前、adapter 硬顶只做兜底”，避免同一段历史被两套策略重复裁。
3. **没有可观测性**：当前 UI/日志不显示“本次请求被裁了多少、哪些工具不可用”。**建议**（P2）：把 `maxPromptedToolCatalogChars` 触发的省略做成一条诊断事件（不含用户内容）。

---

## 5. 待办汇总（按优先级）

| 级别 | 项 | 落点 | 验收 |
|---|---|---|---|
| P0 | 在真机上确认主面板空白是否已被 §1 的 shim 解决（本次报告写作时 shim 是 02:12 落盘，尚未有设备证据） | — | 截图 + `[data-slot-error]` 为空 |
| P1 | shim 增加 `--mca-dsh-vh` px 变量 + `visualViewport.scroll` 重算 | `DshPageLayoutShim` | 键盘弹起/收起、旋转、横屏各一次截图 |
| P1 | 页面级 safe-area（`viewport-fit=cover` 所有权 + `env()` 成对规则 + `box-sizing:border-box`） | 移动适配层（只读注入） | 刘海/手势条设备截图 |
| P1 | 缩放回归；必要时 `setSupportZoom(false)` | `DshWebViewFactory` | 双指缩放、聚焦、旋转 |
| P1 | 模型感知的上下文预算（`contextWindow` → 字符预算 → 先裁后报错） | `DshAdapterLimits`/`McaDshGatewayBackend` | 单测 + 真实小上下文模型一次超限 |
| P2 | 用户自选工作区目录（SAF → Node 路径） | `DshCapabilityPort` 生产实现 | 需先定“允许根”策略 |
| P2 | 弹层夹具（短屏/键盘/横屏） | 移动适配层 | 单独截图验收（冻结视觉） |
| — | PiP / LAN / 原生目录选择器 | — | 本文建议不做（理由见 §2.3-3、§3.3-4） |

---

## 6. 非声明

1. 本文**没有**运行 DSHA，也没有在其设备上验证 §1/§3 的任何修复；所有 DSHA 结论都是源码阅读。
2. 本文**没有**在 MCA 设备上验证 `DshPageLayoutShim` 是否真的修好了本次空白主面板；该文件在 2026-10-20 02:12 才落盘，本文写作时**无设备证据**。
3. `DSHA_WORKSPACE_DOCUMENTS` 的消费方（官方 workspace controller 或 DSHA 补丁）**未核对**；只能说 DSHA 在启动 shell 里注入了该变量，且其运行环境里 `/root/Documents` 真实存在。
4. MCA `DshCapabilityPort` 是否已有生产实现，本次只做了源码检索（未发现实现类）；若并行任务已补，需以最新源码为准。
5. DSHA 的 `dsh-web-mobile` 是**第三方插件**（上游 `mexiaosqwq/dsh-web-mobile`，MIT；DSHA 锁定 commit `ab3a85be…`，见主文档 §3.6）。引用它的 CSS/JS 行为不等于可以照抄其代码；若要复用，必须按主文档 §7 的许可证口径逐文件核对。
6. 本文不改变任何 MCA 验收状态；P0/P1 的完成必须由真机截图与日志绑定到具体 APK 哈希，不能以“代码已写”代替。
