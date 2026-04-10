# World One — Application 层设计

World One 是 Entitir 的唯一交互应用，形态是一个 Chatbot。

**关联文档**：
- AIP 层逻辑（工具、提示词、阶段）→ `spec/worldone-skill.md`
- Widget 协议标准（canvas 指令、widget 类型）→ `spec/widget-protocol.md`

---

## 技术选型：Web

Java 后端同时提供 API 和静态文件服务，前端为 **Vanilla JS**，无框架重依赖。

| | Web | Electron |
|---|---|---|
| 分发 | 浏览器直接访问，零安装 | 打包安装 |
| 更新 | 服务端推送即生效 | 重新打包分发 |
| Anna 集成 | 同源，天然互通 | 跨进程复杂 |
| 流式输出 | SSE 原生支持 | 无本质差异 |

---

## 布局

### Chat Mode（默认）

```
┌────────────┬──────────────────────────────────────┐
│ Sessions   │                                      │
│ & Tasks    │           Chat Area                  │
│ [展开]     │   流式 Markdown + 工具调用状态        │
│            │   内联小表单（仅参数收集时出现）      │
│            │                                      │
│            │  [输入框 ─────────────────── Send]  │
└────────────┴──────────────────────────────────────┘
```

### Canvas Mode（agent 发出 canvas.open 时自动切换）

```
┌──┬─────────────────────────┬───────────────┐
│≡ │                         │               │
│  │        Canvas           │  Chat Panel   │
│  │   (entity-graph /       │  (窄，Cursor  │
│  │    expression-editor    │   风格)       │
│  │    action-flow / ...)   │               │
│  │                         │  [输入框]    │
└──┴─────────────────────────┴───────────────┘
```

### Sessions & Tasks 面板行为

| 模式 | 默认状态 | 原因 |
|---|---|---|
| Chat Mode | **展开** | 导航是主要操作，session 列表需要可见 |
| Canvas Mode | **收缩**（仅图标条） | Canvas 需要最大宽度，session 导航退居次要 |

两种模式下均可手动点击 `≡` 按钮切换展开/收缩状态，手动状态在当次会话内保持（不随模式切换重置）。

- **左栏**：历史 session 列表、任务进度（本期占位，后续设计）
- **中栏**：Canvas 区域，仅 Canvas Mode 下展示
- **右栏**：Chat Panel，Chat Mode 下铺满左右两栏

布局切换由 CSS Grid class 控制（`body.canvas-mode`、`body.panel-collapsed`），由前端监听 agent 响应的 `canvas.action` 字段驱动，panel 状态独立管理。

---

## Application 层文件结构

```
application/src/main/java/org/twelve/entitir/application/
├── WorldOneServer.java     — 启动入口，嵌入 Jetty，挂载 /api + 静态文件
├── WorldOneHandler.java    — POST /api/chat → WorldOneSkill.handleTurn()
│                             SSE 流推送 text chunks
│                             响应结束后推送 canvas/inline_form（JSON 片段）
├── SessionStore.java       — Map<sessionId, WorldOneSession>（内存或持久化）
└── WorldOneConfig.java     — 读取 application.yml（LLMConfig, DB path, port）

application/src/main/resources/static/
├── index.html              — 单页应用骨架
├── chat.js                 — 消息流渲染（SSE）、内联表单拦截
├── canvas.js               — Canvas Mode 切换、widget 注册表、patch 应用
├── session.js              — 左侧 session 列表（后续）
└── widgets/
    ├── entity-graph.js     — D3 / Cytoscape 实体关系图
    ├── schema-editor.js    — 字段编辑表格
    ├── expression-editor.js— CodeMirror + Outline 补全
    ├── action-flow.js      — 流程图（ReactFlow 或轻量实现）
    ├── data-table.js       — 查询结果表格
    ├── approval-panel.js   — Decision 审批卡片
    └── iframe-bridge.js    — Legacy 工具 iframe + postMessage 桥接
```

---

## HTTP API

### `POST /api/chat`

发送用户消息或 widget 交互结果，以 SSE 流式返回 agent 响应。

**请求体**
```json
{
  "session_id":    "string（新会话传 null，服务端生成）",
  "message":       "string（用户输入文字）",
  "widget_result": { ... }
}
```

`message` 和 `widget_result` 二选一，不同时出现。

**SSE 响应事件序列**

```
event: text_chunk
data: {"text": "正在生成实体模型..."}

event: text_chunk
data: {"text": " 包含 3 个实体"}

event: tool_call
data: {"tool": "world_add_definition", "status": "calling"}

event: tool_result
data: {"tool": "world_add_definition", "status": "done"}

event: canvas
data: {"action": "open", "widget": {"id": "c1", "type": "entity-graph", "props": {...}}}

event: done
data: {"session_id": "sess-001"}
```

前端监听 `event: canvas` 驱动布局切换；`event: text_chunk` 流式追加到 chat。

### `GET /api/sessions`

返回当前用户的 session 列表（左侧面板，后续实现）。

### `DELETE /api/sessions/:id`

删除 session。

---

## 前端渲染循环

```javascript
// chat.js — 核心渲染循环
async function sendMessage(input) {
  const sse = await fetch('/api/chat', {
    method: 'POST',
    body: JSON.stringify({ session_id: currentSessionId, message: input })
  });

  for await (const event of parseSSE(sse)) {
    switch (event.type) {
      case 'text_chunk':
        appendMarkdown(event.data.text);
        break;
      case 'tool_call':
        showToolStatus(event.data.tool, 'calling');
        break;
      case 'tool_result':
        showToolStatus(event.data.tool, 'done');
        break;
      case 'canvas':
        handleCanvas(event.data);        // canvas.js
        break;
      case 'done':
        currentSessionId = event.data.session_id;
        break;
    }
  }
}

// canvas.js — widget 注册表 + 布局切换
const WIDGETS = {
  'entity-graph':      () => import('./widgets/entity-graph.js'),
  'schema-editor':     () => import('./widgets/schema-editor.js'),
  'expression-editor': () => import('./widgets/expression-editor.js'),
  'action-flow':       () => import('./widgets/action-flow.js'),
  'data-table':        () => import('./widgets/data-table.js'),
  'approval-panel':    () => import('./widgets/approval-panel.js'),
  'iframe':            () => import('./widgets/iframe-bridge.js'),
};

async function handleCanvas(cmd) {
  switch (cmd.action) {
    case 'open':
      const mod = await WIDGETS[cmd.widget.type]();
      mod.mount(document.getElementById('canvas-area'), cmd.widget.props, onWidgetAction);
      document.body.classList.add('canvas-mode');
      break;
    case 'patch':
      currentWidget.applyPatch(cmd.patch);   // JSON Patch，保留布局状态
      break;
    case 'replace':
      currentWidget.unmount();
      handleCanvas({ action: 'open', widget: cmd.widget });
      break;
    case 'close':
      currentWidget?.unmount();
      document.body.classList.remove('canvas-mode');
      break;
  }
}

function onWidgetAction(widgetId, action, data) {
  sendMessage(null, { widget_id: widgetId, action, data });  // widget_result 路径
}
```

---

## TODO — 下一步功能设计

以下三个功能是让 OntologyWorld **端到端运行**的关键缺口，均在 application 层实现，但需要与 aip 层协同设计。

---

### T1：执行引擎（Execution Engine，插件结构）

**背景**：当前 Action 只支持内嵌 Java lambda，无法满足不同客户的集成需求。

**设计要点**：

```
ExecutionEngine（接口）
  execute(type, id, actionName, params, principal) → ExecutionResult

内置实现：
  LocalLambdaEngine   — 调用 ActionDescriptor 中注册的 lambda（现有）
  OutlineExprEngine   — 执行 Outline 表达式 action（LLM 可生成，无需写 Java）
  HttpWebhookEngine   — POST 到外部 URL，携带 entity 快照 + params
  MessageQueueEngine  — 发布到 MQ topic，异步执行，返回 jobId
```

注入方式：
```java
OntologyWorld.builder()
    .executionEngine("Employee", "lock", new HttpWebhookEngine("https://crm.corp/api/lock"))
    .build()
```

未配置时默认使用 `LocalLambdaEngine`。插件选择可通过 YAML 配置文件驱动，不需要重新编译。

---

### T2：事件中心（Event Center）

**背景**：OntologyWorld 的 EventBus 已在运行，但没有可视化入口，运营人员无法观察或干预事件流。

**功能定义**：

```
事件中心 = 所有 ActionEvent + DecisionEvent 的可视化管理界面

核心功能：
  1. 实时事件流（WebSocket 推送，SSE 降级）
  2. 事件过滤（实体类型 / action 名称 / 状态）
  3. AWAITING_INPUT 事件：展示缺少的参数表单，填写后继续执行
  4. AWAITING_APPROVAL 事件：进入审批流
  5. 执行引擎连接状态（Webhook / MQ 是否健康）

Widget 类型（新增到 widget-protocol.md）：
  event-center
    props: { filter?: { type, status }, live: bool }
    output: { event_id, action, data }   ← 用户操作某个事件时回传
```

API 端点（新增）：
```
GET  /api/events?type=&status=&limit=     ← 事件列表
POST /api/events/:id/approve              ← 审批
POST /api/events/:id/supply-params        ← 补填参数
GET  /api/events/stream                   ← SSE 实时流
```

---

### T3：任务面板（Task Panel，World One 左侧）

**背景**：World One 的左侧面板目前仅规划了 sessions 列表，需要将"需要人工处理的事件"以任务形式集成进来。

**任务来源**：

| 来源 | 状态 | 任务标题来源 |
|---|---|---|
| Decision | `INTENTION` | `intent().goal()` |
| Decision | `DECIDED` | `"待执行：" + goal` |
| ActionEvent | `AWAITING_INPUT` | `actionName + " 缺少参数"` |
| ActionEvent | `AWAITING_APPROVAL` | `"需审批：" + actionName` |

**面板 DOM 设计**：

```
左侧面板（可折叠）
  ├── [Sessions]       ← 历史对话列表（已有）
  └── [Tasks]          ← 待处理任务（新增）
       ├── 🔴 待审批：员工解锁 (Alice)
       ├── 🟡 待执行：合同续签预警
       └── 🟠 缺少参数：lock (Employee#2)
```

**点击任务的行为**：
```
点击任务条目
  → agent 调用 canvas.open 展示任务详情 widget
  → widget 类型根据任务类型决定：
       AWAITING_APPROVAL  → approval-panel
       AWAITING_INPUT     → inline-form（动态生成）
       DECIDED            → approval-panel（显示决策详情 + 执行按钮）
  → 用户操作完成后，agent 调用执行引擎
  → 事件状态更新，任务从列表消失
```

**API 端点（新增）**：
```
GET  /api/tasks          ← 当前所有待处理任务（轮询 / WebSocket）
POST /api/tasks/:id/act  ← 执行某个任务（approve / supply / execute）
```

---

### T4：WorldRegistry（世界持久化，已在 harness-os.md 列出）

```
GET  /api/worlds                ← 世界列表（左侧 Sessions 上方）
POST /api/worlds                ← world_build 后持久化
GET  /api/worlds/:name/meta     ← 世界元数据（schema, modules, 统计）
DELETE /api/worlds/:name        ← 删除世界
```

---

## 对话流示例

```
[Chat Mode — 全宽 chat]
用户：  帮我创建一个 HR 领域的本体世界

Agent：  好的，我来为你设计……
         → tool: world_add_definition × 4
         → canvas: open entity-graph

[自动切换 Canvas Mode — 左大图，右窄 chat]

右侧 chat：  已生成 Company、Department、Employee、Gender，如图所示。
             如需修改，直接告诉我，或在图中操作。

用户：  加一个 Contract 实体，包含开始日期、结束日期

Agent：  → tool: world_add_definition (Contract)
         → canvas: patch（图中增加 Contract 节点 + 2 条边）
         右侧 chat（流式）：已添加 Contract，与 Employee 建立 FK 关联。

用户：  确认，构建世界

Agent：  → tool: world_build
         → canvas: close
         世界已构建！5 个实体，可以开始配置决策模板了。

[切回 Chat Mode]
...
```
