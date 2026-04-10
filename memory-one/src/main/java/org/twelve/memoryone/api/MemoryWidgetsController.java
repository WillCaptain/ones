package org.twelve.memoryone.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * GET /api/widgets — 公布 memory-one 的 Widget（memory-manager）。
 */
@RestController
@RequestMapping("/api")
public class MemoryWidgetsController {

    @GetMapping("/widgets")
    public Map<String, Object> widgets() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app",     "memory-one");
        result.put("version", "1.0");
        result.put("widgets", List.of(buildMemoryManagerWidget()));
        return result;
    }

    private Map<String, Object> buildMemoryManagerWidget() {
        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("type",        "memory-manager");
        widget.put("description", "Memory 管理面板：查看、编辑、删除、提升 Agent 的所有记忆。");
        widget.put("source",      "external");
        widget.put("app_id",      "memory-one");
        widget.put("is_main",     true);
        widget.put("is_canvas_mode", true);

        widget.put("renders_output_of_skill", "memory_view");
        widget.put("welcome_message", "记忆管理面板已打开。你可以查看所有记忆，也可以直接告诉我修改或删除某条。");

        widget.put("internal_tools", List.of(
            "memory_query", "memory_create", "memory_update",
            "memory_supersede", "memory_delete", "memory_promote",
            "memory_set_instruction", "memory_load"
        ));

        widget.put("canvas_skill", Map.of(
            "tools",  buildCanvasTools(),
            "prompt", """
                ## Memory 管理 Canvas 模式
                当前处于 Memory 管理 Canvas 中。你可以帮用户查询、创建、修改、删除、提升 memory。
                - 用 memory_query 查询（支持关键词、类型、scope 过滤）
                - 用 memory_update 修改 content/importance/tags
                - 用 memory_supersede 用新内容取代旧 memory
                - 用 memory_delete 软删除
                - 用 memory_promote 把 SESSION memory 提升为 GLOBAL
                - 用 memory_create 手工添加新 memory
                每次操作后简洁说明结果即可，不要列出完整 JSON。
                """
        ));

        widget.put("context_prompt", """
            ## 当前 Canvas：Memory 管理
            用户正在查看和管理 Agent 的 Memory。
            你可以使用 memory_query/memory_update/memory_delete/memory_promote/memory_create 工具操作 memory。
            优先用工具完成操作，操作后用 1-2 句话确认结果。
            """);

        // AIPP Widget 能力声明 — AippWidgetSpec 验证此字段
        widget.put("supports", Map.of(
            "disable", true,
            "theme",   List.of("background", "surface", "text", "textDim",
                               "border", "accent", "font", "fontSize", "radius", "language")
        ));

        // ── View 协议声明 ──────────────────────────────────────────────────────
        // 每个视图对应 Memory Manager 中的一个 Tab；llm_hint 由 Host 注入 LLM 上下文。
        // {refresh_skill} 占位符由 AppRegistry.buildUiHints() 在运行时替换为 "memory_view"。
        widget.put("views", List.of(
            Map.of("id", "ALL",        "label", "全部记忆",
                   "llm_hint", "用户正在查看所有类型的记忆列表。如修改了任何记忆，操作后请调用 {refresh_skill} 刷新展示。"),
            Map.of("id", "SEMANTIC",   "label", "事实",
                   "llm_hint", "用户正在查看事实（Semantic）类记忆列表。"),
            Map.of("id", "EPISODIC",   "label", "事件",
                   "llm_hint", "用户正在查看事件（Episodic）记录列表。"),
            Map.of("id", "PROCEDURAL", "label", "约定",
                   "llm_hint", "用户正在查看约定/规则（Procedural）类记忆列表。"),
            Map.of("id", "GOAL",       "label", "目标",
                   "llm_hint", "用户正在查看目标（Goal）类记忆列表。"),
            Map.of("id", "RELATION",   "label", "关系图谱",
                   "llm_hint", "用户正在查看实体关系图谱（Relation Graph）。" +
                               "若用户要求合并实体，请创建 IS_SAME_AS 谓词的 RELATION 类记忆；" +
                               "所有变更完成后必须调用 {refresh_skill} 刷新图谱展示。")
        ));
        widget.put("refresh_skill", "memory_view");
        widget.put("mutating_tools", List.of(
            "memory_create", "memory_update", "memory_delete",
            "memory_supersede", "memory_promote"
        ));

        return widget;
    }

    private List<Map<String, Object>> buildCanvasTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(buildTool("memory_query",
            "查询 memories，支持关键词、类型、scope 过滤",
            Map.of(
                "session_id",     Map.of("type", "string", "description", "过滤指定 session 的 memories"),
                "keyword",        Map.of("type", "string", "description", "全文关键词搜索"),
                "type",           Map.of("type", "string", "enum", List.of("SEMANTIC","EPISODIC","RELATION","PROCEDURAL","GOAL")),
                "scope",          Map.of("type", "string", "enum", List.of("GLOBAL","WORKSPACE","SESSION")),
                "min_importance", Map.of("type", "number", "description", "最低 importance 阈值"),
                "limit",          Map.of("type", "integer", "description", "返回数量上限，默认 50")
            ), List.of()));

        tools.add(buildTool("memory_create",
            "手工创建一条新 memory。RELATION 类型必须提供 subject_entity/predicate/object_entity 三元组",
            Map.of(
                "content",        Map.of("type", "string", "description", "记忆内容（自然语言）"),
                "type",           Map.of("type", "string", "enum", List.of("SEMANTIC","EPISODIC","RELATION","PROCEDURAL","GOAL")),
                "scope",          Map.of("type", "string", "enum", List.of("GLOBAL","WORKSPACE","SESSION")),
                "importance",     Map.of("type", "number", "description", "重要度 0-1，默认 0.7"),
                "tags",           Map.of("type", "array", "items", Map.of("type", "string")),
                "subject_entity", Map.of("type", "string", "description", "(RELATION 类型) 三元组主语实体名，如 'Alice'"),
                "predicate",      Map.of("type", "string", "description", "(RELATION 类型) 关系谓语，如 'is_manager_of'"),
                "object_entity",  Map.of("type", "string", "description", "(RELATION 类型) 三元组客语实体名，如 'Bob'")
            ), List.of("content")));

        tools.add(buildTool("memory_update",
            "修改 memory 的 content、importance、tags 或 RELATION 三元组字段（用于纠正幻觉）",
            Map.of(
                "id",             Map.of("type", "string", "description", "Memory UUID"),
                "content",        Map.of("type", "string"),
                "importance",     Map.of("type", "number"),
                "tags",           Map.of("type", "array", "items", Map.of("type", "string")),
                "subject_entity", Map.of("type", "string", "description", "(RELATION) 纠正主语实体"),
                "predicate",      Map.of("type", "string", "description", "(RELATION) 纠正关系谓语"),
                "object_entity",  Map.of("type", "string", "description", "(RELATION) 纠正客语实体")
            ), List.of("id")));

        tools.add(buildTool("memory_supersede",
            "用新内容取代旧 memory",
            Map.of(
                "old_id",      Map.of("type", "string", "description", "被取代的 Memory UUID"),
                "new_content", Map.of("type", "string", "description", "新内容")
            ), List.of("old_id", "new_content")));

        tools.add(buildTool("memory_delete",
            "软删除一条 memory",
            Map.of("id", Map.of("type", "string", "description", "Memory UUID")),
            List.of("id")));

        tools.add(buildTool("memory_promote",
            "提升 memory 的 scope（SESSION → WORKSPACE → GLOBAL）",
            Map.of(
                "id",        Map.of("type", "string", "description", "Memory UUID"),
                "new_scope", Map.of("type", "string", "enum", List.of("WORKSPACE", "GLOBAL"))
            ), List.of("id", "new_scope")));

        tools.add(buildTool("memory_set_instruction",
            "记录用户的记忆指令，下一轮 Memory Agent 将按此调整记忆策略",
            Map.of(
                "content",    Map.of("type", "string", "description", "指令内容"),
                "scope",      Map.of("type", "string", "enum", List.of("GLOBAL", "SESSION")),
                "session_id", Map.of("type", "string", "description", "scope=SESSION 时绑定")
            ), List.of("content")));

        return tools;
    }

    private Map<String, Object> buildTool(String name, String desc,
                                           Map<String, Object> props,
                                           List<String> required) {
        return Map.of(
            "name",        name,
            "description", desc,
            "parameters",  Map.of("type", "object", "properties", props, "required", required)
        );
    }
}
