# AIPP Interaction Model

> 版本：1.0  
> 状态：正式规范  
> 适用范围：world-one（uni-bot）及所有 AIPP 应用开发者

---

## 零、核心设计原则

1. **world-one 是唯一的导航控制器**：widget 通过 canvas 指令申请导航，world-one 决定如何执行。
2. **Session 不嵌套**：一个 session 就是一个独立的 LLM 上下文；canvas widget 是当前 session 的视图层，不产生新 session。session 的创建/归一仅由 skill 的 `session` 块（`aipp-protocol.md` §3.1.1）决定；**非 main session 禁止再派生 session**，已在 session 内再进入"创建新 session 语义"的 skill，归一为视图覆盖。
3. **所有路由基于 widget_type 字符串**：AIPP 中不存在 URL 跳转，world-one 解析 widget_type 找到渲染器。
4. **系统 chrome 由 world-one 生成**：后退按钮、关闭按钮、modal 边框均由 world-one 产生，widget 只提供内容区。

---

## 一、Session 模型

world-one 维护一个**并行 session 池**，四类 session 互不干扰：

```
world-one session 池：

[Chat Session]      用户正常对话（默认）
[App Session M]     某个 app 的工作台（可单实例或多实例）
[Task Session A]    内部创建的异步任务（LLM 或用户发起）
[Event Session B]   外部事件触发（外部系统 POST /api/events）
[Task Session C]    另一个任务...
```

### Session 类型对比

| 类型 | 创建方式 | 是否有 LLM 上下文 | 关闭后 |
|------|---------|-----------------|--------|
| **Chat Session** | 用户开启新对话 | ✅ 是 | 留存历史记录 |
| **App Session** | 进入 app main widget 或 app 内声明 session 的 widget | ✅ 是 | 留存 app 内上下文 |
| **Task Session** | world-one 内部（LLM/用户）发起 | 可选 | 留存执行结果 |
| **Event Session** | 外部系统 POST `/api/events` | 可选 | 留存执行结果 |

> **Task 和 Event 的区别仅在来源**：Task 是内部创建的，Event 是外部推入的。两者的 session 管理、widget 展示规则完全相同。

### App Session 路由（1-N 自然形成）

world-one 不预设“每个 app 只能 1 个”或“必须 N 个”。

- `session_type=app` 且无 `session_id`：按 `app_id` 命中单实例 app session
- `session_type=app` 且有 `session_id`：按 `(app_id, session_id)` 命中多实例 app session

示例：
- memory-one 常见单实例（`app_id=memory-one`）
- world-entitir 常见多实例（`app_id=world` + `session_id=HR|EAI|...`）

> App Session 不进入 Task Panel；Task Panel 展示 task / event session。若主对话中的 LLM 显式打开某个 app-owned widget，Host 会把这次入口归一为可见 task 行，并保留 `app_id` 做幂等复用；直接点击应用入口的 app session 仍不进入 Task Panel。

### Task 创建不变量

Task Panel 只展示真正独立的 task / event session。Host 在处理 widget/session 导航时必须遵守以下不变量：

- **只有 main session 可以派生新 session**：当前活动 session 是 `main`，且工具响应明确声明 `session=new` / `new_session` 时，Host 才允许创建新的 task/app/event session。
- **`session != new` 表示当前 session 内导航**：它不是“恢复已有 session”，也不能创建 task；Host 应在当前活动 session 内打开、替换或覆盖 widget 视图。
- **非 main session 不允许嵌套派生**：如果当前已经在 task/app/event session 内，即使工具响应再次声明 `session=new` / `new_session`，Host 也必须降级为当前 session 内的 widget 打开/覆盖。
- **Task id 必须幂等**：同一个固定 widget 入口多次打开时，应使用稳定的 canonical key（例如 `app_id + widget_type + normalized session_id`）命中已有 session；若已存在则复用并激活，不得在 Task Panel 追加重复条目。
- **直接 app session 不进入 Task Panel**：直接点击应用入口时，例如 `memory-one` 的单实例管理台按 `app_id=memory-one` 复用 app session，不显示为 task。主对话中由 LLM 发起的 app-owned widget 入口可归一为 task 行，但必须保留 `app_id` 做幂等去重。

### Widget 激活态的上下文与能力裁剪（不是新 session）

Widget 打开不会新建 session。但 widget 激活期间，Host 会按 `aipp-protocol.md` §3.2.1 Widget Context & Scope 向当前 session 的 system prompt 追加 `widget.system_prompt`，并按 `widget.scope` 裁剪本轮可见工具；`views[].system_prompt` + `views[].scope` 进一步按前端传来的 `active_view` 做 tab 级细化。

**关键特性**：

- **同一 session、同一 history**：widget 打开/关闭、view 切换不破坏对话上下文
- **能力随视图动**：tab 切换 → 本轮 tool list 自动窄化；"当前 tab 无关的工具"对 LLM 不可见，避免误调
- **执行禁令可选**：widget/view 可声明 `forbid_execution: true`，此时 LLM 看不到任何 `kind=execution` 工具；若用户请求执行，widget 的 `system_prompt` 应给出引导话术（如"请回到主会话执行"）

---

## 二、六种交互模式

### 模式 1：Chat Mode（纯对话）

```
用户输入 → LLM 回复（文本/Markdown）→ 显示在 chat 流
```

- 无 canvas，无 widget
- Session：当前 Chat Session
- 触发条件：LLM 响应中无 `canvas` 字段

---

### 模式 2：Canvas Mode（全屏 Widget）

```
LLM tool response → { canvas: { action: "open", widget_type: "entity-graph" } }
    → world-one 渲染全屏 widget
    → 用户在 widget 中交互
```

- Widget 替换聊天区（或并排显示），进入专属操作视图
- Session：当前 Chat Session（canvas 是视图层，不新建 session）
- 触发条件：tool response 包含 `canvas.action = "open"`

#### Canvas 导航栈

每个 session 维护一个 widget 导航栈：

```
session widget stack:
  [0] entity-graph           ← 底层
  [1] entity-detail          ← 当前（world-one 自动生成后退按钮）
```

**入栈规则：**
- `canvas.open` + 不同 `widget_type` → push 新层
- `canvas.open` + 相同 `widget_type` → patch 替换当前层状态（不 push）

**出栈规则：**
- `canvas.close` → pop，回到上一层
- 用户点击系统后退按钮 → pop，**刷新恢复前一层状态**（前一层可能因下层操作而数据变化）
- Widget 关闭 → pop，同样刷新

**特殊规则：**
- **Session 不嵌套**：即使 canvas.open 声明了 `creates_on: "new"`，若当前已在 canvas 上下文中，world-one 强制降级为 push 栈操作
- **Session 归一**：若当前 widget 已命中 new-session 语义，在该上下文再进入另一个 new-session widget，不创建新 session，只做视图覆盖（可返回）
- **`sys.*` widget** 始终以模态覆盖层渲染，不进入导航栈

---

### 模式 3：Inline Card（Chat 流内嵌卡片）

```
LLM tool response → { "html_widget": { "html": "<div>...</div>", "height": "400px", "title": "摘要标题" } }
    → world-one 在 chat 消息流中嵌入卡片（srcdoc iframe）
```

- 轻量展示，不替换聊天区，不进入导航栈
- Session：当前 Chat Session
- CSS 与主 DOM 完全隔离
- 触发条件：tool response 包含 `html_widget` 字段
- `title` 为必选字段，Host 在"已处理"卡片（`{title} · 已在界面上打开`）中使用

#### 与「声明了 Canvas 的 Skill」共存

若某 Skill 在 `/api/skills` 中声明了 `canvas.triggers: true`（例如本体设计），**单次**工具响应仍可能**仅**包含 `html_widget`（例如候选列表、消歧选择）。此时：

- Host **优先**按 Inline Card 渲染，**不会**根据**同一次**响应生成 `canvas.open` / `SESSION` 导航；
- 用户在内嵌 HTML 中完成选择（如 `postMessage` 触发再调 `world_design` 并带上 `session_id`）后，**后续**工具返回再进入 Canvas Mode。

该行为由 Host 的 `extractEvents` 约定保证（`html_widget` 分支先于 session/canvas），应用侧无需改 Host 代码即可实现消歧 UI。

---

### 模式 4：ToolProxy Direct（Widget 直调工具）

```
Widget 内按钮点击
    → ToolProxy POST /api/proxy/tools/{toolName}（绕过 LLM）
    → tool response 可包含 canvas 指令
    → world-one 解释 canvas 指令并执行（push/patch/pop）
```

- 不经过 LLM，速度快，用于确定性操作
- Session：当前 session（button 行为的 canvas 指令中 session 字段完全忽略）
- 触发条件：widget 内按钮直接触发 ToolProxy

---

### 模式 5：Task（内部异步任务）

```
LLM 或用户发起长耗时操作
    → world-one 创建独立 Task Session
    → 有 widget_type → 打开对应 widget（canvas 或 chat 内嵌）
    → 只有 tool_id  → 直接执行工具 + 系统 sys.progress spinner
    → 任务完成/关闭 → 回到原 Chat Session
```

- **独立 Session**，与对话 session 完全隔离
- 用户可切换回 Chat Session 继续对话，Task 在后台运行
- 触发条件：world-one 内部（LLM tool_call 或用户操作）

---

### 模式 6：Event（外部事件触发）

```
外部系统 POST /api/events
    → world-one 接收事件
    → 有 widget_type → 打开对应 widget（独立 Event Session）
    → 只有 tool_id  → 直接执行工具 + 系统 sys.progress spinner
    → 处理完成/关闭 → 回到原状态
```

- **独立 Session**，与对话 session 完全隔离
- 与 Task 行为完全相同，唯一区别是来源为外部系统
- 触发条件：外部系统 `POST /api/events`

#### Event 载荷格式

```json
{
  "event_id":    "evt-001",
  "source_app":  "ci-agent",
  "title":       "Pipeline #123 构建完成",
  "widget_type": "pipeline-result",
  "tool_id":     null,
  "data":        { "pipeline_id": "123", "status": "success" },
  "priority":    "HIGH"
}
```

**路由规则：**

| 载荷 | world-one 行为 |
|------|--------------|
| 有 `widget_type` | 打开对应 widget（独立 Event Session） |
| 只有 `tool_id` | 直接执行工具，显示 `sys.progress` |
| 两者都有 | 打开 widget，widget 内部驱动工具 |
| `priority: URGENT` | 立即弹出（不等用户点击 Task Panel） |

---

## 三、系统内置 Widget（sys.*）

由 world-one 内置实现，AIPP 应用**无需注册**，可直接在 canvas 指令中引用。  
所有 `sys.*` widget 以**模态覆盖层**渲染，不进导航栈，用户必须响应后才能继续。

| widget_type | 对应模式 | 说明 |
|-------------|---------|------|
| `sys.confirm` | Yes/No 或 OK/Cancel | 危险操作确认，`danger:true` 时红色按钮 |
| `sys.alert` | 仅 OK | 信息通知，关闭时可选发 chat 消息 |
| `sys.prompt` | 输入框 + OK/Cancel | 获取用户文本输入，提交调用指定 tool |
| `sys.selection` | 选项列表（推荐） | 从选项中选一项，每项对应 tool 或消息 |
| `sys.choice` | 选项列表（兼容别名） | 旧字段，行为与 `sys.selection` 相同 |
| `sys.progress` | spinner 或进度条 | 后台工具执行进度，支持 poll_tool 轮询 |

> **保留前缀**：AIPP 应用注册 widget 时不得使用 `sys.` 前缀。

### sys.confirm 使用示例（memory 删除场景）

完整流程：
```
用户 → "删除关于 John 会议的记忆"
LLM  → tool_call: memory_delete_request({ memory_ids: ["m1","m2"] })
Tool → { canvas: { action:"open", widget_type:"sys.confirm", data:{...} } }
world-one → 渲染确认框（模态覆盖层）
用户点击"确认删除" → world-one ToolProxy: memory_delete_confirmed({ memory_ids:["m1","m2"] })
用户点击"取消"    → world-one → chat 消息: "已取消删除操作"
```

canvas 指令数据结构：
```json
{
  "canvas": {
    "action":      "open",
    "widget_type": "sys.confirm",
    "data": {
      "mode":    "yes_no",
      "title":   "确认删除记忆",
      "message": "确定要删除关于 John 会议的 2 条记忆吗？此操作不可撤销。",
      "danger":  true,
      "yes": {
        "tool": "memory_delete_confirmed",
        "args": { "memory_ids": ["m1", "m2"] }
      },
      "no": {
        "message": "已取消删除操作"
      }
    }
  }
}
```

---

## 四、Canvas 动作完整参考

| action | 说明 | 导航栈 | 典型场景 |
|--------|------|--------|---------|
| `open` | 打开新 widget | push（不同类型）/ replace（相同类型） | 进入设计视图 |
| `patch` | 增量更新当前 widget 状态 | 不变 | 添加节点、更新数据 |
| `replace` | 全量替换当前 widget 状态 | 不变 | 重新加载数据 |
| `close` | 关闭当前 widget | pop | 退出设计视图 |
| `inline` | chat 流内嵌卡片 | 不进栈 | 轻量数据展示 |

> `sys.*` widget 使用 `open` action，world-one 识别 `sys.` 前缀后自动以模态覆盖层渲染。

---

## 五、Button 触发的导航（ToolProxy 规则）

Widget 内的按钮**不直接决定导航目标**，而是通过 ToolProxy 调用 tool，tool response 中的 canvas 指令由 world-one 执行：

```
button click
  → ToolProxy POST /api/proxy/tools/{name}
  → tool response: { canvas: { action: "open", widget_type: "entity-detail" } }
  → world-one 解释并执行导航
```

**重要约束：**
- Button 触发的 canvas 指令中，`session` 字段完全忽略——永远沿用当前 session
- Button 不能创建新 session（不论 canvas 指令怎么声明）

---

## 六、Task Panel

world-one 左侧面板的 **Task Panel** 区域展示所有 Task 和 Event session：

```
┌─ Task Panel ─────────────────────────┐
│ ▶ Pipeline #123 构建完成  [ci-agent] │  ← Event，priority: HIGH（高亮）
│ ⏳ 正在构建 World #42      [world]   │  ← Task，运行中
│ ✓  记忆整合               [memory]  │  ← Task，已完成
└──────────────────────────────────────┘
```

**点击行为：**
- 点击任意条目 → 切换到该 Task/Event Session
- 关闭 Task/Event Session → 回到最近活跃的 Chat Session
- Task/Event Session 不影响 Chat Session 的对话历史

**优先级显示：**
- `NORMAL`：后台静默，进入队列
- `HIGH`：高亮提示
- `URGENT`：立即弹出，不等用户点击

---

## 七、完整交互场景索引

| 场景 | 模式 | Session | 文档参考 |
|------|------|---------|---------|
| 用户提问 → LLM 回答 | Chat | 当前 | — |
| LLM 返回 entity-graph | Canvas | 当前 | widget-protocol.md |
| Canvas 类 Skill 先返回候选列表（html_widget） | Inline Card | 当前 | 本文 §二 模式 3 |
| LLM 返回记忆摘要卡片 | Inline Card | 当前 | — |
| 用户点击"删除"按钮 | ToolProxy → sys.confirm | 当前（modal） | 本文 §三 |
| LLM 发起 world_build | Task | 独立 Task Session（结果 → STAGING） | 本文 §一 |
| LLM 发起 world_promote | Task | 独立 Task Session（STAGING → PRODUCTION） | 本文 §一 |
| CI pipeline 完成通知 | Event | 独立 Event Session | 本文 §一 |
| canvas 内 LLM 再 open widget | Canvas stack push | 当前（push） | 本文 §二 |
| 用户点击后退 | Canvas stack pop + 刷新 | 当前（pop） | 本文 §二 |
