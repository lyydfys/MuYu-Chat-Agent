# ADR 0011: 模型生成预设与角色卡字段覆盖

- 状态：Accepted
- 日期：2026-11-03
- 相关：`ModelGenerationProfileStore`、`AssistantRecord.paramsJson`、`AssistantEditorPage`

## 背景

生成参数此前实际上绑定在角色卡上：`AssistantRecord.paramsJson` 保存完整快照，聊天页参数面板和“导入酒馆预设”都会把整套参数写入当前角色卡；模型侧的 `ModelGenerationProfileStore` 虽然存在，但两条写入路径互相覆盖，无法表达“模型默认值 + 角色卡个别覆盖”。

## 决策

1. **模型预设是语义默认值的权威来源。** 现有 `ModelGenerationProfileStore`（`mca.model.generation.profile.v1`）继续按键 `local:<id>` / `cloud:<id>` 保存每个模型的采样、输出、思考模式与运行参数。
2. **角色卡只保存显式覆盖。** `AssistantRecord.paramsJson` 保留完整值快照用于导出/旧版兼容，并新增 `override_fields`（`mca.assistant.generation.v2` 内的元数据）记录用户明确覆盖的字段。没有该元数据的旧角色卡按“整份快照覆盖”处理，保持既有行为。
3. **解析顺序固定为：角色卡覆盖 → 模型预设 → 全局/内建默认。** 系统提示词始终来自角色卡/会话快照，不进入模型预设。
4. **聊天页参数面板只写模型预设。** `updateParams` 只把用户实际改动的字段覆盖到当前模型 profile，然后重新计算生效参数；不再写角色卡。
5. **角色卡编辑器用模型预设初始化展示值。** 保存时按“与模型预设不同的字段”生成 `override_fields`；“使用模型预设”会把编辑器字段恢复为模型预设值，保存后覆盖集合为空。
6. **旧数据不回写。** 既有角色卡不会在加载时被批量迁移成稀疏覆盖；用户编辑并保存时才生成新的覆盖元数据。

## 影响与回退

- 现有用户首次编辑角色卡后即可获得“跟随模型预设”的行为；未编辑的旧卡保持旧行为。
- 模型 profile 的写入仍然只有一个 owner（`MainViewModel.updateParams` / 模型切换路径），角色卡保存不再写模型 profile。
- 回退：保留 `override_fields` 元数据即可恢复旧快照语义；读取端仍能解析无元数据的旧格式。禁止通过删除用户 `paramsJson` 字段来“迁移”。
