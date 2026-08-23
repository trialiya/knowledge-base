package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ToolCallMessage;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Live-события TOOL_CALL, которые {@link ChatHistoryService#append} шлёт по только что сохранённым
 * рядам: STARTED при записи ASSISTANT-сегмента с tool_calls, OK с гистом при записи TOOL-ответов.
 * {@code callIndex} сквозной по прогону и обнуляется на новом — так он совпадает со счётчиком
 * {@code ToolInvocationCollector}, из которого приходят итоговые меты.
 */
class ToolCallEventPublisherTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private ChatEventService events;
    private ChatHistoryService history;

    @BeforeEach
    void setUp() {
        final ChatMessageRepository messageRepo = mock(ChatMessageRepository.class);
        events = mock(ChatEventService.class);
        history =
                new ChatHistoryService(
                        messageRepo,
                        new ContextItemService(mock(AttachmentService.class)),
                        new ToolCallService(messageRepo, mock(ToolCallIndexRepository.class)),
                        new ToolCallEventPublisher(events));
        ToolCallTestSupport.echoSavedWithIds(messageRepo);
    }

    private List<ToolInvocationMeta> publishedMetas(String runId) {
        final ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeastOnce())
                .publish(
                        eq(CONV),
                        eq(ChatEventType.TOOL_CALL),
                        eq(runId),
                        eq(null),
                        payloads.capture());
        return payloads.getAllValues().stream().map(p -> ((ToolCallMessage) p).toolCall()).toList();
    }

    @Test
    void startedAndOkEventsForNewSegmentAndResponses() {
        when(events.activeRunId(CONV)).thenReturn(Optional.of(RUN));

        history.append(
                CONV,
                List.of(
                        new UserMessage("hi"),
                        ToolCallTestSupport.assistantWithCalls(
                                ToolCallTestSupport.call(
                                        "id-0", "searchDocuments", "{\"q\": \"a\"}"),
                                ToolCallTestSupport.call("id-1", "getCurrentDateTime", "{}")),
                        new ToolResponseMessage(
                                List.<ToolResponseMessage.ToolResponse>of(
                                        new ToolResponseMessage.ToolResponse(
                                                "id-0", "searchDocuments", "\"found 3 docs\""),
                                        new ToolResponseMessage.ToolResponse(
                                                "id-1",
                                                "getCurrentDateTime",
                                                "\"2026-07-19T12:00\"")),
                                Map.of()) {}));

        // SKIP_TOOLS (getCurrentDateTime) не публикуется вовсе — ни STARTED, ни OK,
        // но callIndex остальных вызовов считает его (позиция в toolCalls).
        final List<ToolInvocationMeta> metas = publishedMetas(RUN);
        assertThat(metas).hasSize(2);
        assertThat(metas.get(0).status()).isEqualTo(ToolInvocationStatus.STARTED);
        assertThat(metas.get(0).name()).isEqualTo("searchDocuments");
        assertThat(metas.get(0).callIndex()).isEqualTo(0);
        assertThat(metas.get(0).arguments()).containsEntry("q", "a");
        assertThat(metas.get(0).hasDetails()).isTrue();
        assertThat(metas.get(1).status()).isEqualTo(ToolInvocationStatus.OK);
        assertThat(metas.get(1).name()).isEqualTo("searchDocuments");
        assertThat(metas.get(1).callIndex()).isEqualTo(0);
        assertThat(metas.get(1).arguments()).containsEntry("q", "a");
        assertThat(metas.get(1).resultGist()).contains("found 3 docs");
    }

    @Test
    void callIndexContinuesAcrossSegmentsOfTheSameRun() {
        when(events.activeRunId(CONV)).thenReturn(Optional.of(RUN));

        // Первая итерация tool-цикла: два вызова — номера 0 и 1.
        history.append(
                CONV,
                List.of(
                        ToolCallTestSupport.assistantWithCalls(
                                ToolCallTestSupport.call("id-0", "getDocument", "{}"),
                                ToolCallTestSupport.call("id-1", "getDocument", "{}"))));
        // Вторая итерация приходит отдельным append — счётчик продолжается, а не начинается заново.
        history.append(
                CONV,
                List.of(
                        ToolCallTestSupport.assistantWithCalls(
                                ToolCallTestSupport.call("id-2", "searchDocuments", "{}"))));

        final List<ToolInvocationMeta> metas = publishedMetas(RUN);
        assertThat(metas).extracting(ToolInvocationMeta::callIndex).containsExactly(0, 1, 2);
    }

    @Test
    void newRunStartsCountingFromZero() {
        when(events.activeRunId(CONV)).thenReturn(Optional.of(RUN));
        history.append(
                CONV,
                List.of(
                        ToolCallTestSupport.assistantWithCalls(
                                ToolCallTestSupport.call("id-0", "getDocument", "{}"))));

        // Повтор упавшего прогона: его сегменты остаются в истории, но счётчик вызовов —
        // это счётчик прогона, и коллектор нового прогона тоже начинает с нуля.
        when(events.activeRunId(CONV)).thenReturn(Optional.of("run-2"));
        history.append(
                CONV,
                List.of(
                        ToolCallTestSupport.assistantWithCalls(
                                ToolCallTestSupport.call("id-1", "searchDocuments", "{}"))));

        assertThat(publishedMetas("run-2"))
                .extracting(ToolInvocationMeta::callIndex)
                .containsExactly(0);
    }

    @Test
    void noEventsWithoutActiveRun() {
        when(events.activeRunId(CONV)).thenReturn(Optional.empty());

        history.append(
                CONV,
                List.of(
                        new UserMessage("hi"),
                        ToolCallTestSupport.assistantWithCalls(
                                ToolCallTestSupport.call("id-0", "searchDocuments", "{}"))));

        verify(events, never()).publish(any(), any(), any(), any(), any());
    }
}
