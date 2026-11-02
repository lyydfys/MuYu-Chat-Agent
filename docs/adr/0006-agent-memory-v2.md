# ADR 0006：Agent 记忆 V2（可审计写入、检索、链接与外发）

- 状态：Proposed（N00 草案；N02/N13–N16 实现中）
- 日期：2026-10-20
- 关联：README §3/§5、DESIGN §6、EXECUTION_PACK N02/N13–N16、PARITY_ACCEPTANCE MEM-01..25、REG-02/03
- 兼容：Room 29 现有空间/条目/分块 ID 与既有管理页行为保留

## 背景

V1 已有 Agent 独立记忆空间、条目/分块、词法检索、选择授权与 `memory.search`。静态审查发现：

- 删除路径先按 memoryId 删 chunks、再按 spaceId+memoryId 删 item，A 空间配 B 条目可能误删 B 的 chunks（B06）。
- 检索先固定排序分页再 rank，后页/新空间相关条目不可达（B05）。
- 记忆用伪逻辑路径 `memory` 进入 workspace scope，审批摘要无法表达真实资源（B07）。
- 迁移 fixture 未写非空 revision（B08）。
- 缺 revision/metadata/proposal/link、语义索引、提取/自动保存、整理与外发 provenance。

## 决策

1. **唯一 writer + 真实 scope**：Room 记忆仓储是唯一写者；UI、工具、脚本都走同一业务事务。ResourceScope v2 的 `Memory(spaceIds,itemIds?,revisions,access)` 表达真实空间/条目/版本，旧 workspace 审批按已知规则 upcast，旧 memory 伪路径审批失效并重新申请。
2. **归属校验先行**：删除/移动/链接先在同一事务校验 item 属于目标 space，再按父关系清理 chunks/links/索引；错误归属零变化。
3. **版本与元数据**：item revision CAS、text hash、来源（task/run/artifact）、validTime/recordedAt、folders/tags/typed properties/aliases、proposal、weighted directed links；旧字段显式默认/回填，未知 enum 明确错误。
4. **Query-driven 混合检索**：授权空间内先取词法候选（FTS/有界倒排），扫描预算与 topK 独立；本地 embedding 可用时同 space/同 fingerprint 取向量候选，RRF 融合、可选本地 rerank、一跳链接扩展仍需端点授权；最终 context 重算预算，返回 itemRevision/chunkHash/sourceRef/truncated。
5. **本地辅助模型**：embedding/提取/重排经 MCA 现有资源 owner 申请，不新建 engine；embedding 必须是真实文本向量（tokenizer/pooling/normalize/dim/fingerprint 固定），不得用文本 hash 或图像 CLIP 冒充；无本地向量能力时明确 BLOCKED + 词法回落。
6. **写入与自动提取**：手动保存与模型提案共用事务；默认只产生待确认 proposal；自动保存必须窄策略（space/来源/类别/期限），SECRET/凭据不自动写；修改用 revision CAS，冲突/覆盖/删除需决议。
7. **删除与外发**：tombstone 立即拒绝新查，再可恢复清理派生数据；任务副本删除范围明确。provenance 由宿主保守 join 输入全集，cloud/model/network/MCP/分享/导出均为 egress 检查点；LOCAL_ONLY/SECRET 阻断，只有用户明确 declassification 可降级。

## 备选方案

- 把历史塞进 prompt 或仅 latest-N 词法：拒绝。不满足 recall/隔离/可审计。
- 全库 ANN 后过滤空间：拒绝。会泄露/饥饿未授权候选。
- 用云 embedding 验收离线语义：拒绝。不能把联网能力写成离线 PASS。

## 后果

- Room 新表按 N13/N16 分配迁移版本；`@Database`/schema/迁移聚合由整合者维护，内容权威仍是 Room，索引可重建。
- 检索指标（Recall@6、MRR@10、无关键词子集）需要固定 qrels；词法/向量/融合分别记录，未达标保持 FAIL/NOT_RUN。
- 记忆文本/来源/凭据进入日志或错误时必须脱敏；记忆不直写聊天/助手/知识库表。

## 退出条件

MEM-01..25 逐项有候选证据；跨空间删除零变化、迁移 fixture 真实执行、query-driven 晚页召回、未授权检索泄露为零、自动提取误写为零、离线语义/词法状态如实；REG-02/03 旧数据与旧审批边界通过。
