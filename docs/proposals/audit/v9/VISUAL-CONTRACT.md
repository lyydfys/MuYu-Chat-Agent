# MCA V9 视觉合同（本切片）

## 必须保持

- 不得复制 Gemini/ChatGPT 品牌、标志、完整布局或资产。
- 不改现有导航、页面入口、默认密度、色系、字体族、字号、字重、行高、字距、间距、圆角语言和图标体系。
- 不改聊天、图片、模型、API、角色、知识、搜索、附件和生图的业务语义。
- 默认状态必须保持现有聊天与历史抽屉的几何和行为；新增搜索只改变“用户主动激活搜索”后的局部状态。
- 不新增渐变、玻璃、装饰阴影、卡片层级或新的强调色来解决质感问题。

## 当前有效来源

- 全局主题：`core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaTheme.kt`。
- 聊天专属色：`feature/chat/src/main/java/com/muyuchat/feature/chat/ChatColorTokens.kt`。
- 浅色 primary/background/surface：`#1A73E8` / `#F8F9FA` / `#FFFFFF`。
- 浅色 onSurface/muted/outline：`#202124` / `#5F6368` / `#DADCE0`。
- 深色 primary/background/surface：`#8AB4F8` / `#121212` / `#202124`。
- 聊天蓝：`#3F7DE8`；输入 shell/field/icon surface：`#FEFEFF` / `#F3F7FF` / `#EAF1FF`。
- 页面横向 gutter：多数聊天/抽屉内容 `18dp`。
- 输入 shell 圆角参数：`36dp`；输入 shell 外 margin：`16dp` 横向、`10dp` 纵向。
- 用户气泡圆角：`20/20/20/6dp`，最大宽度 `280dp`。
- 助手内容透明全宽渲染。
- 历史菜单/下拉菜单圆角：`20dp`，与现有组件一致。
- Markdown 分页：`16_384` UTF-16 字符；流式滚动桶：`512` 字符。

## 本切片允许的局部改动

- 搜索激活态：复用 Material3 `OutlinedTextField`，沿用现有色板，不新增 hex。
- 搜索必须能显示/收起、请求焦点、打开/关闭键盘、清空、处理无结果。
- 品牌按钮只补准确 accessible name，不改变视觉边界。
- Markdown 只消除同一输入重复解析，不改变源文本、换行、代码复制或分页合同。