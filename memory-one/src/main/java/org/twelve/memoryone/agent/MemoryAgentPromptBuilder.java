package org.twelve.memoryone.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.twelve.memoryone.model.Memory;
import org.twelve.memoryone.store.MemoryStore;

import java.util.List;

/**
 * Memory Agent 提示词组装器。
 *
 * <p>组装顺序：
 * <pre>
 * Layer 0  — MemoryAgentPrompt.LAYER_0（固定核心规则）
 * Layer 1a — MEMORY_ONE_RULES（memory-one 自身追踪规则）
 * Layer 2  — 用户指令（MemoryStore 动态查询，tag="memory_instruction"）
 * </pre>
 */
@Component
public class MemoryAgentPromptBuilder {

    static final String MEMORY_ONE_RULES = """
            追踪以下 Agent 平台级别的信息：

            Session 与模式变化：
            - 用户每次切换到新 session，记录 EPISODIC（时间 + 目标 session）
            - 用户每次进入 canvas mode，记录 EPISODIC（widget 类型 + 名称）
            - 用户每次退出 canvas mode，更新对应 GOAL memory 的进度

            用户偏好（从对话推断）：
            - 用户偏好的语言风格（简洁/详细、中/英文）→ PROCEDURAL, scope=GLOBAL
            - 用户的工作节奏（连续多轮 vs 跳跃切换）→ PROCEDURAL

            Skill 使用模式：
            - 某个 skill 被高频调用（同一 session 内 ≥3 次）→ SEMANTIC，表明用户对该功能有强需求
            """;

    @Autowired
    private MemoryStore memoryStore;

    public String build(String agentId, String userId, String sessionId) {
        return build(agentId, userId, sessionId, null, null);
    }

    public String build(String agentId, String userId, String sessionId,
                        String workspaceId, String workspaceTitle) {
        List<Memory> userInstructions = safeQueryInstructions(agentId, userId, sessionId);
        List<String> appHints = buildWorkspaceHint(workspaceId, workspaceTitle);
        return MemoryAgentPrompt.compose(MEMORY_ONE_RULES, appHints, userInstructions);
    }

    /**
     * Inject workspace context as a Layer-1b hint so the LLM knows:
     * 1. Which workspace is active (for correct WORKSPACE scope assignment)
     * 2. That user-personal info must NOT be promoted to WORKSPACE
     */
    private List<String> buildWorkspaceHint(String workspaceId, String workspaceTitle) {
        if (workspaceId == null || workspaceId.isBlank()) return List.of();
        String title = (workspaceTitle != null && !workspaceTitle.isBlank()) ? workspaceTitle : workspaceId;
        return List.of("""
                当前工作空间：%s（workspaceId: %s）
                这是一个协作任务空间，多人可能同时在此工作。

                ⚠️  WORKSPACE scope 写入规则：
                - 关于该任务/对象本身的事实、关系、约定 → scope=WORKSPACE（共享给所有协作者）
                - 用户的个人信息（姓名、偏好、经历、身份）→ 严禁写入 WORKSPACE，应使用 GLOBAL 或 SESSION
                - 区分标准：这条信息"是关于这个工作空间/任务的"还是"关于这个用户的"？
                  前者 → WORKSPACE，后者 → 绝对不能是 WORKSPACE

                示例：
                  ✓ "HR系统要支持多组织架构" → WORKSPACE SEMANTIC
                  ✓ "Employee --[belongs_to]--> Department" → WORKSPACE RELATION
                  ✗ "用户叫张三" → GLOBAL SEMANTIC（不是 WORKSPACE）
                  ✗ "用户偏好中文" → GLOBAL PROCEDURAL（不是 WORKSPACE）
                """.formatted(title, workspaceId));
    }

    private List<Memory> safeQueryInstructions(String agentId, String userId, String sessionId) {
        try {
            return memoryStore.findMemoryInstructions(agentId, userId, sessionId);
        } catch (Exception e) {
            return List.of();
        }
    }
}
