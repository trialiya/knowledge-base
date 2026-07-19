package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ToolCallMessage;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolCallIndexEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Live-события TOOL_CALL из {@link ChatMemoryService#saveAll}: STARTED — при сохранении
 * ASSISTANT-сегмента с tool_calls, OK с гистом — при сохранении TOOL-ответов; callIndex сквозной по
 * прогону (смещение по уже сохранённым сегментам). Плюс синтез мет плашек из tool_data для
 * сегментов без meta.invocations ({@link ChatMemoryService#invocationsFor}).
 */
class ChatMemoryServiceToolEventsTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private ChatTopicRepository topicRepo;
    private ChatMessageRepository messageRepo;
    private ChatEventService events;
    private ToolCallIndexRepository toolCallIndexRepo;
    private ChatMemoryService service;

    @BeforeEach
    void setUp() {
        topicRepo = mock(ChatTopicRepository.class);
        messageRepo = mock(ChatMessageRepository.class);
        events = mock(ChatEventService.class);
        toolCallIndexRepo = mock(ToolCallIndexRepository.class);
        service = new ChatMemoryService(topicRepo, messageRepo, events, toolCallIndexRepo);
        // saveAll в БД возвращает сущности с проставленными id — saveAll сервиса на этом строит
        // messageId строк tool_call_index (см. ChatMemoryService#indexToolCalls).
        final java.util.concurrent.atomic.AtomicLong nextId =
                new java.util.concurrent.atomic.AtomicLong(100);
        when(messageRepo.saveAll(any()))
                .thenAnswer(
                        inv -> {
                            final Iterable<ChatMessageEntity> entities = inv.getArgument(0);
                            final List<ChatMessageEntity> saved = new java.util.ArrayList<>();
                            for (ChatMessageEntity e : entities) {
                                saved.add(
                                        new ChatMessageEntity(
                                                nextId.incrementAndGet(),
                                                e.getConversationId(),
                                                e.getContent(),
                                                e.getType(),
                                                e.getPosition(),
                                                e.isSummarized(),
                                                e.isSummary(),
                                                e.getCreatedAt(),
                                                e.getMeta(),
                                                e.getToolData()));
                            }
                            return saved;
                        });
    }

    private static AssistantMessage assistantWithCalls(AssistantMessage.ToolCall... calls) {
        return new AssistantMessage("", Map.of(), List.of(calls), List.of()) {};
    }

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    private List<ToolInvocationMeta> publishedMetas() {
        final ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeastOnce())
                .publish(
                        eq(CONV),
                        eq(ChatEventType.TOOL_CALL),
                        eq(RUN),
                        eq(null),
                        payloads.capture());
        return payloads.getAllValues().stream().map(p -> ((ToolCallMessage) p).toolCall()).toList();
    }

    @Test
    void startedAndOkEventsForNewSegmentAndResponses() {
        when(events.activeRunId(CONV)).thenReturn(Optional.of(RUN));

        service.saveAll(
                CONV,
                List.of(
                        new UserMessage("hi"),
                        assistantWithCalls(
                                call("id-0", "searchDocuments", "{\"q\": \"a\"}"),
                                call("id-1", "getCurrentDateTime", "{}")),
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
        final List<ToolInvocationMeta> metas = publishedMetas();
        assertThat(metas).hasSize(2);
        assertThat(metas.get(0).status()).isEqualTo(ToolInvocationStatus.STARTED);
        assertThat(metas.get(0).name()).isEqualTo("searchDocuments");
        assertThat(metas.get(0).callIndex()).isEqualTo(0);
        assertThat(metas.get(0).arguments()).containsEntry("q", "a");
        assertThat(metas.get(0).hasDetails()).isTrue();
        assertThat(metas.get(1).status()).isEqualTo(ToolInvocationStatus.OK);
        assertThat(metas.get(1).name()).isEqualTo("searchDocuments");
        assertThat(metas.get(1).callIndex()).isEqualTo(0);
        assertThat(metas.get(1).resultGist()).contains("found 3 docs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexesToolCallsOnSave() {
        when(events.activeRunId(CONV)).thenReturn(Optional.of(RUN));

        service.saveAll(
                CONV,
                List.of(
                        new UserMessage("hi"),
                        assistantWithCalls(call("id-0", "searchDocuments", "{}")),
                        new ToolResponseMessage(
                                List.<ToolResponseMessage.ToolResponse>of(
                                        new ToolResponseMessage.ToolResponse(
                                                "id-0", "searchDocuments", "\"found 3 docs\"")),
                                Map.of()) {}));

        final ArgumentCaptor<List<ToolCallIndexEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(toolCallIndexRepo).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        assertThat(rows.getValue().get(0).getCallId()).isEqualTo("id-0");
        assertThat(rows.getValue().get(0).getConversationId()).isEqualTo(CONV);

        // responseMessageId проставляется отдельно, по id только что сохранённой TOOL-строки —
        // без похода за messageId сегмента и без позиционной арифметики.
        verify(toolCallIndexRepo).setResponseMessageId(eq(CONV), eq("id-0"), anyLong());
    }

    @Test
    void callIndexOffsetBySegmentsAlreadySavedInRun() {
        when(events.activeRunId(CONV)).thenReturn(Optional.of(RUN));
        // Уже сохранённый сегмент прогона с двумя вызовами — смещает callIndex новых на 2.
        final ChatMessageEntity savedSegment =
                new ChatMessageEntity(
                        1L,
                        CONV,
                        "",
                        MessageType.ASSISTANT,
                        2,
                        false,
                        false,
                        LocalDateTime.now(),
                        null,
                        new ToolData(
                                List.of(
                                        new ToolData.Call("id-0", "function", "a", "{}"),
                                        new ToolData.Call("id-1", "function", "b", "{}")),
                                null));

        service.saveAll(
                CONV,
                List.of(
                        new UserMessage("hi"),
                        (Message) savedSegment.getMessage(),
                        assistantWithCalls(call("id-2", "searchDocuments", "{}"))));

        final List<ToolInvocationMeta> metas = publishedMetas();
        // События только по новому сегменту, но с учётом смещения.
        assertThat(metas).hasSize(1);
        assertThat(metas.get(0).callIndex()).isEqualTo(2);
        assertThat(metas.get(0).status()).isEqualTo(ToolInvocationStatus.STARTED);
    }

    @Test
    void noEventsWithoutActiveRun() {
        when(events.activeRunId(CONV)).thenReturn(Optional.empty());

        service.saveAll(
                CONV,
                List.of(
                        new UserMessage("hi"),
                        assistantWithCalls(call("id-0", "searchDocuments", "{}"))));

        verify(events, never()).publish(any(), any(), any(), any(), any());
    }

    // ── invocationsFor ────────────────────────────────────────────────────────

    private static ChatMessageEntity entity(
            MessageType type, ChatMessageMeta meta, ToolData toolData) {
        return new ChatMessageEntity(
                1L, CONV, "", type, 1, false, false, LocalDateTime.now(), meta, toolData);
    }

    @Test
    void invocationsForSynthesizesFromToolDataWhenMetaAbsent() {
        final ChatMessageEntity segment =
                entity(
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "id-0",
                                                "function",
                                                "searchDocuments",
                                                "{\"q\": \"a\"}"),
                                        new ToolData.Call("id-1", "function", "getUserName", "{}")),
                                null));
        final ChatMessageEntity toolRow =
                entity(
                        MessageType.TOOL,
                        null,
                        new ToolData(
                                null,
                                List.of(
                                        new ToolData.Response(
                                                "id-0", "searchDocuments", "\"found 3 docs\""))));

        final List<ToolInvocationMeta> metas =
                service.invocationsFor(segment, List.of(segment, toolRow));

        // SKIP_TOOLS (getUserName) вырезан, как и в attachRunMeta.
        assertThat(metas).hasSize(1);
        assertThat(metas.get(0).name()).isEqualTo("searchDocuments");
        assertThat(metas.get(0).status()).isEqualTo(ToolInvocationStatus.OK);
        assertThat(metas.get(0).hasDetails()).isFalse();
        assertThat(metas.get(0).callIndex()).isNull();
        assertThat(metas.get(0).arguments()).containsEntry("q", "a");
        assertThat(metas.get(0).resultGist()).contains("found 3 docs");
    }

    @Test
    void invocationsForPrefersStoredMeta() {
        final ToolInvocationMeta stored =
                new ToolInvocationMeta(
                        "searchDocuments",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        null,
                        true,
                        0,
                        null,
                        null);
        final ChatMessageEntity segment =
                entity(
                        MessageType.ASSISTANT,
                        new ChatMessageMeta(RUN, false, List.of(stored)),
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "id-0", "function", "searchDocuments", "{}")),
                                null));

        assertThat(service.invocationsFor(segment, List.of(segment))).containsExactly(stored);
    }

    @Test
    void invocationsForNullForPlainMessages() {
        assertThat(service.invocationsFor(entity(MessageType.ASSISTANT, null, null), List.of()))
                .isNull();
        assertThat(
                        service.invocationsFor(
                                entity(
                                        MessageType.TOOL,
                                        null,
                                        new ToolData(
                                                null,
                                                List.of(
                                                        new ToolData.Response(
                                                                "id-0", "a", "\"x\"")))),
                                List.of()))
                .isNull();
    }
}
