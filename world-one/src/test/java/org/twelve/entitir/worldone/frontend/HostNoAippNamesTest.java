package org.twelve.entitir.worldone.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guard test (Phase 0 of frontend decoupling).
 *
 * <p>Scans every host static asset (html/js/css) under
 * {@code world-one/src/main/resources/static/} and fails if any token that
 * belongs to a registered AIPP app appears.
 *
 * <p>The dictionary of forbidden tokens is built at runtime by hitting each
 * app's {@code /api/app}, {@code /api/tools}, {@code /api/widgets}. <b>No
 * hardcoded blacklists.</b> If an AIPP is offline its tokens are skipped (the
 * test still runs but warns).
 *
 * <p>A small whitelist of generic UI words is kept inline to avoid false
 * positives from unrelated host code (e.g. "session", "chat").
 *
 * <p>This test is allowed to fail until the migration is complete; it is the
 * acceptance criterion for "frontend is decoupled".
 */
public class HostNoAippNamesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Generic UI vocabulary that may legitimately appear in host static.
     *  KEEP THIS LIST MINIMAL. Do NOT add AIPP app-ids here to make the
     *  test pass — that defeats the entire purpose. If a real collision
     *  exists (e.g. CSS `outline:` keyword vs `outline` app-id), fix it
     *  by renaming the app-id, scoping the CSS, or adding a more precise
     *  token form (e.g. require a non-word boundary check) — not by
     *  whitelisting the app-id. */
    private static final Set<String> WHITELIST = Set.of(
            "session", "sessions", "chat", "message", "messages", "canvas",
            "widget", "widgets", "settings", "login", "app", "apps",
            "tool", "tools", "skill", "skills", "view", "views"
    );

    /** Apps registry root — same convention as {@code AppRegistry.APPS_ROOT}. */
    private static final Path APPS_ROOT = Paths.get(
            System.getProperty("user.home"), ".ones", "apps");

    private static final Path STATIC_ROOT = Paths.get(
            System.getProperty("user.dir"),
            "src", "main", "resources", "static");

    @Test
    void hostStaticContainsNoAippSpecificTokens() throws Exception {
        Set<String> forbidden = buildForbiddenDictionary();
        if (forbidden.isEmpty()) {
            System.out.println("[HostNoAippNamesTest] No AIPPs reachable — skipping (no dictionary).");
            return;
        }
        System.out.println("[HostNoAippNamesTest] forbidden tokens: " + forbidden);

        if (!Files.isDirectory(STATIC_ROOT)) {
            fail("Static root not found: " + STATIC_ROOT);
        }

        Map<Path, List<String>> hits = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(STATIC_ROOT)) {
            files.filter(Files::isRegularFile)
                 .filter(HostNoAippNamesTest::isScannableFile)
                 .forEach(p -> {
                     String content;
                     try { content = Files.readString(p); }
                     catch (IOException e) { return; }
                     for (String token : forbidden) {
                         // word-boundary-ish match: also catch kebab-case fragments
                         Pattern pat = Pattern.compile(
                                 "(?i)(?<![A-Za-z0-9_-])" + Pattern.quote(token) + "(?![A-Za-z0-9_-])");
                         Matcher m = pat.matcher(content);
                         if (m.find()) {
                             hits.computeIfAbsent(p, k -> new ArrayList<>()).add(token);
                         }
                     }
                 });
        }

        if (!hits.isEmpty()) {
            StringBuilder sb = new StringBuilder("Host static still contains AIPP-specific tokens:\n");
            hits.forEach((p, toks) ->
                sb.append("  ").append(STATIC_ROOT.relativize(p))
                  .append(" → ").append(toks).append('\n'));
            sb.append("\nMove the offending UI to the owning AIPP's static/widgets/ directory.");
            fail(sb.toString());
        }
    }

    /** Vendored / generic libraries that may legitimately mention domain words. */
    private static final Set<String> VENDORED = Set.of(
            "marked.min.js",   // markdown lib
            "outline-lang.js"  // shared outline grammar definition (cross-AIPP)
    );

    private static boolean isScannableFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".min.js")) return false;
        if (VENDORED.contains(n)) return false;
        return n.endsWith(".html") || n.endsWith(".js") || n.endsWith(".css")
                || n.endsWith(".mjs");
    }

    /** Build the forbidden-token dictionary from live AIPP endpoints. */
    private Set<String> buildForbiddenDictionary() throws IOException {
        Set<String> tokens = new TreeSet<>();
        if (!Files.isDirectory(APPS_ROOT)) return tokens;

        try (Stream<Path> dirs = Files.list(APPS_ROOT)) {
            dirs.filter(Files::isDirectory).forEach(appDir -> {
                Path manifestFile = appDir.resolve("manifest.json");
                if (!Files.exists(manifestFile)) return;
                try {
                    JsonNode m = JSON.readTree(Files.readString(manifestFile));
                    String appId = m.path("id").asText("");
                    String baseUrl = m.path("api").path("base_url").asText("");
                    if (appId.isBlank() || baseUrl.isBlank()) return;

                    addToken(tokens, appId);

                    JsonNode app = httpJson(baseUrl + "/api/app");
                    if (app != null) {
                        addToken(tokens, app.path("app_id").asText(null));
                        addToken(tokens, app.path("app_name").asText(null));
                        addToken(tokens, app.path("display_name").asText(null));
                    }

                    JsonNode tools = httpJson(baseUrl + "/api/tools");
                    if (tools != null) {
                        for (JsonNode t : tools.path("tools")) {
                            addToken(tokens, t.path("name").asText(null));
                        }
                    }

                    JsonNode widgets = httpJson(baseUrl + "/api/widgets");
                    if (widgets != null) {
                        for (JsonNode w : widgets.path("widgets")) {
                            addToken(tokens, w.path("type").asText(null));
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[HostNoAippNamesTest] skip app dir " + appDir + ": " + e.getMessage());
                }
            });
        }

        // Strip whitelist + drop anything obviously generic (≤2 chars).
        tokens.removeIf(t -> t.length() < 3 || WHITELIST.contains(t.toLowerCase(Locale.ROOT)));
        return tokens;
    }

    private static void addToken(Set<String> out, String s) {
        if (s == null || s.isBlank()) return;
        // Add the raw token AND its kebab/snake variants — front-end may use either form.
        String trimmed = s.trim();
        out.add(trimmed);
        if (trimmed.contains("_")) out.add(trimmed.replace('_', '-'));
        if (trimmed.contains("-")) out.add(trimmed.replace('-', '_'));
    }

    private JsonNode httpJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return JSON.readTree(resp.body());
        } catch (Exception e) {
            return null;
        }
    }
}
