package org.twelve.memoryone.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml memory-one.llm.* 映射。
 */
@ConfigurationProperties(prefix = "memory-one.llm")
public class LLMConfigProperties {

    private String apiKey         = "";
    private String baseUrl        = "https://api.deepseek.com/v1";
    private String model          = "deepseek-chat";
    private int    timeoutSeconds = 120;
    private String sharedConfigFile = "";

    public String getApiKey()                { return apiKey; }
    public void   setApiKey(String v)        { apiKey = v; }
    public String getBaseUrl()               { return baseUrl; }
    public void   setBaseUrl(String v)       { baseUrl = v; }
    public String getModel()                 { return model; }
    public void   setModel(String v)         { model = v; }
    public int    getTimeoutSeconds()        { return timeoutSeconds; }
    public void   setTimeoutSeconds(int v)   { timeoutSeconds = v; }
    public String getSharedConfigFile()      { return sharedConfigFile; }
    public void   setSharedConfigFile(String v) { sharedConfigFile = v; }
}
