# memory-one Widgets

## 1) memory-one 自有 widget

`memory-one` 当前只注册一个主 widget：

- `memory-manager`（`is_main=true`, `is_canvas_mode=true`）
- `renders_output_of_skill`: `memory_view`
- 作用：记忆浏览、编辑、删除、提升、关系图谱查看

该 widget 的声明入口：

- `/api/widgets` → `MemoryWidgetsController`
- skill 入口：`memory_view`（`/api/skills`）

## 2) 视图与刷新协议

`memory-manager` 已声明：

- `views`（ALL / SEMANTIC / EPISODIC / PROCEDURAL / GOAL / RELATION）
- `refresh_skill = memory_view`
- `mutating_tools`（create/update/delete/promote 等）

Host 会基于当前 view 注入 `llm_hint`，并在必要时兜底刷新。

## 3) 使用的系统级 widget（由 world-one 提供）

`memory-one` 不注册 `sys.*`，但可以在 tool response 中直接引用：

- `sys.confirm`（已用于 `memory_delete_request`）
- `sys.selection`（推荐用于多候选记忆消歧；`sys.choice` 为兼容别名）

示例场景：

- 用户说“删掉那条关于 John 的记忆”，命中多条候选  
  → 返回 `sys.selection` 让用户选具体条目  
  → 用户确认后再调用 `memory_delete_confirmed`。
