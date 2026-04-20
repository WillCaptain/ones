package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行时可切换的调试开关集合。所有开关默认关闭（生产态），通过
 * {@code application.properties} 预设或 {@code POST /api/debug/flags} 动态翻转。
 *
 * <p>开关读取走 {@link AtomicBoolean#get()}（volatile 语义），对 hot path 影响极小，
 * 关闭状态下几乎零成本。
 *
 * <h2>当前开关</h2>
 * <ul>
 *   <li>{@code agent_loop} — 打印 {@code GenericAgentLoop} 的思考过程：
 *     用户消息、visible skills、tool list、每轮 LLM 决策、tool 调用、load_skill 拦截、文本输出等</li>
 * </ul>
 */
@Component
public class DebugFlags {

    private final AtomicBoolean agentLoop = new AtomicBoolean(false);

    public DebugFlags(
            @Value("${worldone.debug.agent_loop:false}") boolean agentLoopDefault) {
        this.agentLoop.set(agentLoopDefault);
    }

    public boolean isAgentLoopEnabled() { return agentLoop.get(); }

    public void setAgentLoopEnabled(boolean v) { agentLoop.set(v); }

    /** 当前所有 flag 的快照（供 REST 展示）。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent_loop", agentLoop.get());
        return m;
    }
}
