package org.twelve.memoryone.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

/**
 * LLM 配置的运行时存储。
 *
 * <h2>优先级（高 → 低）</h2>
 * <ol>
 *   <li>application.yml {@code memory-one.llm.shared-config-file} 指定的 JSON 文件
 *       （默认 {@code ~/.worldone-config.json}，可通过环境变量 LLM_SHARED_CONFIG_FILE 覆盖）</li>
 *   <li>application.yml / 环境变量（LLM_API_KEY, LLM_BASE_URL, LLM_MODEL）</li>
 * </ol>
 */
@Component
public class MemoryOneConfigStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private LLMConfigProperties defaults;

    /** Resolved at startup from {@code memory-one.llm.shared-config-file}. */
    private File sharedConfigFile;

    private volatile String rtApiKey;
    private volatile String rtBaseUrl;
    private volatile String rtModel;
    private volatile int    rtTimeout;

    @PostConstruct
    void init() {
        sharedConfigFile = resolveFile(defaults.getSharedConfigFile());
        reload();
    }

    /** Re-read the shared config file so API key changes take effect without restart. */
    public void reload() {
        if (sharedConfigFile == null || !sharedConfigFile.exists()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JSON.readValue(sharedConfigFile, Map.class);
            rtApiKey  = (String) m.get("apiKey");
            rtBaseUrl = (String) m.get("baseUrl");
            rtModel   = (String) m.get("model");
            Object t  = m.get("timeoutSeconds");
            if (t instanceof Number n) rtTimeout = n.intValue();
        } catch (Exception e) {
            System.err.println("[MemoryOne] Failed to load shared config from "
                    + sharedConfigFile + ": " + e.getMessage());
        }
    }

    public String apiKey() {
        reload(); // re-read on every LLM call so changes in the shared file take effect immediately
        return nonBlank(rtApiKey, defaults.getApiKey());
    }

    public String baseUrl()  { return nonBlank(rtBaseUrl, defaults.getBaseUrl()); }
    public String model()    { return nonBlank(rtModel,   defaults.getModel()); }
    public int    timeout()  { return rtTimeout > 0 ? rtTimeout : defaults.getTimeoutSeconds(); }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Expand leading {@code ~} to the user home directory. */
    private static File resolveFile(String path) {
        if (path == null || path.isBlank()) return null;
        if (path.startsWith("~/") || path.equals("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return new File(path);
    }

    private static String nonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
