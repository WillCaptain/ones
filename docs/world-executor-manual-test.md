# World Executor 手工测试清单

> 目标：快速验证 `decision approval`、`awaiting_arg_widget`、`resume action`、`terminal flowback` 四条主链路。
>
> 本文末新增「`Will 入职（AAP-Pre/AAP-Post）`」用例：
> - 先验证当前可闭环主链路（app 命中 -> world 选择 -> 事件写入）
> - 再衔接到后续“决策生成层”多步骤测试

## 前置条件

- `world-entitir` 已启动（含 `/api/worlds/{worldId}/runtime-events*`、`/decision-result`、`/resume-action`）
- `world-one` 已启动
- 至少有一个 executor app 已注册，且暴露：
  - `GET /api/executors`
  - `handle_event`
  - （可选）`resume_arg_flow`
- 打开 world 设计器后，确认有 `worldSessionId`（即 Runtime Events 开始滚动）

## A. 注册发现

1. 在浏览器打开 world-one 后，进入目标 world。
2. 在 devtools network 检查 `GET /api/executors` 返回：
   - 有 `executors[]`
   - `widgets` 中存在 `usage=argument_input`
3. 打开 Action 编辑器，确认可见：
   - `Executor Registration` 下拉
   - `Arg Widget` 下拉（随 registration 联动）

## B. Decision 审批链路

1. 触发一个 `need_approval=true` 的 decision runtime event。
2. Task 面板应出现“待审批”卡片，含两个按钮：
   - `批准并执行`
   - `拒绝`
3. 点“批准并执行”后，检查：
   - 调用了 `POST /api/executors/approval`（`result=approved`）
   - 后续触发 executor `handle_event`
4. 点“拒绝”后，检查：
   - 调用了 `POST /api/executors/approval`（`result=rejected`）
   - world 侧 decision 被标记 `REJECTED`

## C. Arg Widget 与恢复链路

1. 让 executor 返回 `status=awaiting_arg_widget`，并包含 `arg_flow`：
   - `flow_id`
   - `suspension_id`
   - `widget_type`
2. Event 面板应出现“补参”卡片，含两个按钮：
   - `打开 Executor App`
   - `提交参数并恢复`
3. 点击“提交参数并恢复”，输入 JSON 参数后确认。
4. 检查：
   - 调用了 `POST /api/executors/resume-arg`
   - world 侧调用了 `POST /api/worlds/{worldId}/resume-action`
   - 若 executor 提供 `resume_arg_flow`，应同时收到通知调用

### C.1 无 widget_id 的默认补参页

1. 触发一个 `AWAITING_INPUT` 但未指定 `widget_type` 的 action（例如入职登记缺少 `employee_no`）。
2. Task 卡片状态应为 `PENDING`，点击后主会话显示默认 HTML 输入框（JSON 参数输入）。
3. 提交参数后检查：
   - 状态从 `PENDING` -> `RUNNING/EXECUTING` -> 终态
   - 后端链路仍是 `resume-arg` -> `resume-action`
4. 重点验证：缺参是在 action 阶段暴露，不应在外部事件归一化阶段因为硬编码外键默认值提前失败。

## D. 终态回流

1. 让 executor 分别返回：
   - `done`
   - `rejected`
   - `failed`
2. 检查 world-one 路由结果中的 `flowback` 字段存在。
3. 检查 runtime event 中 decision payload：
   - `execution_result=done/rejected/failed`

## E. 回归检查（最短）

- 页面切换 world 后，runtime feed 正常重连，不重复刷旧事件
- task/event 卡片样式与现有 `sys.*` 卡片一致
- `mvn test`：
  - `entitir/world-entitir`
  - `ones/world-one`

## F. Will 入职（AAP-Pre/AAP-Post）基线链路

> 目的：验证“给 will 办理入职”从 app 级命中到 world 事件写入的完整闭环。  
> 范围：当前阶段只验证路由与写入，不覆盖复杂 decision 生成编排。

### F.0 预置数据

- 系统内至少存在 `world-entitir` 与 `memory-one` 两个 app。
- 至少有 1 个可用 world，且包含 `Employee` 实体。
- 建议再准备第 2 个包含 `Employee` 的 world，用于验证 `sys.selection` 分支。

### F.1 输入与第一次路由（AAP-Pre）

1. 用户输入：`给will办理入职`
2. 观察首轮 tool 命中：
   - 预期命中 `world-entitir` 相关 skill（而不是 `memory-one`）。
3. 预期在本轮后续响应中出现 `aap_hit`，包含：
   - `app_id=world`
   - `post_system_prompt`（含 world-catalog）
   - `ttl=this_turn`

通过标准：
- app 级路由正确，且成功进入 AAP-Post 执行态。

### F.2 world 选择（AAP-Post + world-catalog）

1. 检查 `post_system_prompt` 中是否包含 world-catalog 概要（至少含）：
   - `world_id`
   - `name`
   - `phase`
   - `env_ready`
   - `entity_types`
2. 若唯一候选 world：
   - 预期直接选中并继续执行。
3. 若多个候选 world：
   - 预期返回 `sys.selection`；
   - 用户点击后，应回调 `onboarding_intake`（带选中的 `world_id`）。

通过标准：
- 第 4 步“命中后拿 world 信息并定位具体 world”可执行。

### F.3 JSON 组装与事件写入

1. 预期后续调用 `world_entity_change`（直接或经 `onboarding_intake` 内部落地）。
2. 关键参数检查：
   - `world_id` 为选中的 world
   - `entity_type=Employee`
   - `operation=create`
   - `fields` 至少包含 `name=Will`（可附带 `status/sync_status` 等）
3. 返回检查：
   - `status=accepted` 或 `ok`
   - 包含执行摘要（如 `expression/result` 或等价字段）
   - 失败时返回明确错误，不可“伪成功”

通过标准：
- 第 5~6 步“组 JSON + 写入命中 world”可闭环完成。

### F.4 负例（建议至少覆盖 2 条）

- **world 不存在**：返回 `world_not_found`，且仍带 `aap_hit` 便于二次决策。
- **env 不可用**：返回 `world_not_ready`。
- **entity_type 不存在**：返回 `entity_type_not_found`。

#### F.4.1 world 不存在（onboarding_intake）

- 输入（tool 调用）：
  - `onboarding_intake`
  - `args`:
    - `request_text`: `给will办理入职`
    - `world_id`: `not-exists-xyz`
    - `env`: `production`
- 预期结果：
  - `status = world_not_found`
  - 响应中包含 `aap_hit`（至少含 `app_id=world`）

#### F.4.2 env 不可用（onboarding_intake）

- 输入（tool 调用）：
  - `onboarding_intake`
  - `args`:
    - `request_text`: `给will办理入职`
    - `world_id`: `2b2063c3-dfe5-486c-a6a1-ce890a813c09`（示例：当前为 DESIGN 的 HR 本体世界）
    - `env`: `production`
- 预期结果：
  - 顶层 `status = failed`
  - `downstream.status = world_not_ready`

#### F.4.3 entity_type 不存在（world_entity_change）

- 输入（tool 调用）：
  - `world_entity_change`
  - `args`:
    - `world_id`: `world-eai-onboarding`
    - `entity_type`: `NoSuchEntity`
    - `operation`: `create`
    - `fields`: `{ "name": "Will" }`
    - `env`: `production`
- 预期结果：
  - `status = entity_type_not_found`
  - 响应中有可读错误信息（指出实体类型不存在）

---

## G. 决策命中后流程（create_decision，当前阶段）

> 本节对应协议里“Decision 命中编排（create_decision）”。  
> 当前只覆盖 ontology activator 命中后的流程。manual / scheduler 暂不测试。

### G.0 前置条件

- F 章节通过（至少能稳定写入 `Employee` onboarding 事件）
- 目标 world 中存在可触发 decision 的模板（如 `onboarding_started`）
- world-one 与 world-entitir 均已启动，且可查看 runtime event / task panel

### G.1 命中提示是否可见

1. 输入：触发入职（如 `给Ivy Zhao办理入职`）
2. 检查命中后是否出现步骤提示（annotation 或等价提示），至少包含：
   - 命中某个 decision（如 `命中 onboarding_started`）
   - 进入 decision 创建/构建阶段

通过标准：
- 用户能明确看到“命中了哪个 decision 模板”。

### G.2 Decision 模板会话策略（默认新任务）

1. 检查目标模板是否配置 `execution_session_mode`：
   - 可选：`new_task` / `main_session`
   - 未配置时默认按 `main_session`
2. 分别验证两种模式：
   - `main_session`：构建过程留在主会话
   - `new_task`：构建过程出现在 task panel 新任务中

通过标准：
- Host 会话承载位置与模板配置一致。

### G.3 create_decision 调用是否发生

1. decision 命中后，检查是否调用 `create_decision`（不要跳过直接执行）。
2. 检查 `create_decision` 请求最小字段：
   - `world_id`
   - `decision_template_id`
   - `trigger_context`（含 event_type / event_entity 等）

通过标准：
- 命中 decision 后一定经过 `create_decision`。

### G.4 create_decision 立即返回与 widget 策略

1. 检查 `create_decision` 是否“立即返回”接单结果（异步执行不阻塞返回）。
2. 返回中检查：
   - `session_mode`（`new_task` / `main_session`）
   - `decision_id`（或等价唯一标识）
   - `building_widget`（可选）
3. widget 行为检查：
   - 若返回自定义 widget：打开该 widget
   - 若未返回 widget：Host 使用 `system.building`（built-in）兜底

通过标准：
- LLM 能基于即时返回判断后续在主会话还是任务会话等待。

### G.5 等待行为（main vs new task）

1. `main_session` 模式：
   - building 等待与状态更新在主会话持续可见
2. `new_task` 模式：
   - 主会话出现“已创建任务”提示
   - 新任务会话内持续显示构建进度，直到终态

通过标准：
- 等待位置正确，且不会出现“主/任务双写冲突”。

### G.6 action 执行步骤可见性

1. 在 create_decision 异步构建期间，检查是否看到 action 级步骤日志（tips）：
   - action 开始
   - action 成功/失败/等待补参/等待审批
2. 终态检查：
   - `done` / `rejected` / `failed` 之一
   - 终态能回流到对应会话（main 或 task）

通过标准：
- 只要 action 有日志输出，LLM/前端都能看到步骤提示，不可静默丢失。

### G.7 本阶段采样字段（最少）

- `decision_template_id`
- `execution_session_mode`（模板配置值）
- `create_decision.called`（是否调用）
- `create_decision.response.session_mode`
- `create_decision.response.building_widget.widget_type`
- `action_log[]`（按时间序）
- `decision_terminal_status`

