package org.twelve.entitir.worldone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保护 GenericAgentLoop.requiresUserConfirmation() 的行为。
 *
 * <p>该方法决定 LLM 调用工具后是否需要等待用户在 sys.* 确认框中操作，
 * 若检测失败会导致 LLM 继续生成回复（误以为操作已完成）或错误地跳过确认步骤。
 */
@DisplayName("GenericAgentLoop - requiresUserConfirmation()")
class RequiresUserConfirmationTest {

    // ── 应该返回 true 的情况 ───────────────────────────────────────────────

    @Test
    @DisplayName("status=awaiting_confirmation 时返回 true")
    void detectsAwaitingConfirmationStatus() {
        String result = """
                {"ok":true,"status":"awaiting_confirmation","message":"请确认","canvas":{}}
                """;
        assertThat(GenericAgentLoop.requiresUserConfirmation(result)).isTrue();
    }

    @Test
    @DisplayName("canvas.widget_type 以 sys. 开头时返回 true")
    void detectsSysWidgetType() {
        String result = """
                {"ok":true,"canvas":{"action":"open","widget_type":"sys.confirm","data":{}}}
                """;
        assertThat(GenericAgentLoop.requiresUserConfirmation(result)).isTrue();
    }

    @Test
    @DisplayName("sys.confirm 带完整 confirm data 返回 true（真实 deleteRequest 响应格式）")
    void detectsRealDeleteRequestResponse() {
        String result = """
                {
                  "ok": true,
                  "status": "awaiting_confirmation",
                  "message": "确认框已显示，等待用户确认。删除操作尚未执行。",
                  "canvas": {
                    "action": "open",
                    "widget_type": "sys.confirm",
                    "data": {
                      "mode": "yes_no",
                      "title": "确认删除记忆",
                      "message": "确定要删除以下记忆吗？此操作不可撤销。\\n\\n• 今天发了工资",
                      "danger": true,
                      "yes": {"tool": "memory_delete_confirmed", "args": {"ids": ["abc-123"]}},
                      "no":  {"message": "已取消删除操作"}
                    }
                  }
                }
                """;
        assertThat(GenericAgentLoop.requiresUserConfirmation(result)).isTrue();
    }

    @Test
    @DisplayName("sys.alert 也应触发确认等待")
    void detectsSysAlert() {
        String result = """
                {"ok":true,"canvas":{"action":"open","widget_type":"sys.alert","data":{"message":"注意"}}}
                """;
        assertThat(GenericAgentLoop.requiresUserConfirmation(result)).isTrue();
    }

    // ── 应该返回 false 的情况 ──────────────────────────────────────────────

    @Test
    @DisplayName("普通工具响应 ok=true 不触发确认")
    void normalToolResultReturnsFalse() {
        String result = """
                {"ok":true,"message":"操作已完成"}
                """;
        assertThat(GenericAgentLoop.requiresUserConfirmation(result)).isFalse();
    }

    @Test
    @DisplayName("canvas 打开非 sys.* widget 不触发确认（由 session 事件处理）")
    void nonSysWidgetReturnsFalse() {
        String result = """
                {"ok":true,"canvas":{"action":"open","widget_type":"memory-manager","graph":{}}}
                """;
        assertThat(GenericAgentLoop.requiresUserConfirmation(result)).isFalse();
    }

    @Test
    @DisplayName("空 canvas 不触发确认")
    void emptyCanvasReturnsFalse() {
        String result = """
                {"ok":true,"canvas":{}}
                """;
        assertThat(GenericAgentLoop.requiresUserConfirmation(result)).isFalse();
    }

    @Test
    @DisplayName("null 输入安全返回 false")
    void nullInputReturnsFalse() {
        assertThat(GenericAgentLoop.requiresUserConfirmation(null)).isFalse();
    }

    @Test
    @DisplayName("空字符串安全返回 false")
    void emptyInputReturnsFalse() {
        assertThat(GenericAgentLoop.requiresUserConfirmation("")).isFalse();
    }

    @Test
    @DisplayName("非 JSON 字符串安全返回 false（不抛异常）")
    void invalidJsonReturnsFalse() {
        assertThat(GenericAgentLoop.requiresUserConfirmation("not json at all")).isFalse();
    }
}
