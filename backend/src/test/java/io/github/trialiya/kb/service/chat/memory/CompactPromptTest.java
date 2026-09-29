package io.github.trialiya.kb.service.chat.memory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.core.io.ByteArrayResource;

/**
 * Справка о чате в конце запроса сжатия — те числа, за которыми модель сверяет {@code ## User
 * requests}. Они обязаны сходиться с правилами {@code compactor.md}: прошлая сводка переносится
 * пункт в пункт и в число живых вопросов не входит, ряд события вопросом не является.
 */
class CompactPromptTest {

    private static final String CONV = "conv-1";

    private final CompactPrompt prompt =
            new CompactPrompt(
                    chatTopicRepository(), new ByteArrayResource("HANDBOOK".getBytes(UTF_8)));

    @Test
    void aWindowWithoutSummariesAsksForExactlyOneBulletPerQuestion() {
        final String text =
                prompt.instruction(
                        CONV,
                        List.of(
                                user(1, "first"),
                                assistant(2, "answer"),
                                user(3, "second"),
                                assistant(4, "answer")),
                        null);

        assertThat(text)
                .contains("- Messages above: 4\n")
                .contains(
                        "- Of them USER messages with a request: 2 (`## User requests` must have"
                                + " exactly this many bullets)\n")
                .doesNotContain("earlier summary");
    }

    /**
     * Окно {@code /compact-1} после прошлого {@code /compact}: сводка плюс один живой ход. Число
     * вопросов — один, а не два и не ноль, и рядом сказано, что пункты сводки идут сверх него.
     */
    @Test
    void anEarlierSummaryIsCountedApartFromTheLiveQuestions() {
        final String text =
                prompt.instruction(
                        CONV,
                        List.of(summary(1), user(2, "question"), assistant(3, "answer")),
                        null);

        assertThat(text)
                .contains("- Messages above: 3\n")
                .contains(
                        "- Of them USER messages with a request: 1 (`## User requests` must have"
                                + " exactly this many bullets for them, ON TOP OF every bullet"
                                + " carried over from the earlier summaries)\n")
                .contains("- Of them earlier summary messages: 1 (carry their `## User requests`");
    }

    /**
     * Ряд git-команды — USER по типу, но не вопрос: пункта в {@code ## User requests} у него нет.
     */
    @Test
    void anEventRowIsNotCountedAsARequest() {
        final ChatMessageEntity pull =
                new ChatMessageEntity(
                        2,
                        CONV,
                        "",
                        MessageType.USER,
                        2,
                        false,
                        false,
                        LocalDateTime.now(),
                        ChatMessageMeta.ofGitEvent(
                                new GitEventMeta("pull", "kb", true, "ok", "main")));
        final String text =
                prompt.instruction(
                        CONV,
                        List.of(
                                user(1, "question"),
                                new PromptRow(
                                        pull, "<git-command command=\"pull\" outcome=\"ok\"/>"),
                                assistant(3, "answer")),
                        null);

        assertThat(text).contains("- Of them USER messages with a request: 1 (");
    }

    private static PromptRow user(long position, String content) {
        return row(position, MessageType.USER, content, false);
    }

    private static PromptRow assistant(long position, String content) {
        return row(position, MessageType.ASSISTANT, content, false);
    }

    private static PromptRow summary(long position) {
        return row(position, MessageType.ASSISTANT, "<summary>earlier</summary>", true);
    }

    private static PromptRow row(long position, MessageType type, String content, boolean summary) {
        return new PromptRow(
                new ChatMessageEntity(
                        position,
                        CONV,
                        content,
                        type,
                        position,
                        false,
                        summary,
                        LocalDateTime.now(),
                        null),
                content);
    }

    private static ChatTopicRepository chatTopicRepository() {
        final ChatTopicRepository repository = mock(ChatTopicRepository.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        return repository;
    }
}
