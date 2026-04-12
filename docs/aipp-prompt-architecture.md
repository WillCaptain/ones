# AIPP Prompt Architecture

## 设计原则

**World One 的系统提示只管通用 Agent 行为，领域规则由各 AIPP App 自己注入。**

World One 不知道任何具体工具的名称（如 `memory_delete_request`）。
它只声明行为约束：有操作意图必须调工具、历史是参考不是证据。
具体"应该调哪个工具、怎么调"由对应的 AIPP App 通过 `systemPromptContribution` 声明。

---

## 三层提示词结构

```
┌─────────────────────────────────────────────────────┐
│  Layer 0：Memory Context（每轮动态注入）               │
│  "## 用户记忆快照" — 来自 memory_load 的结果           │
│  由 world-one Host 在调用 LLM 前自动注入                │
├─────────────────────────────────────────────────────┤
│  Layer 1：System Prompt（会话生命周期，启动时固定）      │
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
│  │ memory-one systemPromptContribution         │    │
│  │（由 /api/skills 的 system_prompt 字段提供）   │    │
│  │                                             │    │
│  │ • 记忆透明原则（不汇报记忆操作）              │    │
│  │ • 记忆操作历史只是参考，必须重新调工具         │    │
│  │ • 删除时调 memory_delete_request，不调裸删   │    │
│  │ • memory_create/update 只在管理面板操作时用  │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │ world-entitir systemPromptContribution      │    │
│  │ （其他 App 的规则，以此类推）                │    │
│  └─────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────┤
│  Layer 2：Session Entry Prompt（Task/Event session） │
│  仅 task/event session 有；conversation 无           │
├─────────────────────────────────────────────────────┤
│  Layer 3：Widget Context Prompt（canvas 激活时追加）  │
│  来自当前激活 widget 的 llm_hint 字段               │
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

## AIPP App 如何注入 systemPromptContribution

### 协议

AIPP App 在 `GET /api/skills` 响应中包含 `system_prompt` 字段：

```json
{
  "app": "memory-one",
  "version": "1.0",
  "system_prompt": "## 记忆系统行为规范...",
  "skills": [...]
}
```

### world-one 加载流程

```
world-one 启动
  → 扫描 ~/.ones/apps/*/manifest.json
  → 读取每个 app 的 base_url
  → GET {base_url}/api/skills
  → 提取 system_prompt 字段
  → 存为 AppRegistration.systemPromptContribution()
  → aggregatedSystemPrompt() 将其追加到系统提示末尾
```

### 注意事项

- `system_prompt` 应只声明本 App 相关的行为规范
- 不应引用其他 App 的工具名（保持 App 间的隔离）
- 不应重复 world-one 已有的通用规则（避免冗余）

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

## 各 App 的 systemPromptContribution 职责分配

| App | 应在 systemPromptContribution 中声明 |
|-----|-------------------------------------|
| `memory-one` | 记忆透明原则、删除用 delete_request、操作历史只是参考 |
| `world-entitir` | 本体世界操作规范、entity/relation 操作时机 |
| 未来 App | 各自的操作规范、工具使用时机 |
| `world-one` 基础 | 通用铁律、历史=参考、工具响应解读（不含任何领域知识） |

---

## 变更记录

| 日期 | 变更 | 原因 |
|------|------|------|
| 2026-04-10 | 从 world-one 系统提示中移除 memory 专用规则 | 架构解耦：world-one 不应知道具体工具名 |
| 2026-04-10 | 加入通用"历史=参考"规则 | 泛化反幻觉机制，适用于所有操作 |
| 2026-04-10 | memory-one 的删除规则移入 MEMORY_INTENT_PROMPT | App 自声明规范，符合 AIPP 扩展性原则 |
| 2026-04-10 | 添加 sanitizeHistory() 作为兜底安全网 | 防止极端情况下 LLM 绕过提示词的幻觉 |
