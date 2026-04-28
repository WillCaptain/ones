package org.twelve.entitir.worldone;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码扫描护栏：禁止 host（world-one main 源码）出现任何具体 AIPP 字面量。
 *
 * <p>扫描规则：
 * <ul>
 *   <li>遍历 {@code world-one/src/main/java/**\/*.java}；</li>
 *   <li>剥掉 {@code //} 行注释和 {@code /* *\/} 块注释——注释里允许举例；</li>
 *   <li>剥掉字符串常量内的 {@code @SuppressDecoupling} 标记行（保留显式豁免出口）；</li>
 *   <li>断言代码部分（已剥注释）不含禁用字面量。</li>
 * </ul>
 *
 * <p>命中即报：文件路径 + 行号 + 字面量。任何新加的 host 代码必须通过本测试。
 */
class HostCodeDecouplingTest {

    /** 禁用的 AIPP 字面量（注意保留双引号——只查代码字符串，不查标识符）。 */
    private static final List<String> FORBIDDEN_LITERALS = List.of(
        "\"world_design\"",
        "\"world_register_action\"",
        "\"world_list\"",
        "\"memory_consolidate\"",
        "\"memory_workspace_join\"",
        "\"world-entitir\"",
        "\"memory-one\"",
        "\"entity-graph\"",
        "\"memory-manager\"",
        "\"/api/worlds/\"",
        "\"/api/memory_workspace_join\""
    );

    private static final Pattern LINE_COMMENT  = Pattern.compile("//[^\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*([\\s\\S]*?)\\*/");

    @Test
    void hostMainSourcesContainNoAippLiterals() throws IOException {
        Path root = Path.of("src/main/java").toAbsolutePath();
        assertThat(root).exists();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .filter(HostCodeDecouplingTest::isScannable)
                  .forEach(p -> scanFile(p, violations));
        }

        assertThat(violations)
            .as("host 代码不得包含具体 AIPP 字面量；命中条目（文件:行:字面量）：\n  - "
                + String.join("\n  - ", violations))
            .isEmpty();
    }

    private static boolean isScannable(Path p) {
        String name = p.getFileName().toString();
        // 兼容字段保留点：AppRegistration 内部允许构造字段，由协议数据驱动，无字面量违规
        return !name.equals("HostCodeDecouplingTest.java");
    }

    private static void scanFile(Path file, List<String> violations) {
        String src;
        try {
            src = Files.readString(file);
        } catch (IOException e) {
            return;
        }
        // 剥注释
        String stripped = BLOCK_COMMENT.matcher(src).replaceAll(" ");
        stripped = LINE_COMMENT.matcher(stripped).replaceAll("");

        // 拆行供行号定位（用 stripped 后的行号，等价代码行）
        String[] lines = stripped.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("@SuppressDecoupling")) continue;
            for (String lit : FORBIDDEN_LITERALS) {
                int idx = line.indexOf(lit);
                if (idx >= 0) {
                    violations.add(file + ":" + (i + 1) + ":" + lit);
                }
            }
        }
    }

    /** 单元测试：注释剥离正则的 sanity check（避免误判）。 */
    @Test
    void blockCommentExamplesAreStripped() {
        String code = "class X {\n  /* example: \"world_design\" */\n  String s = \"a_tool\";\n}";
        String stripped = BLOCK_COMMENT.matcher(code).replaceAll(" ");
        stripped = LINE_COMMENT.matcher(stripped).replaceAll("");
        for (String lit : FORBIDDEN_LITERALS) {
            assertThat(stripped).doesNotContain(lit);
        }
    }
}
