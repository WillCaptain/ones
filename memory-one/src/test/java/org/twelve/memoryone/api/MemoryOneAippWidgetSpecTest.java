package org.twelve.memoryone.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.twelve.aipp.AippAppSpec;
import org.twelve.aipp.widget.AippWidgetSpec;
import org.twelve.aipp.widget.AippWidgetTheme;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * memory-one AIPP Widget 协议规格测试。
 *
 * <p>验证 {@code /api/widgets}（memory-manager）和 {@code /api/skills} 符合 AIPP 三层规格，
 * 并通过 {@link AippWidgetSpec} 验证 widget 的 disable 和 theme 契约声明。
 *
 * <h2>测试覆盖</h2>
 * <ol>
 *   <li>Skills API 顶层结构</li>
 *   <li>memory_view skill：canvas.triggers=true，widget_type=memory-manager</li>
 *   <li>memory_load / memory_consolidate：canvas.triggers=false（Chat Mode）</li>
 *   <li>Widget Manifest：type / source / internal_tools / renders_output_of_skill</li>
 *   <li><b>AippWidgetSpec</b>：supports.disable + supports.theme 契约声明</li>
 *   <li>AippWidgetTheme 内置预设：CSS 变量完整性、颜色合法性、语言合法性</li>
 *   <li>跨接口一致性：skills 引用的 widget_type 均在 /api/widgets 中注册</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryOneAippWidgetSpecTest {

    private final ObjectMapper   json       = new ObjectMapper();
    private final AippAppSpec    appSpec    = new AippAppSpec();
    private final AippWidgetSpec widgetSpec = new AippWidgetSpec();

    private JsonNode skillsNode;
    private JsonNode widgetsNode;

    @BeforeAll
    void buildApiResponses() {
        Map<String, Object> skillsMap = new LinkedHashMap<>();
        skillsMap.put("app",     "memory-one");
        skillsMap.put("version", "1.0");
        skillsMap.put("skills",  MemorySkillsController.buildSkillList());
        skillsNode = json.valueToTree(skillsMap);

        widgetsNode = json.valueToTree(new MemoryWidgetsController().widgets());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 1 — Skills API 顶层结构
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[skills] /api/skills 顶层结构合规（app / version / skills[]）")
    void skills_api_top_level_structure_complies() {
        appSpec.assertValidSkillsApiStructure(skillsNode);
    }

    @Test
    @DisplayName("[skills] 每个 Skill 三层结构合规（name / description / parameters / canvas / prompt / tools）")
    void each_skill_full_structure_complies() {
        for (JsonNode skill : skillsNode.get("skills")) {
            appSpec.assertValidSkillStructure(skill);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 2 — memory_view：Canvas Mode skill
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[skill] memory_view canvas.triggers=true，widget_type=memory-manager")
    void memory_view_canvas_triggers_true() {
        JsonNode skill  = appSpec.findSkill(skillsNode, "memory_view");
        JsonNode canvas = skill.get("canvas");
        assertThat(canvas.get("triggers").asBoolean())
                .as("memory_view: triggers=true，应打开 memory-manager widget").isTrue();
        assertThat(canvas.get("widget_type").asText())
                .as("memory_view: widget_type=memory-manager").isEqualTo("memory-manager");
    }

    @Test
    @DisplayName("[skill] memory_view session：app session 声明，app_id 与 /api/skills.app 一致")
    void memory_view_session_is_appScoped() {
        JsonNode skill = appSpec.findSkill(skillsNode, "memory_view");
        assertThat(skill.has("session")).isTrue();
        JsonNode session = skill.get("session");
        assertThat(session.path("session_type").asText()).isEqualTo("app");
        assertThat(session.path("app_id").asText())
                .as("session.app_id 必须与 skills 顶层 app 一致")
                .isEqualTo(skillsNode.path("app").asText());
        appSpec.assertValidSkillSessionExtension(skill);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 3 — 后台 Skills：Canvas.triggers=false（Chat Mode / inject_context）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[skill] memory_load / memory_consolidate canvas.triggers=false（不打开 widget）")
    void background_skills_canvas_triggers_false() {
        for (String skillName : new String[]{"memory_load", "memory_consolidate"}) {
            JsonNode skill  = appSpec.findSkill(skillsNode, skillName);
            JsonNode canvas = skill.get("canvas");
            assertThat(canvas.get("triggers").asBoolean())
                    .as(skillName + ": 后台 skill 不应触发 canvas，triggers 必须为 false")
                    .isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 4 — Widget Manifest 结构
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[widgets] /api/widgets 结构合规（widgets[]，含 memory-manager）")
    void widgets_api_structure_complies() {
        appSpec.assertValidWidgetsApiStructure(widgetsNode);
    }

    @Test
    @DisplayName("[widgets] memory-manager type / source / description 字段存在")
    void memory_manager_basic_fields_present() {
        JsonNode widget = widgetSpec.findWidget(widgetsNode, "memory-manager");
        assertThat(widget.get("type").asText()).isEqualTo("memory-manager");
        assertThat(widget.has("source")).isTrue();
        assertThat(widget.has("description")).isTrue();
        assertThat(widget.get("description").asText()).isNotBlank();
    }

    @Test
    @DisplayName("[widgets] memory-manager 声明 internal_tools（含增删改查工具）")
    void memory_manager_declares_required_internal_tools() {
        JsonNode widget = widgetSpec.findWidget(widgetsNode, "memory-manager");
        assertThat(widget.has("internal_tools")).isTrue();
        assertThat(widget.get("internal_tools").isArray()).isTrue();

        for (String tool : new String[]{"memory_query", "memory_create", "memory_update",
                                        "memory_delete", "memory_promote"}) {
            boolean found = toStream(widget.get("internal_tools"))
                    .anyMatch(n -> tool.equals(n.asText()));
            assertThat(found).as("internal_tools 应包含 " + tool).isTrue();
        }
    }

    @Test
    @DisplayName("[widgets] memory-manager 声明 renders_output_of_skill=memory_view")
    void memory_manager_renders_output_of_skill() {
        JsonNode widget = widgetSpec.findWidget(widgetsNode, "memory-manager");
        assertThat(widget.has("renders_output_of_skill")).isTrue();
        assertThat(widget.get("renders_output_of_skill").asText()).isEqualTo("memory_view");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 5 — AippWidgetSpec：disable + theme 契约
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[widget-spec] memory-manager 声明 supports.disable=true")
    void memory_manager_supports_disable() {
        JsonNode widget = widgetSpec.findWidget(widgetsNode, "memory-manager");
        widgetSpec.assertWidgetSupportsDisable(widget);
    }

    @Test
    @DisplayName("[widget-spec] memory-manager supports.theme 覆盖核心主题字段")
    void memory_manager_supports_theme_covers_required_fields() {
        JsonNode widget = widgetSpec.findWidget(widgetsNode, "memory-manager");
        widgetSpec.assertWidgetThemeCoversProperties(widget,
                "background", "surface", "text", "accent", "font", "language");
    }

    @Test
    @DisplayName("[widget-spec] supports.theme 中的字段名均为 AippWidgetTheme 合法字段")
    void memory_manager_theme_fields_are_all_known() {
        JsonNode widget   = widgetSpec.findWidget(widgetsNode, "memory-manager");
        JsonNode supports = widget.get("supports");
        // assertWidgetThemeCoversProperties 已内部验证字段名合法性，此处再做显式断言
        for (JsonNode field : supports.get("theme")) {
            assertThat(AippWidgetSpec.THEME_FIELDS)
                    .as("supports.theme 中的 '%s' 不是 AippWidgetTheme 的合法字段", field.asText())
                    .contains(field.asText());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 6 — AippWidgetTheme 内置预设规格
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[theme] AippWidgetTheme.darkDefault() CSS 变量 Map 完整（含全部 --aipp-* 键）")
    void dark_default_theme_css_vars_complete() {
        AippWidgetTheme theme     = AippWidgetTheme.darkDefault();
        JsonNode        cssVarsNode = json.valueToTree(theme.toCssVars());
        widgetSpec.assertThemeCssVarsComplete(cssVarsNode);
    }

    @Test
    @DisplayName("[theme] AippWidgetTheme.darkDefault() 颜色值均为合法 hex 格式")
    void dark_default_theme_colors_valid_hex() {
        widgetSpec.assertThemeColorsAreValidHex(AippWidgetTheme.darkDefault());
    }

    @Test
    @DisplayName("[theme] AippWidgetTheme.lightDefault() 颜色值均为合法 hex 格式")
    void light_default_theme_colors_valid_hex() {
        widgetSpec.assertThemeColorsAreValidHex(AippWidgetTheme.lightDefault());
    }

    @Test
    @DisplayName("[theme] 两个内置预设的 language 字段均为合法 IETF 语言标签")
    void default_themes_language_valid() {
        widgetSpec.assertThemeLanguageValid(AippWidgetTheme.darkDefault());
        widgetSpec.assertThemeLanguageValid(AippWidgetTheme.lightDefault());
    }

    @Test
    @DisplayName("[theme] darkDefault().darkMode=true，lightDefault().darkMode=false")
    void default_themes_dark_mode_flag_correct() {
        assertThat(AippWidgetTheme.darkDefault().darkMode()).isTrue();
        assertThat(AippWidgetTheme.lightDefault().darkMode()).isFalse();
    }

    @Test
    @DisplayName("[theme] toCssVars() 不含 language（language 通过 data-aipp-language 传递，不是 CSS 变量）")
    void css_vars_do_not_contain_language_key() {
        Map<String, String> vars = AippWidgetTheme.darkDefault().toCssVars();
        assertThat(vars).doesNotContainKey("--aipp-language");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 7 — 跨接口一致性
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[consistency] skills 引用的所有 widget_type 均已在 /api/widgets 注册")
    void all_skill_widget_types_registered_in_widgets() {
        appSpec.assertWidgetTypesRegistered(skillsNode, widgetsNode);
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    private static Stream<JsonNode> toStream(JsonNode arrayNode) {
        return StreamSupport.stream(arrayNode.spliterator(), false);
    }
}
