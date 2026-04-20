package org.twelve.entitir.worldone.skills;

import java.util.Map;

/**
 * 一次 skill 执行的运行时上下文。
 *
 * <p>由 {@code GenericAgentLoop} 在拦截主 LLM 的 {@code load_skill} meta-tool
 * 调用时构造；命中后塞进 {@code setCurrentTurnSkillRun(run)}，turn 结束后自动清理。
 *
 * @param skill         本轮加载的 skill 定义
 * @param extractedArgs 预留字段（Anthropic 风格下通常为空；playbook 自行向用户索要参数）
 * @param playbook      SKILL.md 正文（含 frontmatter；原样作为 system 追加层注入）
 */
public record SkillRun(
        SkillDefinition skill,
        Map<String, Object> extractedArgs,
        String playbook
) {}
