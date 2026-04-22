# World Runtime Event Protocol

> 状态：设计中  
> 关联文档：`world-event-executor-e2e.md`、`world-executor-protocol.md`、`shared-autonomous-ontology-exploration.md`  
> 目标：定义 world 对 Host 暴露的统一运行时事件协议，包括事件 envelope、查询接口、SSE 流、cursor 语义

---

## 1. 文档目的

本文档定义 `WorldRuntimeEvent` 协议。

它的定位是：

- **不是** ontology 内核新的事实模型
- **不是** `EntitirEvent` / `DecisionEvent` / `ActionEvent` / `ParameterInputEvent` 的替代品
- 而是 world 对 Host（如 world-one）暴露的统一事件出口格式

换句话说：

- 内核里继续保留现有事件对象
- 对外统一投影成 `WorldRuntimeEvent`
- Host 只消费 `WorldRuntimeEvent`

---

## 2. 设计原则

### 2.1 单一出口

world 对 Host 只暴露一条统一事件流：

- 查询接口返回 `WorldRuntimeEvent[]`
- SSE 接口推送 `WorldRuntimeEvent`

Host 不应直接依赖 Java 内部事件类型。

### 2.2 Entity change 只认 `EntitirEvent`

本协议中的 `event_type=entity` 只来源于：

- `EntitirEvent`

不再设计第二套并行的 ontology change 事实出口。

### 2.3 稳定 envelope，类型化 payload

协议分两层：

1. 稳定 envelope
2. 按 `event_type` 分类型 payload

这样：

- Host 可以统一处理列表、SSE、cursor、排序
- 各类事件仍能保留自己的类型信息

### 2.4 面向 UI 与路由，而非面向存储

`WorldRuntimeEvent` 的目标是：

- event panel 展示
- task panel 生成待办
- executor router 匹配与转发

它不是事件溯源数据库的持久化格式。

---

## 3. Event Envelope

统一结构如下：

```json
{
  "id": "evt-uuid",
  "cursor": "1744790400000:evt-uuid",
  "world_id": "hr",
  "world_name": "HR World",
  "env": "draft|staging|production",
  "session_id": "ses-001",
  "event_type": "entity|decision|action|arg|approval",
  "source_type": "EntitirEvent|DecisionEvent|ActionEvent|ParameterInputEvent|executor-router",
  "timestamp": "2026-04-16T10:00:00Z",
  "summary": "Employee#12 updated",
  "severity": "info|warning|error",
  "need_user_action": false,
  "payload": {}
}
```

---

## 4. Envelope 字段定义

### `id`

- 事件唯一 ID
- 在当前 world 范围内必须唯一
- 推荐用 UUID

### `cursor`

- 事件流游标
- 用于分页与 SSE 续传
- 建议格式：`{epochMillis}:{id}`

要求：

- 同一 world 内全序可比较
- 同时支持“时间排序 + 同毫秒去重”

### `world_id`

- world 的稳定标识
- 应与 world designer / executor routing 使用的 world id 一致

### `world_name`

- 给 UI 展示用
- 非路由主键

### `env`

枚举：

- `draft`
- `staging`
- `production`

约定：

- 事件从哪个 world runtime 发出，就带哪个 `env`
- 同一 `world_id` 在不同 `env` 下的事件必须可区分

### `session_id`

- 可选
- 用于把一串事件关联到同一轮用户动作 / agent 任务
- 若当前事件不属于某个命名 session，可为空字符串

### `event_type`

一级分类：

- `entity`
- `decision`
- `action`
- `arg`
- `approval`

### `source_type`

事件来源类型：

- `EntitirEvent`
- `DecisionEvent`
- `ActionEvent`
- `ParameterInputEvent`
- `executor-router`

说明：

- `source_type` 反映内部真实来源
- `event_type` 反映 Host 消费语义

### `timestamp`

- ISO-8601 UTC 时间戳

### `summary`

- 给列表 UI 的单行摘要
- 不要求机器可解析
- 仅用于显示

### `severity`

枚举：

- `info`
- `warning`
- `error`

建议：

- 正常流转默认 `info`
- 待补参 / 待审批可标 `warning`
- 失败类事件标 `error`

### `need_user_action`

- 是否应进入 task panel

约定：

- task panel 只展示 `decision`
- 只有需要人工处理的 `decision` 才应为 `true`
- `arg` / `approval` 可出现在事件流中，但默认不直接作为 task panel 项

### `payload`

- 类型化负载
- 由 `event_type` 决定结构

---

## 5. 五类 payload 结构

## 5.1 `entity`

来源：`EntitirEvent`

```json
{
  "entity_type": "Employee",
  "entity_id": 12,
  "change_origin": "external|internal",
  "caused_by_decision_id": null,
  "operation": "CREATE|UPDATE|DELETE",
  "fields": {
    "name": "Will",
    "status": "onboarding"
  }
}
```

字段说明：

- `entity_type`：实体类型
- `entity_id`：实体主键
- `change_origin`：第一次外部变化还是 decision 传播带来的内部变化
- `caused_by_decision_id`：若本次变化由某个 decision 传播产生，则指向该 decision
- `operation`：实体变更类型
- `fields`：本次事件携带的快照/字段

约束：

- `entity` 事件只允许来自 `EntitirEvent`

## 5.2 `decision`

来源：`DecisionEvent`

```json
{
  "decision_id": "dec-001",
  "template_id": "onboarding_started",
  "status": "INTENTION|DECIDED|EXECUTED|REJECTED|WITHDRAWN",
  "execution_result": "pending|done|rejected|failed",
  "need_approval": false,
  "target_ontology": "Employee",
  "entity_ref": "12",
  "triggered_by": "manual|system|agent",
  "actor": "system",
  "reason": ""
}
```

说明：

- `reason` 仅在 rejected / withdrawn 等场景可能出现
- `execution_result` 表示 executor / 用户处理后的终态回流
- `need_approval` 用于 task panel 与默认审批 widget 的展示判断

## 5.3 `action`

来源：`ActionEvent(BEGIN/DONE/FAILED/RESUMED)`

```json
{
  "ontology": "Employee",
  "entity_id": 12,
  "action_name": "initiate_salary",
  "status": "BEGIN|DONE|FAILED|RESUMED",
  "detail": "completed: employees.filter(...).first().initiate_salary()",
  "suspension_id": null
}
```

说明：

- 正常 action 生命周期事件进入 event panel
- 不直接作为 task

## 5.4 `arg`

来源：`ActionEvent(AWAITING_INPUT)`

```json
{
  "ontology": "Employee",
  "entity_id": 12,
  "action_name": "request_asset",
  "status": "AWAITING_INPUT",
  "suspension_id": "sus-001",
  "missing_params": ["asset_type", "reason"],
  "arg_widget": {
    "executor_registration_id": "exec-hr-main",
    "widget_type": "asset-request-form"
  }
}
```

说明：

- `arg` 是 `AWAITING_INPUT` 的 Host-facing 投影
- `arg_widget` 来自 action 配置，不由 Host 推断
- 默认进入 event panel，不直接成为 task panel 主项

## 5.5 `approval`

来源：`executor-router`

```json
{
  "approval_key": "approval-001",
  "registration_id": "exec-hr-main",
  "title": "员工建档外部同步审批",
  "reason": "executor policy requires approval",
  "widget_type": "hr-approval-card",
  "original_event": {
    "event_type": "entity",
    "event_id": "evt-entity-001"
  }
}
```

说明：

- `approval` 不是 ontology 内核原生事实事件
- 它来自 executor routing 阶段
- v1 中 task panel 主项仍然是 `decision`，approval 更偏向审计 / 协议记录

---

## 6. 列表查询接口

建议 world 对外提供：

```text
GET /api/worlds/{worldId}/runtime-events
```

查询参数：

- `env`
- `limit`
- `cursor`
- `event_type`
- `need_user_action`

示例：

```text
GET /api/worlds/hr/runtime-events?env=production&limit=50&event_type=arg
```

响应：

```json
{
  "items": [
    {
      "id": "evt-001",
      "cursor": "1744790400000:evt-001",
      "world_id": "hr",
      "world_name": "HR World",
      "env": "production",
      "session_id": "ses-001",
      "event_type": "arg",
      "source_type": "ActionEvent",
      "timestamp": "2026-04-16T10:00:00Z",
      "summary": "Employee::request_asset 缺少参数",
      "severity": "warning",
      "need_user_action": true,
      "payload": {
        "ontology": "Employee",
        "entity_id": 12,
        "action_name": "request_asset",
        "status": "AWAITING_INPUT",
        "suspension_id": "sus-001",
        "missing_params": ["asset_type", "reason"],
        "arg_widget": {
          "executor_registration_id": "exec-hr-main",
          "widget_type": "asset-request-form"
        }
      }
    }
  ],
  "next_cursor": "1744790400000:evt-001",
  "has_more": false
}
```

---

## 7. 查询接口规则

### 7.1 排序

- 默认按 `timestamp` 升序
- 若同毫秒有多个事件，则按 `id` 排序

### 7.2 `cursor`

- 返回“严格大于该 cursor”的后续事件
- 若不传 `cursor`，从最早或默认窗口开始

### 7.3 `limit`

- 默认建议 `50`
- 最大建议 `200`

### 7.4 `need_user_action=true`

用于 task panel 拉取待办事件。

建议等价于：

- `event_type = "decision"`
- 且 `payload.need_approval = true`

但服务端仍可保留独立字段，便于以后扩展。

---

## 8. SSE 接口

建议 world 对外提供：

```text
GET /api/worlds/{worldId}/runtime-events/stream
```

查询参数：

- `env`
- `cursor`
- `event_type`

示例：

```text
GET /api/worlds/hr/runtime-events/stream?env=production&cursor=1744790400000:evt-001
```

---

## 9. SSE 事件格式

建议使用标准 SSE：

```text
event: runtime_event
id: 1744790401000:evt-002
data: {"id":"evt-002","cursor":"1744790401000:evt-002", ...}
```

心跳：

```text
event: ping
data: {}
```

流结束错误：

```text
event: error
data: {"message":"stream disconnected"}
```

### 9.1 SSE `id`

- 直接使用 `cursor`
- 这样 Host 断线后可用 `Last-Event-ID` 续传

### 9.2 续传规则

若客户端发送：

- `Last-Event-ID`
- 或 query 参数 `cursor`

服务端都应从“严格大于该 cursor”的事件开始推送。

---

## 10. Host 侧消费规则

## 10.1 Event Panel

Host 将所有 `WorldRuntimeEvent` 展示为事件流。

显示字段建议：

- `summary`
- `timestamp`
- `severity`
- `env`

详情展开时显示 `payload`。

## 10.2 Task Panel

Host 只挑选：

- `event_type=decision`
- 且 `need_user_action=true`

作为 task。

默认规则：

- `decision.need_approval=true`：进入 task panel
- `decision.need_approval=false`：不作为待办停留，直接进入 executor 路由

## 10.3 Action

当用户点击 task：

- 若 `event_type=decision` 且 `payload.need_approval=true`，打开默认审批 widget
- 用户批准后，Host 将该 decision 交给 executor
- 用户拒绝后，world 将该 decision 标记为 `rejected`

## 10.4 与场景 3 共享探索机制的衔接

场景 3（无法直接归一化为 external entity change 的输入）由共享探索机制处理：

- `shared-autonomous-ontology-exploration.md`

该机制内部会经历：

- decision / action semantic harvest
- runtime completion 约束下的 expression 构造与执行（`ontology_eval/can_chain`）

对 `WorldRuntimeEvent` 协议的要求是：

1. 事件流不关心“LLM 内部如何写 expression”，只关心可观测的 runtime 结果；
2. 由探索链路触发的实体变化仍投影为 `event_type=entity`（来源 `EntitirEvent`）；
3. 若探索结果形成可执行 decision，则继续走 `decision -> executor -> terminal result` 标准闭环。

---

## 11. Summary 生成建议

`summary` 不作为业务协议字段，仅为 UI 友好展示。

建议规则：

### entity

- `Employee#12 created`
- `Employee#12 updated`
- `Employee#12 deleted`

### decision

- `Decision onboarding_started executed`
- `Decision salary_review rejected`

### action

- `Employee::initiate_salary started`
- `Employee::initiate_salary failed`

### arg

- `Employee::request_asset 缺少参数`

### approval

- `待审批：员工建档外部同步`

---

## 12. Severity 生成建议

建议映射：

- `entity` -> `info`
- `decision.EXECUTED` -> `info`
- `decision.REJECTED` -> `warning`
- `action.BEGIN/DONE/RESUMED` -> `info`
- `action.FAILED` -> `error`
- `arg` -> `warning`
- `approval` -> `warning`

---

## 13. 与内核事件的映射

## 13.1 `EntitirEvent -> WorldRuntimeEvent(entity)`

```json
{
  "event_type": "entity",
  "source_type": "EntitirEvent"
}
```

## 13.2 `DecisionEvent -> WorldRuntimeEvent(decision)`

```json
{
  "event_type": "decision",
  "source_type": "DecisionEvent"
}
```

## 13.3 `ActionEvent(BEGIN/DONE/FAILED/RESUMED) -> WorldRuntimeEvent(action)`

```json
{
  "event_type": "action",
  "source_type": "ActionEvent"
}
```

## 13.4 `ActionEvent(AWAITING_INPUT) -> WorldRuntimeEvent(arg)`

```json
{
  "event_type": "arg",
  "source_type": "ActionEvent"
}
```

## 13.5 `executor router -> WorldRuntimeEvent(approval)`

```json
{
  "event_type": "approval",
  "source_type": "executor-router"
}
```

---

## 14. 存储与缓存建议

本协议不强制事件持久化实现，但建议：

- 至少保留一个最近窗口
- 能支撑 event panel 初次加载
- 能支持 SSE 断线短暂续传

最小建议：

- 每个 `world_id + env` 保留最近 `N=500~2000` 条

---

## 15. 最小实现顺序

### Phase 1：协议冻结

- [x] 冻结 envelope 字段
- [x] 冻结五类 payload
- [x] 冻结 `GET /runtime-events`
- [x] 冻结 `GET /runtime-events/stream`

### Phase 2：只读实现

- [x] world 侧把内核事件投影为 `WorldRuntimeEvent`
- [x] 实现事件缓存
- [x] 实现列表查询
- [x] 实现 SSE

### Phase 3：Host 接入

- [x] world-one event panel 消费 `WorldRuntimeEvent`
- [x] task panel 过滤 `event_type=decision && need_user_action=true`

### Phase 4：与 executor 协议联动

- [x] decision 审批打开默认 widget
- [x] `arg` 打开 action 配置的 widget
- [x] decision 终态结果回流 world

---

## 16. 非目标

本文档不定义：

1. 事件如何持久化到数据库
2. 事件如何做跨节点广播
3. 审批流本身的业务语义
4. executor 内部如何执行业务逻辑

这些属于具体实现层或更上层产品设计。

