# Widget Protocol — Agent 驱动 UI 的通用协议标准

**范围**：本协议是所有接入 Entitir 的前端系统必须实现的标准合约。  
与特定应用（World One）无关，任何 Entitir 智能体均可发出本协议定义的指令。

---

## 设计原则

- **Agent 声明意图，前端负责渲染**。Agent 不生成 HTML/JS，只发出命名 widget + 结构化数据。
- **Widget 协议与工具协议对称**。Agent 调用工具获得数据，发出 widget 展示数据，前端回传 widget_result 继续推理——形成完整闭环。
- **LLM 只在参数真正缺失时才收集输入**。有足够参数 → 直接调工具；缺少基础参数 → `inline_form`；需要复杂输入 → canvas widget。

---

## Agent 响应格式

```json
{
  "session_id": "string",
  "text": "Markdown 文字（可流式输出）",
  "canvas": { ... },
  "inline_form": "<form>...</form>"
}
```

| 字段 | 说明 | 流式 |
|---|---|---|
| `text` | 自然语言说明，Markdown | ✅ SSE chunks |
| `canvas` | Canvas 指令（见下节），出现时驱动布局切换 | ❌ 一次性 |
| `inline_form` | LLM 生成的小型 HTML 表单，仅用于收集基础类型参数 | ❌ 一次性 |

三个字段均为可选，可单独出现或组合出现。

---

## Canvas 指令

```json
{
  "canvas": {
    "action": "open | patch | replace | close",
    "widget": { ... },
    "patch":  [ ... ]
  }
}
```

### `open` — 打开 Canvas，切换到 Canvas Mode

```json
{
  "action": "open",
  "widget": {
    "id":    "canvas-001",
    "type":  "entity-graph",
    "props": { ... }
  }
}
```

前端行为：切换到三栏布局（左侧 session list + 中间 canvas + 右侧窄 chat panel），在 canvas 区渲染指定 widget。

### `patch` — 局部更新（增量，保留前端布局状态）

```json
{
  "action": "patch",
  "patch": [
    { "op": "add",     "path": "/entities/-",       "value": { "name": "Contract", "fields": [] } },
    { "op": "replace", "path": "/entities/0/fields", "value": ["name:String", "code:String"] },
    { "op": "remove",  "path": "/edges/2"                                                          }
  ]
}
```

遵循 JSON Patch（RFC 6902）语义，操作 widget 的 `props` 对象。  
前端应 diff 更新，保留用户手动调整的节点位置等布局状态。

### `replace` — 全量替换 Canvas 内容

```json
{
  "action": "replace",
  "widget": {
    "id":    "canvas-001",
    "type":  "entity-graph",
    "props": { ... }
  }
}
```

适用于话题切换、数据完全重载的场景。前端整体重渲，不保留布局状态。

### `close` — 关闭 Canvas，退回 Chat Mode

```json
{ "action": "close" }
```

前端切回两栏布局（左侧 session list + 右侧全宽 chat）。

---

## Widget 目录

### `entity-graph`

本体实体关系图，可视化 OntologyWorld 的 JSON 定义。

**Props**
```json
{
  "entities": [
    {
      "name":    "Employee",
      "fields":  ["name:String", "age:Int", "locked:Bool"],
      "actions": ["lock", "unlock"]
    }
  ],
  "edges": [
    { "from": "Employee", "to": "Department", "label": "department", "type": "fk"      },
    { "from": "Department","to": "Employee", "label": "employees",  "type": "reverse"  },
    { "from": "Employee", "to": "Employee",  "label": "manager",    "type": "self"     }
  ]
}
```

`edge.type` 枚举：`fk`（单 FK）、`array-fk`（数组 FK）、`reverse`（反向边）、`self`（自引用）

**交互输出（`widget_result.data`）**
```json
{
  "entities": [ ... ],
  "edges":    [ ... ]
}
```
只回传逻辑结构变更（增删节点/边、修改字段），布局位置由前端自管，不回传。

---

### `schema-editor`

单个实体的字段编辑表格。

**Props**
```json
{
  "entity": {
    "name":    "Employee",
    "fields":  [
      { "name": "name",   "type": "String" },
      { "name": "age",    "type": "Int"    },
      { "name": "dept",   "type": "Department", "edge": "fk" }
    ],
    "actions": [
      { "name": "lock", "signature": "Unit -> Unit", "scope": "entity" }
    ]
  }
}
```

**交互输出**
```json
{ "entity": { "name": "...", "fields": [...], "actions": [...] } }
```

---

### `expression-editor`

Outline 表达式编辑器，带语法高亮和自动补全。

**Props**
```json
{
  "collection":    "employees",
  "initial_value": "employees.filter(e -> e.age > 30).count()",
  "completions": [
    { "label": "filter",    "kind": "method",   "insert": "filter()",    "return_type": "~this" },
    { "label": "age",       "kind": "property", "insert": "age",         "return_type": "Int"   }
  ]
}
```

`completions` 由 `world.completionsForCollection()` 生成，在工具调用时附带。

**交互输出**
```json
{ "expression": "employees.filter(e -> e.age > 30).count()" }
```

---

### `action-flow`

Decision Template 的因果链流程图（有向图）。

**Props**
```json
{
  "nodes": [
    { "id": "t1", "label": "contracts.filter(...).exists()", "type": "trigger"   },
    { "id": "a1", "label": "send_expiry_notice",             "type": "action"    },
    { "id": "d1", "label": "合同到期预警",                   "type": "decision"  }
  ],
  "edges": [
    { "from": "t1", "to": "d1", "label": "triggers" },
    { "from": "d1", "to": "a1", "label": "executes" }
  ]
}
```

`node.type` 枚举：`trigger`、`action`、`decision`、`condition`

**交互输出**
```json
{ "nodes": [...], "edges": [...] }
```

---

### `data-table`

查询结果展示表格（只读）。

**Props**
```json
{
  "columns": ["name", "age", "department"],
  "rows":    [["Alice", 30, "Engineering"], ["Bob", 25, "Marketing"]],
  "total":   42,
  "page":    1
}
```

无交互输出（只读）。

---

### `approval-panel`

Decision 审批操作卡片。

**Props**
```json
{
  "decision_id":    "dec-001",
  "goal":           "合同即将到期，需通知相关员工",
  "trigger_result": "3 份合同将在 30 天内到期",
  "current_status": "INTENTION"
}
```

**交互输出**
```json
{
  "action": "confirm | reject | execute",
  "reason": "string（reject 时填写）"
}
```

---

### `iframe`

Legacy 工具嵌入，通过 `postMessage` 通信。

**Props**
```json
{
  "src":    "https://legacy-tool.com/editor?token=...",
  "height": "600px"
}
```

前端监听 `window.message` 事件，将结构化消息转为标准 `widget_result` 格式回传。

---

## 前端回传格式（widget_result）

用户与 widget 交互后，前端将结果封装为 `widget_result` 发回后端：

```json
{
  "session_id": "string",
  "widget_result": {
    "widget_id": "canvas-001",
    "action":    "confirm | edit | add-entity | ...",
    "data":      { ... }
  }
}
```

后端将此包装为工具调用结果注入 LLM 消息历史，继续推理。

---

## Canvas 生命周期规则

1. **同一时刻只有一个 Canvas**。新的 `open` 自动替换已有 canvas（无需先 `close`）。
2. **`patch` 操作不重置布局状态**。前端只更新数据层，保留用户的节点位置、缩放等。
3. **话题切换由 LLM 判断**。当对话转移到与当前 canvas 无关的话题，LLM 发 `close`（或直接 `open` 新 canvas，前端自动替换）。
4. **`widget_result` 路由到当前活跃 canvas 的 id**。如果 canvas 已关闭，前端应忽略或提示过期。
5. **`inline_form` 始终在 chat 区域内展示**，不影响 canvas 状态。

---

## 实现检查清单

前端系统支持本协议，需实现以下内容：

- [ ] 解析 agent 响应体中的 `canvas` 字段
- [ ] 实现布局切换（Chat Mode ↔ Canvas Mode）
- [ ] 注册 widget 组件（至少：`entity-graph`、`expression-editor`、`data-table`）
- [ ] 实现 `patch` 的 diff 更新（不整体刷新）
- [ ] 将 widget 交互结果以 `widget_result` 格式 POST 回后端
- [ ] 渲染 `inline_form` HTML 并拦截 submit 事件转为消息
