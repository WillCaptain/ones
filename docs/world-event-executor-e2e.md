# World Event / Executor End-to-End 设计

> 状态：设计中  
> 范围：`ones/world-one` 作为 Host / Agent，`entitir` 作为 World 内核  
> 目标：定义 world 运行时事件链路、开放式 executor 协议、decision 作为对外处理单元的闭环模型，以及 action resilient 补参 widget 配置模型；并与场景 3 的共享本体探索机制保持一致  

---

## 1. 背景

当前 `OntologyWorld` 已经具备一条可运行的内部闭环：

```text
entity mutation
  -> trigger match
  -> decision run
  -> action execute
  -> entity mutation
```

但这条链路目前主要停留在内核内部，存在三个问题：

1. World One 还没有直接消费 world 内部事件并投射到 task / event panel。
2. executor 还没有抽象成开放协议层，缺少统一的注册、发现、路由标准。
3. resilient action 的补参虽然已有 `AWAITING_INPUT` 机制，但还没有和 AIPP widget、executor app 的能力目录打通。

本文档先定义端到端设计标准，不实现任何具体 executor。

本轮讨论后，系统边界进一步收敛为：

- `entity change` 是 world 内部事实，用于触发 decision
- `decision` 是对外可处理业务单元
- executor 不直接处理 `entity`，而只处理 `decision`
- task panel 只展示 `decision`

---

## 2. 设计结论

### 2.1 结论一：不新增独立 `entity_change(target)` API

现有机制已经满足语义要求：

- 外部通过 entity CRUD / action / pipeline 回写等 API 改变 world 数据
- 内部统一发布 `EntitirEvent(CREATE/UPDATE/DELETE)`
- 后续 trigger / decision / action 全部沿该事件链继续运行

因此本方案不再额外设计一个平行的 `entity_change(target)` API。

**规范要求**：

- 任何会改变 ontology 数据的入口，无论是 REST API、action、pipeline 回写，最终都必须发布 **同一种** `EntitirEvent`
- 事件发布语义必须与实际 API 行为一致，不能存在“API 改了数据但没发事件”或“发了事件但并未真实落库”的分叉

### 2.2 结论二：entity change 只保留一套事件语义

当前代码里有两条容易混淆的线：

- `EntitirEvent / EntitirEventBus`：实体真实变化事件
- `OntologyChangeEvent / WorldEventBus`：另一套 ontology change 表述

本方案要求：

- **以 `EntitirEvent` 作为唯一的 entity change 事实事件**
- 新设计不再扩展 `OntologyChangeEvent`
- 后续如需对外暴露 entity change，只暴露 `EntitirEvent` 的标准化投影，不再发明第二种同义模型

这意味着：

- `EntitirEventBus` 是 **实体变化事实总线**
- `WorldEventBus` 保留给 **decision / action / parameter input** 等语义事件
- 但不再承载一套重复的 ontology change 语义

### 2.3 结论三：executor 是开放协议层，不是内置实现集合

executor 的职责不是“写死几个执行器”，而是定义一个可注册、可发现、可路由的开放协议层。

本阶段只做：

- executor 注册协议
- executor 能力发现协议
- event -> executor 路由协议
- approval / argument / widget 交互协议

本阶段不做：

- 任意具体 HR executor / FMS executor / AMS executor 实现

### 2.4 结论四：resilient action 的补参 widget 属于 action 配置的一部分

当 action 进入 resilient 模式并触发 `AWAITING_INPUT` 时，用户看到哪个 widget，不应由 Host 硬编码决定，而应由 action 配置决定。

但这个 widget 不是 world-one 自己发明的，而是：

- 由某个 executor AIPP 对外注册
- world designer 在 action 配置界面中，从“当前 world 可用 executor 暴露的 widgets”中选择

因此 action 配置需要新增一层与 executor 关联的 UI 元数据。

### 2.5 结论五：decision 必须有终态回流

对外部 executor 来说，处理一个 decision 后只能得到三种终态结果：

- `done`
- `rejected`
- `failed`

无论 executor 处理中间展示了文本、`html_widget`、`canvas` 还是新 session，最终都必须把这三种结果之一回流到 world。

这样 world 才能：

- 确认该 decision 的执行是否完成
- 给相关 entity 记录一个对应结果
- 让 UI 知道这条 decision 是否应从待办中移除

### 2.6 结论六：rejected 不自动作为下次决策参考

被 `rejected` 的 decision 会保留下来，供后续人工查看与整理。

但 v1 规则是：

- `rejected decision` 不自动参与下次 decision making
- 未来可以增加“人工挑选 rejected case，转化为后续决策参考”的机制

也就是说，rejected case 在 v1 是审计 / 人工复盘材料，不是自动负向样本。

---

## 3. 现状核对

## 3.1 已有能力

当前内核中已具备：

- `EntitirEventBus` 发布 `EntitirEvent(CREATE/UPDATE/DELETE)`
- `OntologyWorld` 订阅 `EntitirEventBus`，执行 ontology change activator 匹配
- trigger 在 entity 上下文中求值（如 `event_entity` / `event_type`）
- `runDecision(...)` 执行 decision 并发出 `DecisionEvent`
- `applyAction(...)` 执行动作并发出 `ActionEvent`
- resilient action 缺参时发出 `ActionEvent(AWAITING_INPUT)`
- 外部可通过 `resumeAction(...)` 恢复挂起动作

## 3.2 当前缺口

目前还缺少：

1. world 到 world-one 的统一事件出口
2. executor 注册 / 发现 / 路由协议
3. action 配置与 executor widget 的绑定模型
4. task panel / event panel 与 world 内部事件的直接映射
5. decision 终态回流与 entity 结果投影规则

---

## 4. 标准事件模型

对外给 World One 的事件投影，统一称为 `WorldRuntimeEvent`。

它不是新的内核事实模型，而是对现有事件的 **Host-facing envelope**。

```json
{
  "id": "evt-uuid",
  "world_id": "hr-world",
  "env": "draft|staging|production",
  "session_id": "optional-correlation-id",
  "event_type": "entity|decision|action|arg|approval",
  "source_type": "EntitirEvent|DecisionEvent|ActionEvent|ParameterInputEvent",
  "timestamp": "2026-04-15T12:34:56Z",
  "payload": {},
  "executor_hint": null,
  "need_approval": false
}
```

说明：

- `WorldRuntimeEvent` 是 **出口协议**
- `payload` 保留来源事件的核心字段
- `source_type` 让 Host 知道它映射自哪一种内核事件
- `executor_hint` 是可选路由建议，不是强绑定
- task panel 只消费 `decision`

---

## 5. 五类运行时事件

## 5.1 `entity`

来源：`EntitirEvent`

```json
{
  "event_type": "entity",
  "source_type": "EntitirEvent",
  "payload": {
    "entity_type": "Employee",
    "entity_id": 12,
    "operation": "CREATE|UPDATE|DELETE",
    "fields": {
      "name": "Will",
      "status": "onboarding"
    }
  }
}
```

用途：

- event panel / 审计展示
- 驱动后续 decision activation
- 不作为 executor 的直接输入单元

## 5.2 `decision`

来源：`DecisionEvent`

```json
{
  "event_type": "decision",
  "source_type": "DecisionEvent",
  "payload": {
    "decision_id": "dec-001",
    "template_id": "onboarding_started",
    "status": "INTENTION|DECIDED|EXECUTED|REJECTED|WITHDRAWN",
    "execution_result": "pending|done|rejected|failed",
    "need_approval": true,
    "target_ontology": "Employee",
    "triggered_by": "system",
    "actor": "system"
  }
}
```

用途：

- task panel 展示
- executor router 的直接输入
- UI 展示 decision 生命周期
- approval / audit / replay
- decision 终态闭环

## 5.3 `action`

来源：`ActionEvent(BEGIN/DONE/FAILED/RESUMED)`

```json
{
  "event_type": "action",
  "source_type": "ActionEvent",
  "payload": {
    "ontology": "Employee",
    "entity_id": 12,
    "action_name": "initiate_salary",
    "status": "BEGIN|DONE|FAILED|RESUMED",
    "detail": "..."
  }
}
```

## 5.4 `arg`

来源：`ActionEvent(AWAITING_INPUT)`

```json
{
  "event_type": "arg",
  "source_type": "ActionEvent",
  "payload": {
    "ontology": "Employee",
    "entity_id": 12,
    "action_name": "request_asset",
    "suspension_id": "sus-001",
    "missing_params": ["asset_type", "reason"],
    "widget": {
      "app_id": "hr-executor",
      "widget_type": "asset-request-form"
    }
  }
}
```

说明：

- `arg` 事件不是新的内核事实，仅是 `AWAITING_INPUT` 的对外投影
- widget 信息来自 **action 配置**
- `arg` 不进入 task panel，而是在 decision 执行链中通过 AIPP response / widget 处理

## 5.5 `approval`

来源：路由层投影，不要求当前内核已有原生事件

```json
{
  "event_type": "approval",
  "source_type": "executor-router",
  "need_approval": true,
  "payload": {
    "executor_registration_id": "exec-hr-main",
    "approval_key": "approval-001",
    "reason": "executor policy requires approval before external side effect",
    "original_event": {
      "event_type": "entity",
      "entity_type": "Employee",
      "entity_id": 12
    }
  }
}
```

说明：

- approval 是 **协议层事件**
- 它来自 executor routing policy，而不是来自 ontology 内核事实层
- task panel 不直接展示 approval event，而是通过 decision 的 `need_approval` 驱动默认审批 widget

---

## 6. 端到端主链路

```text
1. 外部 API / action / pipeline 回写改变 ontology 数据
2. 发布 EntitirEvent
3. OntologyWorld 根据 EntitirEvent 匹配 OntologyChange activator
4. 对命中的 decision template 求值 trigger
5. trigger 命中则生成 / 执行 decision
6. world 发出 decision runtime event
7. 若 decision.need_approval = false，自动路由到 executor
8. 若 decision.need_approval = true，task panel 展示该 decision，用户点击后打开默认审批 widget
9. executor / 用户处理后，decision 必须回流 done / rejected / failed
10. 若 decision 触发 action，action 可进一步引发内部 entity change
11. action 若再次改动 ontology，重新发布 EntitirEvent
12. 相关 entity 被投影上本次 decision 的执行结果
```

---

## 7. Executor 开放协议

## 7.1 基本角色

### World

- 负责维护 ontology / decision / action 运行时
- 负责发出内核事件
- 负责接收 decision 终态回流
- 不关心具体哪个 executor 实际处理外部动作

### Executor AIPP

- 一个可注册进来的外部 app
- 声明自己能处理哪些 decision
- 暴露可选 widget、approval、arg collection 等能力

### World One

- 作为 Host
- 负责发现 executor 注册
- 负责展示事件
- 负责按协议驱动 executor，但不内置某个业务 executor
- task panel 只展示 decision

## 7.2 注册模型

executor 对外注册一条或多条 `ExecutorRegistration`：

```json
{
  "id": "exec-hr-main",
  "app_id": "hr-executor",
  "world_selector": {
    "world_ids": ["hr"],
    "envs": ["staging", "production"]
  },
  "match": {
    "event_types": ["decision"],
    "entity_types": ["Employee", "AssetRequest"],
    "operations": [],
    "decision_templates": [],
    "action_names": []
  },
  "capabilities": {
    "can_execute": true,
    "can_collect_args": true,
    "can_render_widgets": true,
    "can_require_approval": true
  }
}
```

路由原则：

- 先按 `world_selector`
- 再按 `match`
- 命中后才进入具体 executor tool 调用

## 7.3 Executor 工具协议

本阶段只定义协议，不定义实现。

建议保留这三类 executor skill：

- `executor_match_event`
- `executor_handle_decision`
- `executor_resume_arg_flow`

语义：

- `executor_match_event`：可选，用于复杂动态判断
- `executor_handle_decision`：真正执行 decision
- `executor_resume_arg_flow`：处理补参后续恢复

---

## 8. Action 上的 executor widget 配置

## 8.1 为什么配置落在 action 上

`AWAITING_INPUT` 时展示哪个 widget，本质上是“这个 action 缺参时，人应该用什么 UI 来补全参数”。

这不是 world-one 的全局策略，而是 action 自身的运行时配置。

因此 action 定义中需要新增可选配置：

```json
{
  "name": "request_asset",
  "entityType": "Employee",
  "type": "outline",
  "code": "...",
  "resilient": true,
  "arg_widget": {
    "executor_app_id": "hr-executor",
    "widget_type": "asset-request-form"
  }
}
```

## 8.2 widget 来源

这些 widget 不应由 world-one 内置，而应：

- 来源于对应 executor AIPP 的 `widgets.json`
- 由 world designer 在 action 编辑界面中选择

换句话说：

- action editor 需要能看到“当前 world 可用 executor 注册暴露的 widgets”
- 用户为某个 action 选中其中一个 widget
- 当 action 进入 `AWAITING_INPUT` 时，Host 直接按配置打开对应 widget

## 8.3 设计器行为

action 界面需要新增一个分组：

```text
Resilient Input
  [x] resilient
  Executor app:   [hr-executor ▼]
  Input widget:   [asset-request-form ▼]
```

选择逻辑：

1. 先筛选对当前 world 可见的 executor registrations
2. 再聚合这些 executor app 暴露的 widgets
3. 只显示声明为“可用于 argument collection”的 widget

---

## 9. World One 侧的事件展示

## 9.1 Event Panel

Event Panel 展示所有 `WorldRuntimeEvent`，偏事实流：

- entity created / updated / deleted
- decision executed / rejected / failed
- action begin / done / failed
- arg waiting
- approval waiting

## 9.2 Task Panel

Task Panel 只展示 `decision`。

原则：

- Event Panel = 全量运行时流
- Task Panel = decision 待办视图

显示规则：

- `decision.need_approval = true`：显示在 task panel，点击打开默认审批 widget
- `decision.need_approval = false`：不作为待办停留，直接路由 executor

---

## 10. Host 与 World 的接口

## 10.1 World -> Host 事件出口

需要一条统一事件流接口，建议：

```text
GET /api/worlds/{worldId}/runtime-events/stream
GET /api/worlds/{worldId}/runtime-events?cursor=...&limit=...
```

返回的是 `WorldRuntimeEvent`，不是原始 Java 事件对象。

## 10.2 Host -> World 操作接口

本阶段不新增 `entity_change` 专用 API。

Host 需要的动作只有：

- 查询 runtime events
- 恢复挂起 action（`resumeAction` 对应 HTTP 包装）
- 将 decision 批准 / 拒绝结果转交给 world
- 将待执行 decision 转交给 executor

## 10.3 与场景 3（Shared Autonomous Ontology Exploration）的关系

场景 3（用户输入无法直接转成 `external entity change`）不走 event ingestion，而走 world-aware exploration。

其共享机制定义在：

- `shared-autonomous-ontology-exploration.md`

在 E2E 里需要保持两个一致性约束：

1. **单一能力源**：场景 3 的探索逻辑只能复用 `entitir/aip`，不能在 world-one / playground 各自维护一套 Phase 3 规则。
2. **单一运行时真理**：LLM 构造 VirtualSet expression 时，后继可链成员必须以 `ontology_eval` 返回的 `can_chain` 为准，而非静态 schema 猜测。

这意味着 E2E 主链路可同时支持：

- 事件驱动主链路（场景 1/2）
- 场景 3 的共享探索链路（先 semantic harvest，再 runtime completion 约束下执行）

但两条链路最终都应回到同一套 world / decision / action 结果语义。

---

## 11. 边界与非目标

本设计 **不** 做以下事情：

1. 不定义任何具体 executor 业务实现
2. 不定义具体审批流产品形态
3. 不把 world-one 变成 executor
4. 不再增加第二套 entity change 事实模型
5. 不为了“语义上好看”而额外引入 `entity_change(target)` API

---

## 12. 实施顺序

### Phase 1：文档与协议冻结

- [x] 冻结 `EntitirEvent` 为唯一 entity change 事实事件
- [x] 冻结 `WorldRuntimeEvent` 作为 Host-facing envelope
- [x] 冻结 `ExecutorRegistration` 协议
- [x] 冻结 action 上的 `arg_widget` 配置模型
- [x] 冻结 decision terminal result 回流模型

### Phase 2：只做只读链路

- [x] world-entitir 暴露 runtime events 查询 / SSE
- [x] world-one 订阅并在 event panel / task panel 展示

### Phase 3：接入 executor 协议

- [x] world-one 发现 executor registrations
- [x] action 编辑器拉取 executor widgets 并支持配置
- [x] `decision` 可转交给 executor
- [x] executor 终态结果回流 world

### Phase 4：再落具体 executor

到这一步才开始实现某个真实的 `hr-executor`、`finance-executor` 等 AIPP。

---

## 13. External / Internal Entity Change 区分

`entity change` 在触发层都遵循同一套 target / activator 监听机制，但来源不同。

### 13.1 external entity change

指第一次由外部世界引入的实体变化，例如：

- 外部系统 webhook
- 外部同步回写
- 用户直接通过 API 改动实体

特征：

- 来源于 world 外部
- 可能没有 `caused_by_decision_id`
- 是触发某个 decision 的起点

### 13.2 internal entity change

指由某个 decision 传播过程中产生的实体变化，例如：

- decision 触发 action
- action 改动 ontology
- 改动再次触发后续 decision

特征：

- 来源于 world 内部传播
- 应携带 `caused_by_decision_id`
- 仍然发布为同一种 `EntitirEvent`

### 13.3 监听规则

两者在 trigger / activator 层面都通过同一套 target 监听：

- 都按 `entity_type + operation + changed_fields` 匹配
- 不因为 external / internal 而使用两套不同 trigger 机制

但协议层应允许在事件元数据中区分来源：

- `change_origin = external | internal`
- `caused_by_decision_id = nullable`

这样既保持单一事件机制，又能区分“第一次外部变化”和“decision 传播带来的内部变化”。

---

## 14. Rejected 后的实体结果与回滚策略

如果 decision 终态为 `rejected`：

- world 必须能把该结果映射到相关 entity
- v1 不要求一定直接写实体业务字段 `status = rejected`
- 但至少要能让 entity 视图看见“该 entity 对应的最新 decision result = rejected”

### 14.1 v1 回滚策略

v1 不做自动 rollback。

原因：

- rollback 在通用 world 中不可安全泛化
- 外部副作用可能不可逆
- 下一次外部同步可能自然覆盖当前 rejected 状态

因此 v1 策略是：

- `rejected` 只记录为明确终态
- 不自动恢复先前 entity 数据
- 等待后续外部同步或人工处理覆盖

### 14.2 TODO

未来可讨论是否引入：

- 可配置 rollback policy
- 仅对可逆 action 启用 rollback
- rollback 前的人机确认流程

---

## 15. Rejected Case 的后续用途

`rejected decision` 在 v1：

- 用于审计
- 用于人工复盘
- 用于后续人工整理是否转化为规则 / 案例

但 v1 不自动把 rejected case 注入下次 decision making。

---

## 16. 与现有代码的兼容原则

为了减少回归，本设计遵守以下兼容原则：

- 不推翻现有 `EntitirEventBus` 触发链
- 不重写现有 `runDecision / applyAction / resumeAction`
- 不在 Host 层硬编码具体业务 executor
- 不在 world-one 内置具体缺参 widget
- 不新引入第二套 ontology change 事实事件

换句话说，后续实现应该是：

```text
复用现有内核链路
  + 增加对外 runtime event 出口
  + 增加 executor 注册 / 发现 / 路由协议
  + 增加 action -> widget 配置
```

而不是重做一套世界运行机制。

