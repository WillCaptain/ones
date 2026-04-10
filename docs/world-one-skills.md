# WorldOne Skill — AIP 层设计规范

**范围**：本文档描述 World One 智能体的 AIP 层逻辑，对应 `aip` 模块中的实现。  
应用层（HTTP 服务、前端）实现见 `application/WORLD-ONE-DESIGN.md`。  
Widget 协议标准见 `spec/widget-protocol.md`。

---

## 职责边界

```
AIP 层（本文档范围）
  ├── WorldOneSkill      — system prompt 组装、阶段路由
  ├── WorldOneSession    — 会话状态（definitions、phase、world 引用）
  ├── WorldOneTools      — world 构建/配置工具（定义 + 执行）
  ├── WidgetCatalog      — widget 目录字符串（注入 system prompt）
  └── CanvasCommand      — canvas 指令数据结构（供 application 层序列化）

Application 层（不在此处）
  └── 只做 HTTP 路由和前端服务，不含业务逻辑
```

---

## 阶段状态机

```
PHASE_CREATE_WORLD
      │  触发条件：会话初始，或 world 尚未构建
      │  退出条件：world_build 工具调用成功
      ▼
PHASE_CONFIGURE_WORLD ◄────────────────────────────────┐
      │  触发条件：world 已构建，尚未有数据交互          │
      │  退出条件：用户开始查询或操作 world 数据         │ 随时可回
      ▼                                                  │
PHASE_USE_WORLD ───────────────────────────────────────┘
      触发条件：用户进行查询/决策/操作
      可重入：随时回到 CONFIGURE_WORLD 继续添加 template/action
```

### 每阶段 system prompt 组成

| 阶段 | System Prompt | 工具集 |
|---|---|---|
| `CREATE_WORLD` | `WorldDefinitionContext.DEFINITION_GUIDE` + `WidgetCatalog.CATALOG` | WorldOneTools（构建类） |
| `CONFIGURE_WORLD` | `WorldDefinitionContext.DEFINITION_GUIDE` + `WorldSystemContext.BASE_PROMPT` + `WidgetCatalog.CATALOG` | WorldOneTools（配置类）+ OntologySkill 工具 |
| `USE_WORLD` | `WorldSystemContext.buildFor(world)` + `WidgetCatalog.CATALOG` | OntologySkill 全套工具 |

### Canvas 提示规则（注入 system prompt）

```
# Canvas 规则
你可以在响应中附带 canvas 指令（参见 Widget 协议）。
规则：
- 生成 entity-graph 后，用 canvas.open 展示，让用户在可视化界面中确认/编辑
- 工具 world_add_definition / world_build 返回的 preview_graph 应立即用 canvas.patch 更新图
- 当对话话题从当前 canvas 主题转移时，发 canvas.close 再处理新话题
- 只有在工具调用缺少基础参数（文本/数字/枚举）且无法从上下文推导时，才在 text 中附带 inline_form
- 有足够参数时，直接调工具，不生成任何表单
```

---

## WorldOneSession — 会话状态

```java
class WorldOneSession {
    String              sessionId;
    WorldPhase          phase;           // CREATE_WORLD | CONFIGURE_WORLD | USE_WORLD
    List<String>        definitions;     // 积累的 JSON 定义字符串
    OntologyWorld       world;           // 构建后非 null
    String              worldName;
    String              activeCanvasId;  // 当前活跃 canvas 的 id（用于 patch 时定位）
}
```

Session 在 AIP 层管理，application 层持有 `Map<sessionId, WorldOneSession>`，仅做 CRUD，不读取 session 内容。

---

## WorldOne 工具定义

### 构建类工具（CREATE_WORLD 阶段）

#### `world_add_definition`

向当前会话追加一条 JSON 定义（枚举或实体）。

```
参数:
  definition_json  string  — 完整的 JSON 定义字符串

返回:
  {
    "ok": true,
    "entity_count": 3,
    "enum_count": 1,
    "preview_graph": {
      "entities": [...],
      "edges":    [...]
    }
  }

canvas_hint:
  调用成功后，agent 应用 canvas.open/patch entity-graph，props = preview_graph
```

#### `world_build`

将积累的定义实体化，构建 OntologyWorld。

```
参数:
  world_name  string  — 世界名称（用于持久化文件名）

返回:
  {
    "ok":           true,
    "world_name":   "hr-world",
    "entity_types": ["Company", "Department", "Employee"],
    "error":        null
  }

副作用:
  session.phase  → PHASE_CONFIGURE_WORLD
  session.world  → 构建完成的 OntologyWorld 实例
```

#### `world_get_definitions`

返回当前会话已积累的全部 JSON 定义（用于确认/回溯）。

```
参数: 无

返回:
  {
    "definitions": [ "{\"class\":\"Company\",...}", ... ],
    "entity_count": 3,
    "enum_count": 1
  }
```

---

### 配置类工具（CONFIGURE_WORLD 阶段）

#### `world_add_decision_template`

注册 Decision Template。

```
参数:
  id                  string           — 唯一标识，如 "contract_expiry_alert"
  goal                string           — 决策目标描述
  activation_source   string           — ACTION | AGENT | PIPELINE
  trigger_expression  string           — Outline 触发条件表达式
  action_chain        array<string>?   — 触发后执行的 action 名称列表（可选）

返回:
  { "ok": true, "template_id": "contract_expiry_alert" }

canvas_hint:
  调用成功后，agent 可用 canvas.open action-flow 展示因果链
```

#### `world_add_action`

声明并注册 action。

```
参数:
  entity_type    string  — 实体类型，如 "Employee"
  action_name    string  — action 名称，如 "lock"
  signature      string  — Outline 签名，如 "Unit -> Unit"
  description    string  — 人类可读说明
  outline_impl   string? — Outline 表达式实现（非空时为 outline expression action）

返回:
  { "ok": true }

备注:
  outline_impl 不为空时，action 完全由 Outline 表达式实现，无需 Java 代码
  这是 LLM 可独立完成的路径
```

#### `world_add_global_action`

注册世界级 global action（不绑定特定实体）。

```
参数:
  name         string  — action 名称（调用时用 global_xxx）
  signature    string
  description  string
  outline_impl string?

返回:
  { "ok": true }
```

---

### 查询类工具（USE_WORLD 阶段，复用 OntologySkill）

USE_WORLD 阶段直接复用 `OntologySkill` 的工具集：

| 工具 | 说明 |
|---|---|
| `ontology_get_variables` | 获取所有 VirtualSet 集合变量 |
| `ontology_get_members` | 获取实体的字段、边、action |
| `ontology_eval` | 执行 Outline 表达式 |
| `ontology_search` | 关键字搜索实体类型 |
| `ontology_describe` | 实体概览 |
| `decision_describe` | Decision Template 详情 |
| `decision_list_all` | 所有 Decision Template 列表 |

---

## WidgetCatalog — 注入 system prompt 的 Widget 描述

```java
public static final String CATALOG = """
        # Available Canvas Widgets

        When you need to show structured data or collect complex input, emit a canvas command
        (see widget protocol). The following widget types are available:

        ## entity-graph
        Visualize the OntologyWorld entity-relationship diagram.
        props: entities[{name, fields[], actions[]}], edges[{from, to, label, type}]
        Use when: after world_add_definition or world_build, to show the current schema.
        Emit: canvas.open on first show; canvas.patch when incrementally adding entities/edges.

        ## schema-editor
        Edit a single entity's fields and actions in a table UI.
        props: entity{name, fields[], actions[]}
        Use when: user wants to add/remove/rename fields on one entity.

        ## expression-editor
        Outline expression editor with autocomplete.
        props: collection, completions[], initial_value?
        Use when: collecting a trigger condition expression or a query expression.
        Provide completions from ontology_get_members / world.completionsForCollection().

        ## action-flow
        Directed graph showing a Decision Template's causal chain.
        props: nodes[{id, label, type}], edges[{from, to, label}]
        node.type: trigger | action | decision | condition
        Use when: after world_add_decision_template, to visualize the template.

        ## data-table
        Display query results. Read-only, no interaction output.
        props: columns[], rows[][], total?, page?
        Use when: ontology_eval returns a list result.

        ## approval-panel
        Decision confirmation/rejection panel.
        props: decision_id, goal, trigger_result, current_status
        Use when: a Decision needs human confirmation (status = INTENTION or DECIDED).

        ## iframe
        Embed a legacy tool via URL. Communicates via postMessage.
        props: src, height?
        Use as last resort for tools not available as native widgets.
        """;
```

---

## WorldOneSkill — 组装逻辑

```java
public class WorldOneSkill {

    public String systemPrompt(WorldOneSession session) {
        return switch (session.phase()) {
            case CREATE_WORLD ->
                WorldDefinitionContext.DEFINITION_GUIDE
                + "\n\n" + WidgetCatalog.CATALOG
                + "\n\n" + CANVAS_RULES;

            case CONFIGURE_WORLD ->
                WorldDefinitionContext.DEFINITION_GUIDE
                + "\n\n" + WorldSystemContext.BASE_PROMPT
                + "\n\n" + WidgetCatalog.CATALOG
                + "\n\n" + CANVAS_RULES;

            case USE_WORLD ->
                WorldSystemContext.buildFor(session.world())
                + "\n\n" + WidgetCatalog.CATALOG
                + "\n\n" + CANVAS_RULES;
        };
    }

    public List<OntologyAgentTool> tools(WorldOneSession session, OntologyWorld world) {
        List<OntologyAgentTool> tools = new ArrayList<>(worldOneTools(session));
        if (world != null) {
            tools.addAll(new OntologySkill().tools(world));
        }
        return tools;
    }

    public AgentResponse handleTurn(String userInput, WorldOneSession session) {
        // 1. 如果 userInput 是 widget_result，包装为工具结果注入历史
        // 2. 运行 LLM tool-call 循环（复用 LLMCaller）
        // 3. 解析 LLM 最终响应中的 canvas 指令，构建 AgentResponse
        // 4. 根据工具调用副作用更新 session.phase
        ...
    }
}
```

---

## AgentResponse — AIP 层返回给 application 层的结构

```java
public record AgentResponse(
    String        text,           // Markdown 文字（流式时为 null，通过 callback）
    CanvasCommand canvas,         // null 表示无 canvas 变更
    String        inlineForm,     // null 表示无内联表单
    String        sessionId
) {}

public record CanvasCommand(
    String       action,    // open | patch | replace | close
    CanvasWidget widget,    // open/replace 时非 null
    List<JsonPatchOp> patch // patch 时非 null
) {}

public record CanvasWidget(
    String id,
    String type,
    Object props
) {}
```

Application 层将 `AgentResponse` 序列化为前端协议格式（见 `spec/widget-protocol.md`）。

---

## 文件结构（AIP 模块）

```
aip/src/main/java/org/twelve/entitir/aip/
├── (已有)
│   ├── WorldSystemContext.java      — world 操作提示词
│   ├── WorldDefinitionContext.java  — world 创建提示词
│   ├── OntologySkill.java           — world 查询/操作工具集
│   └── ...
├── worldone/
│   ├── WorldOneSkill.java           — system prompt 组装 + handleTurn
│   ├── WorldOneSession.java         — 会话状态（phase, definitions, world）
│   ├── WorldOneTools.java           — world 构建/配置工具实现
│   └── WorldPhase.java              — enum: CREATE_WORLD, CONFIGURE_WORLD, USE_WORLD
└── widget/
    ├── WidgetCatalog.java            — widget 目录字符串（String 常量）
    ├── AgentResponse.java            — AIP → application 返回结构
    ├── CanvasCommand.java            — canvas 指令
    └── CanvasWidget.java             — widget id + type + props
```
