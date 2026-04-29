# AIPP Skills — Progressive Disclosure RFC

> 版本：0.1 (draft)
> 状态：设计讨论
> 关联文档：`aipp-protocol.md`、`aipp-prompt-architecture.md`、`world-one-skills.md`

---

## 1. 背景与动机

### 1.1 当前问题

当前 AIPP 的 skill 实际上是 **OpenAI function-calling schema 的薄包装**——每个 skill ≈ 一个可被 LLM 直接调的原子 tool。这带来三个问题：

1. **Token 线性膨胀**：所有 app 的 skills 全量注入 system prompt，每轮推理都带全量；app 数量一多，上下文窗口压力剧增。
2. **LLM 选择退化**：当可见工具超过 ~30 个，LLM 的工具选择质量明显下降（工具名相近、描述重叠时更严重）。
3. **事务完成度低**：多步任务（如"员工入职登记"）靠 LLM 自己一步步试，缺乏 playbook 级别的事务约束；经常走到一半卡住或跑偏。

### 1.2 设计目标

引入 **Anthropic Skills 的 progressive disclosure 思想**，但保留 AIPP 的 HTTP 微服务形态：

- **召回优先**：用户提问时先做意图召回，锁定到一个（或一串）skill，再加载对应 playbook 执行；
- **事务化 skill**：skill 不再是函数签名，而是**一段可执行的说明书**，内部按步骤调 tools；
- **分层搜索**：universal / in-app / in-widget / in-view 作为**召回优先级**，而不是可见性开关；
- **Tools 收窄**：执行阶段只暴露当前 skill 声明的 tools 白名单，LLM 的选择空间从几十个变成几个。

### 1.3 非目标

- 不改 wire 协议（依然用 OpenAI function-calling schema 调 tools）；
- 不替代 AAP-Pre / AAP-Post（它们变成 universal 层的 skill 载体，或保留为 host 级约束）；
- 不做 Anthropic Skills 的文件系统 sandbox（我们的执行在 app 后端，不在 LLM sandbox）。

---

## 2. 核心模型

### 2.1 两阶段 Agent Loop

```
用户提问
  │
  ▼
┌─────────────────────────────────────┐
│  Loop A — 召回 Agent                │
│  (临时 session, 不落 task panel)    │
│                                     │
│  input:  用户 query + 当前位置      │
│          (view/widget/app/global)   │
│  context: skill 索引 (name + desc)  │
│  output: { skill_id, args }         │
│          或 { fallback: "tools" }   │
└─────────────┬───────────────────────┘
              │
    ┌─────────┴─────────┐
    │                   │
命中 skill           未命中
    │                   │
    ▼                   ▼
┌─────────────────┐  ┌──────────────────────┐
│ Loop B — 执行   │  │ Fallback — 原子 tools│
│                 │  │  按当前位置取作用域  │
│ 加载 SKILL.md   │  │                      │
│ + 声明的 tools  │  │  aipp session 内 →   │
│ 按 playbook 跑  │  │    该 app 的 tools   │
│                 │  │  global session →    │
│ 完成后可 chain  │  │    global + 所有     │
│ 回 Loop A       │  │    active-app tools  │
└─────────────────┘  └──────────────────────┘
```

**关键点**：

- Loop A 的 session 是**临时的，不进 task panel**，一次召回用完即抛；
- Loop B 的 session **继承用户主 session 的上下文**（principal / memory / history），是用户感知的执行 session；
- Skill playbook 执行完毕后，LLM 可以选择 `chain_next_skill`，Host 将当前结果作为上下文回到 Loop A 再召回一次。

### 2.2 Skill 的新形态

一个 skill 不再是 JSON schema，而是 **JSON 元信息 + SKILL.md playbook** 双轨：

#### 2.2.1 JSON 元信息（给 Loop A 召回用）

```json
{
  "id": "onboarding-intake",
  "level": "app",
  "owner_app": "world",
  "owner_widget": null,
  "owner_view": null,
  "name": "员工入职登记",
  "description": "接收员工入职信息（姓名、部门、入职日期），建立员工档案并创建首日任务",
  "triggers": ["入职", "onboarding", "新员工", "员工登记"],
  "embedding_hint": "HR 流程 员工管理 人力资源 新人报到",
  "playbook_url": "/api/skills/onboarding-intake/playbook",
  "tools": ["world_create_session", "world_add_definition", "memory_save"],
  "args_schema": {
    "type": "object",
    "properties": {
      "name":   { "type": "string" },
      "dept":   { "type": "string" },
      "date":   { "type": "string", "format": "date" }
    }
  }
}
```

字段说明：

| 字段 | 作用 |
|------|------|
| `level` | 召回分层：`universal` / `app` / `widget` / `view` |
| `owner_*` | 归属，决定召回时是否命中 |
| `triggers` | 关键词快路径（阶段 1 召回） |
| `embedding_hint` | embedding 向量化时拼接到 description 的额外文本 |
| `playbook_url` | SKILL.md 延迟加载地址 |
| `tools` | 执行阶段可见的 tools 白名单 |
| `args_schema` | Loop A 抽取参数用（不是执行参数） |

Loop A **只看到** skill 的 JSON 元信息，体量极小（~200 字节 / skill）。

#### 2.2.2 SKILL.md playbook（给 Loop B 执行用）

```markdown
---
id: onboarding-intake
tools:
  - world_create_session
  - world_add_definition
  - memory_save
---

# 员工入职登记

## 执行步骤

1. 如果用户未提供姓名、部门、入职日期中的任一项，先以自然语言询问补齐。
2. 调用 `world_create_session` 创建员工档案 session：
   - `name` 参数使用员工姓名
3. 对每条基础字段（姓名/部门/入职日期），调用 `world_add_definition` 添加。
4. 调用 `memory_save` 记录「{name} 于 {date} 入职 {dept}」。
5. 回复用户：档案已建立，并给出 session id 供后续追加。

## 约束

- 任何一步失败都要向用户明示并中止，不得静默跳过；
- 不要假装已完成未调用的步骤；
- 如果用户中途改主意（如取消），立即停止并确认。
```

Loop B 拿到这份 md 作为 system prompt 追加层，按步骤执行；看到的 tools 列表就是 frontmatter 里声明的那 3 个。

### 2.3 四级分层（召回优先级）

```
当前位置          召回顺序（短路规则：高层足够置信就不查低层）
─────────────────────────────────────────────────────────────
in-view           view → widget → app → universal
in-widget         widget → app → universal
in-app            app → universal
global (chat)     universal → 所有 working-app 的 app 层
```

**置信度阈值**按层递增：低层（view）命中只要 0.5 即用；高层（universal）要 0.8。理由：低层更贴近用户当前上下文，歧义小。

### 2.4 Fallback 作用域（你的第 5 条）

Loop A 没召回任何 skill 时，退化为原子 tools 模式。**tools 的候选集按当前位置取作用域**：

| 当前位置 | Fallback 可见的 tools |
|----------|----------------------|
| 某 app 的 widget / view 内 | 仅该 app 声明的 tools |
| global chat session | universal tools + 所有 working app 的 app 层 tools |

这样回避了「一 fallback 就回到全量全见」的退路，保持 progressive disclosure 的压力。

---

## 3. Loop A 召回策略（你的决策 2）

三级混合，**短路优化**：

### 3.1 阶段 1 — 触发词快路径

每个 skill 声明 `triggers: [...]`，Host 对用户 query 做正则/分词匹配。命中 **唯一一个** skill 且 triggers 里有强词（如"入职"对应 onboarding），直接跳过 LLM，填入抽取的 args 进 Loop B。

覆盖场景：高频、明确意图的命令式 query（"列出所有世界"、"新建 session"）。

### 3.2 阶段 2 — Embedding 召回

- 离线：对每个 skill 的 `description + embedding_hint` 向量化，存本地 HNSW 索引；
- 在线：用户 query 向量化，取 top-K（默认 K=5）；
- 若 top-1 与 top-2 的距离差 > 阈值，视为高置信，进入阶段 3 消歧前可直接给 LLM 确认。

覆盖场景：用户用模糊/同义词表达（"帮我登记下新来的员工"）。

### 3.3 阶段 3 — LLM 消歧

- Prompt 极简：「下列 skill 中哪个最匹配用户意图？或返回 `none` 走 fallback」；
- 候选集：阶段 2 的 top-K（**不是**全量 skill）；
- 用**便宜模型**（如 gpt-4o-mini / deepseek-v3 / glm-4-flash），目标延迟 < 500ms；
- 输出结构化 JSON：`{ skill_id, confidence, extracted_args }`。

### 3.4 参数抽取

args_schema 的抽取可以放在阶段 3 同一次 LLM 调用里完成（让它一次输出 skill_id + args）。缺参时 **不退回重召回**，而是由 Loop B 的 playbook 在执行步骤里问用户——这符合 Anthropic Skills「playbook 自己处理缺失信息」的模式。

---

## 4. Loop B 执行（多 skill 组合，你的决策 4 第二种）

### 4.1 单 skill 执行

```
Loop B 启动:
  system prompt = [
    Host 铁律（最小集）,
    SKILL.md 正文,
    归属 app 的 AAP-Pre（如果有）,
  ]
  tools = SKILL.md frontmatter.tools
  
每轮:
  LLM 决定 → 调 tool → 拿结果 → 继续
  
终止条件:
  - LLM 输出 stop 信号 + 最终回复给用户
  - LLM 输出 { chain_next_skill: true, hint: "..." } → 回 Loop A
  - 达到 max_rounds（兜底，防止循环）
```

### 4.2 Chain 回 Loop A

Playbook 执行完后，LLM 可输出 `{ done: true, chain?: { hint: "用户现在可能想..." } }`。Host：

1. 把本 skill 的执行摘要沉淀到主 session 历史；
2. 销毁 Loop B 的临时 session（如果该 skill 配置了 `ephemeral: true`）；
3. 若有 `chain.hint`，把 hint 作为新的 query 喂给 Loop A 再召回；
4. 否则回到 chat，等用户下一句。

**为什么选这种策略**：
- 比「Loop A 一次输出 skill 序列」更鲁棒——中间结果可以影响下一个 skill 的选择；
- 对用户 "先 X 再 Y" 的复合指令天然兼容；
- 代价是延迟（每次 chain 多一轮 Loop A），但 Loop A 轻量，可接受。

---

## 5. 与现有 AIPP 的兼容演进

### 5.1 协议层变化

| 原字段 | 演进 |
|--------|------|
| `skills[].name/description/parameters` | **保留**，用于 fallback 模式下的原子 tool 暴露 |
| `skills[].kind` (design/execution) | **保留**，语义不变 |
| `skills[].canvas` | 迁移到 SKILL.md frontmatter 的 `canvas` |
| `widgets[].canvas_skill.tools` | 变成 **widget 级 skills** 的列表 |
| `widgets[].session.scope` | 保留，作为 widget session 的 tools 过滤器 |
| AAP-Pre / AAP-Post | **保留**，但建议各 app 把业务规则逐步迁移到 SKILL.md，AAP 只留纯路由判定 |

### 5.2 端点映射（Phase 4 稳态）

每个 AIPP 应用对外暴露：

```
GET  /api/tools                   — 原子 Tool 清单（权威，带 visibility+scope）
GET  /api/skills                  — Skill Playbook 索引（给 Loop A）
GET  /api/skills/{id}/playbook    — SKILL.md 正文（给 Loop B）
```

> Phase 4 之前 `/api/skills` 承载的是原子工具列表；自 Phase 4 起，该端点整体翻转为 Skill Playbook 索引端点，原子 Tool 列表唯一权威源为 `/api/tools`。

### 5.3 Host 侧（world-one）变化

新增组件：

- `SkillRecaller`：Loop A 实现，持有触发词索引 + embedding 索引 + LLM 消歧器；
- `SkillExecutor`：Loop B 实现，创建临时 / 继承 session，加载 playbook，限定 tools；
- `AippRegistry` / `AippSkillCatalog`：统一 AIPP 注册表门面与内部 skill catalog，按 level 维护四层索引；
- `ChainController`：管理 skill chain 链路、摘要沉淀、临时 session 销毁。

现有 `GenericAgentLoop` 降级为 **fallback tools 模式的执行器**——只在 Loop A 未召回时使用。

### 5.4 渐进迁移路径

1. **Stage 0**（当前）：纯原子 tools 模式，`GenericAgentLoop` 全量注入。
2. **Stage 1**：新增 `AippSkillCatalog` 分层索引，`/api/skills` 翻转为 Skill Playbook 索引端点（Phase 4 已完成），但执行路径不变（验证召回准确率）。
3. **Stage 2**：引入 Loop A 召回 + Loop B 执行，单 skill 场景跑通（以 `onboarding-intake` 为 spike）。
4. **Stage 3**：引入 chain 机制，多 skill 组合场景跑通。
5. **Stage 4**：将高频业务 skill 全部迁移到 SKILL.md；AAP-Pre 精简为纯路由判定。

---

## 6. 关键问题与悬置项

### 6.1 延迟

- Loop A 多一次 LLM 调用，用户感知 +300~500ms；
- 缓解：触发词命中直接跳过、embedding 召回与 UI loading 并行展示、小模型消歧；
- 需要在 spike 阶段实测。

### 6.2 Skill 粒度约束

**经验值**：一个 skill ≈ 用户一句话能描述的一件完整事。

- ✅ 好：创建入职档案、查询本周会议、归档世界；
- ❌ 太粗：「帮我管理 HR」（应该拆成多个 skill）；
- ❌ 太细：「调用 world_add_definition」（这是 tool，不是 skill）。

### 6.3 Loop B 的 session 生命周期

与主 session 的关系有三种候选：

| 模式 | 继承 | 展示 | 适用场景 |
|------|------|------|---------|
| `ephemeral` | 继承 principal/memory | 不上 task panel | 一次性查询、小操作 |
| `task` | 继承 principal/memory/history | 上 task panel | 长流程（设计世界、入职） |
| `inline` | 完全复用主 session | 主 chat 内 | 简单 skill（等同当前行为） |

在 SKILL.md frontmatter 声明：`session_mode: ephemeral | task | inline`。

### 6.4 与 widget canvas 的关系

Widget canvas 事件（open/patch/close）和 skill 是**正交**的：

- 一个 skill 执行过程中可以触发 canvas 事件（通过 tool 响应的 canvas 字段）；
- 进入 canvas 后，widget 级 skills 自动加入当前位置的召回池；
- 退出 canvas，widget 级 skills 从召回池移除。

### 6.5 Skill 冲突

两个 app 都声明了"查询会议"的 skill 怎么办？

- 阶段 2 embedding 会把两者都召回；
- 阶段 3 LLM 消歧时显示归属 app，让 LLM 基于当前 active app 选；
- Host 可配置 `skill_priority_order: [app_id, ...]` 做兜底。

### 6.6 Token 成本估算（待测）

| 项 | 当前 | 新方案 |
|----|------|--------|
| 每轮 system prompt | ~3000 tokens (全 skills) | ~500 tokens (Host + 召回到的 1 个 SKILL.md) |
| 每轮 tools 定义 | ~5000 tokens (全 tools) | ~1000 tokens (skill 声明的子集) |
| 额外成本 | 0 | Loop A 一次小模型调用 (~500 tokens) |

预估每次用户交互节省 50%~70% tokens（spike 阶段实测验证）。

---

## 7. Spike 计划

选 `onboarding-intake` 作为首个迁移对象（world-entitir 里已有雏形）：

### 7.1 Spike 步骤

1. 在 `world-entitir` 新增 `skills/onboarding-intake/SKILL.md`；
2. 把 `SKILL.md` 元信息加入 `GET /api/skills` 索引响应；实现 `GET /api/skills/{id}/playbook` 正文返回；
3. 在 `world-one` 加 feature flag `skills.progressive_disclosure=true`；
4. 实现最小 `SkillRecaller`（只做触发词匹配，跳过 embedding）；
5. 实现最小 `SkillExecutor`（继承主 session，加载 playbook，限 tools）；
6. 对比跑测试场景：
   - 用户说"帮我登记下新来的张三"
   - 用户说"列出所有世界"
   - 用户说"归档 power-grid 世界"

### 7.2 成功标准

- Skill 召回准确率 ≥ 90%（10 次测试对话）；
- 平均响应时间增加 < 500ms；
- Token 消耗减少 ≥ 40%；
- 多步事务（入职）完成率 ≥ 原方案 + 20pp。

---

## 8. 决策记录

本 RFC 在讨论中已确认的决策：

| # | 决策 | 结论 |
|---|------|------|
| D1 | 两阶段 loop 是否分 session | **分 session**；Loop A 临时 session，不上 task panel，用完即抛 |
| D2 | 召回策略 | **三级**：触发词 → embedding → LLM 消歧 |
| D3 | Skill playbook 存放 | **SKILL.md + JSON 元信息双轨** |
| D4 | 多 skill 组合 | **Loop B 执行完 chain 回 Loop A**（非一次性规划） |
| D5 | Fallback tools 作用域 | **按当前位置**：aipp session 内 → 该 app tools；global → universal + 所有 active-app tools |
| D6 | 实施顺序 | **先 RFC，再 spike，再全量迁移** |

---

## 9. 待讨论

- Loop A 的 LLM 模型选型（成本 vs 质量）；
- Embedding 索引用什么引擎（本地 HNSW vs 外部 vector DB）；
- SKILL.md 的作者工具链（lint / 测试 / 版本管理）；
- Skill 跨 app 复用机制（universal 层能不能被多 app 共享声明）；
- 权限/安全：skill 的 tools 白名单是否足以防止越权？是否需要运行时再校验？

