# 上线前检查（MCA 官网）

预览原型本身可以直接打开；这份清单是把它变成正式站点之前必须逐条确认的事。
每条都写清“为什么”和“怎么验”，不写成一句“注意安全”。

---

## A. 域名与规范地址

- [ ] 确定正式域名（建议同时确认 `.com` / `.io` / `.dev` 中可用的一个，避免与 Google 混淆）。
- [ ] 把 `index.html` 头部的 `<!-- 上线前必改 -->` 注释替换为真实 canonical：
      `<link rel="canonical" href="https://<域名>/">`。
- [ ] `robots.txt` 里的 `Sitemap:` 换成真实域名；新增 `sitemap.xml`（单页站点可只列 `/`）。
- [ ] OG 图使用绝对 URL：`<meta property="og:image" content="https://<域名>/og.png">`。
      相对路径在部分社交平台抓取器里不生效。
- [ ] 确认 `og.png`（1200×630）在真实抓取器中显示正常（可用各平台的调试器预览）。

## B. 版本数据

- [ ] `data/release.json` 与 GitHub 最新 Release 一致：
      `node docs/website/scripts/fetch-release.mjs --check`
- [ ] 页面上的版本号、体积、SHA-256 均从该文件核对；发布新版本后重新运行
      `node docs/website/scripts/fetch-release.mjs` 并提交。
- [ ] 若发布流程接入 CI：Release 发布事件 → 拉取 release.json → 部署。失败时保留上一版可回滚。

## C. 下载链路

- [ ] GitHub 直链 200，且 `Content-Type: application/vnd.android.package-archive`。
- [ ] 镜像站 200（当前与 app 内一致使用 `gh-proxy.com`），并在页面上明确标注“第三方镜像”。
- [ ] SHA-256 与 Release 资产的 `digest` 完全一致（64 位，小写）。
- [ ] 二维码在 320 px 宽屏幕上仍可扫描（当前为内联 SVG，无外链依赖）。
- [ ] 移动端点击下载按钮不会误触跳转两次；`target="_blank"` 的外链均带 `noopener`。

## D. 内容准确性

- [ ] 三个工作台（DSH / 生图 / 生视频）的“预览 / 实验性 / 条件可用”标签与当前 Release Notes 一致。
- [ ] 每张工作台截图都能追到仓库里的设备证据（`scripts/device-assets.py` 与
      `scripts/crop-workspace-shots.py` 都写明来源文件）；若证据目录被清理，需重新截图，
      不能保留来源不明的画面。
- [ ] 截图里的设备状态（未加载模型、失败态）不要被误读成“营销样张”：图注已写明来源，改文案时不要去掉。
- [ ] **不要再用冒烟跑当能力证据。** 平板真机上已有 25 个 480×832 · 24 fps 视频与五条
      `COMPLETED · FINALIZE · 已出片` 记录；若某次重拍只剩 64×64 冒烟，应如实标注规模，
      不要把它写成成品样张。
- [ ] DSH 版本号与内置内核实际版本一致（当前 `0.2.2-rc.1`）。
- [ ] 生视频默认参数（480×832、4 步、24 fps、帧数 4k+1）与 `VideoUsageCopy` 一致。
- [ ] 生图三种生成方式（文生图 / 图生图 / 局部重绘）与真机入口一致；图库筛选器数量（当前 8 项）
      与真机一致，改版时重查。
- [ ] 不出现“最快 / 最强 / 10×”这类无来源的绝对表述。
- [ ] 若删除或调整任何能力描述，同步更新 FAQ 与 05 / LIMITS。

## E. 性能与资源

- [ ] 首屏图走 `picture` + WebP（当前 hero 720/1080 两档，19/30 KB），PNG 仅作回退。
- [ ] 工作台配图均为 WebP、带 `width/height` 与 `loading="lazy"`，当前活跃资产合计约 477 KB。
- [ ] 折叠下截图 `loading="lazy"`，且带显式 `width/height`（避免 CLS）。
- [ ] 无第三方脚本、无外链字体、无统计 SDK（当前为纯静态，可直接上 CDN）。
- [ ] 若接入统计：选无 Cookie 方案，只记 `download_click` / `mirror_click` / `github_click`。
- [ ] Lighthouse（移动端）Performance / A11y / Best Practices / SEO ≥ 95。
- [ ] `assets/retired/` 里的旧图不随站点发布（构建时排除），否则会白带约 290 KB。

## F. 可访问性

- [ ] 键盘可走完：导航 → 下载 → 工作台三个 tab → 路径切换 → 复制 → FAQ。
- [ ] 工作台 tab 支持 ↑/↓/←/→/Home/End，`aria-selected` 与面板同步。
- [ ] `prefers-reduced-motion: reduce` 下无开屏动画、无滚动揭示。
- [ ] 320 / 360 / 390 / 768 / 1024 / 1440 六档均无横向滚动。
- [ ] 正文对比度 ≥ 4.5:1，小字蓝使用 `#185ABC`（6.17:1）。

## G. 部署

- [ ] 静态托管（Cloudflare Pages / GitHub Pages / 对象存储 + CDN）。
- [ ] HTTPS 强制跳转；HTML `no-cache`，静态资源 `immutable`。
- [ ] 自定义 404（本目录的 `404.html`），并确认托管平台确实使用它。
- [ ] CSP：`default-src 'self'; img-src 'self' data:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'`（按实际内联脚本调整）。
- [ ] 保留上一版产物与回滚命令。
- [ ] 中国大陆访问：若长期面向国内，评估国内 CDN/备案；否则在页面说明链路并保留镜像。

## H. 交付前自检命令

```powershell
cd docs/website
node scripts/check-site.mjs          # 链接 / 版本一致性 / 占位符 / 图片属性
node scripts/fetch-release.mjs --check   # 发布数据是否过期
```

## I. 主题

- [ ] 浅色/深色两套主题均逐区块检查（当前 10/10 区块随主题翻转，夜间区块单独下沉）。
- [ ] prefers-color-scheme 跟随系统；手动切换写入 localStorage.mca.theme 并优先生效。
- [ ] 首屏不闪白：主题在内联 <head> 脚本里解析，先于首次绘制。
- [ ] 开屏在浅色主题下是**纸白舞台**、深色下是近黑舞台（整台跟随主题，不只是装饰色）。
     实测：浅色平均亮度 247.7 / 深色 10.3；开屏 5 类文字最低对比度 4.84:1 / 10.34:1。
- [ ] 361 个文本节点的对比度审计通过（浅色/深色均为 0 项低于阈值）。
- [ ] meta[name=theme-color] 随主题更新（浅 #F8F9FA / 深 #0B0D10）。
- [ ] QR 码不参与反色（--qr 固定白底），否则扫码失效。

## J. 三主题（浅色 / 深色 / 柔光玻璃）

- [ ] 主题切换是**分段控件**（role=group + 三个 aria-pressed），不是单键循环。
- [ ] 窄屏（<=480px）导航栏隐藏切换控件、抽屉内提供同一组控件，状态同步。
- [ ] 玻璃的**六层**齐全：渐变填充、::before 边缘光、::after 内层柔光、
      双内阴影浮雕、.g-gloss 跟随高光、.g-atmos 氛围层。
      实测 10/10 图层检查通过（44 面板 / 44 高光层）。
- [ ] 指针移动确实改写 --gx/--gy（实测 25.00% / 75.00%）。
- [ ] @supports not (backdrop-filter) 与 prefers-reduced-transparency: reduce
      两条降级都把填充推到 0.97 并关闭氛围层与高光（实测 0.86 -> 0.97）。
- [ ] 主按钮渐变浅端不得过亮（3.59:1 不达标，已改 #1F6FD6）。
- [ ] CSS 括号必须平衡（不平衡会静默丢弃其后全部规则，曾导致开屏常显）。
- [ ] 三主题各跑一遍 361 节点对比度审计，均应为 0 项低于阈值。
- [ ] QR 码在三主题下都保持白底（不可反色，否则扫码失效）。
- [ ] 开屏在三主题下各自成立。

## K. 连续圆角与玻璃光学（v2.6）

- [ ] 所有卡片/控件使用 corner-shape: squircle（Chrome 139+）；Safari/Firefox 退化为圆弧，可接受。
- [ ] 圆角只用 7 级尺度 token，不得出现新的散值（胶囊 999px 与 focus 3px 除外）。
- [ ] 嵌套圆角满足 inner = outer - padding（例：设备外框 22px / 内屏 17px / padding 5px）。
- [ ] 最大面板（.source、工作区）必须带圆角；工作区是两个半块合成**一整块**，不得出现中间接缝。
- [ ] 玻璃光学三层必须实测成立：页面 < 填充 < 边光。
      当前基线：231 / 248 / 253（填充比页面亮 +16.9，边光比填充亮 +5.0）。
- [ ] 填充 alpha 不得高于 ~0.7；高于此值填充接近纯白，边光没有可亮的余地。
- [ ] 边缘光用 conic-gradient（绕周长的角度函数），不要用单条线性渐变。
- [ ] 对比度脚本遇到取不到数据的主题必须报失败，不得在空集上打印 PASS。

## L. 饱和度与内容层次（v2.8）

- [ ] 环境平均饱和度维持在 **0.05-0.07**（高于 0.09 会显艳，低于 0.03 会显灰）。
- [ ] 只调彩度不调明度：降饱和不得改变文字对比度基线。
- [ ] 区块带必须**交替**且有可辨落差（相邻带 >= 2 级，当前 13-25 级）。
- [ ] 内容面板分主次：主面板更不透明 + 更深投影；次面板更平。
- [ ] 装饰光不得使用 ilter: blur()（改用径向渐变衰减），否则叠加 backdrop-filter 会卡死。
- [ ] 玻璃 --glass-blur 不超过 ~24px：44 个面板各自做 backdrop-filter，40px 会让渲染无法完成。
- [ ] 性能基线：1440x2300 全页截图应在 **~120s** 内完成（更久说明合成层过重）。

## M. 结构类缺陷（v2.9 · 像素统计抓不到的一类）

这类问题不是数值不够好，而是规则根本没生效或骨架错了。上线前必须逐项核对：

- [ ] CSS 里每个玻璃选择器都对应真实存在的类名（.ws-grid 而非 .ws）。
      验证方式：在 DOM 里查 getComputedStyle 的 ackdropFilter 非空。
- [ ] 玻璃面板内部不得再有「1px 发丝线分割」骨架（gap:1px; background:var(--line)），
      圆角面板要按真实间距分开。
- [ ] 浮层（移动端抽屉、下拉）必须接近不透明（>= 0.9），光有 blur 不够。
- [ ] 任意两张相邻配图必须真的不同：对齐后差异行数应远大于 1。
- [ ] 玻璃不得直接施加在 <table> 上：需外层 div.table-wrap 承载。
- [ ] 新增的玻璃面板要同时进入三处：CSS 列表、GLASS_SELECTORS、squircle 列表。
- [ ] 性能基线：全页渲染 <= 10s（当前 4.3s）。若骤增，先查是否有重复的模糊层。

## N. 上线就绪评审（v4.8 之后）

本节记录"能否作为旗舰级官网上线"的实测结论。每条都有脚本产出数据支撑，不是自我评价。

### N.1 已通过（21 项实测）

| 类别 | 项目 | 结果 |
|---|---|---|
| 内容完整性 | 图片 alt | **25 / 25** |
| 内容完整性 | 标题层级 | 无跳级（已修 h2→h4）|
| SEO | canonical / sitemap / robots / manifest / JSON-LD | 全部存在 |
| SEO | og:image | `og.png` 1200×630，实测 44 种量化色、3.5% 深色像素，为有效分享卡 |
| SEO | twitter:card | `summary_large_image` + image alt |
| 无障碍 | `html lang` | `zh-CN` |
| 无障碍 | 跳转链接 | 存在 |
| 无障碍 | `:focus-visible` | 存在 |
| 无障碍 | `prefers-reduced-motion` | 已适配 |
| 响应式 | 320 / 360 / 390 / 491 / 1440 横向溢出 | **0** |
| 兼容性 | `corner-shape` | 6 处 `@supports` 守卫，Safari/Firefox 降级为普通圆角 |
| 兼容性 | `backdrop-filter` | `-webkit-` 前缀齐备 |
| 兼容性 | `overflow: clip` | 有 `@supports not` 回退 |
| 卫生 | favicon / theme-color / 404 / charset / viewport | 全部存在 |
| 卫生 | 占位文本（TODO/lorem）| 无 |

规模：1132 个标记节点、CSS 147.8 KB、JS 30.8 KB。

### N.2 上线前必须在仓库外完成（无法在页面里解决）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **替换占位域名** | 三处均为 `https://mca.example.invalid`：`index.html` 的 canonical 与 og:url、`robots.txt` 的 Sitemap、`sitemap.xml` 的 loc。三处必须一致 |
| 2 | **确认发布事实** | 页面把 `v0.4.1`、`612.7 MB`、`SHA-256 4320a4ef…e6f4`、Apache-2.0 源码链接作为事实陈述，上线前须与真实产物核对 |
| 3 | **服务器配置** | HTTPS + HSTS、`.css/.js/.html` 的 gzip/brotli、缓存头。资源文件名无哈希，建议短 max-age 或加哈希 |
| 4 | **CSP** | 当前无 CSP。静态页风险低，但加一条很便宜 |

### N.3 已知限制（不阻塞上线）

| 项 | 说明 |
|---|---|
| `corner-shape` | Chrome 139+ 才有；Safari/Firefox 显示为普通圆角。属渐进增强，不影响可用性 |
| 无 Service Worker | 官网非应用，可离线需求由 APK 承担 |
| 无 SRI | 无第三方脚本，风险为零 |

### N.4 结论

**页面本身达到可上线标准。** 21 项实测全通过，无死链、无占位文本、无横向滚动、三主题对比度全部达标。

真正的阻塞项在**页面之外**：域名（3 处）、发布事实核对、服务器配置。这些无法由前端代码解决。

