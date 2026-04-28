package org.twelve.entitir.worldone.frontend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guard test: enforces feature-by-folder discipline on the host shell.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code index.html} ≤ 200 lines (skeleton only).</li>
 *   <li>No html/js/css file under {@code static/} exceeds 800 lines (soft cap).</li>
 *   <li>Every direct child of {@code static/shell/} is a directory whose name
 *       appears in {@link #ALLOWED_SHELL_FEATURES}; adding a feature requires
 *       updating that set, forcing reviewers to think.</li>
 * </ul>
 *
 * <p>Allowed to fail during migration; turns green at end of Phase 1+5.
 */
public class HostFileSizeByFeatureTest {

    /** Update this when adding a genuinely new host-shell feature. */
    private static final Set<String> ALLOWED_SHELL_FEATURES = Set.of(
            "app-shell",            // boot module
            "session-sidebar",      // session list / filters / new
            "chat-stream",          // SSE + message rendering
            "canvas-host",          // iframe mount + postMessage routing
            "html-widget-renderer", // inline html_widget cards
            "login",                // login form
            "settings",             // settings dialog
            "theme",                // CSS variables / theme switch
            "app-list",             // host-owned app list (worldone-system builtin)
            "i18n",                 // translation tables
            "shell.css"             // top-level CSS file (allowed alongside dirs)
    );

    private static final Path STATIC_ROOT = Paths.get(
            System.getProperty("user.dir"),
            "src", "main", "resources", "static");

    private static final int MAX_INDEX_LINES = 200;
    private static final int MAX_FILE_LINES = 800;

    @Test
    void indexHtmlIsSkeletonOnly() throws IOException {
        Path index = STATIC_ROOT.resolve("index.html");
        assumeExists(index);
        long lines = countLines(index);
        assertTrue(lines <= MAX_INDEX_LINES,
                "index.html must be ≤ " + MAX_INDEX_LINES + " lines (skeleton + module entry only); got " + lines);
    }

    @Test
    void noStaticFileExceedsSoftCap() throws IOException {
        if (!Files.isDirectory(STATIC_ROOT)) return;
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(STATIC_ROOT)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> {
                     String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                     // allow vendored libraries (marked.min.js etc.)
                     if (n.endsWith(".min.js")) return false;
                     if (n.equals("outline-lang.js")) return false; // vendored grammar
                     return n.endsWith(".html") || n.endsWith(".js") || n.endsWith(".css");
                 })
                 .forEach(p -> {
                     try {
                         long n = countLines(p);
                         if (n > MAX_FILE_LINES) {
                             offenders.add(STATIC_ROOT.relativize(p) + " (" + n + " lines)");
                         }
                     } catch (IOException ignored) { }
                 });
        }
        assertTrue(offenders.isEmpty(),
                "Files exceeding " + MAX_FILE_LINES + " lines:\n  " + String.join("\n  ", offenders));
    }

    @Test
    void shellFolderContainsOnlyKnownFeatures() throws IOException {
        Path shell = STATIC_ROOT.resolve("shell");
        if (!Files.isDirectory(shell)) {
            System.out.println("[HostFileSizeByFeatureTest] static/shell/ does not exist yet (pre-Phase 1) — skipping.");
            return;
        }
        List<String> unknown = new ArrayList<>();
        try (Stream<Path> entries = Files.list(shell)) {
            entries.forEach(p -> {
                String name = p.getFileName().toString();
                String key = Files.isDirectory(p) ? name : name; // file or dir compared by name
                if (!ALLOWED_SHELL_FEATURES.contains(key)) unknown.add(name);
            });
        }
        assertTrue(unknown.isEmpty(),
                "Unknown shell features (add to ALLOWED_SHELL_FEATURES if intentional): " + unknown);
    }

    private static void assumeExists(Path p) {
        if (!Files.exists(p)) throw new org.opentest4j.TestAbortedException("missing: " + p);
    }

    private static long countLines(Path p) throws IOException {
        try (Stream<String> s = Files.lines(p)) {
            return s.count();
        }
    }
}
