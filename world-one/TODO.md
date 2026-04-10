# World One — TODO

记录架构讨论中已达成共识但尚未实现的功能点。

---

## Memory 系统（AIP Memory Module）

> 设计文档：`aip/docs/memory-design.md`  
> 代码包：`aip/src/main/java/org/twelve/entitir/aip/memory/`

### 已完成（接口与模型定义）

- [x] `aip/docs/memory-design.md` — 完整设计文档（类型、范围、加载规则、Consolidation、存储）
- [x] `Memory.java` — 核心 record（五大类型、多维度质量指标、关联网络）
- [x] `MemoryType` / `MemoryScope` / `MemoryHorizon` / `MemorySource` / `LinkType` 枚举
- [x] `MemoryLink.java` — 记忆间有向关联
- [x] `MemoryStore` — 持久化接口（save / supersede / promote / query / recordAccess）
- [x] `MemoryLoader` — 加载接口（按规则检索 → 组装注入文本）
- [x] `MemoryConsolidator` — 整合接口（对话后异步 CRUD memory）
- [x] `ContextComposer` — 上下文组合接口（多层提示 + Memory注入 + 对话窗口）
- [x] `MemoryQuery` — 查询参数 Builder

### Phase 1：基础框架 + GOAL Memory（待实现）

**目标**：最小可用 memory，解决"为什么要重新进入 canvas"的上下文丢失问题。

- [ ] 建立数据库表 `worldone.memories`（参考 memory-design.md §8.1）
- [ ] 实现 `JdbcMemoryStore`（PostgreSQL，全文检索）
- [ ] 实现 `DefaultMemoryLoader`（按加载规则，Phase 1 跳过语义向量）
- [ ] 实现 `RuleBasedMemoryConsolidator`（Phase 1：规则驱动，不用 LLM）
  - `world_design` 工具调用成功 → CREATE GOAL memory（"正在设计XX世界，session_id=xxx"）
  - `world_add_definition` 调用成功 → CREATE EPISODIC memory
  - `world_archive` 调用成功 → SUPERSEDE 对应 GOAL memory
- [ ] 实现 `DefaultContextComposer`（worldone 层，替换 `GenericAgentLoop.contextWindow()`）
- [ ] `GenericAgentLoop` 集成：调用 `ContextComposer.compose()`，对话后提交 `MemoryConsolidator`

### Phase 2：LLM 驱动的 Consolidation

- [ ] 实现 `LLMMemoryConsolidator`（异步线程，调用 Memory LLM 分析对话）
- [ ] 支持 CREATE / SUPERSEDE / PROMOTE / GOAL_PROGRESS / LINK 操作
- [ ] 矛盾检测与标记（contradicts 字段）

### Phase 3：语义检索（向量化）

- [ ] pgvector 扩展或外部 Qdrant 集成
- [ ] content embedding 存储与更新
- [ ] 语义相似度检索替换全文检索
- [ ] `MemoryLoader` 中使用向量化的 `retrievalScore()` 排序

---

### Layer 2 细化：Skill 调用前的 prompt 注入

**背景**：Skill 定义（`worldDesignSkill()` 等）的 Layer 2 `prompt` 字段描述了"如何执行这个 skill"，目前只写在 skill 定义里供测试读取，并没有真正注入给 LLM。

**目标**：worldone 在将某个 skill 暴露给 LLM 时，把该 skill 的 `prompt` 字段动态追加到 system context，让 LLM 知道执行该 skill 时的具体行为规范。

**实现思路**：
- `AppRegistry` 建立 `skillName → prompt` 索引
- `GenericAgentLoop` 在 LLM 确定要调用某个 skill 后（`tool_calls` 阶段），把该 skill 的 prompt 追加到下一轮的 system context
- 或者：在聚合 system prompt 时按 skill 分组注入

**相关文件**：
- `AppRegistry`
- `GenericAgentLoop.chat()` / `contextWindow()`
- `SkillsController.buildSkillList()`

---

## 架构完善

### canvas_skill 子层结构

**背景**：当前 `canvas_skill.prompt` 已写入 widget manifest，但 `GenericAgentLoop` 只加载了 `canvas_skill.tools`，并未将 `canvas_skill.prompt` 注入 LLM context（目前靠 `context_prompt` 承担）。

**目标**：进入 canvas 后，除了追加 `context_prompt`（领域知识），也追加 `canvas_skill.prompt`（工具使用指令），明确分开两个子层。

**相关文件**：
- `AppRegistry.widgetContextIndex`（可扩展或新增 `widgetCanvasPromptIndex`）
- `GenericAgentLoop.contextWindow()`

---

### AIPP 规格测试更新

**背景**：新增了 `canvas_skill` 字段结构，`AippAppSpec` 和 `WorldEntitirAippSpecTest` 尚未覆盖此字段的合法性验证。

**目标**：
- `AippAppSpec.assertValidWidgetStructure()` 增加对 `canvas_skill.tools`、`canvas_skill.prompt` 字段的断言
- `WorldEntitirAippSpecTest` 新增 canvas_skill 合规测试

**相关文件**：
- `aipp-protocol/src/main/java/org/twelve/aipp/AippAppSpec.java`
- `world-entitir/src/test/java/WorldEntitirAippSpecTest.java`

---

## 权限设计（Worldone 层）

### 背景

当前所有通过 LLM 触发的 world / entity 操作均无权限检查，任何用户都可以：
- 创建 / 修改 / 删除（归档）任意 World
- 在任意 World 内增删改 Entity 和关系

### 设计目标

#### P1：World 级别权限

| 操作 | 权限描述 |
|---|---|
| `world_list` | 已登录用户均可查询（只返回自己有权限的 world） |
| `world_design`（创建新 world） | 需要 `WORLD_CREATE` 权限 |
| `world_archive`（删除 world） | 需要 `WORLD_OWNER` 或 `WORLD_ADMIN` 权限 |
| `world_build`（发布 world） | 需要 `WORLD_ADMIN` 权限 |

#### P2：Entity 级别权限

| 操作 | 权限描述 |
|---|---|
| `world_add_definition` | 需要 `WORLD_EDITOR` 权限 |
| `world_modify_definition` | 需要 `WORLD_EDITOR` 权限 |
| `world_remove_definition` | 需要 `WORLD_EDITOR` 权限 |

### 实现思路

1. **权限模型**：`world_members` 表（session_id, user_id, role: OWNER/ADMIN/EDITOR/VIEWER）
2. **检查时机**：在 `WorldOneAgentTool.execute()` 层切面（AOP 或手动注入 `PermissionChecker`），执行前验证
3. **Worldone 层**：`GenericAgentLoop` 中注入当前 `loginUser`，传递给 tool 执行上下文
4. **工具层**：`WorldOneSession` 或独立 `PermissionContext` 携带 `userId`，tool 内按需校验
5. **LLM 影响**：权限不足时 tool 返回 `{"error":"permission_denied"}`，LLM 据此回复用户

### 相关文件（待创建 / 修改）
- `worldone/GenericAgentLoop.java`（注入 loginUser）
- `world-entitir/WorldSessionStore.java`（加 world_members 表操作）
- 新建 `world-entitir/WorldPermissionChecker.java`
- `aip/WorldOneAgentTool.java`（接口可扩展权限参数）
