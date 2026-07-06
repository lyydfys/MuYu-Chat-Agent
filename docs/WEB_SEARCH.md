# MCA 联网检索与研究模式指南

MCA 的联网检索不是让本地模型自己访问互联网，而是由 App 先读取网页或调用你配置的搜索服务，再把经过裁剪和标注的来源上下文注入当前这一轮对话。来源卡、检索过程和诊断记录都会保存在本机，方便判断本轮回答是否真的用到了网页资料。

## 能力边界

| 能力 | 当前状态 | 说明 |
|---|---|---|
| 直接读取 URL | 已支持 | 在设置中启用联网检索后，可以读取用户消息里的公开网页链接。 |
| 关键词搜索 | 已支持 | 需要配置 SearxNG、Brave Search、Tavily、Jina Search 或自定义 JSON 搜索接口。 |
| 智能触发 | 已支持 | 遇到“搜索、最新、官网文档、实时信息、URL、调研/评测/对比”等问题时自动生成检索计划。 |
| 深度研究 | 已支持 | 会把问题扩展成官方资料、评测对比、限制问题、社区证据等多组检索词。 |
| 来源卡片 | 已支持 | 回复下方显示来源数量、站点、可信类型、摘要、复制链接和打开网页。 |
| 诊断记录 | 已支持 | 设置页会记录触发依据、查询词、耗时、来源质量、失败原因和闭环检查。 |
| 网络预检 | 已支持 | 设置页可先检查手机活动网络、公网验证、VPN/代理/私人 DNS、公网 DNS、搜索接口域名、Base URL 和必要 API Key。 |
| 私网读取 | 默认阻止 | localhost、局域网、link-local、保留地址默认不会被网页读取器访问。 |

## 推荐配置顺序

1. 打开 `设置 -> 联网检索`。
2. 开启 `启用联网检索`。
3. 先用完整 URL 做一次 `测试当前填写`，确认网页直读可用。
4. 配置搜索服务：
   - `SearxNG`：推荐自建或可信实例，公共实例可能限流或禁用 JSON。
   - `Brave Search`：填写官方 Search API Key；常规搜索可填官方根地址或 Web Search 路径，AI grounding/RAG 可用 LLM Context 路径。
   - `Tavily Search`：填写 Tavily API Key；可填官方根地址或 Search API 路径。
   - `Jina Search`：填写 Jina Key；正文抓取较弱时会尝试 Jina Reader。
   - `自定义 JSON`：适合自建网关或兼容搜索 API。
5. 点击 `网络预检`，先确认手机活动网络、公网验证、DNS、VPN/代理/私人 DNS、Base URL 和必要 Key 没有明显问题。
6. 真实搜索源点击 `闭环自检`；公开 JSON 自检源点击 `协议自检`，确认最近检索里出现来源、质量分和闭环检查。
7. 回到聊天页，在输入框左下角 `+` 菜单里切换 `联网检索` 或 `研究模式`。

## 搜索服务地址填写

设置页会对不同服务做路径预检。DNS 和 Key 通过不代表协议路径一定正确，下面这些地址是推荐起点：

| 服务 | 推荐地址 | 鉴权方式 | 说明 |
|---|---|---|---|
| SearxNG | `https://your-searxng.example` | 通常不需要 | 填实例根地址即可，MCA 会请求 `/search?format=json`。公开实例可能禁用 JSON 或限流。 |
| Brave Search | `https://api.search.brave.com`、`https://api.search.brave.com/res/v1/web/search` 或 `https://api.search.brave.com/res/v1/llm/context` | `X-Subscription-Token` | 常规搜索可直接填官方根地址，MCA 会自动补全到 `/res/v1/web/search`；需要 Brave 聚合好的 grounding 片段时可手动填写 LLM Context。不要填控制台、聊天接口或非搜索路径。 |
| Tavily Search | `https://api.tavily.com` 或 `https://api.tavily.com/search` | `Authorization: Bearer <key>` | 填官方根地址时，MCA 会自动补全 `/search`，并使用 POST JSON 调用。 |
| Jina Search | `https://s.jina.ai` | `Authorization: Bearer <key>` | 用于搜索结果；网页正文不足时，MCA 会尝试 Jina Reader 增强公开网页摘要。 |
| 自定义 JSON | 你的搜索网关地址 | 可选 Bearer Key | MCA 支持 `{query}`、`{max_results}` URL 模板，也会尝试 `q`、`query`、`max_results` 参数，并解析常见 JSON 结果结构。 |

如果你通过自建代理转发 Brave、Tavily 或 Jina，建议优先做成 `自定义 JSON`，让返回结构稳定可控。这样预检、来源卡和失败诊断会更直观。

## 聊天页怎么用

输入框默认保持干净，联网能力放在左下角 `+` 菜单里：

- `联网检索：智能`：跟随设置页策略自动判断是否联网。
- `联网检索：本轮开启`：当前这一轮强制检索。
- `联网检索：本轮关闭`：当前这一轮不检索。
- `研究模式：自动`：普通问题轻量搜索，调研/对比/方案类问题自动扩展多源研究。
- `研究模式：深度`：下一轮尽量扩展成多角度检索。
- `研究模式：普通`：下一轮只做轻量搜索。

发送后，助手消息下方会先出现“正在检索”的过程卡，完成后替换为最终检索过程和来源卡。展开过程卡可以看到触发依据、检索目标、证据分组、不确定性和闭环检查。

## 自定义 JSON 接口

自定义接口适合接入自建搜索服务。MCA 会向接口传入查询词，并尝试解析常见返回结构：

- 顶层数组。
- 顶层对象里的 `results`、`items`、`data`、`hits`、`organic_results`。
- 嵌套对象，如 `data.results`、`response.items`。

常见字段会被自动识别：

| 类型 | 字段示例 |
|---|---|
| 链接 | `url`、`link`、`href`、`html_url`、`story_url`、`canonical_url`、`uri`、`displayLink`、`formattedUrl`、`source.url` |
| 标题 | `title`、`name`、`full_name`、`story_title`、`question`、`source.title` |
| 摘要 | `snippet`、`description`、`summary`、`excerpt`、`text`、`content` |
| 正文 | `raw_content`、`rawContent`、`body`、`page_content`、`pageContent`、`markdown`、`content_text` |

设置页的 `填入公开 JSON 协议自检源` 只用于验证 JSON 接入、上下文注入、来源卡片和诊断链路。它不是通用搜索服务，正式使用时请配置自己的可信搜索源。如果聊天页或诊断记录显示 `公开 JSON 自检源`，说明当前还处在协议验证状态，适合做链路测试，不适合拿来搜索全网实时资料。

聊天页会把公开 JSON 自检源视为 `协议自检`，不会把它当作真实关键词搜索源自动使用。此时仍可读取用户消息里的公开 URL；如果要让“联网检索”真正搜索全网，需要配置 SearxNG、Brave、Tavily、Jina 或可信自建搜索网关。

## 判断是否真的联网成功

一次成功的联网回答应该同时具备：

- 聊天回复下方有 `检索过程` 卡。
- 来源卡里有可打开的 URL、站点名和摘要。
- 展开过程卡能看到触发依据、检索目标、来源数量和闭环检查。
- 设置页 `最近检索` 里能看到同一次记录。
- 回答正文里尽量出现 `[1]`、`[2]` 这样的来源编号。

如果回答说“我无法联网”或“主要基于知识库”，但下方已经有来源卡，说明云端或本地模型没有完全遵守上下文。MCA 会通过提示词和引用审计尽量压制这种话术，但不同模型的服从度仍会有差异。

## 常见失败

| 现象 | 可能原因 | 处理建议 |
|---|---|---|
| `联网检索未配置` | 只开启了开关，没有配置关键词搜索服务 | 配置 SearxNG/Brave/Tavily/Jina/自定义 JSON；如果只是读 URL，确保消息里有完整公开链接。 |
| `网络预检需检查` | 手机网络未验证、公网 DNS、VPN/代理/私人 DNS、应用联网权限、Base URL 域名或必要 API Key 有问题 | 先确认浏览器能打开公网网页；再检查 Wi-Fi 登录页、系统/安全中心是否禁止 MCA 使用 WLAN 或移动数据、私人 DNS/VPN/代理规则、搜索接口域名、协议路径和 Key。 |
| `鉴权失败` 或 401/403 | API Key 错误、权限不足、额度不可用 | 重新复制 Key，确认服务商账号和接口权限。 |
| 404 | Base URL 或接口路径不对 | 使用服务商文档里的搜索端点，不要把聊天模型端点填到搜索页。 |
| 429 | 服务限流 | 换自建/付费/备用搜索源，或稍后重试。 |
| 无来源 | 搜索服务返回空、公共实例屏蔽 JSON、相关性过滤后没有可用资料 | 换更明确的问题，增加官方关键词，或配置备用搜索源。 |
| 网页读取被阻止 | URL 指向 localhost、局域网、link-local 或保留地址 | 这是默认安全策略；公开版本不建议绕过。 |
| 搜索很慢 | 开启了正文抓取、多组研究查询、移动网络不稳定 | 降低结果数量，关闭正文抓取，或使用更快的搜索源。 |

## 真实服务排障矩阵

最近检索卡片会把失败转换成 `处理建议`，复制诊断时也会带上这些建议。常见服务按下面口径排查：

| 服务 | 404 时优先检查 | Key / 权限 | 备注 |
|---|---|---|---|
| SearxNG | 通常填实例根地址，MCA 会自动访问 `/search?format=json` | 多数实例不需要 Key | 公共实例可能关闭 JSON、限流或返回空结果，稳定使用建议自建。 |
| Brave Search | 常规搜索可填 `https://api.search.brave.com` 或 `https://api.search.brave.com/res/v1/web/search`；AI grounding/RAG 可用 `https://api.search.brave.com/res/v1/llm/context` | `X-Subscription-Token` | 官方根地址会自动补全到 Web Search。不要填写 Brave 首页、控制台、聊天模型接口或非搜索路径；Web Search 返回的 News/Discussions/FAQ/Videos 也会被解析为来源。 |
| Tavily Search | 可填 `https://api.tavily.com` 或 `https://api.tavily.com/search` | `Authorization: Bearer <key>` | 官方根地址会自动补全 `/search`；MCA 使用 POST JSON，正文抓取开启时会请求 advanced 搜索深度。 |
| Jina Search | 搜索服务推荐 `https://s.jina.ai` | `Authorization: Bearer <key>` | Reader 只用于网页正文增强，不要把 Reader 地址当作搜索地址。 |
| 自定义 JSON | 确认网关接收 `q/query/max_results`、自带查询参数，或 URL 模板如 `/search?q={query}&limit={max_results}` | 可选 Bearer Key | 返回结构建议包含 `results/items/data/hits/organic_results`，条目里至少有 `url/link/href` 和标题/摘要。 |

如果诊断显示 `公开 JSON 自检源`，说明当前只是验证协议链路，不代表已经接入全网搜索。它能证明请求、JSON 解析、上下文注入和来源卡片可用，但正式使用仍应配置上表中的真实搜索服务或可信自建网关。

## 隐私说明

- 关键词搜索会发送到你配置的搜索服务。
- 直接 URL 读取会访问该网页。
- 本地模型不会自己联网，MCA 只把摘要注入当前一轮。
- API Key 存在本机设置中，优先使用 Android Keystore 加密。
- 最近检索诊断只保存在本机，可在联网检索设置页清空。
- 短缓存只保留搜索结果摘要，不保存 API Key；直接 URL 读取不缓存。
