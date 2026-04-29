package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.twelve.entitir.worldone.skills.AippSkillCatalog;
import org.twelve.entitir.worldone.skills.SkillDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified AIPP registry facade.
 *
 * <p>Externally, World One should treat app/tool/widget/skill discovery as one
 * registry. Internally this facade keeps the existing catalogs separate while
 * presenting a single API to agent loops and controllers.
 */
@Component
public class AippRegistry {
    public static final String AIPP_ENTRY_PLAYBOOK = AippSkillCatalog.AIPP_ENTRY_PLAYBOOK;

    private final AppRegistry apps;
    private final AippSkillCatalog skills;

    @Autowired
    public AippRegistry(AppRegistry apps, AippSkillCatalog skills) {
        this.apps = apps;
        this.skills = skills;
    }

    public AppRegistry appsCatalog() {
        return apps;
    }

    public Collection<AppRegistration> apps() { return apps.apps(); }
    public String hostSystemPrompt() { return apps.hostSystemPrompt(); }
    public String aggregatedSystemPrompt() { return apps.aggregatedSystemPrompt(); }
    public String aggregatedSystemPrompt(Set<String> activeAppIds) { return apps.aggregatedSystemPrompt(activeAppIds); }
    public String appDisplayName(String appId) { return apps.appDisplayName(appId); }
    public List<Map<String, Object>> allTools() { return apps.allTools(); }
    public List<Map<String, Object>> toolsForApp(String appId) { return apps.toolsForApp(appId); }
    public AppRegistration findAppForTool(String toolName) { return apps.findAppForTool(toolName); }
    public Map<String, Object> injectEnvVars(String appId, Map<String, Object> args) { return apps.injectEnvVars(appId, args); }
    public String getAppMainWidgetType(String appId) { return apps.getAppMainWidgetType(appId); }
    public String findOutputSkillForWidget(String widgetType) { return apps.findOutputSkillForWidget(widgetType); }
    public String widgetContextPrompt(String widgetType) { return apps.widgetContextPrompt(widgetType); }
    public String widgetWelcomeMessage(String widgetType) { return apps.widgetWelcomeMessage(widgetType); }
    public String widgetTitle(String widgetType) { return apps.widgetTitle(widgetType); }
    public String getWidgetOwnerAppId(String widgetType) { return apps.getWidgetOwnerAppId(widgetType); }
    public String getWidgetSystemPrompt(String widgetType) { return apps.getWidgetSystemPrompt(widgetType); }
    public String getWidgetViewSystemPrompt(String widgetType, String viewId) { return apps.getWidgetViewSystemPrompt(widgetType, viewId); }
    public String getWidgetRefreshSkill(String widgetType) { return apps.getWidgetRefreshSkill(widgetType); }
    public boolean isWidgetMutatingTool(String widgetType, String toolName) { return apps.isWidgetMutatingTool(widgetType, toolName); }
    public String findOutputWidgetForSkill(String skillName) { return apps.findOutputWidgetForSkill(skillName); }
    public Map<String, Object> getOutputWidgetRules(String skillName) { return apps.getOutputWidgetRules(skillName); }
    public String getDefaultWidget(String skillName) { return apps.getDefaultWidget(skillName); }
    public List<Map<String, Object>> getCanvasTools(String widgetType) { return apps.getCanvasTools(widgetType); }
    public Map<String, Object> getWidgetScope(String widgetType) { return apps.getWidgetScope(widgetType); }
    public Map<String, Object> getWidgetViewScope(String widgetType, String viewId) { return apps.getWidgetViewScope(widgetType, viewId); }
    public boolean isSkillExecution(String toolName) { return apps.isSkillExecution(toolName); }
    public List<Map.Entry<AppRegistration, Map<String, Object>>> getAutoPreTurnSkills() { return apps.getAutoPreTurnSkills(); }
    public List<Map.Entry<AppRegistration, Map<String, Object>>> findSkillsByLifecycle(String lifecycle) { return apps.findSkillsByLifecycle(lifecycle); }
    public boolean requiresTurnMessages(String toolName) { return apps.requiresTurnMessages(toolName); }

    public List<SkillDefinition> appEntrySkills() { return skills.appEntrySkills(); }
    public List<SkillDefinition> visibleSkills(String widgetType, String viewId, Set<String> activeAppIds) {
        return skills.visibleSkills(widgetType, viewId, activeAppIds);
    }
    public String loadSkillPlaybook(SkillDefinition skill) { return skills.loadPlaybook(skill); }
}
