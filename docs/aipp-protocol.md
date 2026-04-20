# AI-Native App Protocol

> 版本：0.2 草案  
> 状态：设计讨论中（已与当前 world-one / AIPP 实现对齐部分章节）  
> 作者：World One Project

---

## 零、核心角色定义

在 AI-Native App 体系中，有两个明确分离的角色：

### AI Agent（AI 代理）— 以 World One 为例

**职责**：理解用户意图、发现应用能力、调用 API、驱动 Widget 渲染。  
**内容**：LLM 连接、Canvas 运行时、Widget Loader、Registry 客户端、Chat UI。  
**没有**：任何领域知识、任何硬编码 API、任何硬编码 Widget、任何业务逻辑。

World One 是一个**几乎空的壳**——它的能力完全来自于已安装的 AI Plugin Programs。

### AI Plugin Program（AI 插件程序）— 以 world.aipp 为例

**职责**：封装某个领域的能力，以标准协议公开给 AI Agent 使用。  
**内容**：Capability Skills（工具定义）、Widget Manifest（界面目录）、API 实现、Widget 实现。  
**不包含**：任何 AI Agent 逻辑、任何 LLM 调用、任何 Chat UI。

`.aipp`（**AI** **P**lugin **P**rogram）是 AI Plugin Program 的**打包格式**，
类似 `.jar` 之于 Java、`.app` 之于 macOS。

AI Plugin Program 对外暴露的能力由两类构成：

- **Tools**：原子能力定义（OpenAI function-calling schema），一次调用完成一个动作；
  带 `visibility`（`llm` / `ui` / `host`）与 `scope`（`universal` / `app` / `widget` / `view`）元字段。
- **Skills**（progressive disclosure）：playbook 级事务单元，内部按步骤编排 tools，由 Host 的召回机制按需加载。详见 `aipp-skills-progressive-disclosure.md`。

### 术语对照（Phase 4 稳态）

| 术语   | AIPP 含义                                          | 类比     |
|--------|----------------------------------------------------|----------|
| Tool   | `GET /api/tools` 的单个条目 / `POST /api/tools/{name}` 可执行端点 | RPC 签名+实现 |
| Skill  | `GET /api/skills` 索引的单个条目 / `GET /api/skills/{id}/playbook` 返回的 SKILL.md | 操作手册 |
| Widget | `GET /api/widgets` 返回的 UI 组件                 | 视图     |

> 历史上存在的 **Function** 与 **Canvas Skill** 概念已统一合并为 **Tool**（由 `visibility` / `scope` 元字段区分层级与调用者），不再单独出现。

### Registry（注册器）— ones 共享基础设施

**职责**：管理已安装的 AI Plugin Programs，向任意 AI Agent 提供统一的能力索引。  
**功能**：发现、安装、卸载 `.aipp` 包；聚合所有 app 的 Skills 和 Widgets。  
**说明**：Registry 是 **ones** 生态的公共基础设施，不归属于任何单一 Agent。

---

## 一、为什么需要这个协议

传统 Web 应用是为**人**设计的：人点击按钮，看到页面，填写表单。  
未来越来越多的应用会被**AI 代理（Agent）**调用——AI 不点按钮，不看页面，它读 API、调工具、理解结果。

但 AI 代理拿到 API 结果之后，如何把结果**呈现给人**？  
如果每次都靠 LLM 凭感觉生成 HTML，质量无法保证，且效率极低。

AI-Native App Protocol 解决这个问题：  
**定义应用如何向 AI 代理公布自己的能力和界面，让 AI 代理能够可靠、一致地驱动界面。**

---

## 二、核心思想

```
传统模式（App for Human）：
  人 → 浏览器 → UI → API → 数据

AI-Native 模式（App for AI）：
  人 → AI Agent → [Skill + Widget] → API → 数据 + Canvas
                                              ↓
                                         Widget 渲染
                                              ↓
                                          人看到结果
```

AI 代理不直接操作 UI，而是：
1. 通过 **Capability Skill** 知道应用能做什么
2. 调用 **API** 得到结果（结果中内嵌 canvas 指令）
3. 通过 **Widget Manifest** 找到对应的界面组件
4. 将 API 结果输入 Widget，完成渲染
5. 进入 **Canvas Mode**，人在 canvas 中查看和交互

---

## 三、应用对外公布的三份契约

一个 AI-Native 应用必须公布以下三份内容：

### 3.1 Capability Tool（能力声明）

```
GET /api/tools                    — 原子 Tool 清单（权威端点）
GET /api/skills                   — Skill Playbook 索引（progressive disclosure）
GET /api/skills/{id}/playbook     — 单个 Skill 的 SKILL.md 正文
```

> 本节下文描述的是 **Tool 层**（`/api/tools` 返回的内容）。Skill 层的设计详见 `aipp-skills-progressive-disclosure.md`；尚未实现 playbook 的 app 的 `/api/skills` 返回空 `skills` 数组即可，Host 自动降级为仅按 Tool 调用。

描述应用能做什么，即 AI 代理 / UI / Host 可以调用哪些工具。  
每个 Tool 对应一个可调用的操作，包含名称、描述、参数 Schema、`visibility`、`scope`，以及  
**该操作是否会触发 canvas 及触发哪种 widget**。

```json
{
  "app": "World One",
  "version": "1.0",
  "system_prompt": "## App 级规则（仅在本 App active 时注入）",
  "prompt_contributions": [
    {
      "id": "world-routing",
      "scope": "conversation",
      "activate_when": ["ui_active", "workspace_bound"],
      "priority": 100,
      "content": "用户在 world 语境中的路由规范..."
    }
  ],
  "skills": [
    {
      "name": "world_create_session",
      "description": "创建一个新的本体世界设计会话，返回 canvas 指令进入图形设计模式",
      "parameters": {
        "type": "object",
        "properties": {
          "name": { "type": "string", "description": "世界名称" }
        },
        "required": ["name"]
      },
      "canvas": {
        "triggers": true,
        "widget_type": "entity-graph",
        "action": "open"
      }
    },
    {
      "name": "world_add_definition",
      "description": "向当前会话添加一个实体或枚举定义",
      "parameters": {
        "type": "object",
        "properties": {
          "definition": { "type": "string", "description": "JSON 格式的类定义" }
        },
        "required": ["definition"]
      },
      "canvas": {
        "triggers": true,
        "widget_type": "entity-graph",
        "action": "patch"
      }
    }
  ]
}
```

**关键字段说明：**
- `canvas.triggers`：此操作是否会在响应中携带 canvas 指令
- `canvas.widget_type`：触发的 widget 类型（对应 Widget Manifest 中的 type）
- `canvas.action`：预期的 canvas 动作（open / patch / replace / close）
- `system_prompt`：App 级系统提示（兼容旧字段；仅 active 时注入）
- `prompt_contributions`：推荐的新字段；每项必须声明 `layer`，用于区分 `aap_pre` / `aap_post`
- `kind`（**必填**）：skill 类别，取值二选一：
  - `design`：纯配置/编排类（增删改元数据、建立关系、定义模板等），不会改变运行时状态、不会对外产生副作用
  - `execution`：会改变运行时状态、触发外部行为、或执行查询返回实时数据（**query 视为 execution**，因为查询结果会被 LLM 当成"已发生"的事实来推理）

  Host 在 dedicated widget session 且 `scope.forbid_execution=true` 时，会从工具列表中剔除所有 `kind=execution` 的 skill。各 app 必须在 skills.json 中显式声明 `kind`，缺省视为 `execution`（保守默认）。

### 3.1.2 Active App Prompt 装载规则（Host 侧强制）

Host（如 world-one）必须区分两类提示词：

1. **Host Prompt（常驻）**  
   仅包含通用代理铁律；不得包含任何具体 AIPP 业务知识。
2. **App Prompt（AAP-Pre，按 active 注入）**  
   来自 app 的 `system_prompt` / `prompt_contributions`，只有 app 在当前回合 `active` 时才注入；主要用于"命中判定/路由边界"。

此外，Host 应支持命中后提示层：

3. **Hit Prompt（AAP-Post，按 hit 注入）**  
   当 LLM 在本轮通过工具真实命中某个 app 后，由该 app 在工具响应中返回命中后操作手册，Host 在同一 user turn 的后续 LLM 循环中临时注入。

`active` 判定来源（可组合）：

- `ui_active`：当前正在浏览该 app 的主界面/面板/widget
- `workspace_bound`：当前工作区绑定到该 app（例如 world canvas）
- `event_source`：当前 task/event 由该 app 发出
- `recent_tool_owner`：最近回合工具调用归属该 app

若无 active app，Host 只注入 Host Prompt。

> 建议：工具可见性与 Prompt 装载保持一致（都按 active 裁剪），避免“看见工具但缺少该 app 规则”的失配。

#### AAP-Pre / AAP-Post 最小强约束

- `prompt_contributions[].layer` 必填，且仅允许：`aap_pre` 或 `aap_post`
- Host 组装命中前提示词时，只加载 `layer=aap_pre`
- `layer=aap_post` 不参与命中前组装，仅供协议与离线校验使用

#### AAP-Post 工具响应约定（建议）

命中 app 的工具响应可包含：

```json
{
  "ok": true,
  "aap_hit": {
    "app_id": "memory-one",
    "post_system_prompt": "## 命中 memory-one 后的操作手册...",
    "ttl": "this_turn"
  }
}
```

字段说明：

- `aap_hit.app_id`：命中的 app id
- `aap_hit.post_system_prompt`：命中后注入的系统提示片段（操作手册）
- `aap_hit.ttl`：作用域，仅允许：
  - `this_turn`：仅当前 user turn 有效（默认）
  - `until_widget_close`：到当前 widget 会话关闭前有效

优先级建议：

- `AAP-Post` 优先级高于 `AAP-Pre`
- 同一轮多次命中时，默认采用最后一次成功命中的 `AAP-Post`
- `AAP-Post` 仅影响行为约束，不应自动扩大工具可见性
- 命中后下一次 LLM 循环建议进入执行态：`Host + AAP-Post`，默认不再混入多 app 的 `AAP-Pre`

### 3.1.1 Session 扩展语义（session 与 canvas 正交）

Skill 可选声明 `session` 扩展，session 语义与 canvas 是否触发互不干涉：

```json
{
  "name": "world_design",
  "canvas": { "triggers": true, "widget_type": "entity-graph" },
  "session": {
    "session_type": "app",
    "app_id":       "world",
    "creates_on":   "name",
    "loads_on":     "session_id"
  }
}
```

路由规则：

- 命中 `session_type=app` 且响应无 `session_id`：按 `app_id` 路由（单实例 app session）
- 命中 `session_type=app` 且响应有 `session_id`：按 `(app_id, session_id)` 路由（多实例 app session）
- 命中 `creates_on/loads_on`：由 world-one 执行 find-or-create，并复用已有会话（不重复创建）

归一规则（不嵌套）：

- 若当前 widget 已进入 new-session 上下文，在该上下文再次进入 new-session widget，不新建 session
- 仅执行视图层覆盖（`canvas.open/replace`），并保留返回路径

> App Session 不进入 Task Panel；Task/Event 仍使用独立面板。

#### 工具 JSON 中 `html_widget` 与 canvas 的优先级（Host 行为）

当 `POST /api/tools/{name}` 返回的 JSON **根节点**含有 `html_widget` 时，World One 等 Host **仅**走内嵌卡片路径，**不**根据**同一次**响应再生成 canvas / session 事件。  
这样，声明了 `canvas.triggers: true` 的应用仍可在「列表/消歧」场景先返回 `html_widget`，用户选择后再由后续调用返回图数据并进入 Canvas。  
规范细节见 `shared/aipp-protocol/README.md`（响应字段优先级、`not_found` 与二次确认新建约定）。

#### Chat 路径下的 session 类型映射（Host 策略，非应用硬编码）

在**主对话**（Chat）中进入某些多实例应用工作区时，Host 可能将应用返回的 `session_type: app` **规范为** `task`，以便在 Task Panel 中展示与切换；应用 HTTP API 仍可保持原有 `session_type` 字段。该逻辑在 Host（如 `WorldOneChatController`）中实现，**不属于**各 AIPP 应用的业务代码。

---

### 3.2 Widget Manifest（界面组件目录）

```
GET /api/widgets
```

描述应用提供哪些可视化组件，每个 Widget 的输入 Schema、交互能力。  
这是 AI 代理将 API 结果映射到界面的依据。

```json
{
  "widgets": [
    {
      "type": "entity-graph",
      "description": "实体关系图：展示实体节点、枚举节点及其关联边",
      "version": "1.0",
      "source": "builtin",
      "props_schema": {
        "type": "object",
        "properties": {
          "session_name": { "type": "string" },
          "nodes": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "id":     { "type": "string" },
                "label":  { "type": "string" },
                "type":   { "type": "string", "enum": ["entity", "enum"] },
                "fields": {
                  "type": "array",
                  "items": {
                    "properties": {
                      "name": { "type": "string" },
                      "type": { "type": "string" }
                    }
                  }
                }
              }
            }
          },
          "edges": {
            "type": "array",
            "items": {
              "properties": {
                "from":  { "type": "string" },
                "to":    { "type": "string" },
                "label": { "type": "string" }
              }
            }
          }
        }
      },
      "actions": ["open", "patch", "replace", "close"],
      "widget_result": {
        "description": "用户在 widget 中需要 AI 介入的操作事件。纯 UI 行为（缩放、拖拽）由 widget 自行处理，不发出。",
        "external_actions": [
          {
            "action": "node_click",
            "description": "用户点击实体节点，AI 可提供编辑建议或展示详情",
            "payload_schema": { "node_id": "string", "node_type": "string" }
          },
          {
            "action": "confirm",
            "description": "用户确认当前设计，AI 继续下一步（如 validate → build）",
            "payload_schema": {}
          }
        ],
        "internal_actions": "所有未列出的 UI 行为（缩放、拖拽、折叠节点等）由 widget 自行处理，不回传 AI"
      }
    }
  ]
}
```

**Widget source 类型（并列，不分优劣）：**

| source | 说明 | 渲染方式 |
|--------|------|---------|
| `builtin` | 应用前端内置组件 | 直接调用 JS 函数 |
| `url` | 远程 JS 模块 | 动态 import，调用标准接口 |
| `iframe` | 遗留 Web 应用 | 沙箱 iframe + postMessage 通信 |

三种 source 在 agent 看来是等价的——只要在 Widget Manifest 中有注册，都算"命中"。
遗留 iframe 应用只需要实现 postMessage 通信协议，即可作为合法的 AI-Native widget。

---

### 3.2.1 Widget Context & Scope（Widget 激活态的上下文与能力作用域）

> 版本：0.2
>
> 本节**只描述 widget 被激活时对 LLM 上下文的影响**（system prompt 注入 + 工具裁剪 + view 级细粒度控制）。
> **session 的创建/归一完全由 skill 的 `session` 块（§3.1.1）+ Host 映射规则决定，与本节无关。**
> widget 自己不决定是否开新 session——这是上层 skill 的事情。

#### 3.2.1.1 声明格式

```json
{
  "type": "entity-graph",
  "description": "...",
  "props_schema": { ... },
  "actions": ["open", "patch", "replace", "close"],

  "system_prompt": "## Widget 操作手册\n当前处于本体世界设计器……",

  "scope": {
    "tools_allow":   ["world_*"],
    "tools_deny":    ["world_create_session"],
    "forbid_execution": true
  },

  "views": [
    {
      "id": "entities",
      "label_i18n": { "zh": "实体", "en": "Entities" },
      "system_prompt": "当前在【实体】视图：可增删改 entity / enum。",
      "scope": { "tools_allow": ["world_*_definition", "world_get_session", "world_pipeline_status"] }
    },
    {
      "id": "decisions",
      "label_i18n": { "zh": "决策", "en": "Decisions" },
      "system_prompt": "当前在【决策】视图：建立链路直接调用 world_link_decision(downstream, upstream, op=\"add\") 逐对调用；A→B→C 调两次 link。",
      "scope": { "tools_allow": ["world_*_decision", "world_link_decision", "world_get_session"] }
    },
    {
      "id": "actions",
      "label_i18n": { "zh": "动作", "en": "Actions" },
      "system_prompt": "当前在【动作】视图：配置 action / webhook。",
      "scope": { "tools_allow": ["world_*_action", "world_list_actions", "world_get_session"] }
    }
  ],

  "widget_result": { ... }
}
```

#### 3.2.1.2 字段语义

**`system_prompt`（可选字符串，widget 级）**

widget 激活期间注入系统提示的一段固定 SOP。**不是**新 session 的系统提示——仅在该 widget 处于激活态时叠加到当前 session 的 system prompt 里；widget 关闭 / 切到别的 widget 时自动撤出。

**`scope`（可选对象，widget 级）**

widget 激活期间对当前 session 可用工具列表的过滤。

| 字段 | 类型 | 语义 |
|------|------|------|
| `tools_allow` | 字符串数组（支持 `*` 通配） | 白名单。空 = 全部允许（仍受 `tools_deny` 过滤） |
| `tools_deny`  | 字符串数组（支持 `*` 通配） | 黑名单。命中则隐藏，优先级高于 allow |
| `forbid_execution` | 布尔，默认 `false` | `true` 时剔除所有 `skill.kind=execution` 的工具；`kind` 缺省视为 `execution`（保守默认） |

> `scope` 不影响 session 本身，只影响"widget 激活时这一轮 LLM 看到什么工具"。widget 切出后恢复 session 默认工具可见性。

**`views`（可选数组）**

widget 内的多视图（tab）声明。每个 view 可单独声明：

- `id`（必填，字符串）：view 标识，前端通过请求里的 `active_view` 字段传递
- `label_i18n`（可选）：展示名国际化
- `system_prompt`（可选）：view 激活时追加到 widget system_prompt **之后**的一段 SOP
- `scope`（可选）：view 级工具过滤。**与 widget 级 scope 取交集**（view 只会更窄，不会更宽）

#### 3.2.1.3 每轮 Prompt 与 Tool 装配（规范）

对于任意 session（不论 chat / task / event），当有 **widget 处于激活态**时，本轮 system prompt 与 tool list 按以下规则叠加——

**System prompt 叠加**（顺序重要，后者优先级更高）：
```
base_system_prompt（Host 基础规则 + memory）
  + active_app.aap_post  （若 AAP-Post 命中且未过期）
  + widget.system_prompt
  + view[active_view].system_prompt
```

**Tool list 裁剪**：
```
tools_visible =
    all_registered_skills
  ∩ widget.scope.tools_allow (default = all)
  − widget.scope.tools_deny
  ∩ view[active_view].scope.tools_allow (default = all)
  − view[active_view].scope.tools_deny
  − { s | widget.scope.forbid_execution AND s.kind != "design" }
  ∪ widget.canvas_skill.tools   （设计态工具，不被 scope 过滤）
```

widget 未激活时，上述步骤全部跳过，退化为 Host 默认装配。

#### 3.2.1.4 `active_view` 请求字段

前端在 canvas 模式下的每一轮对话请求中，附带当前 tab 标识：

```json
{
  "session_id": "...",
  "messages":   [ ... ],
  "canvas": {
    "active_widget_type": "entity-graph",
    "active_view":        "decisions"
  }
}
```

- 切 tab **不换 session、不清 history**——同一个 LLM 上下文，仅本轮 prompt 与 tool list 随 `active_view` 重算
- `active_view` 缺省或未匹配任何 `views[].id` 时，Host 跳过 view 级叠加，仅用 widget 级
- view 切换不产生显式"切换事件"——下一轮请求携带新 `active_view` 即自动生效

#### 3.2.1.5 最小强约束

- widget 级 `scope.forbid_execution` 可被 view 继承增强，**不可被 view 放宽**（view 不能用更松的 scope 覆盖 widget 的禁令）
- 各 app **必须**为所有 skill 显式声明 `kind`，缺省视为 `execution`（见 §3.1 `kind` 定义）
- `widget.canvas_skill.tools` 属于设计态工具，永不被 `scope.forbid_execution` 过滤；若该工具有执行副作用，应**不要**放入 canvas_skill，而挂在普通 skill 上并声明 `kind=execution`

---

### 3.3 API（实际操作接口）

每个 API 的响应遵循以下结构：

```json
{
  "result": {
    /* 业务数据，AI 代理使用 */
  },
  "canvas": {
    "action": "open | patch | replace | close",
    "widget_id": "实例 ID（同一 widget 的多次 patch 使用相同 ID）",
    "widget_type": "entity-graph",
    "props": {
      /* 符合对应 widget props_schema 的数据，由服务器转换完毕 */
    }
  }
}
```

**`result` 与 `props` 的分工：**
- `result`：原始业务数据，AI 代理理解和推理用
- `props`：已转换为 widget-ready 的展示数据，widget 直接消费
- **主路径**：转换发生在服务器端 Tool 层，props 已准备好，AI 无需介入
- **AI 推导路径（未来）**：AI 知道 `result` 的结构 和 widget 的 `props_schema`，
  当服务器未提供 props 时，AI 可自行推导转换。这允许已有 API 在不修改响应的
  情况下接入新的 widget 类型。

`canvas` 字段是可选的。没有 canvas 字段的 API 响应代表纯文字交互，不触发 canvas mode。

---

## 四、AI 代理的运行时流程

```
阶段 0 — 能力加载（一次性）
  AI Agent 启动时读取：
  ├── GET /api/tools    → 加载到 system prompt 或 tool 列表（带 visibility/scope）
  ├── GET /api/skills   → 加载 Skill Playbook 索引（progressive disclosure，按需取 playbook）
  └── GET /api/widgets  → 加载到 widget 上下文

阶段 1 — 意图识别
  用户输入 → AI 分析 → 匹配 Tool（直接）或 Skill playbook（多步事务）

阶段 2 — 工具调用
  AI 调用对应 API（POST /api/xxx）
  ← 返回 { result, canvas }

阶段 3 — Canvas 渲染决策
  if canvas 字段存在:
    widget = WidgetManifest.find(canvas.widget_type)

    if widget 命中（builtin / url / iframe 均算命中）:
      render(widget, canvas.action, canvas.props)  → 进入 Canvas Mode

    if widget 未命中:
      if canvas.props 存在:        LLM 根据 props_schema 推断生成 HTML  ← 兜底
      else:                        格式化展示 result JSON               ← 最终兜底

  if canvas.action == "patch":
    widget.patch(canvas.props)  ← 增量更新，不重新 open

  if canvas 字段不存在:
    保持 Chat Mode，纯文字回复

阶段 4 — 用户在 Canvas 交互
  用户操作 Widget → widget_result 事件回传 AI Agent
  AI Agent 根据 widget_result 决定下一步（继续调用 API / 修改 / 退出）
```

---

## 五、Widget 标准接口

所有 Widget（无论 source 类型）必须实现以下标准接口：

```typescript
interface AIWidget {
  // 首次打开：完整 props 初始化
  init(props: object): void;

  // 增量更新：仅传入变化的部分
  patch(props: Partial<object>): void;

  // 完整替换：等同于 close + init
  replace(props: object): void;

  // 关闭并清理
  close(): void;

  // 监听需要 AI 介入的外部事件（内部 UI 行为不发出）
  onResult(callback: (result: WidgetResult) => void): void;
}

interface WidgetResult {
  widget_id: string;
  action: string;   // 对应 Widget Manifest 中 external_actions 里声明的 action
  payload: object;  // action 对应的 payload_schema 数据
}

// Widget 自行判断哪些事件需要回调：
// - 纯 UI 交互（缩放、拖拽、折叠）：widget 内部消化，不触发 onResult
// - 有业务语义的交互（点击节点请求编辑、确认操作）：触发 onResult，由 AI 决策
```

**iframe Widget 通过 `postMessage` 实现相同接口：**

```javascript
// 宿主 → iframe
iframe.contentWindow.postMessage({ action: 'init', props: {...} }, '*');

// iframe → 宿主（widget_result）
window.parent.postMessage({ type: 'widget_result', action: 'node_click', payload: {...} }, '*');
```

---

## 六、Canvas 指令规范

```
Canvas Action 语义：

open    — 创建新 widget 实例，进入 Canvas Mode
          required: widget_id, widget_type, props（完整）

patch   — 对已有 widget 实例进行增量更新
          required: widget_id
          optional: props（仅包含变化字段）

replace — 对已有 widget 实例进行完整数据替换（不重新布局）
          required: widget_id, props（完整）

close   — 关闭 widget，退出 Canvas Mode
          required: widget_id
```

**widget_id 约定：**
- 由服务器生成，格式：`{widget_type}-{session_id_prefix}`，如 `entity-graph-abc123`
- 同一业务会话的多次 patch 使用同一 widget_id
- 不同会话不复用 widget_id

---

## 七、Widget 解析与 Fallback

### 解析流程

```
canvas.widget_type 存在？
  ↓ 是
Widget Manifest 中有注册？
  ├─ 有（builtin）  → 直接调用内置 renderer
  ├─ 有（url）      → 动态加载 JS 模块，调用标准接口
  ├─ 有（iframe）   → 沙箱 iframe + postMessage
  │    ↑ 这三种 source 都是"命中"，等价
  │
  └─ 无注册（未命中）
       ↓
     canvas.props 存在？
       ├─ 是 → LLM 根据 props + widget_type 描述生成 HTML
       └─ 否 → 格式化展示 result JSON（最终兜底）
```

**iframe 不是降级，是一种 widget 实现方式。**  
遗留 Web 应用注册为 iframe widget 后，从 agent 视角看和 builtin widget 完全等价。

### LLM 生成 HTML 时的上下文

```
widget_type: {type}（描述期望的展示效果）
props_schema: {schema}（数据结构）
data: {props}（实际数据）

请生成一段 HTML 片段，合理展示以上数据。
```

---

## 八、App4AI 设计原则

遵循此协议设计应用时，建议遵守以下原则：

### P1 — API 是 AI 的主要通道
不要把业务逻辑藏在 UI 交互里。所有状态变更必须通过可调用的 API 完成，widget 只负责展示和收集输入。

### P2 — Canvas 是可选的增强
所有 API 在没有 canvas 字段时也必须能正常工作。canvas 是"UI 增强层"，不是数据获取的必要路径。

### P3 — Props 由服务器转换
`canvas.props` 应该是 widget-ready 的，AI 代理不负责数据格式转换。这确保 widget 的输入始终是可预测的结构。

### P4 — Widget 是无状态的界面
Widget 不保存业务状态，每次 init/patch 都由服务器提供完整或增量数据。业务状态由服务器的 Session 管理。

### P5 — Skill 是 AI 的 API 文档
Capability Skill 和 Widget Manifest 是给 AI 读的，不是给人读的。描述要精确、完整、机器友好，避免歧义。

### P6 — widget_result 闭合交互循环
Widget 内的用户操作必须通过 widget_result 回调 AI 代理，由 AI 决定下一步行动，而不是在 widget 内直接调用 API。这保持了 AI 的中心地位。

---

## 九、与现有标准的关系

| 标准 | 关系 |
|------|------|
| OpenAPI / Swagger | Capability Skill 是 OpenAPI 的 AI-native 子集，增加了 canvas 语义 |
| MCP (Model Context Protocol) | 当前采用 OpenAI function calling 格式；MCP 格式兼容为 future work |
| Anthropic Agent Skills | AIPP 的 Skill 层借鉴其 progressive disclosure 思想；wire 层保留 OpenAI function-calling schema 以获得跨模型兼容性，详见 `aipp-skills-progressive-disclosure.md` |
| Web Components | builtin/url widget 可基于 Web Components 实现 |
| postMessage API | iframe widget 的标准通信机制 |

---

## 十、当前实现状态与分层修正

### 分层修正（重要）

当前代码的分层与协议定义存在偏差，需要修正：

| 当前位置 | 应在位置 | 内容 |
|---------|---------|------|
| `worldone/` + `aip/` | `world.aipp` app | WorldOneAgentLoop、WorldOneSession、WorldOneTools |
| `worldone/` 前端 | `world.aipp/widgets/` | entity-graph widget 实现 |
| `worldone/` | World One 核心（保留）| Canvas 运行时、Chat UI、Registry 客户端、LLM 连接 |

### 协议实现状态

| 协议要求 | 实现状态 | 备注 |
|---------|---------|------|
| `.aipp` 包格式 | ❌ 未实现 | 尚无打包机制 |
| Registry（`~/.ones/apps/`）| ✅ 已实现 | `AppRegistry` 扫描目录 + `/api/registry` 端点 |
| `GET /api/tools` | ✅ 已实现 | `world-entitir/SkillsController`（权威 Tool 清单，含 `visibility`+`scope`） |
| `GET /api/skills` | ✅ 已实现（空索引） | `world-entitir/SkillsController` — Skill Playbook 索引（当前无 playbook） |
| `GET /api/widgets` | ✅ 已实现 | `world-entitir/WidgetsController` |
| `POST /api/tools/{name}` | ✅ 已实现 | `world-entitir/ToolsController` |
| 同次响应 `html_widget` 优先于 canvas | ✅ 已实现 | Host `GenericAgentLoop.extractEvents` |
| `canvas` 字段协议 | ✅ 已实现 | 工具返回值携带 canvas 字段 |
| `widget_type` 约定 | ✅ 已实现 | `entity-graph` 类型 |
| `action: open/patch/replace/close` | ✅ 已实现 | |
| Widget `init(props)` | ✅ 隐式实现 | 前端 `renderGraph()` |
| Widget `patch(props)` | ✅ 隐式实现 | 前端 `graphState` 合并 |
| Widget `onResult` / `widget_result` | ⚠️ 部分场景 | 列表类 html_widget 通过 postMessage 续调工具；完整闭环见路线图 |
| iframe / url widget | ❌ 未实现 | 只有 builtin |
| World One 薄壳化 | ✅ 已完成 | GenericAgentLoop + Registry 路由 |
| `GET /api/skills/{id}/playbook` | ❌ 未实现（stub 404） | Skill 层正文 (SKILL.md)；端点已预留 |
| Loop A 召回 / Loop B 执行 | ❌ 未实现 | host 侧 SkillRecaller + SkillExecutor |

---

## 十一、.aipp 包格式

`.aipp`（**AI** **P**lugin **P**rogram）是 AI Plugin Program 的标准打包格式，
类似 `.jar` 之于 Java、`.app` 之于 macOS。

`.aipp` 是一个 ZIP 压缩包（扩展名 `.aipp`），解压后为标准目录：

```
world/                         ← app id（不含扩展名）
├── manifest.json              ← 必须：应用元数据 + 入口声明
├── skills.json                ← 必须：Capability Skills（工具定义）
├── widgets.json               ← 必须：Widget Manifest
├── widgets/                   ← 可选：builtin widget 实现（JS 文件）
│   └── entity-graph.js
└── api/                       ← 可选：本地 API 服务打包（JAR 或脚本）
    ├── world-api.jar
    └── start.sh
```

**安装路径**：解压到 `~/.ones/apps/{app-id}/`

### manifest.json

```json
{
  "id": "world",
  "name": "World — OntologyWorld Designer",
  "version": "1.0",
  "description": "通过对话设计和管理 OntologyWorld",
  "api": {
    "mode": "http",
    "base_url": "http://localhost:8093",
    "tools_path":   "/api/tools",
    "skills_path":  "/api/skills",
    "widgets_path": "/api/widgets"
  },
  "startup": {
    "command": "java -jar api/world-api.jar",
    "health_check": "/api/health",
    "timeout_seconds": 30
  }
}
```

**api.mode 类型：**

| mode | 说明 |
|------|------|
| `http` | app 是独立 HTTP 服务，World One 通过 HTTP 调用 |
| `embedded` | app 打包为 JAR，World One 启动并内嵌 |
| `static` | 无后端，纯前端 widget 应用 |

**第一版只实现 `http` 模式**：app 独立运行，World One 通过 `base_url` 访问。

---

## 十二、Registry 协议

Registry 管理已安装的 `.aipp` 包，是任意 AI Agent 的能力来源。
Registry 是 **ones** 生态的公共基础设施，多个 Agent 可共享同一个 Registry。

### 存储位置

跨平台使用 `System.getProperty("user.home")` 确定根目录：

| OS | 路径示例 |
|----|---------|
| macOS / Linux | `~/.ones/apps/` |
| Windows | `C:\Users\username\.ones\apps\` |

Java 代码：`Paths.get(System.getProperty("user.home"), ".ones", "apps")`

### 存储结构

```
~/.ones/
└── apps/
    └── world/             ← 解压后的 app 目录（app id 即目录名）
        └── manifest.json  ← 入口，其余信息从 app HTTP 接口读取
```

### Registry API（由 AI Agent 内置，如 World One）

```
POST /api/registry/install          ← 安装：传入 app_id + base_url（http 模式）
DELETE /api/registry/apps/{id}      ← 卸载
GET  /api/registry                  ← 列出已安装 app 及状态（running / stopped）
POST /api/registry/apps/{id}/start  ← 启动 app（http 模式）
POST /api/registry/apps/{id}/stop   ← 停止 app

GET  /api/registry/skills           ← 聚合所有 app 的 Skills（Agent 启动时加载）
GET  /api/registry/widgets          ← 聚合所有 app 的 Widgets（Agent 启动时加载）
```

### Agent 启动流程

```
1. 扫描 ~/.ones/apps/ 目录
2. 读取每个 app 的 manifest.json（获取 base_url）
3. 检查 app 是否运行（HTTP health check）
4. 拉取 /api/tools → 合并到全局 Tool 清单（按 `visibility`+`scope` 路由 LLM / UI / Host）
5. 拉取 /api/skills → 合并 Skill Playbook 索引（progressive disclosure，当前可为空）
6. 拉取 /api/widgets → 合并到全局 widget registry
7. 构建 LLM system prompt（包含所有 app 的 Tool 描述）
7. 就绪
```

### API 路由

用户意图触发工具调用时，Agent 通过 skill 的 `app_id` 路由请求：

```json
// skill 定义中包含归属信息
{
  "name": "world_create_session",
  "app_id": "world",              ← Agent 用此字段在 Registry 中找到对应 base_url
  "description": "..."
}
// 路由：POST http://{base_url}/api/tools/world_create_session
```

---

## 十三、实施路线图

### Phase 1 — 协议补全（当前 worldone 模块内，验证设计）

1. `GET /api/tools`：聚合所有 Tool 的定义（带 `visibility`+`scope`），加上 canvas 语义字段
2. `GET /api/widgets`：输出 entity-graph 的完整 props_schema 和 external_actions
3. 前端 Widget Registry 动态化：按 `widget_type` 查找 renderer，不再 hardcode
4. `widget_result` 实现：entity-graph 节点点击 → SSE 回传 AI

### Phase 2 — 以终为始重构（直接到位）

5. **新建 `world-entitir/` 模块**（独立 Spring Boot，端口 8093）
   - 依赖 `aip/` + `ontology/`（domain 逻辑留在 aip 层，world-entitir 做 HTTP 包装）
   - 实现 `GET /api/tools`、`GET /api/skills`、`GET /api/widgets`、`POST /api/tools/{name}`
   - entity-graph widget 作为静态资源在此模块中
   - 含 `manifest.json` 描述自身

6. **重构 `worldone/` 为通用薄壳** ✅ 已完成
   - 移除 WorldOneAgentLoop（含领域知识）
   - 新建 `GenericAgentLoop`：从 Registry 读工具定义，HTTP 路由工具调用
   - 新建 `AppRegistry`：扫描 `~/.ones/apps/`，维护 app → base_url 映射
   - World One 核心只保留：Canvas 运行时、Chat UI、Registry、LLM 连接、GenericAgentLoop

7. **注册 world app 到 Registry** ✅ 已完成
   - 将 world 的 manifest.json 放入 `~/.ones/apps/world/`
   - 或通过 `POST /api/registry/install` 动态注册
   - World One 启动时自动发现并加载

### Phase 4 — 扩展

13. iframe widget 支持（postMessage 通信）
14. url widget 支持（动态加载远程 JS 模块）
15. LLM fallback HTML 生成
16. 第二个 `.aipp` 应用（验证通用性）
