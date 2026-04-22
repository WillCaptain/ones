package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.twelve.entitir.aip.LLMCaller;
import org.twelve.entitir.ontology.llm.LLMConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET/POST /api/settings — LLM 配置读写接口。
 */
@RestController
@RequestMapping("/api/settings")
public class WorldOneSettingsController {

    @Autowired WorldOneConfigStore configStore;
    @Autowired WorldOneSessionStore sessionStore;

    /** 读取当前配置（apiKey 打码返回）。 */
    @GetMapping
    public Map<String, Object> getSettings() {
        return configStore.toMap();
    }

    /**
     * 保存配置并重建所有 AgentLoop（使新 key 立即生效）。
     * Body: { apiKey, baseUrl, model, timeoutSeconds }
     */
    @PostMapping
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> body) {
        String apiKey  = str(body, "apiKey");
        String baseUrl = str(body, "baseUrl");
        String model   = str(body, "model");
        int    timeout = intVal(body, "timeoutSeconds");
        List<Map<String, Object>> envVars = mapList(body.get("envVars"));

        configStore.save(apiKey, baseUrl, model, timeout, envVars);
        sessionStore.invalidateAll(); // 旧 loop 使用旧 config，需要重建

        return Map.of("ok", true, "message", "配置已保存");
    }

    /**
     * POST /api/settings/test — 用当前配置发送一条探测消息，验证连通性。
     */
    @PostMapping("/test")
    public Map<String, Object> testConnection() {
        if (!configStore.isConfigured()) {
            return Map.of("ok", false, "message", "API Key 未配置");
        }
        try {
            LLMConfig cfg = LLMConfig.builder()
                    .apiKey(configStore.apiKey())
                    .baseUrl(configStore.baseUrl())
                    .model(configStore.model())
                    .timeoutSeconds(15)
                    .build();
            LLMCaller caller = new LLMCaller(cfg);
            LLMCaller.LLMResponse resp = caller.callTextOnly(
                    List.of(Map.of("role", "user", "content", "reply OK")), 16);
            String content = resp.content();
            return Map.of("ok", true, "message", "连接成功：" + (content != null ? content.strip() : "(empty)"));
        } catch (Exception e) {
            return Map.of("ok", false, "message", "连接失败：" + e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String s ? s : "";
    }
    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((k, v) -> copy.put(String.valueOf(k), v));
            out.add(copy);
        }
        return out;
    }
}
