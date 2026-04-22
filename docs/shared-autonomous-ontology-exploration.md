# Shared Autonomous Ontology Exploration

> 状态：设计中  
> 关联文档：`world-event-executor-e2e.md`、`world-runtime-event-protocol.md`、`world-executor-protocol.md`  
> 依赖实现：`entitir/aip`  
> 目标：定义场景 3 的唯一共享实现，确保 LLM 基于 `decision` / `action` / `entity collection` 做世界内探索时，复用同一套 prompt、tool、runtime completion 与执行约束，而不是在 playground、world-one、query case、aidec 中各写一套  

---

## 1. 文档目的

本文档只讨论用户输入无法直接归一化为 `external entity change` 时的处理方式。

也就是三种入口中的 **场景 3**：

- 输入是一段用户意图 / 问题 / 任务描述
- LLM 无法安全地把它转换成结构化 `external entity info`
- 因此不能直接走 `EntitirEvent -> trigger -> decision` 的标准事件入口
- 需要转而使用 world 内已有的 `decision`、`action`、`entities`、`VirtualSet collections` 做世界内探索与判定

本文档的核心结论是：

- 场景 3 必须只有 **一套共享机制**
- 这套机制的真实落点应在 `entitir/aip`
- playground 里的 `query case`、`aidec`、未来 world-one 的智能入口，都只能复用它，不能再复制一份 Phase 2B / Phase 3 逻辑

---

## 2. 设计结论

### 2.1 结论一：场景 3 不是 event ingestion，而是 world-aware exploration

场景 3 的输入不是一个已经成立的 entity fact。

因此它不是：

- `external entity change`
- `entity_change(target)`
- 某种隐式的 CRUD 事件包装

它的本质是：

- 基于 world 内容做语义理解
- 从 `decision` / `action` 中抽取 domain hint
- 再严格构造并执行 Outline / VirtualSet expression
- 最后产出结论、候选 decision、建议 action，或继续追问

换句话说：

- 场景 1 / 2 的目标是把输入送进 world 事件链
- 场景 3 的目标是先在 world 内“理解和探索”

### 2.2 结论二：场景 3 的共享实现必须位于 `entitir/aip`

当前 playground 中看起来像“前端/案例逻辑”的部分，真正核心能力已经在 `entitir/aip`：

- `AIPDecisionOrchestrator`
- `WorldSystemContext`
- `DecisionSearchTool`
- `GetTemplateDetailsTool`
- `GetMembersTool`
- `EvalExpressionTool`

因此未来的共享原则是：

- `entitir/aip` 定义场景 3 的唯一探索协议
- `playground/query`
- `playground/aidec`
- `world-one`
- 未来任何 executor / agent

都只能复用这套能力，而不是自己维护另一套 prompt / tool / eval workflow。

### 2.3 结论三：LLM 不能仅凭静态信息安全写出 VirtualSet 代码

这是本方案最重要的约束。

LLM 无法只依赖静态 schema 或成员表，就安全写出最终的 VirtualSet expression。

原因不是“它不够聪明”，而是语言与运行时模型决定了以下事实：

1. `VirtualSet` 表达式是链式的，链上每一步都会改变“下一步允许做什么”。
2. `filter / map / order_by / order_desc_by / take / edge navigation` 之后，后继可链接成员不是只由静态 entity schema 决定。
3. 尤其在 edge、集合/实体切换、terminal operator 之后，下一步能力必须依赖 **当前表达式在运行时推导出来的类型**。
4. 因此真正的“下一步能干什么”必须来自运行时 completion，而不是静态猜测。

所以场景 3 的严格规则必须是：

- 静态信息只负责 **发现候选世界知识**
- 运行时 `completion / can_chain` 才负责 **确认当前表达式后续合法操作**

### 2.4 结论四：`can_chain` 就是场景 3 的运行时真理

在场景 3 中，LLM 最终不是靠“记住 Outline 语法”来写代码，而是靠：

- `decision` 提供语义 hint
- `ontology_get_members` 提供结构信息
- `ontology_eval` 返回的 `can_chain` 提供当前链路的下一步可用操作

因此：

- `can_chain` 是运行时 completion 的 Host-facing 投影
- 它必须成为场景 3 唯一可信的“后继可链接成员列表”
- LLM 只能使用 `can_chain.members` 中已出现的操作继续构造表达式

---

## 3. 为什么静态信息不够

## 3.1 静态 schema 只能回答“某实体有哪些成员”

例如：

- `Employee` 有哪些 field
- `Employee` 有哪些 edge
- `Employee` 有哪些 action

这些信息来自：

- `ontology_get_members(entity_type)`
- discoverer / outline schema

这类信息只能告诉 LLM：

- 某个成员在世界里存在
- 它大致属于 field / edge / action 哪一类

但它不能单独保证以下问题：

- 当前表达式此时还是 `LazySet<Employee>` 还是已经变成单个 `Employee`
- 此时 `.first()` 合不合法
- 某个 edge 返回单体还是集合
- 某次 `map(...)` 之后还能不能再 `.count()` / `.to_list()`
- 某次 terminal operator 之后链路是否已经结束

## 3.2 `filter` 之后最关键的问题不是“原始 schema 有什么”，而是“当前链路上还能做什么”

示例：

```text
employees.filter(e -> e.status == "active")
```

这时下一步能否：

- `.first()`
- `.department()`
- `.order_desc_by(...)`
- `.count()`
- `.map(...)`

看起来都与 `Employee` 相关，但真正合法的集合级操作必须从 **当前表达式结果类型** 推出。

这不是单个 entity schema 能完整决定的。

## 3.3 edge navigation 会让“静态可见成员”与“当前可链成员”发生分离

例如：

```text
employees.filter(e -> e.status == "active").department()
```

此时表达式已经不再处于 `Employee` 集合语境，而是进入 `Department` 侧。

接下来允许做什么，必须看：

- edge 的实际返回类型
- 当前结果是集合还是单体
- 运行时 completion 暴露出的成员

如果 LLM 继续凭 `Employee` 的静态成员表往后写，就会写错。

## 3.4 terminal operator 之后链路终结，这是纯运行时约束

例如：

```text
employees.filter(e -> e.status == "active").count()
```

此时结果已经是标量。

后面不能再继续：

- `.department()`
- `.to_list()`
- `.order_by(...)`

这种“链路已经终结”的约束，不是 entity schema 能表达的，而是 `ontology_eval` 的实际结果类型决定的。

## 3.5 结论

因此场景 3 的正确模型不是：

`静态 schema -> 一次性写完整表达式`

而必须是：

`静态发现 -> 探索性 eval -> 读取 can_chain -> 再构造下一步 -> 最终 eval`

---

## 4. 当前可复用实现

当前场景 3 的核心能力已分布在 `entitir/aip`，主要包括：

### 4.1 `DecisionSearchTool`

作用：

- 对 `DecisionTemplate` 做 BM25 搜索
- 在无直接匹配时返回 `skill_hints`
- `skill_hints` 中包含：
  - `exploration_expressions`
  - `available_actions`
  - `trigger_description`

语义：

- decision 不只是可执行模板
- decision 同时也是世界内探索的语义起点

### 4.2 `GetTemplateDetailsTool`

作用：

- 返回 decision 的 full spec：
  - trigger
  - hint
  - actions
  - `triggersCurrentlyFired`

语义：

- 让 LLM 能从决策模板里提取 expert knowledge
- 即使不直接执行 decision，也可以把其中 hint 用作探索表达式的起点

### 4.3 `WorldSystemContext`

作用：

- 定义 world-aware agent 的基础 prompt
- 明确三阶段 / 多阶段 discovery 规则
- 明确零幻觉和工具优先原则

它是当前最接近“共享 exploration prompt 基座”的组件。

### 4.4 `GetMembersTool`

作用：

- 提供 entity 的 fields / edges / actions / collection usage 说明

但它的定位应明确为：

- **结构发现工具**
- 不是最终链式合法性的判定器

### 4.5 `EvalExpressionTool`

这是场景 3 最关键的共享组件。

它不仅执行表达式，还承担：

- leading collection variable 的预校验
- collection / scalar / entity / dict 的结果分型
- incomplete expression 的自动修复提示
- field-on-collection / collection-method-on-entity 等错误修复
- `can_chain` 输出

因此它不是普通执行器，而是：

- 执行器
- 运行时类型检查器
- 运行时 completion provider
- repair hint provider

---

## 5. 统一共享流程

场景 3 的标准流程应统一为如下四段：

```text
user intent
  -> decision semantic harvest
  -> ontology structural discovery
  -> exploration eval + can_chain
  -> final eval
  -> answer / candidate decision / action proposal
```

## 5.1 Phase A：Decision Semantic Harvest

目标：

- 从 `decision` / `action` 中提取世界运行 hint

输入工具：

- `decision_search`
- `decision_get_template` / `decision_describe`

输出语义：

- 哪些 entity / collection 可能相关
- 哪些 trigger expression 已编码了领域专家判断逻辑
- 哪些 action 暗示了这个世界能做什么
- 哪些 hint expression 可以作为探索起点

规则：

- 即使没有完全命中的 decision，也要吸收 `skill_hints`
- `exploration_expressions` 是探索起点，不是可盲目直接执行的最终表达式

## 5.2 Phase B：Ontology Structural Discovery

目标：

- 把语义 hint 变成明确可探索的 world 结构

输入工具：

- `ontology_get_variables`
- `ontology_search`
- `ontology_get_members`

输出语义：

- 当前 world 中真实存在的 collection variable
- 相关 entity 的 fields / edges / actions
- 当前问题可从哪个 collection 进入

规则：

- collection variable 必须来自 world 的真实注册表
- 不能从 entity 名字自动复数化猜测 collection 名
- `ontology_get_members` 只负责告诉 LLM 某类型有哪些结构成员
- 它不能替代运行时 chain validation

## 5.3 Phase C：Exploration Eval

目标：

- 先构造一个“探索性表达式”，拿到运行时 `can_chain`

输入工具：

- `ontology_eval`

探索性表达式约束：

- 不追求一步到位
- 优先构造 base filter / base traversal
- 先拿到当前链路的 `can_chain`

输出：

- 当前结果类型
- `can_chain.members`
- `entity_fields`
- runtime repair hint

规则：

- LLM 必须读取 `can_chain.members`
- 后续只允许用其中已暴露的 operation 继续链
- 不允许凭静态记忆新增未出现的成员名

## 5.4 Phase D：Final Eval

目标：

- 在 `can_chain` 约束下构造最终表达式并执行

规则：

- 只用已确认的 collection variable
- 只用已确认的 edge / field / action / builtin
- 若结果为 scalar，则链路终止
- 若结果为 list/entity/dict，则直接用于回答
- 若报错且带 `fix`，只能按 `fix` 做一次受控重试

---

## 6. 共享约束

## 6.1 单一 prompt 基座

场景 3 未来只能有一个共享 exploration prompt 基座。

推荐以：

- `WorldSystemContext`

为基础，再为特定使用者追加少量补充说明，而不是每个入口都重写一整套 Phase 3 规则。

## 6.2 单一 runtime completion 真理

场景 3 唯一可信的“下一步可链接成员列表”只能来自：

- `EvalExpressionTool.can_chain`

而不能来自：

- 手写 prompt 中的成员猜测
- 前端本地 mock completion
- entity schema 的静态字段表
- LLM 的记忆

## 6.3 单一 tool contract

以下 tool contract 必须被共享：

- `decision_search`
- `decision_get_template` / `decision_describe`
- `ontology_get_variables`
- `ontology_search`
- `ontology_get_members`
- `ontology_eval`

这些 contract 只能在 `entitir/aip` 里进化，不应在 playground 或 world-one 中派生一份“同名不同义”的版本。

## 6.4 单一 collection variable 真理

collection variable 的来源必须统一为：

- world 的 live virtual set registry

不应依赖：

- 对 entity type 的英文复数推导
- UI 层推测
- prompt 层约定俗成

这是为了避免出现：

- query case 一套 collection 名来源
- aidec 一套 collection 名来源
- world-one 一套 collection 名来源

---

## 7. 对现有代码的收口要求

## 7.1 `query case` 应视为共享能力的一个调用方

`playground/query` 当前已经相对接近目标形态：

- 使用 `WorldSystemContext`
- 使用 `ontology_get_variables`
- 使用 `ontology_get_members`
- 使用 `ontology_eval`

因此它不应继续演化为独立体系，而应回收为共享能力的演示入口。

## 7.2 `aidec / decisionAsk` 的 Phase 2B 必须收口到共享 exploration policy

`AIPDecisionOrchestrator` 当前已经具备：

- decision-first
- no-match -> Phase 2B
- phase 2B -> ontology exploration

但它仍维护了自己的一整套 Phase 2B prompt 规则。

后续应将这部分收口为：

- 共享 exploration prompt fragment
- 共享 tool set
- 共享 eval budget / fix policy

而不是继续独立生长。

## 7.3 `GetVirtualSetsTool` 应成为场景 3 的标准组成

当前如果某些入口仍未统一使用真实 virtual set registry，就存在漂移风险。

因此场景 3 的标准 tool set 应显式包含：

- `GetVirtualSetsTool`

它不是 query case 专属工具，而是场景 3 的基础设施。

---

## 8. 输出边界

场景 3 的输出通常应是以下几类之一：

- `answer`
- `query result`
- `candidate decision`
- `recommended actions`
- `need more info`

默认不应直接伪造一个 `external entity change` 写回 world。

只有当上层产品明确要求，并且人工确认后，才可以把场景 3 的结论再转换为后续结构化动作。

---

## 9. 非目标

本文档不定义：

1. 具体 UI 形态（chat、task panel、canvas、inline widget）
2. 具体 executor 如何消费场景 3 输出
3. LLM 厂商、模型与 temperature 选择
4. 具体 `chartSpec` / 报表格式
5. 具体 world-one 页面如何触发场景 3

---

## 10. 最小落地顺序

### Phase 1：冻结共享原则

1. 确认场景 3 唯一实现归属 `entitir/aip`
2. 确认 `can_chain` 是运行时 completion 唯一真理
3. 确认 collection variable 必须来自 live registry

### Phase 2：收口 prompt 与 tool set

1. 提取共享 exploration prompt fragment
2. 让 `query case` 与 `AIPDecisionOrchestrator` 共同复用
3. 把 `GetVirtualSetsTool` 纳入统一标准 tool set

### Phase 3：收口调用方

1. playground/query 改为明确复用共享 exploration policy
2. aidec / `decisionAsk` 的 Phase 2B 改为明确复用共享 exploration policy
3. world-one 若接入场景 3，也只能调用同一套能力

---

## 11. 一句话总结

场景 3 的本质不是“让 LLM 自由写 Outline 代码”，而是：

**先用 decision/action 提供语义起点，再用 runtime completion (`can_chain`) 严格约束 VirtualSet expression 的逐步生成与执行。**

因此：

- 静态 schema 不是最终真理
- runtime completion 才是最终真理
- `entitir/aip` 必须成为这套能力的唯一共享实现
