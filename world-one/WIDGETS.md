# world-one Widgets（系统级）

`world-one` 是 AIPP Host，本身不只是一个普通 app。  
其中 `sys.*` widget 属于**系统级内置能力**，语义上等同于操作系统内置程序（类似 Windows 的系统 exe）。

## 1) 系统级 widget（Host 内置，所有 app 可用）

这些 widget 由 `world-one` 前端直接实现，外部 app **不需要也不能**在 `/api/widgets` 中注册：

- `sys.confirm`：确认框（危险操作/授权）
- `sys.alert`：提示框（通知）
- `sys.prompt`：输入框（用户补参）
- `sys.selection`：选项选择（推荐）
- `sys.choice`：`sys.selection` 的兼容别名
- `sys.progress`：执行进度（tool-only 事件默认展示）

关键约束：

- `sys.*` 由 Host 渲染，不进入 app 的 widget 注册清单
- AIPP app 的自定义 widget 禁止使用 `sys.` 前缀

## 2) world-one-system 内置 app widget/skill

`world-one` 还注册了一个内置 app：`worldone-system`，当前用于系统导航能力：

- skill：`app_list_view`
- 渲染：`html_widget`（应用列表卡片）
- 用途：列出已注册 app，并支持打开 app 主入口

## 3) 选择组件推荐用法

在存在歧义时（多 world 命中、多 app 路由冲突），推荐返回：

```json
{
  "canvas": {
    "action": "open",
    "widget_type": "sys.selection",
    "data": {
      "title": "请选择目标世界",
      "message": "检测到多个可能匹配的世界：",
      "options": [
        { "label": "HR-Production", "tool": "world_design", "args": { "session_id": "w-hr-1" } },
        { "label": "HR-Staging",    "tool": "world_design", "args": { "session_id": "w-hr-2" } },
        { "label": "取消",           "message": "已取消本次操作" }
      ]
    }
  }
}
```
