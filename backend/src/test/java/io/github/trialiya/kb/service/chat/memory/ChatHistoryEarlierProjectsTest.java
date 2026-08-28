package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * {@link ChatHistoryService#earlierProjects}: какие репозитории чат уже видел. Это единственный
 * источник для списка «выбирались раньше» в промпте ({@code ProjectPromptService}), а значит и для
 * того, какие id модель вообще может назвать в аргументе {@code project} читающего инструмента.
 */
class ChatHistoryEarlierProjectsTest {

    private static final String CONV = "conv-1";

    private ChatMessageRepository messageRepo;
    private ChatHistoryService history;

    @BeforeEach
    void setUp() {
        messageRepo = mock(ChatMessageRepository.class);
        history =
                new ChatHistoryService(
                        messageRepo,
                        new ContextItemService(mock(AttachmentService.class)),
                        new ToolCallService(messageRepo, mock(ToolCallIndexRepository.class)),
                        new ToolCallEventPublisher(
                                mock(ChatEventService.class), new RunRegistry()));
    }

    private void switches(ChatMessageEntity... rows) {
        when(messageRepo.findProjectSwitches(CONV)).thenReturn(List.of(rows));
    }

    private static ChatMessageEntity row(long position, @Nullable ChatMessageMeta meta) {
        return new ChatMessageEntity(
                position,
                CONV,
                "text",
                MessageType.USER,
                position,
                false,
                false,
                LocalDateTime.now(),
                meta);
    }

    private static ChatMessageEntity switched(long position, String from, String to) {
        return row(position, new ChatMessageMeta(null, false, List.of(), List.of(), to, from));
    }

    @Test
    void aChatThatNeverSwitchedHasNoEarlierProjects() {
        switches();

        assertThat(history.earlierProjects(CONV)).isEmpty();
    }

    @Test
    void bothSidesOfEverySwitchAreListedInTheOrderTheyAppeared() {
        switches(switched(3, "kb", "billing"), switched(7, "billing", "docs"));

        assertThat(history.earlierProjects(CONV)).containsExactly("kb", "billing", "docs");
    }

    @Test
    void aProjectVisitedTwiceIsListedOnce() {
        switches(switched(3, "kb", "billing"), switched(7, "billing", "kb"));

        assertThat(history.earlierProjects(CONV)).containsExactly("kb", "billing");
    }

    /**
     * Фильтр запроса ходит по тексту JSON и намеренно приблизителен — ряд без настоящего маркера
     * решается уже здесь, по разобранной {@code meta}.
     */
    @Test
    void aRowWithoutARealSwitchMarkerIsIgnored() {
        switches(
                row(2, null), row(3, new ChatMessageMeta(List.of())), switched(4, "kb", "billing"));

        assertThat(history.earlierProjects(CONV)).containsExactly("kb", "billing");
    }
}
