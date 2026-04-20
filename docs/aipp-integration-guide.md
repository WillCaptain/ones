# AIPP 开发者集成指南

> 面向受众：想把自己的应用接入 World One（或任意 AIPP Agent）的开发者  
> 假设你已有一个可以运行的服务，想让 AI Agent 发现并调用它

---

## 零、全局视图

```
你的应用（AIPP App）              World One（AIPP Agent）
─────────────────────────        ─────────────────────────
GET  /api/tools         ←─────── 启动时拉取，构建 LLM 工具列表（权威 Tool 清单）
GET  /api/skills        ←─────── 启动时拉取，Skill Playbook 索引（可为空）
GET  /api/widgets       ←─────── 启动时拉取，构建 Widget 注册表
POST /api/tools/{name}  ←─────── LLM 决策后路由调用
                                           ↕
                                    前端 Widget（内置渲染）
                                           ↕
                                         用户
```

你需要做的事情只有 **3 步**：

1. 实现 `GET /api/tools` — 告诉 AI / UI / Host 你能做什么（带 `visibility`+`scope`）
2. 实现 `GET /api/widgets` — 告诉 AI 如何显示结果
3. 实现 `POST /api/tools/{name}` — 真正执行工具

（可选）当你有多步事务型能力时，再实现 `GET /api/skills` + `GET /api/skills/{id}/playbook` 发布 Skill Playbook。

然后通过 **Registry** 注册，World One 启动后自动发现你的应用。

---

## 第一步：实现 `GET /api/tools`

这是 AI Agent / UI / Host 的"说明书"。返回你的应用能做什么；每个 Tool 条目带 `visibility`（`llm` / `ui` / `host`）和 `scope`（`universal` / `app` / `widget` / `view`）。

### 最小合规示例（Spring Boot）

```java
@GetMapping("/api/tools")
public Map<String, Object> tools() {
    return Map.of(
        "app",     "my-app",
        "version", "1.0",
        "tools",   List.of(buildMyTool())
    );
}

private Map<String, Object> buildMyTool() {
    Map<String, Object> skill = new LinkedHashMap<>();

    // ── Layer 1：OpenAI 兼容（必须）─────────────────────────
    skill.put("name",        "my_search");
    skill.put("description", "在我的数据库中搜索内容，返回匹配列表");
    skill.put("parameters",  Map.of(
        "type",       "object",
        "properties", Map.of(
            "keyword", Map.of("type", "string", "description", "搜索关键词")
        ),
        "required",   List.of("keyword")
    ));

    // ── Layer 2：mini-agent 自述（必须）──────────────────────
    skill.put("prompt", "调用 my_search_tool 工具，传入 keyword 参数，返回搜索结果列表。");
    skill.put("tools",  List.of("my_search_tool"));

    // ── Layer 3：canvas 触发声明（必须）──────────────────────
    skill.put("canvas", Map.of(
        "triggers",    false    // 此 Skill 不进入 canvas，纯对话回复
    ));

    return skill;
}
```

### 如果你的 Skill 需要打开 Canvas

```java
skill.put("canvas", Map.of(
    "triggers",    true,
    "widget_type", "my-widget"   // 必须与 /api/widgets 中的 type 匹配
));

// 可选：声明 session 管理语义
skill.put("session", Map.of(
    "creates_on", "name",        // 传 name 参数时新建 session
    "loads_on",   "session_id"   // 传 session_id 时恢复 session
));
```

### Layer 2 为什么重要？

`prompt` + `tools` 字段是 `AippAppSpec` 验证的强制要求。即使当前没有运行时语义，
也必须填写——这是 AIPP 的自描述完整性契约，方便：
- 开发者理解 Skill 的编排意图
- 测试用例验证合规性
- 未来支持 mini-agent 本地执行

---

## 第二步：实现 `GET /api/widgets`

如果你的 Skill 触发 canvas（`canvas.triggers: true`），就需要声明 Widget。

### 最小合规示例

```java
@GetMapping("/api/widgets")
public Map<String, Object> widgets() {
    return Map.of(
        "app",     "my-app",
        "version", "1.0",
        "widgets", List.of(buildMyWidget())
    );
}

private Map<String, Object> buildMyWidget() {
    Map<String, Object> w = new LinkedHashMap<>();

    // 基础字段（必须）
    w.put("type",        "my-widget");
    w.put("source",      "builtin");          // builtin / url / iframe
    w.put("description", "我的数据展示组件：显示搜索结果列表");

    // 触发此 Widget 的 Skill（推荐）
    w.put("renders_output_of_skill", "my_search");

    // LLM 进入 canvas 时的领域知识（推荐）
    w.put("context_prompt",  "当前在数据搜索 Canvas 中。用 my_search_tool 搜索，my_delete_tool 删除。");
    w.put("welcome_message", "数据面板已打开，你可以直接告诉我搜索或操作什么。");

    return w;
}
```

> **不再在 widget manifest 里声明 `internal_tools` 和 `canvas_skill`**：自 Phase 3 起，widget 级 tool 的 `visibility`（`ui` / `llm`）与 `scope.visible_when`（`canvas_open` / `always`）由 `/api/tools` 的 tool 条目直接表达，Host 按此索引并注入到 LLM / ToolProxy。
```

### 声明 Disable & Theme 支持（推荐）

表明你的 Widget 遵守 AIPP disable 和 theme 契约，允许 world-one 统一管理：

```java
w.put("supports", Map.of(
    "disable", true,
    "theme",   List.of("background", "surface", "text", "textDim",
                       "border", "accent", "font", "fontSize", "radius", "language")
));
```

**Disable 契约**：当 world-one 把当前 session 标为只读（如 completed session），
所有变更类工具的响应必须是：

```json
{ "ok": false, "error": "widget_disabled" }
```

只读类工具（如 query）正常返回。

### 声明多视图上下文（如果 Widget 有多个 Tab）

这是最重要的新特性。让 LLM 知道用户当前在哪个视图，从而给出精确指令：

```java
w.put("views", List.of(
    Map.of("id", "list",   "label", "列表视图",
           "llm_hint", "用户在查看数据列表。修改数据后请调用 {refresh_skill} 刷新。"),
    Map.of("id", "detail", "label", "详情视图",
           "llm_hint", "用户在查看某条数据的详情。修改此条数据后请调用 {refresh_skill} 更新显示。"),
    Map.of("id", "chart",  "label", "图表视图",
           "llm_hint", "用户在查看统计图表。数据变更后必须调用 {refresh_skill} 刷新图表。")
));
w.put("refresh_skill", "my_search");
w.put("mutating_tools", List.of("my_create_tool", "my_update_tool", "my_delete_tool"));
```

`{refresh_skill}` 是占位符，world-one 会自动替换为实际 skill 名（此处为 `"my_search"`）。

---

## 第三步：实现 `POST /api/tools/{name}`

这是真正的执行层，world-one 把 LLM 的工具调用路由到这里。

```java
@PostMapping("/api/tools/{name}")
public Map<String, Object> executeTool(
        @PathVariable("name") String name,
        @RequestBody Map<String, Object> body) {

    @SuppressWarnings("unchecked")
    Map<String, Object> args    = (Map<String, Object>) body.get("args");
    @SuppressWarnings("unchecked")
    Map<String, Object> context = (Map<String, Object>) body.get("_context");

    // context 包含 world-one 自动注入的字段：
    // context.get("userId")         — 当前用户 ID
    // context.get("sessionId")      — agent session ID
    // context.get("workspaceId")    — canvas session/world ID（若在 canvas 中）
    // context.get("workspaceTitle") — canvas session 名称
    // context.get("agentId")        — "worldone"

    return switch (name) {
        case "my_search_tool"  -> doSearch(args, context);
        case "my_update_tool"  -> doUpdate(args, context);
        case "my_delete_tool"  -> doDelete(args, context);
        default -> Map.of("ok", false, "error", "unknown_tool: " + name);
    };
}
```

### 如果工具需要触发 canvas 更新

在响应中携带 `canvas` 字段，world-one 会自动路由到前端 Widget 渲染：

```java
private Map<String, Object> doSearch(Map<String, Object> args, Map<String, Object> context) {
    List<MyItem> items = searchService.search((String) args.get("keyword"));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok",     true);
    result.put("count",  items.size());
    result.put("canvas", Map.of(
        "action",      "open",          // open / patch / replace / close
        "widget_type", "my-widget",
        "session_id",  context.get("workspaceId"),
        "data",        Map.of("items", items)
    ));
    return result;
}
```

### Disable 检查

如果你声明了 `supports.disable: true`，变更类工具必须响应 disable 状态：

```java
private Map<String, Object> doDelete(Map<String, Object> args, Map<String, Object> context) {
    // 检查是否处于 disabled 状态（world-one 当前通过 session 状态管理，
    // 你可以在 context 中传递或通过 session 状态查询）
    if (isWidgetDisabled(context)) {
        return Map.of("ok", false, "error", "widget_disabled");
    }
    // ... 正常执行
}
```

---

## 第四步：前端 Widget 集成（如果有自定义 UI）

如果你的 Widget 需要在 world-one 前端渲染自定义 UI，需要实现以下规范。

### 视图上报

当用户切换 Tab 或视图时，调用 world-one 注入的全局函数：

```javascript
// world-one 在页面初始化时注入 aippReportView()
// 你的 Widget 直接调用即可

function onMyTabChange(viewId) {
    // viewId 必须与 /api/widgets 的 views[].id 对应
    aippReportView('my-widget', viewId);
}
```

### 主题变量

使用 CSS 变量而不是硬编码颜色，world-one 会注入 `--aipp-*` 变量：

```css
.my-widget-container {
    background-color: var(--aipp-bg,      #0a0b10);
    color:            var(--aipp-text,    #d0d8f0);
    border-color:     var(--aipp-border,  #272b3e);
    accent-color:     var(--aipp-accent,  #7c6ff7);
    font-family:      var(--aipp-font,    system-ui);
    font-size:        var(--aipp-font-size, 13px);
    border-radius:    var(--aipp-radius,  8px);
}
```

### Disable 状态

监听 `data-aipp-disabled` 属性变化（world-one 在只读 session 中会设置此属性）：

```javascript
const container = document.getElementById('my-widget');
const observer = new MutationObserver(() => {
    const disabled = container.dataset.aippDisabled === 'true';
    // 禁用/恢复所有增删改操作
    setWidgetInteractive(!disabled);
});
observer.observe(container, { attributes: true, attributeFilter: ['data-aipp-disabled'] });
```

---

## 第五步：注册到 Registry

### 方法 A：写入 manifest.json（推荐，持久化）

在 `~/.ones/apps/{app-id}/manifest.json` 写入：

```json
{
  "id":   "my-app",
  "name": "My Application",
  "api": {
    "base_url": "http://localhost:9000"
  }
}
```

World One 启动时自动发现并加载（调用你的 `/api/tools`、`/api/skills`、`/api/widgets`）。

### 方法 B：动态注册（开发时便捷）

```bash
curl -X POST http://localhost:8090/api/registry/install \
  -H "Content-Type: application/json" \
  -d '{"app_id": "my-app", "base_url": "http://localhost:9000"}'
```

响应：`{"message": "App my-app installed successfully"}`

### 验证注册结果

```bash
curl http://localhost:8090/api/registry
# → { "apps": [{"id": "my-app", "baseUrl": "...", ...}, ...] }
```

---

## 第六步：编写合规测试

建议在你的应用中编写 AIPP 合规测试，确保协议正确实现。

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MyAppAippComplianceTest {

    @LocalServerPort int port;

    RestTemplate rest = new RestTemplate();
    ObjectMapper  json = new ObjectMapper();
    AippAppSpec    appSpec    = new AippAppSpec();
    AippWidgetSpec widgetSpec = new AippWidgetSpec();

    @Test
    void tools_api_is_compliant() throws Exception {
        JsonNode tools = json.readTree(
            rest.getForString("http://localhost:" + port + "/api/tools"));

        // Tool 三层结构合规（visibility / scope + OpenAI function schema + canvas 声明）
        appSpec.assertValidToolsApiStructure(tools);
    }

    @Test
    void skills_api_is_compliant() throws Exception {
        JsonNode skills = json.readTree(
            rest.getForString("http://localhost:" + port + "/api/skills"));

        // Skill Playbook 索引形状合规（skills 可为空数组）
        appSpec.assertValidSkillsApiStructure(skills);
    }

    @Test
    void widgets_api_is_compliant() throws Exception {
        JsonNode widgets = json.readTree(
            rest.getForString("http://localhost:" + port + "/api/widgets"));

        appSpec.assertValidWidgetsApiStructure(widgets);

        JsonNode myWidget = widgetSpec.findWidget(widgets, "my-widget");

        // Disable + Theme 契约
        widgetSpec.assertWidgetSupportsDisable(myWidget);
        widgetSpec.assertWidgetThemeCoversProperties(myWidget,
            "background", "surface", "text", "accent", "language");

        // View 协议
        widgetSpec.assertWidgetDeclaresViews(myWidget);
        widgetSpec.assertWidgetDeclaresRefreshSkill(myWidget);
        widgetSpec.assertWidgetDeclareMutatingTools(myWidget);
        widgetSpec.assertWidgetHasViews(myWidget, "list", "detail");
    }

    @Test
    void mutating_tool_is_blocked_when_disabled() throws Exception {
        // 模拟 disabled 状态下调用变更工具
        ResponseEntity<String> resp = rest.postForEntity(
            "http://localhost:" + port + "/api/tools/my_delete_tool",
            Map.of("args", Map.of("id", "test"),
                   "_context", Map.of("disabled", true)),
            String.class);
        JsonNode body = json.readTree(resp.getBody());
        widgetSpec.assertMutatingToolBlockedWhenDisabled("my_delete_tool", body);
    }
}
```

---

## 附录 A：完整 Widget Manifest 字段参考

| 字段 | 类型 | 必选 | 说明 |
|------|------|------|------|
| `type` | string | ✅ | 全局唯一标识符，snake-case，如 `my-widget` |
| `source` | string | ✅ | `builtin` / `url` / `iframe` |
| `description` | string | ✅ | 人类可读描述；world-one 截取 `：` 前的内容作 session 名称 |
| `version` | string | — | 版本号 |
| `renders_output_of_skill` | string | 推荐 | 触发此 Widget 渲染的 Skill 名称 |
| `welcome_message` | string | 推荐 | 进入 canvas session 时的欢迎语 |
| `context_prompt` | string | 推荐 | canvas 激活时追加到 LLM system prompt 的领域上下文 |
| `supports.disable` | boolean | 推荐 | 声明支持 disable 契约 |
| `supports.theme` | string[] | 推荐 | 声明支持的主题 CSS 变量字段 |
| `views[].id` | string | — | 视图唯一标识，与前端 `aippReportView()` 对应 |
| `views[].label` | string | — | 视图标签（日志/调试用） |
| `views[].llm_hint` | string | — | 用户在此视图时注入 LLM 的上下文指令，支持 `{refresh_skill}` 占位符 |
| `refresh_skill` | string | — | 变更后用于刷新 Widget 数据的 Skill 名称 |
| `mutating_tools` | string[] | — | 会改变 Widget 数据的工具名列表（触发兜底刷新） |

---

## 附录 B：完整 Skill 字段参考

| 字段 | 层 | 必选 | 说明 |
|------|----|----- |------|
| `name` | L1 | ✅ | snake_case，全局唯一 |
| `description` | L1 | ✅ | 非空描述（LLM 据此决定是否调用） |
| `parameters` | L1 | ✅ | OpenAI object schema |
| `canvas` | L1 | ✅ | 含 `triggers` boolean；`triggers:true` 时需含 `widget_type` |
| `prompt` | L2 | ✅ | Skill 执行指令 |
| `tools` | L2 | ✅ | 依赖的原子工具列表（可为空数组 `[]`） |
| `resources` | L2 | — | 可读数据源列表 |
| `session` | L3 | — | 含 `creates_on` 和/或 `loads_on` |
| `inject_context` | L3 | — | `request_context: true` / `turn_messages: true` |
| `memory_hints` | L3 | — | Memory Agent 应关注的信息提示 |

---

## 附录 C：常见问题

**Q：我的应用不需要 canvas，也要实现 Widget Manifest 吗？**  
不需要。如果所有 Skill 的 `canvas.triggers` 都是 `false`，可以不实现 `/api/widgets`（返回空数组即可）。

**Q：widget UI 直接调用的工具 vs LLM 在 canvas 模式下调用的工具，怎么区分？**

由 `/api/tools` 的 tool 条目元字段决定：

| | `visibility=["ui"]` + `scope.visible_when="always"` | `visibility=["llm"]` + `scope.visible_when="canvas_open"` |
|---|---|---|
| 调用方 | Widget UI（Property Panel 直接操作） | LLM（用户自然语言触发） |
| 路径 | Widget → world-one ToolProxy → 你的应用 | LLM Tool Call → GenericAgentLoop → 你的应用 |
| 是否经 LLM | ❌ | ✅ |

两者都在 widget 级 scope（`scope.level="widget"` + `scope.owner_widget="my-widget"`）下。同一个 tool 也可以同时对 UI 与 LLM 暴露：`visibility=["llm","ui"]`。

**Q：`views` 是必须的吗？**  
不强制，但**强烈推荐**。如果你的 Widget 有多个 Tab 或视图切换，声明 `views` + 调用 `aippReportView()` 能让 LLM 在每次对话中拥有精确的 UI 上下文，避免 LLM 在错误的上下文下给出错误操作指令。

**Q：`{refresh_skill}` 占位符是什么意思？**  
在 `views[].llm_hint` 中，`{refresh_skill}` 会在运行时被 world-one 替换为 `refresh_skill` 字段的值。这样 hint 文本中不需要硬编码 skill 名称，方便复用。

**Q：我的工具 HTTP 端点是什么格式？**  
world-one 调用：`POST {base_url}/api/tools/{toolName}`  
工具名就是 Skill `name` 字段中声明的名称（如 `my_search_tool`）。

**Q：服务重启后 session 状态还在吗？**  
world-one 会在每次对话时通过 `_context.sessionId` / `_context.workspaceId` 传递 session 信息，你的应用自行决定如何持久化状态。

**Q：如何测试我的集成是否正确？**  
使用 `aipp-protocol` 模块提供的 `AippAppSpec` 和 `AippWidgetSpec` 编写合规测试（参见第六步），能在 CI 中自动验证协议合规性。

---

## 附录 D：最小可运行示例（Spring Boot）

```
my-aipp-app/
├── pom.xml                      (依赖 aipp-protocol)
├── src/main/java/
│   └── com/example/MyAippApp.java
│       ├── SkillsController.java  ← GET /api/tools + GET /api/skills
│       ├── WidgetsController.java ← GET /api/widgets
│       └── ToolsController.java   ← POST /api/tools/{name}
└── src/test/java/
    └── com/example/AippComplianceTest.java
```

`pom.xml` 依赖：

```xml
<dependency>
    <groupId>org.twelve.entitir</groupId>
    <artifactId>aipp-protocol</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

注册到 world-one：

```bash
# 方法 A：写 manifest（持久化）
mkdir -p ~/.ones/apps/my-app
echo '{"id":"my-app","api":{"base_url":"http://localhost:9000"}}' \
  > ~/.ones/apps/my-app/manifest.json

# 方法 B：动态注册（无需重启 world-one）
curl -X POST http://localhost:8090/api/registry/install \
  -H "Content-Type: application/json" \
  -d '{"app_id":"my-app","base_url":"http://localhost:9000"}'
```
