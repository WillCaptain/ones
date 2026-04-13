# AI-Native App Protocol

> 版本：0.1 草案  
> 状态：设计讨论中  
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

### 3.1 Capability Skill（能力声明）

```
GET /api/skills
```

描述应用能做什么，即 AI 代理可以调用哪些工具。  
每个 Skill 对应一个可调用的操作，包含名称、描述、参数 Schema，以及  
**该操作是否会触发 canvas 及触发哪种 widget**。

```json
{
  "app": "World One",
  "version": "1.0",
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
  ├── GET /api/skills   → 加载到 system prompt 或 tool 列表
  └── GET /api/widgets  → 加载到 widget 上下文

阶段 1 — 意图识别
  用户输入 → AI 分析 → 匹配 Capability Skill

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
| `GET /api/skills` | ✅ 已实现 | `world-entitir/SkillsController` |
| `GET /api/widgets` | ✅ 已实现 | `world-entitir/WidgetsController` |
| `POST /api/tools/{name}` | ✅ 已实现 | `world-entitir/ToolsController` |
| `canvas` 字段协议 | ✅ 已实现 | 工具返回值携带 canvas 字段 |
| `widget_type` 约定 | ✅ 已实现 | `entity-graph` 类型 |
| `action: open/patch/replace/close` | ✅ 已实现 | |
| Widget `init(props)` | ✅ 隐式实现 | 前端 `renderGraph()` |
| Widget `patch(props)` | ✅ 隐式实现 | 前端 `graphState` 合并 |
| Widget `onResult` / `widget_result` | ❌ 未实现 | 用户操作无法回传 AI |
| iframe / url widget | ❌ 未实现 | 只有 builtin |
| World One 薄壳化 | ✅ 已完成 | GenericAgentLoop + Registry 路由 |

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
    "skills_path": "/api/skills",
    "widgets_path": "/api/widgets",
    "tools_path": "/api/tools"
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
4. 拉取 /api/skills → 合并到全局 skill 列表（含 app_id 路由字段）
5. 拉取 /api/widgets → 合并到全局 widget registry
6. 构建 LLM system prompt（包含所有 app 的 skill 描述）
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

1. `GET /api/skills`：聚合所有 Tool 的定义，加上 canvas 语义字段
2. `GET /api/widgets`：输出 entity-graph 的完整 props_schema 和 external_actions
3. 前端 Widget Registry 动态化：按 `widget_type` 查找 renderer，不再 hardcode
4. `widget_result` 实现：entity-graph 节点点击 → SSE 回传 AI

### Phase 2 — 以终为始重构（直接到位）

5. **新建 `world-entitir/` 模块**（独立 Spring Boot，端口 8093）
   - 依赖 `aip/` + `ontology/`（domain 逻辑留在 aip 层，world-entitir 做 HTTP 包装）
   - 实现 `GET /api/skills`、`GET /api/widgets`、`POST /api/tools/{name}`
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
