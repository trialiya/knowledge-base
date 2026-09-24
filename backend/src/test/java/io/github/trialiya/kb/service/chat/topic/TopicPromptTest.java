package io.github.trialiya.kb.service.chat.topic;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.service.chat.topic.TopicPrompt.Line;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

class TopicPromptTest {

    private final List<ChatMessageEntity> rows = new ArrayList<>();

    @Test
    void checkpointsAreTheFirstTheThirdAndEveryTenthAnswer() {
        assertThat(
                        java.util.stream.IntStream.rangeClosed(0, 31)
                                .map(TopicPrompt::lastCheckpoint)
                                .distinct()
                                .boxed()
                                .toList())
                .containsExactly(0, 1, 3, 10, 20, 30);
    }

    @Test
    void aMissedCheckpointIsTakenByTheNextAnswer() {
        assertThat(TopicPrompt.due(2, 1)).isFalse();
        assertThat(TopicPrompt.due(3, 1)).isTrue();
        // Ответ на третьем остановили — точку берёт четвёртый.
        assertThat(TopicPrompt.due(4, 1)).isTrue();
        assertThat(TopicPrompt.due(9, 3)).isFalse();
        assertThat(TopicPrompt.due(12, 3)).isTrue();
        assertThat(TopicPrompt.due(12, 10)).isFalse();
    }

    @Test
    void turnsCountAnswersWritten() {
        user("first");
        assistant("answer 1");
        row(MessageType.USER, "", ChatMessageMeta.ofInterjection(List.of()));
        // Второй сегмент того же ответа — tool-цикл, а не новый ответ.
        assistant("answer 1, continued");
        row(
                MessageType.USER,
                "",
                ChatMessageMeta.ofGitEvent(new GitEventMeta("pull", "kb", true, "ok", "main")));
        command("/compact keep the numbers");
        compactPlaque();
        user("second");
        assistant("answer 2");
        // Следующий вопрос из очереди уже в истории, но ответа на него ещё нет.
        user("third, not answered yet");

        assertThat(TopicPrompt.turns(rows)).isEqualTo(2);
    }

    @Test
    void aBatchOfQueuedQuestionsWithOneAnswerIsOneTurn() {
        user("first");
        // Досланное, пока шёл ответ: доставляется всей очередью сразу, отвечает на неё один прогон.
        user("and also this");
        user("and this");
        assistant("answer to all three");

        assertThat(TopicPrompt.turns(rows)).isEqualTo(1);
    }

    @Test
    void theExcerptEndsWithTheLastAnswerAndSkipsServiceRows() {
        user("How do I configure pgvector?");
        assistant("");
        assistant("Install the extension first.");
        command("/сжать");
        compactPlaque();
        user("queued question");

        assertThat(TopicPrompt.excerpt(rows))
                .containsExactly(
                        new Line(MessageType.USER, "How do I configure pgvector?"),
                        new Line(MessageType.ASSISTANT, "Install the extension first."));
    }

    @Test
    void theExcerptTakesAtMostSixMessages() {
        for (int i = 1; i <= 5; i++) {
            user("q" + i);
            assistant("a" + i);
        }

        assertThat(TopicPrompt.excerpt(rows))
                .extracting(Line::text)
                .containsExactly("q3", "a3", "q4", "a4", "q5", "a5");
    }

    @Test
    void theExcerptStopsOnTheMessageThatReachesTheBudget() {
        user("older question");
        user("x".repeat(900));
        assistant("y".repeat(500));
        user("z".repeat(900));
        assistant("w".repeat(500));

        // 500 + 900 + 500 = 1900 — ещё мало; вопрос в 900 символов доводит до 2800, и на нём
        // набор кончается, до «older question» дело не доходит.
        assertThat(TopicPrompt.excerpt(rows))
                .extracting(Line::text)
                .extracting(String::length)
                .containsExactly(900, 500, 900, 500);
    }

    @Test
    void aLongMessageKeepsItsBeginningAndItsEnd() {
        user("PROBLEM " + "details ".repeat(300) + " SOLUTION");
        assistant("START " + "table ".repeat(300) + " END");

        final List<Line> excerpt = TopicPrompt.excerpt(rows);

        assertThat(excerpt.get(0).text())
                .hasSizeLessThanOrEqualTo(TopicPrompt.USER_CHARS)
                .startsWith("PROBLEM")
                .endsWith("SOLUTION")
                .contains(TopicPrompt.CUT);
        assertThat(excerpt.get(1).text())
                .hasSizeLessThanOrEqualTo(TopicPrompt.ASSISTANT_CHARS)
                .startsWith("START")
                .endsWith("END");
    }

    @Test
    void codeBlocksCollapseToTheirLanguage() {
        assertThat(
                        TopicPrompt.collapseCode(
                                """
                Look:
                ```java
                class A {}
                ```
                and
                ````
                raw
                ```
                still raw
                ````
                then
                ~~~sql
                select 1"""
                                        .stripIndent()))
                .isEqualTo("Look:\n[code: java]\nand\n[code]\nthen\n[code: sql]");
    }

    @Test
    void attachmentNamesRideWithTheQuestion() {
        row(
                MessageType.USER,
                "What is wrong here?",
                ChatMessageMeta.ofContextItems(
                        List.of(
                                new ContextItem(ContextItemKind.ATTACHMENT, "1", "report.pdf"),
                                new ContextItem(ContextItemKind.ATTACHMENT, "2", "trace.log"))));
        row(
                MessageType.USER,
                "",
                ChatMessageMeta.ofContextItems(
                        List.of(new ContextItem(ContextItemKind.ATTACHMENT, "3", "photo.png"))));
        assistant("Looks fine.");

        assertThat(TopicPrompt.excerpt(rows))
                .extracting(Line::text)
                .containsExactly(
                        "What is wrong here?\n[attachments: report.pdf, trace.log]",
                        "[attachments: photo.png]",
                        "Looks fine.");
    }

    @Test
    void theRequestNamesTheCurrentTitleWhenThereIsOne() {
        final List<Line> lines =
                List.of(new Line(MessageType.USER, "q"), new Line(MessageType.ASSISTANT, "a"));

        assertThat(TopicPrompt.request(null, lines))
                .isEqualTo("Conversation, oldest first:\n\nUser: q\n\nAssistant: a");
        assertThat(TopicPrompt.request("Old title", lines))
                .startsWith("Current title: Old title\n\nConversation");
    }

    @Test
    void theReplyIsCleanedToABareTitle() {
        assertThat(TopicPrompt.clean("«Настройка pgvector».")).isEqualTo("Настройка pgvector");
        assertThat(TopicPrompt.clean("**Title:** \"Kafka retries\"\n\nBecause..."))
                .isEqualTo("Kafka retries");
        assertThat(TopicPrompt.clean("<think>hmm\nmaybe</think>\nТема: Git   rebase"))
                .isEqualTo("Git rebase");
        assertThat(TopicPrompt.clean("Async в C#.")).isEqualTo("Async в C#");
        assertThat(TopicPrompt.clean("# __init__ в Python")).isEqualTo("__init__ в Python");
        assertThat(TopicPrompt.clean("*.gradle зависимости")).isEqualTo("*.gradle зависимости");
        assertThat(TopicPrompt.clean("**Title:**\nНастройка pgvector"))
                .isEqualTo("Настройка pgvector");
        assertThat(TopicPrompt.clean("<think>\nstill thinking, cut off")).isNull();
        assertThat(TopicPrompt.clean("   \n")).isNull();
        assertThat(TopicPrompt.clean(null)).isNull();
        assertThat(TopicPrompt.clean("word ".repeat(40)))
                .hasSizeLessThanOrEqualTo(TopicPrompt.MAX_TOPIC_CHARS);
    }

    private void user(String text) {
        row(MessageType.USER, text, null);
    }

    private void assistant(String text) {
        row(MessageType.ASSISTANT, text, null);
    }

    private void command(String text) {
        row(MessageType.USER, text, ChatMessageMeta.ofCommand());
    }

    private void compactPlaque() {
        row(
                MessageType.ASSISTANT,
                "",
                ChatMessageMeta.ofCompact(
                        new CompactMeta(4, 100, 1, CompactMeta.Kind.COMPACT, null)));
    }

    /**
     * Ряды такие, какими их отдаёт {@code ChatMessageRepository.findConversationTurns}: без сводок
     * и без протокола инструментов — их отсеивает сама выборка.
     */
    private void row(MessageType type, String content, @Nullable ChatMessageMeta meta) {
        rows.add(
                new ChatMessageEntity(
                        rows.size() + 1L,
                        "conv",
                        content,
                        type,
                        rows.size(),
                        false,
                        false,
                        LocalDateTime.now(),
                        meta));
    }
}
