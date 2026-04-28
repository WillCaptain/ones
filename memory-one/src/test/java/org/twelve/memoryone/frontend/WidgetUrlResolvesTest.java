package org.twelve.memoryone.frontend;

import org.junit.jupiter.api.Test;
import org.twelve.aipp.frontend.WidgetGuardSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 guard test (memory-one flavor). See {@code WidgetGuardSupport}.
 */
public class WidgetUrlResolvesTest {

    private static final String BASE_URL = "http://localhost:8091";

    @Test
    void allWidgetUrlsServeHtml() {
        var result = WidgetGuardSupport.checkAllWidgetUrls(BASE_URL);
        if (result.skipped()) {
            System.out.println("[WidgetUrlResolvesTest] skipped: " + result.failures());
            return;
        }
        assertTrue(result.failures().isEmpty(),
                "Widget URL failures:\n  " + String.join("\n  ", result.failures()));
    }
}
