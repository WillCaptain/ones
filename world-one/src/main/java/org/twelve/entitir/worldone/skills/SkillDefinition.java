package org.twelve.entitir.worldone.skills;

import java.util.List;

/**
 * Skill 索引条目（Anthropic Skills 风格，2025-10 spec）。
 *
 * <p>对应 AIPP 应用 {@code GET /api/skills} 每一项，以及 {@code SKILL.md} 顶部
 * YAML frontmatter。 <b>{@code name} 即唯一标识</b>，等于 playbook 所在目录名，
 * 在 app 内唯一。正文（SKILL.md 的 Markdown 部分）按需延迟拉取
 * {@code GET /api/skills/{name}/playbook}。
 *
 * <h2>Fields（与 Anthropic 官方 schema 对齐）</h2>
 * <ul>
 *   <li>{@code name}         — 唯一标识 / 目录名</li>
 *   <li>{@code description}  — WHAT + WHEN，主 LLM 召回的唯一语义信号（≤1024 char）</li>
 *   <li>{@code allowedTools} — 执行期允许调用的 tool 白名单（非空）</li>
 *   <li>{@code playbookUrl}  — 相对 app baseUrl 的 {@code SKILL.md} 路径</li>
 * </ul>
 *
 * <h2>Extension fields（本项目自有）</h2>
 * <ul>
 *   <li>{@code level}       — {@code universal} / {@code app} / {@code widget} / {@code view}</li>
 *   <li>{@code ownerWidget} — {@code level=widget/view} 时归属的 widget type</li>
 *   <li>{@code ownerView}   — {@code level=view} 时归属的 view id</li>
 *   <li>{@code appId}       — 来源 app（host 侧维护，schema 里不出现）</li>
 * </ul>
 *
 * <p>旧版本的 {@code triggers} / {@code embeddingHint} 字段已 <b>彻底移除</b>：
 * Anthropic 从不使用关键词匹配，召回完全依赖主 LLM 读 {@code description}。
 */
public record SkillDefinition(
        String name,
        String appId,
        String level,
        String ownerWidget,
        String ownerView,
        String description,
        List<String> allowedTools,
        String playbookUrl
) {
    public static final String LEVEL_UNIVERSAL = "universal";
    public static final String LEVEL_APP       = "app";
    public static final String LEVEL_WIDGET    = "widget";
    public static final String LEVEL_VIEW      = "view";
}
