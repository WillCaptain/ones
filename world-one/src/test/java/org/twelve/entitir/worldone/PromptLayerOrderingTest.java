package org.twelve.entitir.worldone;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 {@code GenericAgentLoop.contextWindow()} 的 Layer 装配顺序，
 * 与 {@code docs/aipp-prompt-architecture.md} 「六层提示词结构」保持一致。
 *
 * <p>设计原则：先立身份与铁律（Layer 0），再给参考资料（Layer 1 Memory），
 * 最后追加任务上下文（Layer 2/3）、可选层（Layer 4/5），最后是 history。
 *
 * <p>本测试通过源码扫描实现 —— 不依赖 Spring 上下文 / Mock 框架，
 * 只锁定 contextWindow 内 Layer 标记的相对顺序，避免后续重构误调换。
 */
class PromptLayerOrderingTest {

    /**
     * Layer 标记（必须按此顺序在 contextWindow 源码中出现）。
     * 注：Layer 5 编号最大但写入位置在 sysContent 最前；本测试锁定的是
     * <b>装配代码顺序</b>，与文档 Layer 编号一致。
     */
    private static final List<String> LAYER_MARKERS_IN_ORDER = List.of(
        "Layer 0：Host 铁律",
        "Layer 1：Memory Context",
        "Layer 2：Session Entry Prompt",
        "Layer 3：Widget llm_hint",
        "Layer 4：Skill Playbook",
        "Layer 5：UI Hints"
    );

    @Test
    void contextWindow_assemblesLayersInDocumentedOrder() throws Exception {
        Path src = Path.of("src/main/java/org/twelve/entitir/worldone/GenericAgentLoop.java");
        String code = Files.readString(src);

        int contextWindowStart = code.indexOf("private List<Map<String, Object>> contextWindow()");
        assertThat(contextWindowStart)
                .as("contextWindow() 方法必须存在")
                .isPositive();

        // 截取 contextWindow 方法体（粗略：从签名到下一个 `\n    }` 顶层右花括号）
        String body = code.substring(contextWindowStart);
        int bodyEnd = body.indexOf("\n    }");
        assertThat(bodyEnd).isPositive();
        body = body.substring(0, bodyEnd);

        int prevPos = -1;
        String prevMarker = null;
        for (String marker : LAYER_MARKERS_IN_ORDER) {
            int pos = body.indexOf(marker);
            assertThat(pos)
                    .as("contextWindow() 必须包含 Layer 标记: %s（与文档同步）", marker)
                    .isPositive();
            if (prevMarker != null) {
                assertThat(pos)
                        .as("Layer 顺序错乱：'%s' 必须出现在 '%s' 之后", marker, prevMarker)
                        .isGreaterThan(prevPos);
            }
            prevPos = pos;
            prevMarker = marker;
        }
    }

    @Test
    void contextWindow_memoryComesAfterHostBase() throws Exception {
        Path src = Path.of("src/main/java/org/twelve/entitir/worldone/GenericAgentLoop.java");
        String code = Files.readString(src);

        int contextWindowStart = code.indexOf("private List<Map<String, Object>> contextWindow()");
        String body = code.substring(contextWindowStart, code.indexOf("\n    }", contextWindowStart));

        int hostBase = body.indexOf("buildSystemPromptForTurn()");
        int memoryAppend = body.indexOf("currentTurnMemoryContext");

        assertThat(hostBase).isPositive();
        assertThat(memoryAppend).isPositive();
        assertThat(memoryAppend)
                .as("Memory（Layer 1）必须在 Host 铁律（Layer 0 buildSystemPromptForTurn）之后追加，"
                  + "提示工程铁律：先立身份铁律，再给参考资料")
                .isGreaterThan(hostBase);
    }

    @Test
    void contextWindow_uiHintsPrependedLast() throws Exception {
        Path src = Path.of("src/main/java/org/twelve/entitir/worldone/GenericAgentLoop.java");
        String code = Files.readString(src);

        int contextWindowStart = code.indexOf("private List<Map<String, Object>> contextWindow()");
        String body = code.substring(contextWindowStart, code.indexOf("\n    }", contextWindowStart));

        int uiHintsAssembly = body.indexOf("Layer 5：UI Hints");
        int historyAppend   = body.indexOf("CONTEXT_WINDOW");

        assertThat(uiHintsAssembly).isPositive();
        assertThat(historyAppend).isPositive();
        assertThat(historyAppend)
                .as("UI Hints 必须在 history 追加之前装配完成（仍在 sysContent 内）")
                .isGreaterThan(uiHintsAssembly);

        // UI Hints 是"前置 prepend"，写入语义必须是 hintBlock + sysContent
        String hintsRegion = body.substring(uiHintsAssembly,
                Math.min(body.length(), uiHintsAssembly + 600));
        assertThat(hintsRegion)
                .as("UI Hints 必须前置（hintBlock + sysContent），覆盖更早的指令")
                .contains("hintBlock + sysContent");
    }
}
