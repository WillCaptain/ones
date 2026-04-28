package org.twelve.memoryone.frontend;

import org.junit.jupiter.api.Test;
import org.twelve.aipp.frontend.WidgetGuardSupport;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class WidgetNoHostCouplingTest {

    private static final Path WIDGETS_ROOT = Paths.get(
            System.getProperty("user.dir"),
            "src", "main", "resources", "static", "widgets");

    @Test
    void widgetsDoNotCoupleWithHost() {
        List<String> hits = WidgetGuardSupport.scanWidgetCoupling(WIDGETS_ROOT);
        assertTrue(hits.isEmpty(),
                "Host-coupling violations in widgets:\n  " + String.join("\n  ", hits));
    }
}
