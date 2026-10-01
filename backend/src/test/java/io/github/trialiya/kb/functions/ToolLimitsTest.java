package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.doc.dto.DocumentSkeletonNode;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The bounds the read tools put on what reaches the model: patches, grep lines, the KB tree. */
class ToolLimitsTest {

    private static GitDiffEntry entry(String path, int patchLines) {
        String patch = IntStream.range(0, patchLines).mapToObj(i -> "+l" + i).collect(Collectors.joining("\n"));
        return new GitDiffEntry("M", path, null, patchLines, 0, "diff --git a/" + path, patch);
    }

    @Test
    void patchesStopAtTheBudgetAndASmallerOneLaterStillFits() {
        List<GitDiffEntry> out =
                PatchBudget.ofEntries(List.of(entry("a", 2000), entry("b", 1500), entry("c", 900), entry("d", 200)));

        assertThat(out.get(0).patch()).doesNotStartWith("...");
        assertThat(out.get(1).patch()).isEqualTo(PatchBudget.LEFT_OUT);
        assertThat(out.get(1).patchHeader()).isNull();
        assertThat(out.get(1).additions()).isEqualTo(1500);
        assertThat(out.get(2).patch()).doesNotStartWith("...");
        assertThat(out.get(3).patch()).isEqualTo(PatchBudget.LEFT_OUT);
    }

    @Test
    void anEntryWithoutAPatchIsLeftAlone() {
        GitDiffEntry bare = new GitDiffEntry("A", "x", null, 1, 0, null, null);

        assertThat(PatchBudget.ofEntries(List.of(bare))).containsExactly(bare);
    }

    @Test
    void aLongGrepLineIsCutWithTheRestCounted() {
        String text = "-1-short\n:2:" + "z".repeat(1000);

        String capped = GrepLines.cap(text);

        assertThat(capped.lines().toList().getFirst()).isEqualTo("-1-short");
        assertThat(capped.lines().toList().get(1))
                .hasSize(GrepLines.MAX_LINE_CHARS + "… (+503 chars)".length())
                .endsWith("… (+503 chars)");
        assertThat(GrepLines.cap(":1:short")).isEqualTo(":1:short");
    }

    private static DocumentSkeletonNode node(long id, @Nullable Long parentId) {
        return new DocumentSkeletonNode(id, "n" + id, "folder", parentId, 1, 1, true, false);
    }

    @Test
    void aTreeTooBigKeepsWholeUpperLevels() {
        List<DocumentSkeletonNode> nodes = new ArrayList<>();
        nodes.add(node(1, null));
        nodes.add(node(2, null));
        // Level 1: three children; level 2: ten grandchildren under node 10.
        nodes.add(node(10, 1L));
        nodes.add(node(11, 1L));
        nodes.add(node(12, 2L));
        for (long g = 100; g < 110; g++) {
            nodes.add(node(g, 10L));
        }

        assertThat(DocumentFunction.upperLevels(nodes, 100)).isSameAs(nodes);
        assertThat(DocumentFunction.upperLevels(nodes, 10))
                .extracting(DocumentSkeletonNode::id)
                .containsExactly(1L, 2L, 10L, 11L, 12L);
        assertThat(DocumentFunction.upperLevels(nodes, 1))
                .extracting(DocumentSkeletonNode::id)
                .containsExactly(1L);
    }

    @Test
    void aCycleDoesNotHangTheCut() {
        List<DocumentSkeletonNode> nodes = List.of(node(1, 2L), node(2, 1L), node(3, null));

        assertThat(DocumentFunction.upperLevels(nodes, 2)).isNotEmpty();
    }
}
