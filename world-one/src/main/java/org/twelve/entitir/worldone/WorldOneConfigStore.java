package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 配置的运行时存储。
 *
 * <h2>优先级（高 → 低）</h2>
 * <ol>
 *   <li>通过 {@link #save(String, String, String, int)} 写入的运行时值（持久化到 ~/.worldone-config.json）</li>
 *   <li>application.yml / 环境变量（LLM_API_KEY, LLM_BASE_URL, LLM_MODEL）</li>
 * </ol>
 */
@Component
public class WorldOneConfigStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final File CONFIG_FILE  =
            new File(System.getProperty("user.home"), ".worldone-config.json");

    @Autowired
    private LLMConfigProperties defaultProps;

    // runtime overrides (null = not set, fall back to defaultProps)
    private volatile String rtApiKey;
    private volatile String rtBaseUrl;
    private volatile String rtModel;
    private volatile int    rtTimeout = 0;
    /** 运行时环境变量配置：[{key, globalValue, appValues:{appId:value}}] */
    private volatile List<Map<String, Object>> rtEnvVars = List.of();

    // ── init ──────────────────────────────────────────────────────────────────

    @jakarta.annotation.PostConstruct
    void init() {
        if (CONFIG_FILE.exists()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.readValue(CONFIG_FILE, Map.class);
                rtApiKey  = (String) m.get("apiKey");
                rtBaseUrl = (String) m.get("baseUrl");
                rtModel   = (String) m.get("model");
                Object t  = m.get("timeoutSeconds");
                if (t instanceof Number n) rtTimeout = n.intValue();
                rtEnvVars = ensureDefaultEnvVar(normalizeEnvVarConfigs(m.get("envVars")));
            } catch (Exception e) {
                System.err.println("[WorldOne] Failed to load config file: " + e.getMessage());
            }
        } else {
            rtEnvVars = ensureDefaultEnvVar(List.of());
        }
    }

    // ── readers ───────────────────────────────────────────────────────────────

    public String apiKey()  { return nonBlank(rtApiKey,  defaultProps.getApiKey()); }
    public String baseUrl() { return nonBlank(rtBaseUrl, defaultProps.getBaseUrl()); }
    public String model()   { return nonBlank(rtModel,   defaultProps.getModel()); }
    public int    timeout() { return rtTimeout > 0 ? rtTimeout : defaultProps.getTimeoutSeconds(); }

    public boolean isConfigured() { return !apiKey().isBlank(); }

    /** 返回给前端的配置（apiKey 打码）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        String key = apiKey();
        m.put("apiKey",         key.isBlank() ? "" : maskKey(key));
        m.put("baseUrl",        baseUrl());
        m.put("model",          model());
        m.put("timeoutSeconds", timeout());
        m.put("configured",     isConfigured());
        m.put("envVars",        envVarConfigs());
        return m;
    }

    /** 返回设置面板使用的环境变量配置副本。 */
    public List<Map<String, Object>> envVarConfigs() {
        return ensureDefaultEnvVar(deepCopyEnvVarConfigs(rtEnvVars));
    }

    /**
     * 解析指定 app 的最终环境变量（app 覆盖优先，其次全局值）。
     */
    public Map<String, String> resolveEnvVarsForApp(String appId) {
        String normalizedAppId = appId == null ? "" : appId.trim();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : ensureDefaultEnvVar(deepCopyEnvVarConfigs(rtEnvVars))) {
            String key = str(row.get("key")).trim();
            if (key.isBlank()) continue;

            String value = "";
            Map<String, Object> appValues = map(row.get("appValues"));
            if (!normalizedAppId.isBlank()) {
                String appSpecific = str(appValues.get(normalizedAppId)).trim();
                if (!appSpecific.isBlank()) value = appSpecific;
            }
            if (value.isBlank()) {
                value = str(row.get("globalValue")).trim();
            }
            if (!value.isBlank()) out.put(key, value);
        }
        return out;
    }

    // ── writer ────────────────────────────────────────────────────────────────

    /**
     * 保存新配置，持久化到 ~/.worldone-config.json。
     * apiKey 为空字符串时不覆盖已有 key。
     */
    public synchronized void save(String apiKey, String baseUrl, String model, int timeoutSeconds) {
        save(apiKey, baseUrl, model, timeoutSeconds, null);
    }

    /**
     * 保存新配置，持久化到 ~/.worldone-config.json。
     * apiKey 为空字符串时不覆盖已有 key。
     */
    public synchronized void save(String apiKey, String baseUrl, String model,
                                  int timeoutSeconds, List<Map<String, Object>> envVars) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("*")) {
            rtApiKey = apiKey;
        }
        if (baseUrl != null && !baseUrl.isBlank())  rtBaseUrl      = baseUrl;
        if (model   != null && !model.isBlank())     rtModel        = model;
        if (timeoutSeconds > 0)                      rtTimeout      = timeoutSeconds;
        if (envVars != null)                         rtEnvVars      = ensureDefaultEnvVar(normalizeEnvVarConfigs(envVars));

        // Persist to file
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("apiKey",         rtApiKey  != null ? rtApiKey  : "");
            m.put("baseUrl",        rtBaseUrl != null ? rtBaseUrl : defaultProps.getBaseUrl());
            m.put("model",          rtModel   != null ? rtModel   : defaultProps.getModel());
            m.put("timeoutSeconds", this.timeout());
            m.put("envVars",        envVarConfigs());
            JSON.writeValue(CONFIG_FILE, m);
        } catch (Exception e) {
            System.err.println("[WorldOne] Failed to persist config: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String nonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeEnvVarConfigs(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rowRaw)) continue;
            Map<String, Object> row = (Map<String, Object>) rowRaw;
            String key = nonBlank(str(row.get("key")), str(row.get("name"))).trim();
            if (key.isBlank()) continue;
            String globalValue = str(row.get("globalValue")).trim();
            Map<String, Object> appValuesRaw = map(row.get("appValues"));
            Map<String, String> appValues = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : appValuesRaw.entrySet()) {
                String appId = str(e.getKey()).trim();
                if (appId.isBlank()) continue;
                String value = str(e.getValue()).trim();
                if (!value.isBlank()) appValues.put(appId, value);
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("key", key);
            normalized.put("globalValue", globalValue);
            normalized.put("appValues", appValues);
            out.add(normalized);
        }
        return out;
    }

    private static List<Map<String, Object>> deepCopyEnvVarConfigs(List<Map<String, Object>> src) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : src) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("key", str(row.get("key")));
            copy.put("globalValue", str(row.get("globalValue")));
            Map<String, String> appValues = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map(row.get("appValues")).entrySet()) {
                appValues.put(e.getKey(), str(e.getValue()));
            }
            copy.put("appValues", appValues);
            out.add(copy);
        }
        return out;
    }

    private static List<Map<String, Object>> ensureDefaultEnvVar(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows == null ? List.of() : rows);
        boolean found = false;
        for (Map<String, Object> row : out) {
            String key = str(row.get("key")).trim();
            if (!"env".equalsIgnoreCase(key)) continue;
            found = true;
            String gv = str(row.get("globalValue")).trim();
            if (gv.isBlank()) row.put("globalValue", "production");
            break;
        }
        if (!found) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("key", "env");
            def.put("globalValue", "production");
            def.put("appValues", new LinkedHashMap<>());
            out.add(0, def);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return Map.of();
        return (Map<String, Object>) m;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    /** sk-abcd1234efgh5678  →  sk-****5678 */
    private static String maskKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
