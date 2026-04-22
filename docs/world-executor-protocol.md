# World Executor Protocol

> 状态：设计中  
> 关联文档：`world-event-executor-e2e.md`、`shared-autonomous-ontology-exploration.md`  
> 目标：定义 executor 的注册、发现、匹配、事件交接、补参 widget、审批协作协议  

---

## 1. 文档目的

本文档只定义 **协议**，不定义任何具体 executor 实现。

executor 在本体系中的定位是：

- 一个可注册的 AIPP app
- 声明自己能处理哪些 decision
- 可选提供 argument collection widget / approval widget
- 接收 Host 转发的 decision 并执行自己的业务逻辑

World One 只负责：

- 发现 executor
- 将 decision 路由给合适的 executor
- 在需要时打开 executor 暴露的 widget

World 本体内核只负责：

- 发布运行时事件
- 恢复挂起 action
- 接收 decision terminal result 回流
- 不关心具体由哪个 executor 处理

---

## 2. 基本原则

### 2.1 Executor 是开放协议层

协议必须支持：

- 多个 executor app 并存
- 同一个 world 下多个 executor 同时注册
- 同一种 event 被不同 executor 按不同匹配规则处理

协议不应假设：

- 只有一个 executor
- executor 一定是 HTTP webhook
- executor 一定需要 approval
- executor 一定提供 widget

### 2.2 Host 不硬编码具体 executor 语义

World One 不应写死：

- `hr-executor`
- `finance-executor`
- `asset-request-form`
- `approval-panel-x`

Host 只能读协议字段并执行通用流程。

### 2.3 Action 的补参 UI 来自 executor app

当某个 action 缺参进入 `AWAITING_INPUT`，使用哪个 widget 由 action 配置决定。

该 widget 必须：

- 来自某个 executor app 的 widget manifest
- 通过协议显式声明用途是 `argument_input`

### 2.4 Entity change 仍以 `EntitirEvent` 为唯一事实来源

本协议不直接把 `entity event` 交给 executor。

`entity change` 仍然通过 `EntitirEvent` 作为唯一事实来源，用于 world 内部触发 decision。

executor 对外消费的是由 world 生成的 `WorldRuntimeEvent(event_type=decision, source_type=DecisionEvent)`。

---

## 3. 协议对象总览

本协议引入 6 个核心对象：

1. `ExecutorRegistration`
2. `ExecutorWidgetCapability`
3. `ExecutorSkillBinding`
4. `ExecutorRouteDecision`
5. `ExecutorHandleRequest`
6. `ExecutorHandleResponse`

---

## 4. ExecutorRegistration

`ExecutorRegistration` 表示一个 executor 对外注册的一条可路由规则。

一个 app 可以注册多条。

```json
{
  "id": "exec-hr-main",
  "app_id": "hr-executor",
  "name": "HR Main Executor",
  "description": "处理 hr world 中与员工入职相关的事件",
  "priority": 100,
  "handles": [
    {
      "input_event": "decision",
      "source_type": "DecisionEvent",
      "description": "处理 hr world 中与员工入职相关的 decision"
    }
  ],
  "world_selector": {
    "world_ids": ["hr"],
    "world_tags": [],
    "envs": ["staging", "production"]
  },
  "match": {
    "event_types": ["decision"],
    "entity_types": ["Employee", "AssetRequest", "BadgeRequest"],
    "operations": [],
    "decision_template_ids": [],
    "action_names": [],
    "statuses": []
  },
  "capabilities": {
    "can_execute": true,
    "can_collect_args": true,
    "can_require_approval": true,
    "can_render_widgets": true
  },
  "widgets": [
    {
      "usage": "argument_input",
      "widget_type": "asset-request-form",
      "title": "资产申请补参表单"
    },
    {
      "usage": "approval",
      "widget_type": "hr-approval-card",
      "title": "HR 审批卡片"
    }
  ],
  "skills": {
    "match_event": "executor_match_event",
    "handle_event": "executor_handle_decision",
    "resume_arg_flow": "executor_resume_arg_flow"
  }
}
```

### 4.1 字段说明

#### `id`

- executor registration 的唯一 ID
- 用于 action 配置、路由结果、审计日志引用

#### `app_id`

- 对应 AIPP app id
- Host 通过它找到 app 的 `baseUrl`、skills、widgets

#### `priority`

- 数字越小优先级越高
- Host 在多个 registration 同时命中时按优先级排序

#### `handles`

`handles` 用于显式声明当前 registration 处理哪些输入事件契约。

它和 `match` 的区别是：

- `handles`：声明级语义，告诉 Host“这个 executor 接受哪几类输入事件”
- `match`：过滤级语义，告诉 Host“在这些输入事件里，哪些实例会命中这条 registration”

推荐理解为：

```text
handles = executor 的输入协议面
match   = registration 的路由过滤面
```

在当前收敛方案里，推荐只声明 `decision`：

```json
[
  {
    "input_event": "decision",
    "source_type": "DecisionEvent",
    "description": "处理 Employee onboarding decision"
  }
]
```

约定：

- `input_event` 必须是 `WorldRuntimeEvent.event_type` 的合法值
- `source_type` 必须与该 `input_event` 对应的来源类型一致
- `handles` 为空时，Host 可退化为只依据 `match` 判断；但推荐始终显式声明
- v1 推荐 executor 只声明 `decision`

#### `world_selector`

限定该 registration 对哪些 world 生效。

```json
{
  "world_ids": ["hr", "supply-chain"],
  "world_tags": ["enterprise"],
  "envs": ["production"]
}
```

约定：

- `world_ids` 为空表示不按 world id 限制
- `world_tags` 为空表示不按标签限制
- `envs` 为空表示所有环境都可用

#### `match`

限定该 registration 对哪些事件命中。

```json
{
  "event_types": ["decision"],
  "entity_types": ["Employee"],
  "operations": []
}
```

约定：

- 任一数组为空，表示该维度不限制
- 所有非空维度按 AND 组合

#### `capabilities`

| 字段 | 含义 |
|---|---|
| `can_execute` | 能否直接处理事件并执行业务动作 |
| `can_collect_args` | 能否处理补参流程 |
| `can_require_approval` | 能否把事件转成 approval 待办 |
| `can_render_widgets` | 是否提供 Host 可打开的 widget |

#### `widgets`

只列出和 executor 协议相关的 widget，不等于 app 全部 widgets。

#### `skills`

绑定 executor 对应的 skill 名称。

允许只实现部分：

```json
{
  "handle_event": "executor_handle_decision"
}
```

如果某 skill 未提供，则视为该能力不可用。

---

## 5. ExecutorWidgetCapability

用于描述 executor 暴露给协议层使用的 widget。

```json
{
  "usage": "argument_input",
  "widget_type": "asset-request-form",
  "title": "资产申请补参表单",
  "description": "收集资产类型、使用原因、预算等参数",
  "input_schema": {
    "type": "object",
    "properties": {
      "missing_params": {
        "type": "array",
        "items": { "type": "string" }
      },
      "action_context": {
        "type": "object"
      }
    }
  }
}
```

### 5.1 `usage` 枚举

- `argument_input`
- `approval`
- `event_detail`

其中：

- `argument_input`：给 resilient action 补参
- `approval`：给审批待办展示与确认
- `event_detail`：展示事件详情，不一定可操作

---

## 6. Action 配置中的 executor widget 引用

action JSON 建议新增字段：

```json
{
  "name": "request_asset",
  "entityType": "Employee",
  "type": "outline",
  "resilient": true,
  "arg_widget": {
    "executor_registration_id": "exec-hr-main",
    "widget_type": "asset-request-form"
  }
}
```

### 6.1 约束

- `arg_widget` 只在 `resilient=true` 时有意义
- `widget_type` 必须来自对应 registration 暴露的 `widgets`
- `usage` 必须为 `argument_input`

### 6.2 Host 行为

当收到 `WorldRuntimeEvent(event_type=arg)` 时：

1. 从 action 配置读取 `arg_widget`
2. 找到对应 `executor_registration_id`
3. 从对应 app 的 widget 清单中打开 `widget_type`

如果配置缺失，则 Host 可以退回默认通用表单，但这只是兜底，不是主路径。

---

## 7. 事件匹配阶段

Host 路由一个 runtime event 时，先做静态匹配。

```text
WorldRuntimeEvent
  -> 过滤 world_selector
  -> 过滤 match
  -> 得到 candidate registrations
  -> 按 priority 升序
  -> 若 registration 提供 match_event skill，可再做动态匹配
```

### 7.1 静态匹配输入

Host 用以下字段参与匹配：

```json
{
  "world_id": "hr",
  "env": "production",
  "event_type": "decision",
  "entity_type": "Employee",
  "operation": null,
  "decision_template_id": "onboarding_started",
  "action_name": null,
  "status": "DECIDED"
}
```

### 7.2 动态匹配 skill

若 registration 配置了：

```json
{
  "skills": {
    "match_event": "executor_match_event"
  }
}
```

则 Host 可以调用该 skill，获取更细粒度判断。

请求：

```json
{
  "event": {
    "id": "evt-001",
    "event_type": "decision",
    "payload": {
      "target_ontology": "Employee",
      "template_id": "onboarding_started",
      "status": "DECIDED"
    }
  },
  "registration": {
    "id": "exec-hr-main"
  }
}
```

响应：

```json
{
  "match": true,
  "score": 0.92,
  "reason": "Employee UPDATE with sync_status change should be handled by HR executor"
}
```

约定：

- `match=false`：跳过该 registration
- `score`：可选，用于在同优先级下排序

### 7.3 Decision 命中编排（create_decision）

当 world 侧命中某个 `DecisionTemplate` 后，Host 进入“决策构建阶段”。

该阶段的目标是：

- 明确告诉用户“已命中哪个决策模板”
- 由 Host 统一调用 `create_decision`
- 根据模板配置决定在主会话继续，还是创建新任务会话
- 在构建执行期间展示可见步骤（building + action logs）

#### 7.3.1 命中提示（步骤可见）

命中 decision 后，Host 应在对话中输出结构化步骤提示（annotation 或等价 UI）：

- `命中 {decision_template_id}`
- `开始创建 decision`
- `decision 构建中`

示例（逻辑语义）：

```text
命中 onboarding_started 决策
调用 create_decision
等待决策构建结果...
```

#### 7.3.2 模板级会话策略配置（默认新任务）

`DecisionTemplate` 增加一个会话策略字段，决定命中后在哪个会话承载构建过程：

```json
{
  "id": "onboarding_started",
  "execution_session_mode": "new_task"
}
```

约定：

- `execution_session_mode` 可选值：
  - `new_task`
  - `main_session`
- 默认值：`main_session`
- 当为 `new_task` 时，Host 应在 task panel 创建新任务并在该任务流内展示构建状态
- 当为 `main_session` 时，Host 在当前主会话内展示构建状态

> 说明：当前阶段只定义 ontology activator 下的命中后编排。manual / scheduler 暂不在本节范围。

#### 7.3.3 create_decision 调用约定

一旦命中 `DecisionTemplate`，Host 必须调用统一 skill：

- `create_decision`

该调用是“异步构建触发”，不要求在同步返回内完成全部 action。

请求体示例：

```json
{
  "world": {
    "world_id": "world-eai-onboarding",
    "env": "production"
  },
  "decision_template_id": "onboarding_started",
  "trigger_context": {
    "event_type": "CREATED",
    "event_entity_type": "Employee",
    "event_entity_id": "123"
  },
  "session_mode": "new_task"
}
```

#### 7.3.4 create_decision 立即返回（用于路由会话）

`create_decision` 必须立即返回一个“接单响应”，供 LLM/Host 决定 UI 承载位置：

```json
{
  "status": "accepted",
  "decision_id": "dec-001",
  "session_mode": "new_task",
  "task_session": {
    "name": "Decision: onboarding_started",
    "session_type": "task"
  },
  "building_widget": {
    "widget_type": "system.building",
    "widget_id": "sys-building-dec-001"
  }
}
```

约定：

- `session_mode` 必须回传（`new_task` / `main_session`）
- `building_widget` 为可选：
  - 若业务返回自定义 widget（有 `widget_type`）：Host 打开该 widget
  - 若未提供：Host 使用 built-in `system.building` widget 兜底

#### 7.3.5 等待行为（main vs new task）

- `main_session`：
  - LLM 在主会话内等待构建进度与终态
  - building 卡片也留在主会话
- `new_task`：
  - Host 创建新任务会话
  - 构建等待与进度展示都在新任务会话中进行
  - 主会话只保留“已创建任务”的跳转提示

#### 7.3.6 构建过程可见性（action logs）

`create_decision` 的异步构建过程本质是执行 action 链。  
执行了哪些 action，Host/LLM 应能看到步骤日志（tips）：

- action 开始：`执行 action: Employee::register_onboarding`
- action 结果：`成功/失败/等待补参/等待审批`
- 终态汇总：`done/rejected/failed`

日志来源可为：

- runtime events
- executor flowback
- decision activity log

关键要求：

- 只要有日志输出，就必须可被 LLM/前端消费到（不可静默吞掉）

---

## 8. 事件交接协议

## 8.1 ExecutorHandleRequest

当 Host 决定把某个 decision 交给 executor，调用 `handle_event` skill。

请求体：

```json
{
  "registration_id": "exec-hr-main",
  "world": {
    "world_id": "hr",
    "env": "production"
  },
  "event": {
    "id": "evt-001",
    "event_type": "decision",
    "source_type": "DecisionEvent",
    "session_id": "ses-001",
    "timestamp": "2026-04-16T10:00:00Z",
    "payload": {
      "decision_id": "dec-001",
      "template_id": "onboarding_started",
      "status": "DECIDED",
      "execution_result": "pending",
      "need_approval": false,
      "target_ontology": "Employee",
      "entity_ref": "12"
    }
  },
  "context": {
    "need_approval": false
  }
}
```

## 8.2 ExecutorHandleResponse

`ExecutorHandleResponse` 在语义上仍然是一个 **AIPP response**。

也就是说，executor 处理完事件后，它返回的结果仍然必须符合 AIPP 标准响应规范，而不是重新发明一套“executor 专用 UI 协议”。

这意味着 executor 的响应可以是：

- 纯数据 / 文本语义结果
- `html_widget`
- `canvas`
- `new_session`
- 或这些字段的合法组合

换句话说：

- executor 的**输入**是 executor protocol
- executor 的**输出**仍然落回 AIPP response

因此 Host 处理 executor 响应时，不需要为 executor 写特殊 UI 通道，只需要继续复用现有 AIPP response 解析逻辑。

最小响应体：

```json
{
  "status": "accepted",
  "executor_event_id": "hexec-001",
  "need_approval": false,
  "message": "event accepted for async handling"
}
```

带 widget 的响应示例：

```json
{
  "status": "awaiting_arg_widget",
  "executor_event_id": "hexec-002",
  "arg_flow": {
    "flow_id": "arg-001",
    "widget_type": "asset-request-form",
    "title": "补充资产申请参数"
  },
  "html_widget": {
    "html": "<div>请填写资产申请参数</div>",
    "height": "320px",
    "title": "资产申请补参"
  }
}
```

带 canvas 的响应示例：

```json
{
  "status": "accepted",
  "executor_event_id": "hexec-003",
  "canvas": {
    "action": "open",
    "widget_id": "executor-event-detail-001",
    "widget_type": "executor-event-detail",
    "props": {
      "event_id": "evt-001",
      "registration_id": "exec-hr-main"
    }
  }
}
```

说明：

- `status` 仍由 executor protocol 消费
- `html_widget` / `canvas` / `new_session` 等字段仍由 AIPP Host 消费
- executor response 可以“不只是文本”，也可以直接驱动对应 executor app 的 widget 展示
- 但这些都只是处理中间态；最终仍必须把该 decision 的终态结果回流给 world

### 8.3 `status` 枚举

- `accepted`
- `ignored`
- `requires_approval`
- `awaiting_arg_widget`
- `failed`

#### `accepted`

表示 executor 已接单，后续异步处理。

#### `ignored`

表示 executor 虽然被调用，但决定不处理。

#### `requires_approval`

表示 executor 要求先进入审批。

示例：

```json
{
  "status": "requires_approval",
  "need_approval": true,
  "approval": {
    "approval_key": "approval-001",
    "widget_type": "hr-approval-card",
    "title": "员工建档外部同步审批"
  }
}
```

#### `awaiting_arg_widget`

表示 executor 要求先打开某个 widget 收集更多参数。

```json
{
  "status": "awaiting_arg_widget",
  "arg_flow": {
    "flow_id": "arg-001",
    "widget_type": "asset-request-form",
    "title": "补充资产申请参数"
  }
}
```

这里的 `widget_type` 只是协议层提示；若响应里同时带了标准 AIPP 的 `html_widget` 或 `canvas`，Host 应按 AIPP response 正常渲染，而不是只看这个简化字段。

> 约束补充（入职链路等场景）：
> - 外部事件归一化（如 `onboarding_intake`）应只提供“触发决策所需的最小字段”，不得臆造业务外键默认值（如 `department_id=0`）。
> - 缺失业务参数应在 action 执行阶段通过 `AWAITING_INPUT` 暴露，由 Host 按 AIPP 渲染补参交互（有 `widget_type` 用对应 widget；无 `widget_type` 用默认 HTML 输入）。

#### `failed`

```json
{
  "status": "failed",
  "message": "executor rejected event payload"
}
```

### 8.4 Decision terminal result 回流

executor 返回的 `accepted` / `awaiting_arg_widget` / `requires_approval` 都只是处理中间态。

对 world 来说，decision 最终必须收敛为以下三种终态之一：

- `done`
- `rejected`
- `failed`

建议保留一个标准回流请求：

```json
{
  "decision_id": "dec-001",
  "result": "done|rejected|failed",
  "message": "optional human-readable result",
  "executor_event_id": "hexec-001",
  "entity_effect": {
    "mark_related_entity_result": true
  }
}
```

约束：

- world 收到终态后，必须更新 decision 的最终状态
- 若结果为 `rejected`，world 必须让相关 entity 可见该结果
- v1 不要求自动 rollback entity 数据

---

## 9. Approval 协议

task panel 不单独展示 approval event。

推荐做法是：

- task panel 只展示 `decision`
- 当 `decision.need_approval=true` 时，用户点击该 decision，打开默认审批 widget

当用户批准 / 拒绝时，Host 调用：

- world 的 decision approval / reject 接口
- 若批准，再由 Host 把 decision 交给 executor
- 若拒绝，world 直接将该 decision 标记为 `rejected`

建议标准请求：

```json
{
  "registration_id": "exec-hr-main",
  "approval": {
    "approval_key": "approval-001",
    "result": "approved",
    "approved_by": "user-001",
    "comment": "looks good"
  }
}
```

其中 `result` 枚举：

- `approved`
- `rejected`

---

## 10. Argument Resume 协议

当某个 action 因缺参进入 `AWAITING_INPUT`，Host 打开 action 配置的 widget。

用户提交表单后，Host 需要做两件事：

1. 调用 world 侧恢复挂起 action
2. 可选通知 executor arg flow 已完成

## 10.1 world 恢复调用

恢复仍以 world 原生机制为准：

```text
resumeAction(suspensionId, params)
```

这部分不是 executor 协议重新发明。

## 10.2 executor 通知

如果 registration 提供了：

```json
{
  "skills": {
    "resume_arg_flow": "executor_resume_arg_flow"
  }
}
```

则 Host 可通知：

```json
{
  "registration_id": "exec-hr-main",
  "flow": {
    "flow_id": "arg-001",
    "suspension_id": "sus-001",
    "submitted_params": {
      "asset_type": "Laptop",
      "reason": "Onboarding"
    }
  }
}
```

---

## 11. Discovery 协议

executor app 建议新增端点：

```text
GET /api/executors
```

响应：

```json
{
  "executors": [
    {
      "id": "exec-hr-main",
      "app_id": "hr-executor",
      "name": "HR Main Executor",
      "priority": 100,
      "world_selector": {
        "world_ids": ["hr"],
        "envs": ["production"]
      },
      "match": {
        "event_types": ["decision"],
        "entity_types": ["Employee"],
        "operations": []
      },
      "capabilities": {
        "can_execute": true,
        "can_collect_args": true,
        "can_require_approval": true,
        "can_render_widgets": true
      },
      "widgets": [
        {
          "usage": "argument_input",
          "widget_type": "asset-request-form",
          "title": "资产申请补参表单"
        }
      ],
      "skills": {
        "handle_event": "executor_handle_decision",
        "resume_arg_flow": "executor_resume_arg_flow"
      }
    }
  ]
}
```

### 11.1 Host 聚合规则

World One 启动或刷新 registry 时：

1. 扫描所有 app
2. 若 app 暴露 `/api/executors`，则读取 registrations
3. 将 registrations 加入全局 executor registry
4. 供 world designer 与 runtime router 共用

---

## 12. 与 Widget Manifest 的关系

executor protocol 不替代 AIPP widget manifest。

关系如下：

- widget manifest：描述 app 全部 widgets
- executor `widgets`：只挑出“协议层有意义的那些 widgets”

也就是说：

- `widgets.json` 是全集
- `GET /api/executors` 中的 `widgets` 是协议子集引用

Host 必须校验：

- registration 中引用的 `widget_type` 必须在 app widget manifest 中真实存在

进一步地，executor 返回的 `html_widget` / `canvas.widget_type` 也必须满足正常 AIPP widget 解析规则。

也就是说：

- executor 只是“谁来返回这个 widget”的协议层
- widget 自身仍然受 AIPP widget manifest 约束

---

## 13. 与 WorldRuntimeEvent 的关系

本协议消费的输入事件，来自 `world-event-executor-e2e.md` 中定义的 `WorldRuntimeEvent`。

因此实现顺序必须是：

1. 先冻结 `WorldRuntimeEvent`
2. 再冻结 executor protocol
3. 然后再做 world -> host 事件流

### 13.1 与场景 3 共享探索机制的边界

场景 3 的本体探索（decision hint -> ontology discovery -> runtime completion -> eval）属于：

- `shared-autonomous-ontology-exploration.md`
- `entitir/aip` 共享能力

executor protocol 在该链路中的边界是：

1. executor **不负责**重新实现 VirtualSet expression 生成策略；
2. executor **不应**绕过 `ontology_eval/can_chain` 自行发明一套静态成员推导；
3. executor 只消费场景 3 的结果（decision / action / approval 语义），不复制场景 3 的推理引擎。

换句话说：

- 场景 3 负责“如何安全探索 world”
- executor 协议负责“探索后如何执行外部处理与回流结果”

---

## 14. 最小实现顺序

### Phase 1：协议冻结

- [x] 冻结 `GET /api/executors` 响应结构
- [x] 冻结 `ExecutorRegistration`
- [x] 冻结 `handle_event` / `resume_arg_flow` 请求与响应
- [x] 冻结 `arg_widget` action 配置结构

### Phase 2：只做发现与展示

- [x] World One 聚合 executor registrations
- [x] action editor 能看到可选 executor widgets
- [ ] 不真正路由任何事件

### Phase 3：接入 runtime routing

- [x] Host 订阅 world runtime events
- [x] Host 做静态匹配
- [x] Host 调用 executor `handle_event`

### Phase 4：补充 approval / arg 流

- [x] decision 审批 widget
- [x] `awaiting_arg_widget` widget 打开
- [x] action 恢复

---

## 15. 非目标

本文档不定义：

1. executor 内部如何调用外部系统
2. executor 是否用 webhook / MQ / workflow engine
3. 审批 UI 的具体视觉样式
4. 参数表单 widget 的具体前端实现
5. 任意行业特定字段

这些都应由具体 executor AIPP 自行决定。

