package org.twelve.entitir.worldone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 调试开关 REST 端点 —— 让运维/开发在不重启服务的情况下翻转 {@link DebugFlags}。
 *
 * <pre>
 * GET  /api/debug/flags                           → 返回当前所有 flag
 * POST /api/debug/flags {"agent_loop": true}      → 翻转 agent_loop
 * POST /api/debug/flags/agent_loop?enabled=true   → 便捷单字段切换
 * </pre>
 *
 * <p>默认所有 flag 关闭；生产环境保持默认即可。
 */
@RestController
@RequestMapping("/api/debug/flags")
public class DebugFlagsController {

    private static final Logger log = LoggerFactory.getLogger(DebugFlagsController.class);

    @Autowired
    private DebugFlags flags;

    @GetMapping
    public Map<String, Object> get() {
        return flags.snapshot();
    }

    @PostMapping
    public Map<String, Object> setAll(@RequestBody Map<String, Object> body) {
        if (body != null && body.get("agent_loop") instanceof Boolean v) {
            flags.setAgentLoopEnabled(v);
            log.info("[DebugFlags] agent_loop toggled → {}", v);
        }
        return flags.snapshot();
    }

    @PostMapping("/agent_loop")
    public Map<String, Object> setAgentLoop(@RequestParam("enabled") boolean enabled) {
        flags.setAgentLoopEnabled(enabled);
        log.info("[DebugFlags] agent_loop toggled → {}", enabled);
        return flags.snapshot();
    }
}
