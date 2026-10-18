# MCA V9 验证场景（当前切片）

## 搜索与可访问性

- 默认抽屉：无搜索框，列表分组和时间显示与 before 等值。
- 点击搜索：搜索框出现、自动聚焦、IME 打开；再次点击关闭并清空 query/focus/IME。
- 查询：大小写不敏感，覆盖 title/displayName/summary/updatedAtText；前后空白被 trim。
- 空结果：显示现有“没有找到匹配的对话。”空态。
- 清除：只清空 query，不关闭搜索、不误触会话。
- 输入法 Search：收起键盘，结果保持。
- 深色、fontScale 1.3/2.0、长标题和长摘要：不裁切主要操作，不遮挡列表。
- TalkBack：搜索、关闭搜索、清空搜索、打开应用菜单均有准确名称。

## Markdown 等值

- `content == fullContent`：结果、代码块、copyCode 与原来的单内容语义一致。
- `content` 为截断显示片段、`fullContent` 为完整原文：代码块按顺序关联完整 copyCode。
- 未闭合围栏、CRLF/LF、emoji/ZWJ、多个代码块和 16_384 边界：不改变原文和渲染块顺序。
- 不宣称帧率或内存提升，除非另建 Macrobenchmark 并产生原始 trace。

## 真机验证

- 设备：`98a37aa7`，浅/深色，fontScale 1.0；搜索键盘开启/关闭。
- before：`artifacts/ui-v9/before/*`。
- after：实现后固定同设备、同分辨率、同 density/fontScale 重新截图。