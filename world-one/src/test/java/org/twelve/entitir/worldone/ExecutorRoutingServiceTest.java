package org.twelve.entitir.worldone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ExecutorRoutingService static match")
class ExecutorRoutingServiceTest {

    @Test
    @DisplayName("decision 事件命中 handles + world_selector + match")
    void staticMatch_true_whenAllFiltersPass() {
        ExecutorRoutingService svc = new ExecutorRoutingService(mock(AppRegistry.class));
        Map<String, Object> reg = Map.of(
                "handles", List.of(Map.of("input_event", "decision", "source_type", "DecisionEvent")),
                "world_selector", Map.of("world_ids", List.of("hr"), "envs", List.of("draft")),
                "match", Map.of(
                        "event_types", List.of("decision"),
                        "entity_types", List.of("Employee"),
                        "decision_template_ids", List.of("onboarding_started"),
                        "statuses", List.of("DECIDED")
                )
        );
        Map<String, Object> event = Map.of(
                "eventType", "decision",
                "sourceType", "DecisionEvent",
                "payload", Map.of(
                        "target_ontology", "Employee",
                        "template_id", "onboarding_started",
                        "status", "DECIDED"
                )
        );
        assertThat(svc.staticMatch(reg, "hr", "draft", event)).isTrue();
    }

    @Test
    @DisplayName("world/env 不匹配时拒绝")
    void staticMatch_false_whenWorldSelectorFails() {
        ExecutorRoutingService svc = new ExecutorRoutingService(mock(AppRegistry.class));
        Map<String, Object> reg = Map.of(
                "handles", List.of(Map.of("input_event", "decision", "source_type", "DecisionEvent")),
                "world_selector", Map.of("world_ids", List.of("finance"), "envs", List.of("production")),
                "match", Map.of("event_types", List.of("decision"))
        );
        Map<String, Object> event = Map.of(
                "eventType", "decision",
                "sourceType", "DecisionEvent",
                "payload", Map.of("status", "DECIDED")
        );
        assertThat(svc.staticMatch(reg, "hr", "draft", event)).isFalse();
    }

    @Test
    @DisplayName("terminal status 判定：done/rejected/failed 为终态")
    void terminalStatus_detection() {
        assertThat(ExecutorRoutingService.isTerminalExecutorStatus("done")).isTrue();
        assertThat(ExecutorRoutingService.isTerminalExecutorStatus("rejected")).isTrue();
        assertThat(ExecutorRoutingService.isTerminalExecutorStatus("failed")).isTrue();
        assertThat(ExecutorRoutingService.isTerminalExecutorStatus("accepted")).isFalse();
    }

    @Test
    @DisplayName("approval result 非法时返回 invalid_approval_result")
    void processApproval_invalidResult() {
        ExecutorRoutingService svc = new ExecutorRoutingService(mock(AppRegistry.class));
        Map<String, Object> out = svc.processApproval("hr", "draft", Map.of("id", "evt-1"), "maybe", "u1", "");
        assertThat(out.get("status")).isEqualTo("invalid_approval_result");
    }

    @Test
    @DisplayName("无 executor 且不需要审批时，host 自动 done")
    void routeDecisionEvent_autoDone_whenNoExecutorConfigured() {
        AppRegistry registry = mock(AppRegistry.class);
        when(registry.apps()).thenReturn(List.of());
        when(registry.findAppForTool("world_register_action")).thenReturn(
                new AppRegistration("world", "world", "http://localhost:1", "", List.of(), List.of(), List.of())
        );
        ExecutorRoutingService svc = new ExecutorRoutingService(registry) {
            @Override
            public List<Map<String, Object>> discoverRegistrations() {
                return List.of();
            }
        };
        Map<String, Object> event = Map.of(
                "id", "evt-auto-done",
                "eventType", "decision",
                "payload", Map.of(
                        "decision_id", "dec-1",
                        "need_approval", false
                )
        );
        Map<String, Object> out = svc.routeDecisionEvent("hr", "production", event);
        assertThat(out.get("status")).isEqualTo("done");
        assertThat(out.get("dispatch")).isEqualTo("auto_done_without_executor");
    }
}

