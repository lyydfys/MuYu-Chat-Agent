# ADR 0012: 聊天历史删除的显式边界

- 状态：Accepted
- 日期：2026-11-03
- 相关：`ChatSessionStore`、`ChatSessionDao.reconcileSnapshot`、`ChatSessionPersistenceBounds`

## 背景

聊天历史以 UI 全量快照写入 Room。旧实现用 `pruneSessions(liveSessionIds)` 删除所有“不在本次快照中的会话”。当 `ChatSessionStore.load()` 因单行 JSON 损坏、数据库暂时不可读或历史 bounds 裁剪而返回空/部分快照时，下一次保存就会把仍在磁盘上的真实历史永久删除；记忆表不受影响，于是表现为“聊天记录全没了，但记忆还在”。

## 决策

1. **缺失不等于删除。** `reconcileSnapshot` 只在调用方明确给出 `deletedSessionIds` 时删除会话；UI 快照中缺失的持久化会话默认保留。
2. **空快照不是清空指令。** 空快照且没有显式删除时是 no-op。清空聊天、删除会话和删除最后一条会话走已有的 destructive mutation 路径，由 rollback 差集提供显式删除 ID。
3. **单行损坏隔离。** `loadRecords` 按会话捕获解析异常并记录诊断；一个损坏行只隐藏该会话，其余历史仍可显示，损坏行保留在 Room 中等待导出/修复。
4. **bounds 只限制本次写入，不删除历史。** 超过 `maxSessions/maxMessages/maxSerializedBytes` 的旧数据可以不被重写，但不会被 prune。

## 影响与回退

- 任何依赖“保存一个子集即删除其余会话”的旧路径都必须改为显式删除；当前删除/清空路径已经提供 ID。
- 回退点：`reconcileSnapshot` 的显式删除参数和 `sessionsEligibleForDeletion` 纯函数；回退会重新引入静默数据丢失，不应作为常规回退。
- 本变更不尝试恢复已经被旧版本删除的 SQLite 行；恢复只能依赖用户备份、WAL/文件快照或系统备份。
