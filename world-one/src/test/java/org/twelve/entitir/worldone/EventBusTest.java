package org.twelve.entitir.worldone;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Host 通用事件总线：
 * <ul>
 *   <li>app 通过 {@code /api/tools.event_subscriptions} 声明订阅；</li>
 *   <li>{@link AppRegistry#publishEvent} POST 到 app 的 {@code /api/events}（payload {@code {type, data}}）；</li>
 *   <li>未订阅事件不投递。</li>
 * </ul>
 */
class EventBusTest {
    static {
        System.setProperty("ones.apps.root",
            System.getProperty("java.io.tmpdir") + "/ones-test-empty-" + System.nanoTime());
    }


    @Test
    void publishEvent_deliversToSubscribedApp() throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // /api/tools 返回订阅声明
        server.createContext("/api/tools", ex -> {
            String body = "{"
                + "\"app\":\"sub\",\"version\":\"1.0\","
                + "\"system_prompt\":\"\","
                + "\"tools\":[],"
                + "\"event_subscriptions\":[\"workspace.changed\"]"
                + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.createContext("/api/widgets", ex -> {
            byte[] b = "{\"widgets\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/events", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.complete(body);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AppRegistry reg = new AppRegistry();
            reg.install("sub", "http://127.0.0.1:" + port);

            reg.publishEvent("workspace.changed",
                Map.of("workspace_id", "w1", "workspace_title", "T", "user_id", "u"));

            String body = received.get(5, TimeUnit.SECONDS);
            assertThat(body).contains("\"type\":\"workspace.changed\"");
            assertThat(body).contains("\"workspace_id\":\"w1\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishEvent_unsubscribedEventNotDelivered() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean[] hit = {false};
        server.createContext("/api/tools", ex -> {
            byte[] b = ("{\"app\":\"sub\",\"version\":\"1.0\","
                    + "\"system_prompt\":\"\",\"tools\":[],"
                    + "\"event_subscriptions\":[\"workspace.changed\"]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/widgets", ex -> {
            byte[] b = "{\"widgets\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/events", ex -> { hit[0] = true; ex.sendResponseHeaders(200, -1); ex.close(); });
        server.start();
        try {
            AppRegistry reg = new AppRegistry();
            reg.install("sub", "http://127.0.0.1:" + server.getAddress().getPort());
            reg.publishEvent("nope.changed", Map.of("k", "v"));
            try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            assertThat(hit[0]).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
