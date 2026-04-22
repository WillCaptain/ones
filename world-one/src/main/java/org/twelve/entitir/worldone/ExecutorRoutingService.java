package org.twelve.entitir.worldone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executor protocol routing:
 * 1) discover registrations from app /api/executors
 * 2) static match runtime event
 * 3) call handle_event skill
 */
@Service
public class ExecutorRoutingService {
    private static final Logger log = LoggerFactory.getLogger(ExecutorRoutingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int DEFAULT_PRIORITY = 1000;

    private final AppRegistry appRegistry;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Set<String> routedEventIds = ConcurrentHashMap.newKeySet();

    public ExecutorRoutingService(AppRegistry appRegistry) {
        this.appRegistry = appRegistry;
    }

    public List<Map<String, Object>> discoverRegistrations() {
        List<Map<String, Object>> all = new ArrayList<>();
        Map<String, AppRegistration> appMap = new LinkedHashMap<>();
        for (AppRegistration app : appRegistry.apps()) appMap.put(app.appId(), app);

        for (AppRegistration app : appMap.values()) {
            if ("worldone-system".equals(app.appId())) continue;
            try {
                String url = app.baseUrl() + "/api/executors";
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) continue;
                Map<String, Object> root = JSON.readValue(resp.body(), MAP_TYPE);
                Object arr = root.get("executors");
                if (!(arr instanceof List<?> list)) continue;
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> raw)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> reg = new LinkedHashMap<>((Map<String, Object>) raw);
                    reg.putIfAbsent("app_id", app.appId());
                    reg.put("_app_base_url", app.baseUrl());
                    all.add(reg);
                }
            } catch (Exception ignored) {
                // app may not implement executor protocol yet
            }
        }
        all.sort(Comparator.comparingInt(this::priorityOf));
        return all;
    }

    public Map<String, Object> routeDecisionEvent(String worldId, String env, Map<String, Object> runtimeEvent) {
        Objects.requireNonNull(runtimeEvent, "runtimeEvent");
        String eventId = str(runtimeEvent.get("id"));
        if (!eventId.isBlank() && !routedEventIds.add(eventId)) {
            return Map.of("status", "duplicate", "message", "event already routed", "event_id", eventId);
        }

        boolean needApproval = bool(runtimeEvent.get("needUserAction"))
                || bool(getMap(runtimeEvent.get("payload")).get("need_approval"));
        if (needApproval) {
            return Map.of("status", "skipped_need_approval", "event_id", eventId);
        }

        List<Map<String, Object>> candidates = discoverRegistrations().stream()
                .filter(reg -> staticMatch(reg, worldId, env, runtimeEvent))
                .sorted(Comparator.comparingInt(this::priorityOf))
                .toList();

        if (candidates.isEmpty()) {
            return autoDoneWithoutExecutor(worldId, env, runtimeEvent, eventId);
        }

        for (Map<String, Object> reg : candidates) {
            String appBase = str(reg.get("_app_base_url"));
            Map<String, Object> skills = getMap(reg.get("skills"));
            String handleSkill = str(skills.get("handle_event"));
            if (handleSkill.isBlank()) continue;
            try {
                String url = appBase + "/api/tools/" + handleSkill;
                String appId = str(reg.get("app_id"));
                Map<String, Object> reqBody = Map.of(
                        "args", appRegistry.injectEnvVars(appId, buildHandleRequest(reg, worldId, env, runtimeEvent))
                );
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(reqBody)))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> result = JSON.readValue(resp.body(), MAP_TYPE);

                String status = str(result.get("status"));
                if ("ignored".equalsIgnoreCase(status)) {
                    continue;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", status.isBlank() ? "accepted" : status);
                out.put("registration_id", str(reg.get("id")));
                out.put("app_id", str(reg.get("app_id")));
                out.put("event_id", eventId);
                out.put("result", result);
                if (isTerminalExecutorStatus(status)) {
                    Map<String, Object> flowback = flowbackDecisionResult(
                            worldId, env, runtimeEvent, status, str(result.get("message")));
                    out.put("flowback", flowback);
                }
                return out;
            } catch (Exception e) {
                log.warn("executor handle_event failed: reg={}, err={}", reg.get("id"), e.getMessage());
            }
        }

        return Map.of(
                "status", "no_handler",
                "event_id", eventId,
                "message", "matched registrations but no handle_event accepted"
        );
    }

    public Map<String, Object> processApproval(
            String worldId,
            String env,
            Map<String, Object> runtimeEvent,
            String approvalResult,
            String approvedBy,
            String comment
    ) {
        String ar = approvalResult == null ? "" : approvalResult.toLowerCase();
        if ("rejected".equals(ar)) {
            Map<String, Object> flowback = flowbackDecisionResult(worldId, env, runtimeEvent, "rejected", comment);
            return Map.of("status", "rejected", "flowback", flowback);
        }
        if (!"approved".equals(ar)) {
            return Map.of("status", "invalid_approval_result", "error", "result must be approved|rejected");
        }

        // 跳过 need_approval 拦截，携带审批上下文继续路由到 executor
        return routeDecisionEventWithContext(worldId, env, runtimeEvent, Map.of(
                "approval", Map.of(
                        "result", "approved",
                        "approved_by", approvedBy == null || approvedBy.isBlank() ? "user" : approvedBy,
                        "comment", comment == null ? "" : comment
                )
        ));
    }

    public Map<String, Object> resumeArgFlow(
            String worldId,
            String env,
            String registrationId,
            String appId,
            String suspensionId,
            String flowId,
            Map<String, Object> submittedParams
    ) {
        Map<String, Object> worldResume = resumeWorldAction(worldId, env, suspensionId, submittedParams);
        Map<String, Object> executorNotify = notifyExecutorResumeArgFlow(
                registrationId, appId, worldId, env, flowId, suspensionId, submittedParams
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("world_resume", worldResume);
        out.put("executor_notify", executorNotify);
        return out;
    }

    private Map<String, Object> routeDecisionEventWithContext(
            String worldId,
            String env,
            Map<String, Object> runtimeEvent,
            Map<String, Object> extraContext
    ) {
        String eventId = str(runtimeEvent.get("id"));
        List<Map<String, Object>> candidates = discoverRegistrations().stream()
                .filter(reg -> staticMatch(reg, worldId, env, runtimeEvent))
                .sorted(Comparator.comparingInt(this::priorityOf))
                .toList();
        if (candidates.isEmpty()) {
            return autoDoneWithoutExecutor(worldId, env, runtimeEvent, eventId);
        }
        for (Map<String, Object> reg : candidates) {
            String appBase = str(reg.get("_app_base_url"));
            Map<String, Object> skills = getMap(reg.get("skills"));
            String handleSkill = str(skills.get("handle_event"));
            if (handleSkill.isBlank()) continue;
            try {
                String url = appBase + "/api/tools/" + handleSkill;
                String appId = str(reg.get("app_id"));
                Map<String, Object> reqBody = Map.of(
                        "args", appRegistry.injectEnvVars(appId, buildHandleRequest(reg, worldId, env, runtimeEvent, extraContext))
                );
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(reqBody)))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> result = JSON.readValue(resp.body(), MAP_TYPE);
                String status = str(result.get("status"));
                if ("ignored".equalsIgnoreCase(status)) continue;

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", status.isBlank() ? "accepted" : status);
                out.put("registration_id", str(reg.get("id")));
                out.put("app_id", str(reg.get("app_id")));
                out.put("event_id", eventId);
                out.put("result", result);
                if (isTerminalExecutorStatus(status)) {
                    out.put("flowback", flowbackDecisionResult(
                            worldId, env, runtimeEvent, status, str(result.get("message"))));
                }
                return out;
            } catch (Exception e) {
                log.warn("executor handle_event(with context) failed: reg={}, err={}", reg.get("id"), e.getMessage());
            }
        }
        return Map.of("status", "no_handler", "event_id", eventId);
    }

    boolean staticMatch(Map<String, Object> reg, String worldId, String env, Map<String, Object> event) {
        String eventType = str(event.getOrDefault("eventType", event.get("event_type")));
        String sourceType = str(event.get("sourceType"));
        Map<String, Object> payload = getMap(event.get("payload"));

        List<Map<String, Object>> handles = getMapList(reg.get("handles"));
        if (!handles.isEmpty()) {
            boolean ok = handles.stream().anyMatch(h ->
                    isEmptyOrContains(str(h.get("input_event")), eventType)
                            && isEmptyOrContains(str(h.get("source_type")), sourceType));
            if (!ok) return false;
        }

        Map<String, Object> ws = getMap(reg.get("world_selector"));
        if (!matchList(ws.get("world_ids"), worldId)) return false;
        if (!matchList(ws.get("envs"), env)) return false;

        Map<String, Object> match = getMap(reg.get("match"));
        if (!matchList(match.get("event_types"), eventType)) return false;
        if (!matchList(match.get("entity_types"), str(payload.getOrDefault("target_ontology", payload.get("entity_type"))))) return false;
        if (!matchList(match.get("operations"), str(payload.get("operation")))) return false;
        if (!matchList(match.get("decision_template_ids"), str(payload.getOrDefault("template_id", payload.get("decision_template_id"))))) return false;
        if (!matchList(match.get("action_names"), str(payload.get("action_name")))) return false;
        return matchList(match.get("statuses"), str(payload.get("status")));
    }

    private Map<String, Object> buildHandleRequest(
            Map<String, Object> reg,
            String worldId,
            String env,
            Map<String, Object> event
    ) {
        return buildHandleRequest(reg, worldId, env, event, Map.of());
    }

    private Map<String, Object> buildHandleRequest(
            Map<String, Object> reg,
            String worldId,
            String env,
            Map<String, Object> event,
            Map<String, Object> extraContext
    ) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("registration_id", reg.get("id"));
        req.put("world", Map.of("world_id", worldId, "env", env));
        req.put("event", event);
        Map<String, Object> context = new LinkedHashMap<>(Map.of(
                "need_approval", bool(event.get("needUserAction")) || bool(getMap(event.get("payload")).get("need_approval"))
        ));
        if (extraContext != null && !extraContext.isEmpty()) context.putAll(extraContext);
        req.put("context", context);
        return req;
    }

    static boolean isTerminalExecutorStatus(String status) {
        String s = status == null ? "" : status.toLowerCase();
        return "done".equals(s) || "rejected".equals(s) || "failed".equals(s);
    }

    private Map<String, Object> autoDoneWithoutExecutor(
            String worldId,
            String env,
            Map<String, Object> runtimeEvent,
            String eventId
    ) {
        Map<String, Object> flowback = flowbackDecisionResult(
                worldId, env, runtimeEvent, "done", "no executor configured; auto-completed by host");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "done");
        out.put("dispatch", "auto_done_without_executor");
        out.put("event_id", eventId);
        out.put("world_id", worldId);
        out.put("flowback", flowback);
        return out;
    }

    private Map<String, Object> flowbackDecisionResult(
            String worldId,
            String env,
            Map<String, Object> runtimeEvent,
            String result,
            String reason
    ) {
        try {
            AppRegistration worldApp = appRegistry.findAppForTool("world_register_action");
            Map<String, Object> payload = getMap(runtimeEvent.get("payload"));
            String decisionId = str(payload.get("decision_id"));
            if (decisionId.isBlank()) {
                return Map.of("status", "skipped", "reason", "missing decision_id");
            }
            String url = worldApp.baseUrl()
                    + "/api/worlds/" + java.net.URLEncoder.encode(worldId, java.nio.charset.StandardCharsets.UTF_8)
                    + "/decision-result";
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("env", env);
            reqBody.put("decision_id", decisionId);
            reqBody.put("result", result.toLowerCase());
            if (reason != null && !reason.isBlank()) reqBody.put("reason", reason);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(reqBody)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = JSON.readValue(resp.body(), MAP_TYPE);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("http_status", resp.statusCode());
            out.put("body", body);
            return out;
        } catch (Exception e) {
            return Map.of(
                    "status", "error",
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            );
        }
    }

    private Map<String, Object> resumeWorldAction(
            String worldId,
            String env,
            String suspensionId,
            Map<String, Object> submittedParams
    ) {
        try {
            AppRegistration worldApp = appRegistry.findAppForTool("world_register_action");
            String url = worldApp.baseUrl()
                    + "/api/worlds/" + java.net.URLEncoder.encode(worldId, java.nio.charset.StandardCharsets.UTF_8)
                    + "/resume-action";
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("env", env);
            reqBody.put("suspension_id", suspensionId);
            reqBody.put("params", submittedParams == null ? Map.of() : submittedParams);
            reqBody.put("supplied_by", "executor-router");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(reqBody)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return Map.of(
                    "http_status", resp.statusCode(),
                    "body", JSON.readValue(resp.body(), MAP_TYPE)
            );
        } catch (Exception e) {
            return Map.of(
                    "status", "error",
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            );
        }
    }

    private Map<String, Object> notifyExecutorResumeArgFlow(
            String registrationId,
            String appId,
            String worldId,
            String env,
            String flowId,
            String suspensionId,
            Map<String, Object> submittedParams
    ) {
        try {
            Map<String, Object> reg = null;
            for (Map<String, Object> r : discoverRegistrations()) {
                if (!registrationId.isBlank() && registrationId.equals(str(r.get("id")))) {
                    reg = r; break;
                }
                if (registrationId.isBlank() && !appId.isBlank() && appId.equals(str(r.get("app_id")))) {
                    reg = r; break;
                }
            }
            if (reg == null) return Map.of("status", "skipped", "reason", "registration not found");

            Map<String, Object> skills = getMap(reg.get("skills"));
            String resumeSkill = str(skills.get("resume_arg_flow"));
            if (resumeSkill.isBlank()) {
                return Map.of("status", "skipped", "reason", "resume_arg_flow not configured");
            }
            String appBase = str(reg.get("_app_base_url"));
            String url = appBase + "/api/tools/" + resumeSkill;
            Map<String, Object> reqArgs = new LinkedHashMap<>();
            reqArgs.put("registration_id", str(reg.get("id")));
            reqArgs.put("world", Map.of("world_id", worldId, "env", env));
            reqArgs.put("flow", Map.of(
                    "flow_id", flowId == null ? "" : flowId,
                    "suspension_id", suspensionId == null ? "" : suspensionId,
                    "submitted_params", submittedParams == null ? Map.of() : submittedParams
            ));
            reqArgs = appRegistry.injectEnvVars(str(reg.get("app_id")), reqArgs);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("args", reqArgs))))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return Map.of(
                    "http_status", resp.statusCode(),
                    "body", JSON.readValue(resp.body(), MAP_TYPE)
            );
        } catch (Exception e) {
            return Map.of(
                    "status", "error",
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            );
        }
    }

    private int priorityOf(Map<String, Object> reg) {
        Object p = reg.get("priority");
        if (p instanceof Number n) return n.intValue();
        try {
            return p == null ? DEFAULT_PRIORITY : Integer.parseInt(String.valueOf(p));
        } catch (Exception ignored) {
            return DEFAULT_PRIORITY;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(str(v));
    }

    private static Map<String, Object> getMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            return typed;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> getMapList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                out.add(typed);
            }
        }
        return out;
    }

    private static boolean isEmptyOrContains(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private static boolean matchList(Object listObj, String actual) {
        if (!(listObj instanceof List<?> list) || list.isEmpty()) return true;
        for (Object o : list) {
            if (o != null && String.valueOf(o).equals(actual)) return true;
        }
        return false;
    }
}

