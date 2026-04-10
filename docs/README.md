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

## Memory One

| 文档 | 说明 |
|------|------|
| [memory-one/docs/memory-design.md](../memory-one/docs/memory-design.md) | 记忆设计：记忆模型、加载策略、整合算法 |
| [memory-one/docs/MANUAL-TEST-GUIDE.md](../memory-one/docs/MANUAL-TEST-GUIDE.md) | 手动测试指南 |

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
