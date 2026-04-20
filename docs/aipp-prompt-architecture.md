# AIPP Prompt Architecture

## 设计原则

**World One 的系统提示只管通用 Agent 行为，领域规则由各 AIPP App 自己注入。**

World One 不知道任何具体工具的名称（如 `memory_delete_request`）。
它只声明行为约束：有操作意图必须调工具、历史是参考不是证据。
具体"应该调哪个工具、怎么调"由对应的 AIPP App 通过 AAP（Active App Prompt）声明。

AAP 分两层：

- `AAP-Pre`（命中前）：每轮对话都参与 system 组装，用于告诉 LLM 何时应命中该 app。
- `AAP-Post`（命中后）：仅在本轮真实命中该 app 后注入，用于提供该 app 的操作手册。

---

## AAP 双层模型

### AAP-Pre（命中前）

- 注入时机：每轮 LLM 调用前
- 作用：路由与命中判定（什么时候该命中我、什么时候不该命中我）
- 内容特征：短、规则化、聚焦意图识别
- 来源：`system_prompt` 或 `prompt_contributions`（当前 active app）

### AAP-Post（命中后）

- 注入时机：在同一 user turn 内，LLM 调用工具命中 app 之后，进入下一次 LLM 循环前
- 作用：执行手册（命中后按什么步骤做、如何解释工具结果、何时继续调用工具）
- 内容特征：可稍长，偏 SOP/操作规程
- 来源：工具响应中的 `aap_hit.post_system_prompt`

---

## 四层提示词结构 + 命中后动态层

```
┌─────────────────────────────────────────────────────┐
│  Layer 0：Memory Context（每轮动态注入）               │
│  "## 用户记忆快照" — 来自 memory_load 的结果           │
│  由 world-one Host 在调用 LLM 前自动注入                │
├─────────────────────────────────────────────────────┤
│  Layer 1：System Prompt（每轮动态装配）                  │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │ world-one 基础系统提示（通用 Agent 行为规范）  │    │
│  │                                             │    │
│  │ • 工具调用铁律：有意图必调工具               │    │
│  │ • 历史解读规则：历史=参考，不=已执行  ←核心   │    │
│  │ • 工具响应解读规范                          │    │
│  │ • Session 判断规范                         │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │ active app AAP-Pre contributions            │    │
│  │（仅注入当前回合 active 的 app）              │    │
│  │                                             │    │
│  │ • memory-one（当 memory app active）         │    │
│  │ • world-entitir（当 world app active）       │    │
│  │ • 其他 app（同理）                           │    │
│  └─────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────┤
│  Layer 2：Session Entry Prompt（Task/Event session） │
│  仅 task/event session 有；conversation 无           │
├─────────────────────────────────────────────────────┤
│  Layer 3：Widget Context Prompt（canvas 激活时追加）  │
│  来自当前激活 widget 的 llm_hint 字段               │
├─────────────────────────────────────────────────────┤
│  Layer X：AAP-Post（命中后临时注入）                  │
│  仅在本轮命中某 AIPP 后进入下一轮 LLM 循环时注入       │
│  默认作用域 this_turn（本轮结束即清理）                │
└─────────────────────────────────────────────────────┘
```

---

## 关键规则：历史=参考，不=已执行

这是防止 LLM 幻觉的核心原则，放在 world-one **通用系统提示**里。

**问题根因：**
LLM 从对话历史中学习 few-shot 模式。如果历史里有：
```
user: 删除旺旺的记忆
assistant: 已删除所有关于旺旺的记忆。
```
LLM 会模仿这个模式，下次直接输出"已删除"，而不调用工具——即使实际上什么都没执行。

**解决方案：**
在系统提示中明确声明：
```
对话历史仅是"参考上下文"，不代表工具已被调用或操作已完成。
用户每次发出操作指令，不管历史中有没有类似的回复，
都必须重新调用相应工具来执行。
```

这个规则是**通用的**，适用于所有 AIPP App 的所有操作，不只是 memory 的删除。

---

## AIPP App 如何注入 AAP

### 协议

AIPP App 在 `GET /api/tools` 响应中可包含命中前声明（AAP-Pre）：

- `system_prompt`（兼容字段，可作为 AAP-Pre 主体）
- `prompt_contributions`（推荐字段，支持 scope / activate_when / priority）

```json
{
  "app": "memory-one",
  "version": "1.0",
  "system_prompt": "## 记忆系统行为规范...",
  "prompt_contributions": [
    {
      "id": "memory-routing",
      "layer": "aap_pre",
      "scope": "conversation",
      "activate_when": ["ui_active", "recent_tool_owner"],
      "priority": 100,
      "content": "..."
    }
  ],
  "skills": [...]
}
```

工具响应可包含命中后声明（AAP-Post）：

```json
{
  "ok": true,
  "aap_hit": {
    "app_id": "world-entitir",
    "post_system_prompt": "## 命中 world-entitir 后的操作手册...",
    "ttl": "this_turn"
  }
}
```

字段约定：

- `aap_hit.app_id`：声明命中的 app
- `aap_hit.post_system_prompt`：命中后注入的操作手册
- `aap_hit.ttl`：作用域，仅允许：`this_turn`（默认）、`until_widget_close`

最小强约束：

- `prompt_contributions[].layer` 必填，且仅允许 `aap_pre | aap_post`
- 命中前组装只加载 `layer=aap_pre`
- 命中后执行层由工具响应 `aap_hit.post_system_prompt` 驱动

### world-one 装载流程（AAP 双层）

```
world-one 启动 / 运行时刷新
  → 扫描 ~/.ones/apps/*/manifest.json
  → GET {base_url}/api/tools
  → 缓存 app AAP-Pre 贡献（system_prompt / prompt_contributions）
  → GET {base_url}/api/skills （Skill Playbook 索引，按需触发 playbook 拉取）

每轮对话
  → Host 先注入 world-one host prompt
  → 判定当前 active app 集合
  → 仅合并 active app 的 AAP-Pre
  → LLM 决策并调用工具
  → 若工具响应含 aap_hit.post_system_prompt
      → 同一轮下一次 LLM 循环前进入执行态：仅保留 Host + AAP-Post（默认不再带 AAP-Pre）
      → 本轮结束后按 ttl 清理（默认 this_turn）
```

### 注意事项

- AAP-Pre 应短小，专注"命中条件/路由规则"，不要塞完整操作手册
- AAP-Post 才承载操作手册，且应只声明本 app 的执行规范
- 命中后默认切到执行态：`Host + 命中 app 的 AAP-Post`，不再混入多 app 的 AAP-Pre（降噪）
- 不应引用其他 app 的工具名（保持 app 间隔离）
- 不应重复 world-one host prompt 的通用规则（避免冗余）
- 不应假设始终注入；必须容忍 app 未 active 或本轮未命中
- 多 app 同轮命中时，建议只保留最后一次成功命中的 AAP-Post，避免手册冲突

---

## Widget / View 激活态的 Prompt 装配规则

> 对应 `aipp-protocol.md` § 3.2.1 Widget Context & Scope。
> 本节只描述 **widget/view 激活对当前 session system prompt 与 tool list 的叠加影响**，
> 不定义新的 session 类型。session 的创建/归一由 skill 的 `session` 块（§3.1.1）决定。

### 触发条件

当 session（chat / task / event 皆可）的当前状态满足：

1. 存在 `activeWidgetType`（canvas.open 已发生，widget 处于激活态）；
2. 该 widget manifest 声明了 `system_prompt` 或 `scope` 或 `views`（至少其一）。

则本轮按下文规则做 **叠加装配**（不替换主装配，只在其上追加）。

### Prompt 层次

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 0：用户记忆快照（Host 注入，不受 widget 影响）         │
├─────────────────────────────────────────────────────────────┤
│  Layer 1：Host 通用铁律 + 当前 session 的 AAP-Pre 集合        │
│    （chat session：全部 active app；task session：归属 app） │
├─────────────────────────────────────────────────────────────┤
│  Layer 2：Session Entry Prompt（task/event session 专属）    │
├─────────────────────────────────────────────────────────────┤
│  Layer 3：AAP-Post（this_turn / until_widget_close，按命中）│
├─────────────────────────────────────────────────────────────┤
│  Layer 4a：Widget system_prompt（widget 激活态 SOP）          │
│    · 贯穿整个 widget 激活期（canvas.close 前）                │
│  Layer 4b：View system_prompt（active_view 对应的追加 SOP）   │
│    · 每轮按请求里的 active_view 动态替换                      │
├─────────────────────────────────────────────────────────────┤
│  Layer 5：Canvas 当前 widget 的 llm_hint（refresh_skill 等） │
└─────────────────────────────────────────────────────────────┘
```

- **同一个 session，同一个 history**——widget 打开/关闭、view 切换都不破坏 LLM 对话上下文
- Layer 4a/4b 只在本轮（该 widget/view 激活时）参与装配；切走即撤出

### 工具可见性

```
tools_visible =
    tools_from_api_tools
  ∩ widget.scope.tools_allow (default = all)
  − widget.scope.tools_deny
  ∩ view[active_view].scope.tools_allow (default = all)   ← view 级相交
  − view[active_view].scope.tools_deny
  − { t | widget.scope.forbid_execution AND t.kind != "design" }
  ∪ { t ∈ tools_from_api_tools | t.scope.level=widget
                              ∧ t.scope.owner_widget=active_widget
                              ∧ t.scope.visible_when=canvas_open
                              ∧ "llm" ∈ t.visibility }   （canvas 级设计态工具不被 scope 过滤）
```

约束：

- view 只能**更窄**，不能放宽 widget 已有的 scope/forbid_execution
- `kind` 缺省视为 `execution`（保守默认）
- Widget 级 canvas 工具（`scope.level=widget` + `visible_when=canvas_open`）由 `/api/tools` 的 tool 条目元字段直接表达；永不被 scope 过滤，若该工具有副作用应改挂普通 app 级 tool

### active_view 的一致性承诺

- 前端每轮请求携带 `canvas.active_view`
- Host 每轮重算 Layer 4b 与 tool list
- LLM **不感知 view 切换事件**——无需在 history 里插"切 tab"的痕迹
- Session / history / memory 全部同构于 widget 打开之前，不重建

### 为何这样设计

主会话 `AAP-Pre` 路由需要多 app 全集，但 widget 打开后用户意图已收敛到该 widget 领域；再放全部 AAP-Pre 与全部 app skill 会引发误命中。
widget 级 `scope` 做第一层收紧，view 级 `scope` 做第二层收紧——tab 切换就自然把 "当前视图无关的工具" 从 LLM 视野里摘掉，避免 LLM 在决策 tab 误调实体 tab 的工具。

---

## 兜底安全网：sanitizeHistory()

`GenericAgentLoop.sanitizeHistory()` 在每次组装 `contextWindow()` 时运行，
将无工具调用但含"完成"短语的 assistant 消息替换为中性占位文本。

**这不是主要防线**，而是极端情况（LLM 绕过提示词）的最后一道兜底。
主要防线是系统提示中的"历史=参考"原则和 App 级别的行为规范。

```
检测规则：
  assistant 纯文本消息（无 tool_calls 字段）
  + 其前一条消息不是 tool 结果（非 role=tool）
  + 内容包含：已删除/删除成功/已清除/已创建/创建成功/已完成操作/已更新等
  → 替换为：好的，我会通过相应的工具来处理你的请求。
```

**为什么合法的"已完成"不会被误清洗？**
正常工具调用后的 assistant 回复，其前一条消息必然是 `role=tool` 的工具结果。
sanitizeHistory 只处理"前一条不是 tool 结果"的情况，因此不影响正常流程。

---

## 各 App 的 AAP 职责分配

| 层级 | 职责 |
|-----|------|
| `world-one` Host Prompt | 通用铁律、历史=参考、工具响应解读（不含领域知识） |
| `AAP-Pre`（active app） | 命中条件、路由边界、误命中规避 |
| `AAP-Post`（hit app） | 命中后操作手册、执行步骤、结果解释规范 |

---

## 变更记录

| 日期 | 变更 | 原因 |
|------|------|------|
| 2026-04-18 | 回滚 "Dedicated Widget Session"，改为 Widget/View 激活态叠加装配 | `session.mode=dedicated` 污染了 mode 语义（mode 原指 chat/canvas 渲染方式）；session 创建本由 skill `session` 块管辖；widget 只提供激活期 system_prompt + scope 叠加，view 再细化到 tab |
| 2026-04-16 | ~~新增 Dedicated Widget Session 装配规则~~（已撤回，见 2026-04-18） | 原为解决 widget 内 LLM 被主会话 AAP-Pre 误路由问题；实际应通过 widget/view 激活态叠加解决 |
| 2026-04-16 | 引入 AAP 双层模型（AAP-Pre/AAP-Post） | 将"命中判定"与"命中后操作手册"解耦，降低全局 prompt 噪音 |
| 2026-04-10 | 从 world-one 系统提示中移除 memory 专用规则 | 架构解耦：world-one 不应知道具体工具名 |
| 2026-04-10 | 加入通用"历史=参考"规则 | 泛化反幻觉机制，适用于所有操作 |
| 2026-04-10 | memory-one 的删除规则移入 MEMORY_INTENT_PROMPT | App 自声明规范，符合 AIPP 扩展性原则 |
| 2026-04-10 | 添加 sanitizeHistory() 作为兜底安全网 | 防止极端情况下 LLM 绕过提示词的幻觉 |
