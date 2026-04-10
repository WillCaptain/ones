# AIP Memory System — 设计文档

> 版本：0.3 · 状态：实现中  
> 参考：Generative Agents (Park et al., 2023)、MemGPT (Packer et al., 2023)、  
> A-MEM (Xu et al., 2024)、MemoryOS (2024)、HippoRAG (2024)

---

## 1. 总体目标

LLM 的 context window 有限，且全量载入聊天历史既浪费 token 又引入噪声。  
Memory 系统将对话中产生的知识**结构化持久化**，在每轮对话时**按需检索注入**，
使 Agent 具备跨会话的持续认知能力，而不依赖长对话历史。

### 组合上下文公式

```
完整 context = [系统提示（多层固定）]
             + [Memory 注入（动态检索）]
             + [对话窗口（滑动最近 N 条）]
```

---

## 2. Memory 类型（五大类）

| 类型 | 英文 | 默认操作模式 | 描述 | 示例 |
|---|---|---|---|---|
| **语义/事实** | SEMANTIC | **覆盖**（SUPERSEDE 旧记录） | 稳定的领域知识、用户信息、实体状态 | "用户是AI编程领域工程师" |
| **事件** | EPISODIC | **追加**（永远 CREATE，绝不 SUPERSEDE） | 有时序的发生记录，可回溯 | "2024-01-05 添加了 Employee 实体" |
| **关系** | RELATION | **去重创建**（先查再建，消亡则 SUPERSEDE） | 两个命名实体间的结构性联系，三元组格式 | "Will --[has_pet]--> anna" |
| **约定/黑话** | PROCEDURAL | **覆盖**（变更时 SUPERSEDE，不新建副本） | 操作偏好、习惯、用户专属约定 | "'lets edit' = 进入 canvas 编辑模式" |
| **目标/意图** | GOAL | **进度模式**（GOAL_PROGRESS；只有明确放弃才 SUPERSEDE） | 当前未完成的多步骤目标（跨轮次持续） | "设计HR本体：目标10实体，进度5/10" |

> **为何需要 GOAL 类型**：事实描述"现在是什么"，事件记录"发生了什么"，
> 而 GOAL 描述"还要做什么"——是驱动多步骤任务持续执行的关键，
> 也是 Generative Agents 论文中 *Plan* 层的对应物。

### 2.1 SEMANTIC vs RELATION 的判断边界

**RELATION 触发条件（两实体判断法）**：从句子中能否提取出`实体A → 关系谓语 → 实体B`，且 A、B 均为可命名实体？

| 用户话语 | 应创建的类型 | 理由 |
|---|---|---|
| "我养了一只猫，叫anna" | **RELATION** + SEMANTIC | 两命名实体（用户/Will + anna）有关系 |
| "我和Bob是同事" | **RELATION** | 两命名实体（用户 + Bob）有关系 |
| "我是工程师" | **SEMANTIC** | "工程师"是属性，不是命名实体 |
| "anna 是橘猫" | **SEMANTIC** | "橘猫"是属性描述，不是命名实体 |
| "用户喜欢简洁风格" | **SEMANTIC** | 偏好属性，无第二实体 |

**伴随 SEMANTIC 规则**：当 RELATION 引入了新的命名实体（如宠物名、同事名），同时为该实体建一条 SEMANTIC 记录其基本属性，使检索时两条路径都能命中。

```
"我养了一只猫，叫anna"  →
  1. RELATION: 用户 --[has_pet]--> anna  (GLOBAL, LONG_TERM)
  2. SEMANTIC: "anna 是用户的宠物猫"      (GLOBAL, importance=0.5)
```

### 2.2 RELATION predicate 词汇表

| 域 | predicate 示例 | scope |
|---|---|---|
| 个人/生活（永久） | `has_pet`, `married_to`, `is_parent_of`, `is_child_of`, `is_sibling_of`, `is_friend_of`, `owns`, `lives_with` | **GLOBAL** |
| 组织/职业（永久） | `is_manager_of`, `reports_to`, `is_colleague_of`, `founded`, `is_member_of`, `works_at` | **GLOBAL** |
| 项目协作 | `works_on`, `leads_project`, `collaborates_in`, `is_assigned_to` | **WORKSPACE** |
| 临时互动 | `is_reviewing`, `met_with`, `discussed_with` | **SESSION** |

---

## 3. Memory 记录结构

```java
Memory {
  // 标识
  id            : UUID             // 全局唯一
  type          : MemoryType       // SEMANTIC | EPISODIC | RELATION | PROCEDURAL | GOAL
  scope         : MemoryScope      // GLOBAL | WORKSPACE | SESSION

  // 归属
  agent_id      : String           // 所属 Agent（如 "worldone"）
  workspace_id  : String?          // scope=WORKSPACE 时填写（对应 canvas world 的 ID）
  session_id    : String?          // scope=SESSION 时填写

  // 内容
  content       : String           // 自然语言描述（LLM 直接读）
  structured    : JSON?            // 可选结构化补充（供程序查询）
  tags          : String[]         // 自由标签，用于快速过滤（memory_instruction 等保留 tag）

  // RELATION 类型专用三元组字段（其他类型为 null）
  subject_entity : String?         // 主语实体名，如 "Alice"
  predicate      : String?         // 谓语，下划线格式，如 "is_manager_of"
  object_entity  : String?         // 宾语实体名，如 "Bob"

  // 质量指标
  importance    : Float [0,1]      // 重要性（影响检索优先级和 decay 速度）
                                   // ⚠️ importance 与 scope 独立：高重要性 ≠ 升 GLOBAL
  confidence    : Float [0,1]      // 置信度（INFERRED 类通常低于 USER_STATED）
                                   // RELATION 类型默认 0.65（LLM 提取有幻觉风险）
  source        : MemorySource     // USER_STATED | INFERRED | SYSTEM

  // 时效（horizon 由 Memory Agent 根据 type+scope 分配）
  horizon       : MemoryHorizon    // LONG_TERM | MEDIUM_TERM | SHORT_TERM
  created_at    : Timestamp
  updated_at    : Timestamp
  last_accessed : Timestamp        // 最后被检索/注入的时间
  access_count  : Int              // 访问次数（影响 decay 速度）
  expires_at    : Timestamp?       // SHORT_TERM 时设置过期时间

  // 版本与矛盾
  superseded_by : UUID?            // 被哪条 memory 替代（软删除语义）
  contradicts   : UUID[]           // 与哪些 memory 存在已知矛盾

  // 关联网络（A-MEM 核心思路）
  linked_to     : MemoryLink[]     // 与其他 memory 的有向关联
  provenance    : UUID[]           // 从哪些对话/事件 ID 产生
}
```

### 3.1 MemoryLink（记忆间关联）

```java
MemoryLink {
  target_id  : UUID
  link_type  : LinkType     // SUPPORTS | CONTRADICTS | CAUSES | PART_OF | FOLLOWS | REFINES
  weight     : Float [0,1]  // 关联强度
}
```

---

## 4. Memory 范围（三级）

```
GLOBAL     — 跨所有 session，Agent 全局可见
             代表关于用户本人或全局约定的事实，不依附于任何特定任务/项目
             示例："用户是工程师"、"'lets edit' = 进入编辑模式"

WORKSPACE  — 绑定到特定任务实体（workspaceId = 任务对象的持久 ID），多人协作共享
             代表"某个具体任务/对象"的知识，跨该任务的多次会话和多位协作者成立
             workspaceId 是泛化的任务实体 ID：
               - 编辑 HR 本体世界 → workspaceId = worldId
               - 编辑某个员工档案 → workspaceId = personId
               - 处理某个项目     → workspaceId = projectId
             ⚠️ WORKSPACE 记忆是多人共享的：任何进入该 workspace 的用户都能读取
             ⚠️ WORKSPACE 记忆写入时保留 userId（用于协作溯源），但查询时不过滤 userId

SESSION    — 单个 session 私有，session 结束后可按需升级或过期
             代表本次会话中发生的事，或尚未确认是否普遍成立的临时事实
             示例："这次添加了 Department 实体"、"用户这次想优先处理关系设计"
```

### 4.1 Scope 分配原则（Memory Agent 创建时判断）

**Main session（对话类型，conversation）的记忆默认 scope：**

| 类型 | 默认 Scope | 理由 |
|---|---|---|
| SEMANTIC（用户事实） | **GLOBAL** | 主 session 是用户"环境对话"，所说事实关于自身，普遍成立 |
| PROCEDURAL（偏好/约定） | **GLOBAL** | 用户习惯不依附具体任务 |
| EPISODIC（对话事件） | SESSION | 这次对话发生的事件，不需要全局可见 |
| GOAL | SESSION | 目标属于当前任务 |
| RELATION | 由谓语决定（见下文） | — |

**Task session（任务类型，如本体编辑、记忆管理）的记忆默认 scope：**

| 类型 | 默认 Scope | 理由 |
|---|---|---|
| SEMANTIC（关于这个 world 的知识） | **WORKSPACE** | 本体结构/命名规则属于 workspace 知识 |
| SEMANTIC（关于用户本人的事实） | GLOBAL | 如用户在任务中说了关于自己的事实，仍是 GLOBAL |
| PROCEDURAL | WORKSPACE | 任务级约定 |
| EPISODIC | SESSION | 本次操作记录 |
| GOAL | SESSION | 任务目标 |

**RELATION 的 scope 由谓语语义决定：**

| 谓语类型 | 示例 | Scope |
|---|---|---|
| 结构性/永久关系 | is_manager_of, owns, reports_to, founded | GLOBAL |
| 项目级关系 | works_on, leads_project, collaborates_in | WORKSPACE |
| 临时关系 | discussed_today, is_reviewing, met_with | SESSION |

> LLM 在 consolidate 时根据谓语语义判断 scope，不应盲目默认 SESSION。

### 4.2 importance 与 scope 的分离原则

**`importance` 和 `scope` 是完全独立的两个维度，不可混淆：**

```
"这很重要" / "重点记一下"
  → importance ↑（建议 0.85~0.9）
  → scope 不变（内容决定 scope，不是强调程度）

"永远记住" / "以后都这样" / "全局规则"
  → importance ↑ + scope → GLOBAL + horizon → LONG_TERM

"这个世界里" / "在这个项目中"
  → scope → WORKSPACE（绑定当前 workspaceId）

"这次" / "暂时" / "今天"
  → scope → SESSION + horizon → SHORT_TERM
```

### 4.3 Promotion 机制（scope 升级）

触发条件（满足任一）：
- 用户明确指令："记住这个作为全局规则" / "永远记住" → SESSION/WORKSPACE → GLOBAL
- 用户明确说当前任务相关："这个世界里" → SESSION → WORKSPACE
- 自动：同类内容在 ≥ 3 个不同 session 中独立产生（频率阈值）
- 自动：importance > 0.85 且 access_count > 5（仅升 WORKSPACE，不自动升 GLOBAL）

> ⚠️ 自动升级到 GLOBAL 需要谨慎，应以用户明确意图为准。
> 频率触发最多升至 WORKSPACE，避免错误记忆污染全局。

---

## 4.4 Horizon（时效层级）分配规则

`horizon` 字段由 Memory Agent 在 CREATE 时根据 type + scope 分配，
也可由用户指令显式指定（如"永久记住"）。

| Horizon | 含义 | 典型场景 |
|---|---|---|
| `LONG_TERM` | 永久，除非明确撤销 | GLOBAL PROCEDURAL（用户约定）；GLOBAL SEMANTIC importance > 0.8；用户说"永久"/"全局规则" |
| `MEDIUM_TERM` | 中期（数天到数周） | 所有 GOAL memory；WORKSPACE/SESSION RELATION；GLOBAL SEMANTIC importance 0.4~0.8；跨 session 的重要 EPISODIC |
| `SHORT_TERM` | 短期（session 级） | SESSION EPISODIC（即时事件）；SESSION SEMANTIC（临时状态）；用户说"暂时"/"这次"/"这个世界里" |

**Fallback 推断规则**（LLM 未在 CREATE 中提供 horizon 时）：

```
PROCEDURAL + GLOBAL              → LONG_TERM
SEMANTIC   + GLOBAL              → LONG_TERM
GOAL                             → MEDIUM_TERM
RELATION   + GLOBAL              → LONG_TERM（结构性关系，长期成立）
RELATION   + WORKSPACE/SESSION   → MEDIUM_TERM
EPISODIC   + SESSION             → SHORT_TERM
EPISODIC   + GLOBAL              → MEDIUM_TERM
其他                             → MEDIUM_TERM（默认）
```

---

## 5. Memory 加载规则

### 5.1 Token 预算分配

```
系统提示（固定多层）：   ~800  tokens  ← 详见 §7
Memory 注入（动态）：    ~1200 tokens  ← 本节
对话窗口（滑动）：       ~2000 tokens  ← 最近 N 条对话
─────────────────────────────────────────
合计上限：               ~4000 tokens（可配置）
```

### 5.2 加载优先级

**Always Load（无条件注入）**

| 类型 | 条件 |
|---|---|
| GLOBAL SEMANTIC | importance ≥ 0.5，全量；< 0.5 且 access_count > 3 |
| GLOBAL PROCEDURAL | 全量（数量通常不多） |
| 当前 SESSION 的 GOAL | 全量，status ≠ COMPLETED（最多 5 条） |
| WORKSPACE RELATION | importance ≥ 0.6（当前 workspaceId 匹配） |
| GLOBAL RELATION | confidence ≥ 0.7（结构性关系，全局成立） |

**Conditional Load（检索后注入）**

使用评分公式：
```
score = 0.4 × recency  +  0.3 × importance  +  0.3 × semantic_similarity
recency = exp(-λ × hours_since_access)   // λ=0.1，约7天半衰
```

| 类型 | 载入数量上限 |
|---|---|
| GLOBAL EPISODIC | score 最高的 5 条 |
| SESSION EPISODIC | 最近 10 条（时序优先）|
| SESSION PROCEDURAL | 全量 |
| SESSION RELATION | score 最高的 5 条（仅当 sessionId 匹配）|

**实体锚定检索（Entity-anchored）— RELATION 专用**

当用户消息中提到已知实体名时，额外检索：
```
用户消息 → 提取候选实体词 → findRelationsByEntity(agentId, userId, entity, sessionId, now)
           WHERE (scope='GLOBAL') OR (scope='WORKSPACE') OR (scope='SESSION' AND session_id=:sessionId)
           AND (subject_entity=entity OR object_entity=entity)
```
优先于 Conditional Load，确保上下文中包含对话实体的关系网络。

**Never Bulk Load**
- 所有 `superseded_by != null` 的记录（已过时）
- importance < 0.2 且 last_accessed > 14 天
- expires_at < now
- 其他 session 的 SESSION 级记忆（跨 session 隔离）

### 5.3 注入格式

```
## Agent Memory
### 当前目标
- [GOAL] 设计HR本体：目标10实体，已完成5个（Employee, Department...）

### 全局事实
- [FACT] HR本体世界 session_id: abc123，phase: DESIGN
- [FACT] 用户偏好：实体间用FK关联，不内嵌

### 最近事件
- [EVENT 2024-01-05] 添加了 Employee 实体（name, age, gender 三个字段）
- [EVENT 2024-01-05] 建立 Employee → Department 的 FK 关联

### 约定
- [CONVENTION] "lets edit XX" = 进入 XX 的 canvas 编辑模式
```

---

## 5.4 WORKSPACE 在 world-one 中的映射

### workspaceId 的来源

**workspaceId = 进入 task session 时的任务实体持久 ID**，由宿主 APP（如 world-one）在
canvas 事件的 `session_id` 字段中提供。memory-one 不感知任务类型，只存储和查询。

```
任务类型                    workspaceId 来源
─────────────────────────────────────────────
编辑 HR 本体世界            worldId（world-entitir 中的 WorldOneSession.id）
编辑某员工档案              personId
处理某个工单                ticketId
讨论某个项目                projectId
```

### WORKSPACE 记忆的协作可见性

```
Alice 在 HR 本体世界写入 WORKSPACE 记忆 →  workspaceId = "world-hr"
Bob   进入同一个 HR 本体世界           →  查询时 workspaceId = "world-hr"
                                           Alice 写的 WORKSPACE 记忆对 Bob 可见 ✓
                                           Bob 个人的 GLOBAL memories 对 Alice 不可见 ✓
```

查询规则（MemoryRepository.findActive）：
```
GLOBAL    : userId 必须匹配（用户独占）
WORKSPACE : workspaceId 必须匹配（不过滤 userId，协作共享）
SESSION   : userId + sessionId 都必须匹配（会话独占）
```

### 协作参与记录（自动）

用户进入 task session 时，world-one 自动调用 `memory_workspace_join`，
在 memory-one 中创建一条 WORKSPACE RELATION：

```
type    : RELATION
scope   : WORKSPACE
userId  : "张三"（写入时保留，用于溯源）
subject : "张三"
predicate: "contributed_to"
object  : "world-hr"
workspaceId: "world-hr"
```

查询"HR本体世界谁编辑过"：实体锚定检索 `object_entity = "world-hr"` +
`predicate = "contributed_to"`。

### 严格禁止写入 WORKSPACE 的信息

```
❌ 用户姓名、职务、联系方式
❌ 用户个人偏好（语言风格、交互习惯）
❌ 用户个人目标（除非明确说"这是整个团队的目标"）
❌ 用户历史操作记录（EPISODIC 类型永远是 SESSION 级别）
```

### 数据结构示意

```
User (userId = "alice")
├── GLOBAL memories（alice 独占）
│     [SEMANTIC/GLOBAL] Alice 是后端工程师
│     [PROCEDURAL/GLOBAL] 偏好 Java 代码示例
│     [RELATION/GLOBAL] Alice --[is_manager_of]--> Bob
│
├── workspace: "HR本体世界" (workspaceId = "world-hr")  ← 任何进入该空间的用户均可见
│     [SEMANTIC/WORKSPACE]  userId=alice  Employee 实体有 name/age 字段
│     [PROCEDURAL/WORKSPACE] userId=alice  实体名用英文
│     [RELATION/WORKSPACE]  userId=alice  Employee --[belongs_to]--> Department
│     [RELATION/WORKSPACE]  userId=alice  alice --[contributed_to]--> world-hr
│     [RELATION/WORKSPACE]  userId=bob    bob   --[contributed_to]--> world-hr
│     └── session "alice-2024-01": SESSION memories（alice 本次操作，alice 独占）
│     └── session "bob-2024-01":  SESSION memories（bob 本次操作，bob 独占）
│
└── workspace: "销售本体世界" (workspaceId = "world-sales")
      与 HR 本体完全隔离，互不可见
```

---

## 6. Memory 更新机制（Consolidation）

### 6.1 触发时机

每次 assistant 消息写入历史后，**异步**启动 Memory Consolidation。
不阻塞当前对话响应，在后台完成。

### 6.2 Consolidation LLM 的输入（完整格式）

```
## 本轮对话
[user] ...
[assistant] ...
[tool:xxx] ...

## 当前 Active Memories（本轮已注入 context 的记忆，先查这里再决定操作）
- [<UUID>][SEMANTIC/GLOBAL] 内容...
- [<UUID>][GOAL/SESSION] 内容...

## 当前 GOAL Memories（当前 session 所有活跃目标）
- [<UUID>] 目标描述...
```

Memory Agent 在决定任何操作前，必须先检查 Active Memories（向前查找），
避免重复创建已存在的记忆。

任务输出的操作类型：
```
- CREATE        : 新建记忆（含 type, scope, horizon, content, importance, confidence, source, tags）
- SUPERSEDE     : { old_id, new_content, reason }   — 事实被更新
- PROMOTE       : { id, new_scope, reason }         — session → global
- LINK          : { from_id, to_id, link_type, weight }
- GOAL_PROGRESS : { id, progress_note }             — 目标进度更新
- MARK_CONTRADICTION : { id1, id2, description }   — 矛盾标记（不自动解决）
```

### 6.3 按类型操作规则

| 类型 | 规则 |
|---|---|
| **EPISODIC** | 永远只 CREATE，禁止对事件型记忆执行 SUPERSEDE |
| **SEMANTIC** | 同一事实只有一条有效记录；内容变化 → SUPERSEDE（旧记录保留为历史） |
| **PROCEDURAL** | 与 SEMANTIC 相同：约定变更 = SUPERSEDE，不新建副本 |
| **GOAL** | 进度变化 → GOAL_PROGRESS；新子目标 → CREATE + LINK；目标完成/放弃 → SUPERSEDE |
| **RELATION** | 先查是否已有相同关系；关系消亡 → SUPERSEDE；创建时必须填写 subject/predicate/object 三元组 |

### 6.4 事实更新示例

```
用户说："我完成了第4道题"（之前是3道）

Consolidation 输出:
[
  { "op": "SUPERSEDE",
    "old_id": "mem-001",
    "new_content": "用户已完成4道题" },
  { "op": "CREATE",
    "type": "EPISODIC", "scope": "SESSION", "horizon": "SHORT_TERM",
    "content": "完成第4道题",
    "importance": 0.5, "source": "USER_STATED" }
]
```

### 6.5 矛盾处理

当新 memory 与已有 memory 内容矛盾：
1. 新记录标记 `contradicts: [old_id]`
2. 旧记录标记 `contradicts: [new_id]`
3. 下一轮对话时，矛盾对注入一条特殊提示让 LLM 确认："存在矛盾记忆，请判断以哪条为准"
4. 用户/LLM 确认后，执行 SUPERSEDE

---

## 6.6 Memory Agent 提示词分层（关键设计）

Memory Agent 自身的系统提示词也是**分层组合**的，且用户对 memory 的指令
本身就是一条 PROCEDURAL memory——这是系统的自引用特性。

### 提示词组合

```
Memory Agent System Prompt =
  [Layer 0] 固定核心规则（hardcoded，定义于 MemoryAgentPrompt.LAYER_0）
  [Layer 1a] Agent 自身的 memory 追踪规则
  [Layer 1b] 应用级 memory hints（来自 AIPP skill 的 memory_hints 字段）
  [Layer 2] 用户自定义指令（从 PROCEDURAL memories with tag="memory_instruction" 动态加载）
```

Layer 0 包含：
- 向前查找规则（先检查 Active Memories 再决定操作）
- 六种操作类型完整定义（含 horizon 字段）
- 按类型操作规则（EPISODIC 追加 / SEMANTIC 覆盖 / GOAL 进度模式等）
- Horizon 分配规则
- 稳定性保护规则
- 输入格式规范（Active Memories + GOAL Memories 格式）

### 流程示例

```
用户说："记住我打开过的所有界面"

① 主 Agent 识别 memory 指令意图
   → 调用 memory_set_instruction(content="记录每次打开的界面", scope="SESSION")

② 工具执行：创建 Memory {
     type: PROCEDURAL, scope: SESSION,
     content: "用户要求：记录每次打开的界面（widget type、名称、时间）",
     tags: ["memory_instruction"],
     importance: 0.95, source: USER_STATED, horizon: MEDIUM_TERM
   }

③ 下一轮对话后，Memory Agent 的提示词变为：
   [Layer 0] ...固定规则...
   [Layer 1b] world-entitir hints: "追踪 world_design / world_add_definition..."
   [Layer 2] ## 用户自定义规则
             - 记录每次打开的界面（widget type、名称、时间）

④ Memory Agent 现在会在每次 enterCanvas 事件后主动 CREATE EPISODIC memory
```

### 应用级 memory_hints（Layer 1b）

AIPP 应用在 `/api/skills` 的技能定义中可声明 `memory_hints` 字段，
告诉 Memory Agent 应重点追踪哪些类型的事件：

```json
{
  "name": "world_design",
  "memory_hints": "每次调用成功 → CREATE GOAL memory（正在设计哪个世界，session_id）"
}
```

AppRegistry 启动时收集所有 memory_hints，在 Memory Agent 提示词 Layer 1b 中注入。

### 用户指令的 scope 与 horizon 分配

```
"记住" / "以后都" / "全局规则"  → scope=GLOBAL, horizon=LONG_TERM
"这次" / "这个世界里" / "暂时"  → scope=SESSION, horizon=MEDIUM_TERM

用户后续说"取消这个规则" → SUPERSEDE 对应的 PROCEDURAL memory
```

---

## 7. 多层系统提示（固定部分）

在 worldone 的 `ContextComposer` 中，系统提示按以下层次拼接（与 `aipp-protocol/README.md` 保持一致）：

```
Layer 1 — worldone 全局铁律 + app 能力说明  （AppRegistry.aggregatedSystemPrompt()，启动时加载）
Layer 2 — session entry prompt               （task/event session 创建时注入，conversation 无）
           内容：简洁回复规范、任务类型行为约束
Layer 3 — 当前 Widget 的操作规范             （进入 canvas 时注入，canvas close 时移除）
           内容：entity/enum 格式规范、canvas 编辑规则（get-before-modify）
```

完整上下文公式（三个部分叠加）：

```
完整 context = [系统提示（Layer 1-3，固定多层）]
             + [Memory 注入（动态检索，§5 加载规则）]
             + [对话窗口（滑动最近 N 条）]
```

Token 预算分配（参考 §5.1）：

```
系统提示（Layer 1-3）：  ~800  tokens
Memory 注入（动态）：    ~1200 tokens
对话窗口（滑动）：       ~2000 tokens
─────────────────────────────────────────
合计上限：               ~4000 tokens（可配置）
```

每层独立维护，互不覆盖，拼接时有明确分隔标记（`---`）。

---

## 8. 存储设计

### 8.1 表结构（PostgreSQL）

```sql
-- worldone schema 下
CREATE TABLE worldone.memories (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    type            VARCHAR(20)  NOT NULL,   -- SEMANTIC/EPISODIC/RELATIONAL/PROCEDURAL/GOAL
    scope           VARCHAR(20)  NOT NULL,   -- GLOBAL/WORKSPACE/SESSION
    agent_id        VARCHAR(100) NOT NULL,
    workspace_id    VARCHAR(100),
    session_id      VARCHAR(100),
    content         TEXT         NOT NULL,
    structured      JSONB,
    tags            TEXT[]       DEFAULT '{}',
    importance      FLOAT        NOT NULL DEFAULT 0.5,
    confidence      FLOAT        NOT NULL DEFAULT 0.8,
    source          VARCHAR(20)  NOT NULL DEFAULT 'INFERRED',
    horizon         VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM_TERM',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_accessed   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    access_count    INT          NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,
    superseded_by   UUID         REFERENCES worldone.memories(id),
    contradicts     UUID[]       DEFAULT '{}',
    linked_to       JSONB        DEFAULT '[]',  -- [{target_id, link_type, weight}]
    provenance      UUID[]       DEFAULT '{}'
);

CREATE INDEX idx_memories_scope     ON worldone.memories(agent_id, scope, type);
CREATE INDEX idx_memories_session   ON worldone.memories(session_id) WHERE session_id IS NOT NULL;
CREATE INDEX idx_memories_active    ON worldone.memories(superseded_by, expires_at);
CREATE INDEX idx_memories_tags      ON worldone.memories USING GIN(tags);
CREATE INDEX idx_memories_content   ON worldone.memories USING GIN(to_tsvector('simple', content));
```

### 8.2 语义检索（Phase 2）

Phase 1 使用全文检索（PostgreSQL `tsvector`）。  
Phase 2 接入向量数据库（pgvector 扩展或外部 Qdrant），存储 content 的 embedding，
支持语义相似度检索（`1 - cosine_distance`）。

---

## 9. Java 模块结构（aip/memory）

```
aip/src/main/java/org/twelve/entitir/aip/memory/
├── model/
│   ├── Memory.java              // 核心记录（record），含 withScope/withContent 工具方法
│   ├── MemoryLink.java          // 记忆关联
│   ├── MemoryType.java          // 枚举：SEMANTIC|EPISODIC|RELATIONAL|PROCEDURAL|GOAL
│   ├── MemoryScope.java         // 枚举：GLOBAL|WORKSPACE|SESSION
│   ├── MemoryHorizon.java       // 枚举：LONG_TERM|MEDIUM_TERM|SHORT_TERM
│   ├── MemorySource.java        // 枚举：USER_STATED|INFERRED|SYSTEM
│   └── LinkType.java            // 枚举：SUPPORTS|CONTRADICTS|CAUSES|PART_OF|FOLLOWS|REFINES
├── MemoryStore.java             // 接口：save / findById / query / supersede / promote ...
├── MemoryLoader.java            // 接口：load() → String（返回注入文本）
├── MemoryLoadResult.java        // record：injectionText + loadedIds（用于 consolidation 前查）
├── MemoryConsolidator.java      // 接口：consolidate(sessionId, agentId, turn, activeIds)
├── ContextComposer.java         // 接口：compose() → List<Message>
├── MemoryAgentPrompt.java       // 静态类：LAYER_0 常量 + compose(1a, 1b, L2) 分层方法
└── MemoryQuery.java             // 查询参数 builder

worldone/.../memory/
├── JdbcMemoryStore.java         // MemoryStore 实现（Spring JPA + PostgreSQL）
├── DefaultMemoryLoader.java     // MemoryLoader 实现，同时提供 loadWithIds/loadGoals
├── DefaultContextComposer.java  // ContextComposer 实现，返回 ComposeResult（含 loadedIds）
├── LLMMemoryConsolidator.java   // MemoryConsolidator 实现（异步 LLM 调用）
├── WorldoneMemoryTools.java     // memory_* 工具实现（query/create/update/supersede/delete/promote/set_instruction）
├── MemoryToolsController.java   // REST 控制器：POST /api/tools/memory_*
└── WorldoneBuiltins.java        // @PostConstruct 注册 worldone 内置 skills/widgets
```

---

## 10. 实现阶段规划

### Phase 1 — 基础框架 + GOAL Memory ✅ 已完成

- [x] 建立 `aip/memory/` 包，定义所有接口和枚举
- [x] 建立数据库表（worldone schema）
- [x] 实现 `JdbcMemoryStore`（PostgreSQL）
- [x] 实现 `DefaultMemoryLoader`（按加载规则组装注入文本，暴露 loadedIds）
- [x] worldone `DefaultContextComposer` 替换 `contextWindow()` 逻辑
- [x] `GenericAgentLoop` 接入 ContextComposer + MemoryConsolidator，传递 loadedIds

### Phase 2 — Consolidation LLM ✅ 已完成

- [x] 实现 `LLMMemoryConsolidator`（异步 CompletableFuture）
- [x] 支持全部操作类型（CREATE/SUPERSEDE/PROMOTE/GOAL_PROGRESS/LINK/MARK_CONTRADICTION）
- [x] Memory Agent 提示词分层（LAYER_0 含 5 项关键规则）
- [x] buildUserMessage 包含 Active Memories + GOAL Memories
- [x] horizon 字段：LLM 指定优先，fallback 自动推断
- [x] `memory_set_instruction` 工具（用户自定义记忆规则 → Memory Agent Layer 2）
- [x] 矛盾检测与标记（MARK_CONTRADICTION）

### Phase 2.5 — Memory 管理 Widget ✅ 已完成

- [x] `WorldoneBuiltins`：注册 `memory_view` skill + `memory-manager` widget
- [x] `MemoryToolsController`：REST API（/api/tools/memory_*）
- [x] `WorldoneMemoryTools`：7 个工具方法（含 set_instruction）

### Phase 3 — 语义检索（向量化）待实现

- [ ] pgvector 扩展 或 Qdrant 集成
- [ ] content embedding 存储与更新
- [ ] 语义相似度检索替换全文检索

---

## 11. 与现有架构的关系

```
worldone (进程)
  └── DefaultContextComposer         ← 替代 GenericAgentLoop.contextWindow()
        ├── Layer 1:   AppRegistry.aggregatedSystemPrompt()
        ├── Layer 2:   session entry prompt（task/event session 创建时注入）
        ├── Layer 3:   Widget context_prompt（canvas 激活时注入）
        └── Memory注入: DefaultMemoryLoader.loadWithIds() → 返回 text + loadedIds

  └── GenericAgentLoop
        └── 对话后: LLMMemoryConsolidator.consolidate(turn, loadedIds) [异步]
              └── Memory Agent LLM
                    ├── 系统提示: MemoryAgentPromptBuilder.compose(L1a, L1b, L2)
                    │             其中 L2 = PROCEDURAL memories tagged "memory_instruction"
                    └── 用户消息: buildUserMessage(turn, activeIds, goalMemories)
                          ├── ## 本轮对话
                          ├── ## 当前 Active Memories（用于向前查找去重）
                          └── ## 当前 GOAL Memories（用于 GOAL_PROGRESS 操作）
```

**AIPP 合规性**：`worldone` 自身通过 `AppRegistry.registerBuiltin()` 注册为内置 AIPP 应用，
memory 管理工具走 `/api/tools/memory_*` 端点，与外部 AIPP 应用的路由规则完全一致。
