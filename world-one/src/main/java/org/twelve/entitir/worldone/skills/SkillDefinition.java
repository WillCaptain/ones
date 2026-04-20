package org.twelve.entitir.worldone.skills;

import java.util.List;

/**
 * Skill 元信息（给 Loop A 召回用）。
 *
 * <p>对应 AIPP 应用的 {@code GET /api/skills} 端点每一项（Phase 4 之后专用于
 * Skill Playbook 索引）。Playbook（SKILL.md 正文）延迟从
 * {@code GET /api/skills/{id}/playbook} 拉取，此处只存索引字段。
 *
 * <p>详见 {@code ones/docs/aipp-skills-progressive-disclosure.md}。
 *
 * @param id            skill 唯一 id（在 app 内唯一）
 * @param appId         归属 app
 * @param level         召回分层：universal / app / widget / view
 * @param ownerWidget   level=widget/view 时的归属 widget type，否则 null
 * @param ownerView     level=view 时的 view id，否则 null
 * @param name          用户可读名称
 * @param description   召回索引描述（一句话即可）
 * @param triggers      触发词列表（阶段 1 快路径）
 * @param embeddingHint 拼到 description 后用于向量化的补充文本（本轮未使用）
 * @param toolsWhitelist 执行阶段允许调用的 tool 名称白名单
 * @param playbookUrl   相对 app baseUrl 的路径，如 {@code /api/skills/{id}/playbook}
 */
public record SkillDefinition(
        String id,
        String appId,
        String level,
        String ownerWidget,
        String ownerView,
        String name,
        String description,
        List<String> triggers,
        String embeddingHint,
        List<String> toolsWhitelist,
        String playbookUrl
) {
    public static final String LEVEL_UNIVERSAL = "universal";
    public static final String LEVEL_APP       = "app";
    public static final String LEVEL_WIDGET    = "widget";
    public static final String LEVEL_VIEW      = "view";
}
