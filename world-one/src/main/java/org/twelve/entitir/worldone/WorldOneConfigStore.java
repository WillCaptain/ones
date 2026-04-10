package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.LinkedHashMap;
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
            } catch (Exception e) {
                System.err.println("[WorldOne] Failed to load config file: " + e.getMessage());
            }
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
        return m;
    }

    // ── writer ────────────────────────────────────────────────────────────────

    /**
     * 保存新配置，持久化到 ~/.worldone-config.json。
     * apiKey 为空字符串时不覆盖已有 key。
     */
    public synchronized void save(String apiKey, String baseUrl, String model, int timeoutSeconds) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("*")) {
            rtApiKey = apiKey;
        }
        if (baseUrl != null && !baseUrl.isBlank())  rtBaseUrl      = baseUrl;
        if (model   != null && !model.isBlank())     rtModel        = model;
        if (timeoutSeconds > 0)                      rtTimeout      = timeoutSeconds;

        // Persist to file
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("apiKey",         rtApiKey  != null ? rtApiKey  : "");
            m.put("baseUrl",        rtBaseUrl != null ? rtBaseUrl : defaultProps.getBaseUrl());
            m.put("model",          rtModel   != null ? rtModel   : defaultProps.getModel());
            m.put("timeoutSeconds", this.timeout());
            JSON.writeValue(CONFIG_FILE, m);
        } catch (Exception e) {
            System.err.println("[WorldOne] Failed to persist config: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String nonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /** sk-abcd1234efgh5678  →  sk-****5678 */
    private static String maskKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
