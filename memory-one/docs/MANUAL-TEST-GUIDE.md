# Memory-One 手工测试问题单

> **测试环境要求**
> - worldone 已启动（port 8090）
> - memory-one 已启动（port 8091）
> - memory-one 已向 worldone 注册（POST /api/registry/install）
> - 每轮测试前建议开启新 session，避免记忆污染
>
> **观察点说明**
> - ✅ 通过 = 行为符合预期
> - 🔍 观察工具调用序列（worldone 界面或控制台日志）
> - 📋 通过「查看我的记忆」进入 memory-manager widget 验证持久化结果

---

## T1 · SEMANTIC — 用户基本信息建立与更新

### T1-1 初始事实建立

**发送消息：**
```
我叫张伟，是一名在北京工作的 Java 后端工程师，工作了 5 年。
```

**预期行为：**
- LLM 调用 `memory_load`（无记忆返回）
- 回复正常确认
- LLM 调用 `memory_consolidate`
- memory-one 创建：`SEMANTIC / GLOBAL / LONG_TERM`，内容包含姓名、职业、城市

**验证：**
```
查看我的记忆
```
→ memory-manager 应出现一条 **[FACT]** 类型记忆

---

### T1-2 事实更新（SUPERSEDE 验证）

> 接上条对话，同一 session

**发送消息：**
```
对了，我上个月刚升职了，现在是技术架构师了。
```

**预期行为：**
- LLM 调用 `memory_load`（应能看到 T1-1 的记忆上下文）
- `memory_consolidate` 触发
- memory-one 执行 `SUPERSEDE`：旧的"Java 后端工程师"记忆被标记失效，新的"技术架构师"记忆创建

**验证：**
```
你还记得我的职位是什么？
```
→ 应回答「技术架构师」，不应再说「Java 后端工程师」

---

### T1-3 跨 session 记忆持久化

> **开启新 session**

**发送消息：**
```
你还记得我是谁吗？
```

**预期行为：**
- LLM 调用 `memory_load`，返回的上下文中包含 T1-1/T1-2 建立的记忆
- 回复应包含：姓名「张伟」、职位「技术架构师」、城市「北京」

---

## T2 · PROCEDURAL — 用户偏好约定

### T2-1 全局约定建立

**发送消息：**
```
记住，以后回答我的问题时，始终用简洁中文，不要超过 3 句话，代码用 Java。
```

**预期行为：**
- `memory_consolidate` 创建 `PROCEDURAL / GLOBAL / LONG_TERM`
- tags 包含 `memory_instruction`

**验证：**
```
什么是依赖注入？
```
→ 回答应当简洁，不超过 3 句，且如有代码示例使用 Java

---

### T2-2 约定变更（SUPERSEDE）

> 接上 session

**发送消息：**
```
好吧，以后可以详细一点，代码改用 TypeScript。
```

**预期行为：**
- `memory_consolidate` 对旧的 PROCEDURAL 执行 `SUPERSEDE`
- 新约定覆盖旧约定

**验证：**
```
什么是观察者模式？
```
→ 回答应当详细，代码使用 TypeScript

---

## T3 · GOAL — 目标追踪与进度更新

### T3-1 目标建立

**发送消息：**
```
我今天的目标是完成一个电商系统的数据库设计，需要设计用户、商品、订单三张表。
```

**预期行为：**
- `memory_consolidate` 创建 `GOAL / SESSION / MEDIUM_TERM`
- 内容包含三张表的信息

---

### T3-2 目标进度（GOAL_PROGRESS）

**发送消息：**
```
好，用户表设计完了，有 id、username、email、phone、created_at 五个字段。
```

**预期行为：**
- `memory_consolidate` 触发 `GOAL_PROGRESS`
- 目标记忆内容追加进度注记 `[进度] 用户表已完成`

**验证：**
```
我的目标完成了多少？
```
→ 应能回答「用户表已完成，还剩商品表和订单表」

---

### T3-3 目标继续推进

**发送消息：**
```
商品表也搞定了：id、name、price、stock、category_id。
```

**预期行为：**
- 再次 `GOAL_PROGRESS`
- 目标记忆内容累积两条进度

---

## T4 · EPISODIC — 事件记录只追加

### T4-1 第一轮事件

**发送消息：**
```
帮我把订单表的 status 字段改为枚举类型：PENDING、PAID、SHIPPED、CANCELLED。
```

**预期行为：**
- `memory_consolidate` 创建 `EPISODIC / SESSION / SHORT_TERM`
- 记录本次操作事件

---

### T4-2 第二轮事件（不覆盖第一轮）

**发送消息：**
```
好，再给订单表加一个 payment_method 字段，类型为 VARCHAR(20)。
```

**预期行为：**
- 创建第二条 EPISODIC 记忆
- **第一条 EPISODIC 记忆保持不变**（EPISODIC 只追加，从不 SUPERSEDE）

**验证：**
```
查看我的记忆
```
→ 最近事件区域应有 **2 条** 独立事件记录

---

## T5 · RELATION — 实体关系记忆

### T5-1 建立关系记忆

**发送消息：**
```
在我们的系统里，User 和 Order 是一对多关系，一个用户可以有多个订单。
```

**预期行为：**
- `memory_consolidate` 创建 `RELATION / WORKSPACE / MEDIUM_TERM`

---

### T5-2 跨 session 验证关系记忆

> **开启新 session**

**发送消息：**
```
Order 表的 user_id 外键应该怎么设计？
```

**预期行为：**
- `memory_load` 返回 T5-1 建立的关系记忆
- 回答应结合「一对多关系」背景给出建议，而非泛泛而谈

---

## T6 · 用户记忆指令（memory_set_instruction）

### T6-1 设置全局记忆规则

**发送消息：**
```
记住一条规则：每次我提到数据库设计时，都要提醒我考虑索引优化。
```

**预期行为：**
- LLM 调用 `memory_set_instruction`（而非 `memory_consolidate`）
- 创建 `PROCEDURAL / GLOBAL`，tag=`memory_instruction`

**验证（新 session）：**
```
我要设计一个日志表，字段有 id、level、message、timestamp。
```
→ 回答中应主动提及「建议考虑索引优化（如在 timestamp 上建索引）」

---

## T7 · 记忆管理 Widget

### T7-1 查看记忆面板

**发送消息：**
```
查看我的记忆
```

**预期行为：**
- LLM 调用 `memory_view`
- 界面进入 canvas 模式，展示 memory-manager widget
- 面板应显示前几轮建立的所有记忆（按类型分组）

---

### T7-2 通过 Widget 修改记忆

在 memory-manager 面板中：
1. 找到 T1-1 建立的姓名记忆
2. 尝试直接修改内容
3. 点击保存

**预期行为：**
- 修改后，下一轮对话中 `memory_load` 应返回更新后的内容

---

## T8 · 矛盾记忆处理

### T8-1 制造矛盾偏好

**Session A：**
```
记住，我喜欢微服务架构，认为它灵活可扩展。
```

**同一 session：**
```
不对，我其实更倾向单体架构，微服务太复杂了。
```

**预期行为：**
- Memory Agent 识别到偏好冲突
- 执行 `MARK_CONTRADICTION`（或直接 `SUPERSEDE`）
- **不应静默忽略，也不应自动选择一个**

**验证：**
```
我对架构风格的偏好是什么？
```
→ 应诚实回复「之前记录了两种不同偏好，存在矛盾，请确认」

---

## T9 · 用户隔离验证

### T9-1 用户 A 建立记忆

> 登录用户 A

**发送消息：**
```
我是 Alice，专注于前端开发，技术栈是 Vue3 + TypeScript。
```

---

### T9-2 用户 B 无法看到用户 A 的记忆

> 切换登录用户 B

**发送消息：**
```
你还记得我是谁吗？
```

**预期行为：**
- `memory_load` 返回空（用户 B 没有任何记忆）
- 回复应是「这是我们第一次对话，我还不了解你」
- **不应出现任何关于 Alice 或 Vue3 的内容**

---

## T10 · SESSION 记忆升级为 GLOBAL（PROMOTE）

### T10-1 Session 内建立重要信息

**发送消息：**
```
顺便说一下，我们公司的数据库命名规范是：表名用复数下划线命名法（如 user_orders），字段名统一小写。
```

**预期行为：**
- 初始可能建立为 `SESSION` 记忆（或直接 WORKSPACE/GLOBAL，取决于 LLM 判断）

---

### T10-2 要求提升为全局规范

**发送消息：**
```
把这个数据库命名规范记为全局规则，以后所有项目都适用。
```

**预期行为：**
- `memory_consolidate` 触发 `PROMOTE` 或直接更新 scope 为 GLOBAL
- 该约定在新 session 中仍然有效

**验证（新 session）：**
```
帮我设计一个博客系统的表结构
```
→ 表名和字段应自动遵循 T10 中设置的命名规范

---

## T11 · WORKSPACE 协作共享与参与记录

### T11-1 同一 workspace 跨用户共享（WORKSPACE 可见，GLOBAL 不可见）

> 该测试需要两个用户（A/B），并进入同一个 task workspace（同一个 `workspaceId`）。

**用户 A 在同一 workspace 中发送：**
```
HR 本体里 Employee 和 Department 是多对多关系。
```

**预期行为：**
- A 侧 `memory_consolidate` 产生 `RELATION / WORKSPACE`
- 该记忆的 `workspace_id` 等于当前任务实体 ID（如 `world-hr`）

**切换到用户 B（进入同一个 workspace）发送：**
```
Employee 和 Department 是什么关系？
```

**预期行为：**
- B 侧 `memory_load` 能命中 A 创建的 WORKSPACE 关系记忆（跨用户共享）
- 若 A 曾设置过 GLOBAL 个人偏好（如“我喜欢简洁回答”），B 不应继承该偏好

---

### T11-2 自动贡献记录（memory_workspace_join）

> 用户进入 task session 时，系统应自动调用 `memory_workspace_join`。

**操作：**
1. 用户 A 进入 `HR 本体世界`
2. 用户 B 进入 `HR 本体世界`
3. 任一用户询问：
```
这个 HR 本体世界谁编辑过？
```

**预期行为：**
- 数据库中应存在两条 WORKSPACE RELATION：
  - `A --[contributed_to]--> world-hr`
  - `B --[contributed_to]--> world-hr`
- 回答可列出 A、B 两位协作者（顺序不限）

---

### T11-3 个人信息禁止上升到 WORKSPACE

**在 task workspace 中发送：**
```
我叫 Alice，我偏好用中文简洁回答。
```

**预期行为：**
- “我叫 Alice”“偏好中文简洁”应被记录为 `GLOBAL/SESSION`（依策略）而不是 WORKSPACE
- 在同 workspace 的用户 B 会看到任务事实，但不会“继承 Alice 的个人信息”

---

## 观察清单

每轮测试时，通过 worldone 控制台日志或界面验证以下调用序列：

```
用户消息 → [tool: memory_load] → LLM 处理 → [tool: 业务工具(可选)] → [tool: memory_consolidate] → 文字回复
```

| 检查项 | 位置 |
|--------|------|
| 每轮对话都触发了 `memory_load` | worldone 工具调用日志 |
| 每轮对话结束前触发了 `memory_consolidate` | worldone 工具调用日志 |
| memory-one 日志出现 `[MemoryAgent] CREATE/SUPERSEDE...` | memory-one 控制台 |
| 新 session 能看到旧 session 的全局记忆 | 聊天回复内容 |
| 不同用户的记忆完全隔离 | 聊天回复内容 |

---

## 快速验证命令

```bash
# 直接查询 memory-one 的 /api/skills（验证服务正常）
curl http://localhost:8091/api/skills | jq '.skills[].name'

# 手动触发 memory_load（userId=default）
curl -X POST http://localhost:8091/api/tools/memory_load \
  -H "Content-Type: application/json" \
  -d '{"args":{"user_message":"Employee 和 Department 关系是什么"},"_context":{"userId":"default","sessionId":"test-001","workspaceId":"world-hr","workspaceTitle":"HR 本体世界","agentId":"worldone"}}'

# 手动触发 workspace 参与记录（一般由 world-one 自动触发）
curl -X POST http://localhost:8091/api/tools/memory_workspace_join \
  -H "Content-Type: application/json" \
  -d '{"args":{"workspace_id":"world-hr","workspace_title":"HR 本体世界"},"_context":{"userId":"default","workspaceId":"world-hr","agentId":"worldone"}}'

# 查看数据库中所有活跃记忆（需 psql 访问）
psql -U worldone -d worldone -c \
  "SELECT user_id, workspace_id, type, scope, horizon, left(content,60) FROM memories WHERE superseded_by IS NULL ORDER BY created_at DESC LIMIT 30;"

# 查看某 workspace 的贡献者
psql -U worldone -d worldone -c \
  "SELECT user_id, subject_entity, predicate, object_entity FROM memories WHERE scope='WORKSPACE' AND type='RELATION' AND predicate='contributed_to' AND workspace_id='world-hr' AND superseded_by IS NULL;"
```
