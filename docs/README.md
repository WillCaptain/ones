# ones 文档索引

> ones = AIPP 平台（Agent OS）：world-one（uni-bot）+ memory-one + 未来更多 AIPP 应用

---

## 平台核心

| 文档 | 说明 |
|------|------|
| [interaction-model.md](./interaction-model.md) | **⭐ 核心**：6 种交互模式、Session 模型、Task/Event/Chat 区别、导航栈规则、sys.* 系统 Widget |
| [aipp-protocol.md](./aipp-protocol.md) | AI-Native App Protocol：AIPP 核心角色定义、三层 Skill 规范、Widget Manifest |
| [widget-protocol.md](./widget-protocol.md) | Widget 协议：canvas 指令格式、open/patch/replace/close/inline 动作详解 |
| [aipp-integration-guide.md](./aipp-integration-guide.md) | AIPP 接入指南：如何开发一个符合协议的 AIPP 应用 |

## World One（uni-bot）

| 文档 | 说明 |
|------|------|
| [world-one-design.md](./world-one-design.md) | World One 应用层设计：布局、Session 面板、Chat/Canvas 模式切换 |
| [world-one-skills.md](./world-one-skills.md) | World One Skill 层：AIP 层逻辑、阶段状态机、工具集 |
| [../world-one/WIDGETS.md](../world-one/WIDGETS.md) | World One 系统级 Widget：`sys.*` 内置能力与使用约定 |
| [world-event-executor-e2e.md](./world-event-executor-e2e.md) | World 运行时端到端设计：事件链路、开放式 executor 协议、resilient action widget 配置 |
| [world-executor-protocol.md](./world-executor-protocol.md) | Executor 协议细化：注册、发现、匹配、事件交接、approval / arg flow JSON 契约 |
| [world-executor-manual-test.md](./world-executor-manual-test.md) | Executor 链路手工测试：approval、arg widget、resume action、terminal flowback 校验清单 |
| [world-runtime-event-protocol.md](./world-runtime-event-protocol.md) | Runtime Event 协议：统一事件 envelope、列表查询、SSE 流、cursor 与五类 payload |
| [shared-autonomous-ontology-exploration.md](./shared-autonomous-ontology-exploration.md) | 场景 3 共享探索机制：decision/action hint、runtime completion、VirtualSet expression 严格构造与复用边界 |

## Memory One

| 文档 | 说明 |
|------|------|
| [memory-one/docs/memory-design.md](../memory-one/docs/memory-design.md) | 记忆设计：记忆模型、加载策略、整合算法 |
| [memory-one/docs/MANUAL-TEST-GUIDE.md](../memory-one/docs/MANUAL-TEST-GUIDE.md) | 手动测试指南 |
| [../memory-one/WIDGETS.md](../memory-one/WIDGETS.md) | memory-one Widget 清单：`memory-manager` 与系统级 `sys.*` 用法 |

---

## 依赖关系说明

```
ones（本仓库）→ entitir（本体引擎）→ shared（零依赖协议包）

ones:     world-one, memory-one
entitir:  ontology, aip, world-entitir（entitir 专属 AIPP）
shared:   aipp-protocol（AippAppSpec, AippSystemWidget, AippEvent 等）
```

**注意**：`entitir/spec/event.md` 描述的是**本体引擎的事件总线**（EntitirEventBus），
与本文档中的 AIPP Event（外部推送事件）是完全不同的概念，请勿混淆。
