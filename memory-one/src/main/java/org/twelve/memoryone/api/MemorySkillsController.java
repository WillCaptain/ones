package org.twelve.memoryone.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * GET /api/skills — 公布 memory-one 对外暴露的 Skills。
 *
 * <h2>Skills</h2>
 * <ul>
 *   <li>{@code memory_load}          — 加载记忆上下文（inject_context.request_context=true）</li>
 *   <li>{@code memory_consolidate}   — 整合本轮对话（inject_context.turn_messages=true）</li>
 *   <li>{@code memory_view}          — 打开记忆管理面板（canvas.triggers=true）</li>
 *   <li>{@code memory_set_instruction} — 记录用户记忆指令</li>
 * </ul>
 *
 * <h2>inject_context 协议扩展</h2>
 * <p>worldone 读取每个 skill 的 {@code inject_context} 字段，在调用工具时自动注入：
 * <ul>
 *   <li>{@code request_context: true} → worldone 在请求体注入 {@code _context.userId/sessionId/userMessage}</li>
 *   <li>{@code turn_messages: true}   → worldone 额外注入 {@code turn_messages}（完整本轮消息列表）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class MemorySkillsController {

    @GetMapping("/skills")
    public Map<String, Object> skills() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app",           "memory-one");
        result.put("version",       "1.0");
        result.put("system_prompt", MEMORY_INTENT_PROMPT);
        result.put("skills",        buildSkillList());
        return result;
    }

    public static List<Map<String, Object>> buildSkillList() {
        return List.of(memoryLoadSkill(), memoryConsolidateSkill(),
                       memoryViewSkill(), memorySetInstructionSkill(),
                       memoryDeleteRequestSkill(),
                       memoryWorkspaceJoinSkill());
    }

    /**
     * memory_load：加载与当前对话相关的记忆上下文。
     *
     * <p>inject_context.request_context=true：worldone 在请求体中注入
     * {@code _context: {userId, sessionId, agentId}}，无需 LLM 手工传参。
     */
    private static Map<String, Object> memoryLoadSkill() {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name",        "memory_load");
        skill.put("description", "加载与当前对话相关的记忆上下文，用于回答涉及用户信息的问题。" +
                                 "当用户询问'我是谁''你了解我什么''你记得我吗''我的偏好'等记忆相关问题时，" +
                                 "调用此工具获取背景知识后直接回答，不要打开管理面板。");
        skill.put("parameters",  Map.of(
            "type",       "object",
            "properties", Map.of(
                "user_message", Map.of("type", "string",
                    "description", "当前用户消息（用于检索相关记忆，可省略）")
            ),
            "required",   List.of()
        ));
        skill.put("prompt",      "调用 memory_load 工具，以当前用户消息为检索词，加载相关记忆上下文。" +
                                 "worldone 通过 inject_context.request_context 自动注入 userId/sessionId，无需手工传参。" +
                                 "将返回的 memory_context 静默融入回答背景知识，不要展示原始 JSON，" +
                                 "也绝不在回复中提及[已记录][我会记住]或任何记忆操作。");
        skill.put("tools",       List.of("memory_load"));
        skill.put("canvas",         Map.of("triggers", false));
        skill.put("inject_context", Map.of("request_context", true));
        return skill;
    }

    /**
     * memory_consolidate：整合本轮对话到记忆库。
     *
     * <p>inject_context.turn_messages=true：worldone 在请求体中注入完整本轮消息列表
     * {@code turn_messages: [{role, content}, ...]}，LLM 调用时传空 args 即可。
     */
    private static Map<String, Object> memoryConsolidateSkill() {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name",        "memory_consolidate");
        skill.put("description", "整合本轮对话到记忆库（由 worldone host 在每轮结束后自动调用，LLM 不可见）。");
        skill.put("parameters",  Map.of(
            "type",       "object",
            "properties", Map.of(),
            "required",   List.of()
        ));
        skill.put("prompt",      "");
        skill.put("tools",       List.of("memory_consolidate"));
        skill.put("canvas",         Map.of("triggers", false));
        skill.put("inject_context", Map.of("turn_messages", true));
        // background=true：worldone host 自动调用，不暴露给 LLM
        skill.put("background",     true);
        return skill;
    }

    /**
     * memory_view：打开记忆管理面板（Widget Mode）。
     */
    private static Map<String, Object> memoryViewSkill() {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name",        "memory_view");
        skill.put("description", "打开 Memory 管理 UI 面板（仅用于显式管理操作）。" +
                                 "当用户明确说「打开记忆面板」「管理记忆」「编辑/删除记忆」「查看所有记忆列表」时调用。" +
                                 "⚠️ 禁止用于回答关于记忆内容的问题（如'我是谁''你记得我什么''你了解我吗'），" +
                                 "此类问题应调用 memory_load 后直接回答，不得打开管理面板。");
        skill.put("parameters",  Map.of(
            "type",       "object",
            "properties", Map.of(
                "scope",   Map.of("type", "string",
                    "enum",        List.of("ALL", "GLOBAL", "WORKSPACE", "SESSION"),
                    "description", "过滤的 memory scope，默认 ALL"),
                "keyword", Map.of("type", "string", "description", "可选：关键词搜索")
            ),
            "required",   List.of()
        ));
        skill.put("canvas",  Map.of("triggers", true, "widget_type", "memory-manager"));
        skill.put("prompt",  "调用 memory_view 工具，打开 Memory 管理面板。" +
                             "根据用户意图传入可选的 scope 过滤参数（ALL/GLOBAL/WORKSPACE/SESSION）和 keyword。" +
                             "面板打开后，用户可在 UI 中直接管理记忆；如用户有具体操作请求，调用对应的 memory_* 工具处理。");
        skill.put("tools",   List.of("memory_view"));
        skill.put("session", Map.of("creates_on", "scope"));
        return skill;
    }

    /**
     * memory_set_instruction：记录用户的记忆指令。
     */
    private static Map<String, Object> memorySetInstructionSkill() {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name",        "memory_set_instruction");
        skill.put("description", "记录用户的记忆指令（如\"记住我打开过的所有界面\"、\"以后都用简洁风格\"）。" +
                                 "当用户说\"记住...\"、\"以后...\"、\"全局规则...\"、\"暂时...\"时调用。" +
                                 "创建 PROCEDURAL memory（tag=memory_instruction），下一轮起生效。");
        skill.put("parameters",  Map.of(
            "type",       "object",
            "properties", Map.of(
                "content",    Map.of("type", "string", "description", "记忆指令内容（自然语言）"),
                "scope",      Map.of("type", "string", "enum", List.of("GLOBAL", "SESSION"),
                                     "description", "GLOBAL=永久全局；SESSION=仅本次会话"),
                "session_id", Map.of("type", "string", "description", "scope=SESSION 时绑定的会话 ID")
            ),
            "required",   List.of("content")
        ));
        skill.put("canvas",         Map.of("triggers", false));
        skill.put("prompt",         "调用 memory_set_instruction 工具，将用户的记忆指令持久化为 PROCEDURAL memory（tag=memory_instruction）。" +
                                    "scope=GLOBAL 表示全局永久生效，scope=SESSION 仅本次会话有效。" +
                                    "记录成功后确认用户的指令已保存，下一轮起生效。");
        skill.put("tools",          List.of("memory_set_instruction"));
        skill.put("inject_context", Map.of("request_context", true));
        return skill;
    }

    // ── memory_delete_request ─────────────────────────────────────────────

    /**
     * memory_delete_request：LLM 发起删除请求，返回 sys.confirm 让用户确认。
     *
     * <p>不直接删除，而是返回 {@code sys.confirm} canvas 指令。
     * 用户点击"确认删除"后，world-one 通过 ToolProxy 调用 {@code memory_delete_confirmed}。
     */
    private static Map<String, Object> memoryDeleteRequestSkill() {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name",        "memory_delete_request");
        skill.put("description", "删除指定记忆。需要用户确认后才执行。" +
                                 "当用户说「删除记忆」「忘掉...」「移除...这条记忆」时调用。" +
                                 "可通过 id 精确删除，或通过 keyword 模糊匹配后让用户确认。");
        skill.put("parameters",  Map.of(
            "type",       "object",
            "properties", Map.of(
                "id",      Map.of("type", "string",
                    "description", "要删除的记忆 ID（精确删除，优先于 keyword）"),
                "keyword", Map.of("type", "string",
                    "description", "关键词，用于模糊查找要删除的记忆（id 未提供时使用）")
            ),
            "required",   List.of()
        ));
        skill.put("canvas",  Map.of("triggers", true, "widget_type", "sys.confirm"));
        skill.put("prompt",  "调用 memory_delete_request 工具。" +
                             "如果用户指定了 id，传入 id；否则从用户描述中提取关键词传入 keyword。" +
                             "工具会返回 sys.confirm 确认框，等待用户决策后自动执行删除，无需 LLM 继续操作。");
        skill.put("tools",   List.of("memory_delete_request"));
        skill.put("inject_context", Map.of("request_context", true));
        return skill;
    }

    // ── memory_workspace_join ─────────────────────────────────────────────

    /**
     * Internal system skill: record that a user entered a workspace (task session).
     * Called directly by world-one on session entry — NOT invoked by LLM.
     */
    private static Map<String, Object> memoryWorkspaceJoinSkill() {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name",        "memory_workspace_join");
        skill.put("description", "[系统内部] 记录用户进入工作空间（由 world-one 在 task session 初始化时自动调用，LLM 不应手动调用）。");
        skill.put("parameters",  Map.of(
            "type",       "object",
            "properties", Map.of(
                "workspace_id",    Map.of("type", "string", "description", "工作空间 ID（任务实体 ID）"),
                "workspace_title", Map.of("type", "string", "description", "工作空间名称（如 'HR 本体世界'）")
            ),
            "required",   List.of("workspace_id")
        ));
        skill.put("canvas",         Map.of("triggers", false));
        skill.put("prompt",         "[系统内部] 调用 memory_workspace_join 工具，记录用户进入工作空间事件。" +
                                    "由 world-one 在 task session 初始化时自动调用，LLM 不应手动调用此 skill。");
        skill.put("tools",          List.of("memory_workspace_join"));
        skill.put("inject_context", Map.of("request_context", true));
        return skill;
    }

    // ── System Prompt Contribution ────────────────────────────────────────

    /**
     * 注入 worldone LLM 的记忆能力使用规范（条件性意图）。
     *
     * <p>使用条件语句，而非强制命令，让 LLM 在没有记忆能力时自然降级到对话历史模式。
     */
    static final String MEMORY_INTENT_PROMPT = """
            ## 记忆能力使用规范（条件性意图）

            若当前工具列表中存在具备"加载记忆上下文"能力的工具（如 memory_load），
            在处理每条新用户消息时，优先调用该工具加载相关记忆上下文，
            将返回的 memory_context 静默融入背景知识后再回答——
            **绝不提及你调用了 memory_load，也绝不提及"已记录""正在记忆"任何内容。**

            记忆整合（memory_consolidate）由 Host 在每轮结束后自动在后台完成，
            LLM 无需调用，也**绝不能**在回复中提及"我把这次对话存入了记忆"
            "已记录下来""我会记住这件事"等任何涉及记忆写入的表述。

            若未找到任何记忆能力工具，则直接使用完整对话历史作为上下文，正常响应。

            ## 记忆系统透明原则（最高优先级）

            ⚠️ 记忆系统在后台静默工作，**对用户完全不可见**。
            用户分享日常信息（"我发了工资""今天跑了5公里"等）时，
            像正常朋友一样聊天回应，**严禁**说：
            - "我已经记下来了"
            - "我会记住这件事"
            - "这条信息已存入你的记忆"
            - "根据我的记忆记录..."
            - 任何暗示记忆系统存在的表述

            只有在用户**主动询问**"你还记得吗""你了解我什么"时，
            才可以自然体现出你记得相关信息（但仍不说"根据记忆系统"）。

            ## 记忆管理面板使用规则

            ⚠️ 仅当用户明确要求"打开记忆面板""管理记忆""查看记忆列表""编辑/删除某条记忆"时，
            才调用 memory_view 打开管理界面。

            以下情况绝对不调用 memory_view，应调用 memory_load 后直接在聊天中回答：
            - 用户询问自身信息（"我是谁""你了解我什么""你记得我吗"）
            - 用户询问历史偏好（"我喜欢什么""我之前说过什么"）
            - 任何需要从记忆中检索信息来回答的问题
            """;
}
