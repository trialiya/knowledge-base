package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Синтез мет плашек из {@code tool_data} для сегментов, чьих {@code meta.invocations} нет или не
 * хватает, — оборванные и старые прогоны (см. {@link ToolCallService#invocationsFor}).
 */
class ToolCallServiceTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private final ToolCallIndexRepository indexRepo = mock(ToolCallIndexRepository.class);

    private final ToolCallService service =
            new ToolCallService(mock(ChatMessageRepository.class), indexRepo);

    /** Индекс знает эти вызовы — только по ним синтезированная плашка предлагает детали. */
    private void indexKnows(String... callIds) {
        when(indexRepo.findIndexedCallIds(eq(CONV), any())).thenReturn(List.of(callIds));
    }

    private static ChatMessageEntity entity(
            MessageType type, ChatMessageMeta meta, ToolData toolData) {
        return ToolCallTestSupport.entity(CONV, type, meta, toolData);
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
        indexKnows("id-0");
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

        // SKIP_TOOLS (getUserName) вырезан, как и в runInvocations.
        assertThat(metas).hasSize(1);
        assertThat(metas.get(0).name()).isEqualTo("searchDocuments");
        // Ответ инструмента есть, но чем вызов кончился, знала только несохранённая мета: провал
        // выглядит в tool_data ровно так же, поэтому UNKNOWN, а не OK.
        assertThat(metas.get(0).status()).isEqualTo(ToolInvocationStatus.UNKNOWN);
        // Детали доступны и здесь: callId из tool_data ведёт findToolCallDetail через
        // tool_call_index, а callIndex синтезу взять негде — он жил только в мете прогона.
        assertThat(metas.get(0).hasDetails()).isTrue();
        assertThat(metas.get(0).callId()).isEqualTo("id-0");
        assertThat(metas.get(0).callIndex()).isNull();
        assertThat(metas.get(0).arguments()).containsEntry("q", "a");
        assertThat(metas.get(0).resultGist()).contains("found 3 docs");
    }

    @Test
    void synthesizedCallOutsideTheIndexOffersNoDetails() {
        // История, написанная до самого tool_call_index: искать вызов нечем, и кликабельная
        // плашка ответила бы одним 404 — предлагать детали по ней нельзя.
        final ChatMessageEntity segment =
                entity(
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "id-0", "function", "searchDocuments", "{}")),
                                null));
        indexKnows();

        assertThat(service.invocationsFor(segment, List.of(segment)))
                .singleElement()
                .satisfies(meta -> assertThat(meta.hasDetails()).isFalse());
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
    void invocationsForTopsUpMetaLeftPartialByAnInterruptedBatch() {
        // Параллельный батч, отработавший наполовину: снимок коллектора знает только первый вызов,
        // и мета сегмента записана по нему одному. Без добора брошенный вызов после перезагрузки
        // пропал бы из истории вовсе — хотя живой чат плашку показывал.
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
                        "id-0");
        final ChatMessageEntity segment =
                entity(
                        MessageType.ASSISTANT,
                        new ChatMessageMeta(RUN, false, List.of(stored)),
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "id-0", "function", "searchDocuments", "{}"),
                                        new ToolData.Call(
                                                "id-1",
                                                "function",
                                                "runScript",
                                                "{\"script\": \"x\"}")),
                                null));
        indexKnows("id-0", "id-1");
        final ChatMessageEntity repaired =
                entity(
                        MessageType.TOOL,
                        null,
                        new ToolData(
                                null,
                                List.of(
                                        new ToolData.Response(
                                                "id-1",
                                                "runScript",
                                                "[interrupted — no result]"))));

        final List<ToolInvocationMeta> metas =
                service.invocationsFor(segment, List.of(segment, repaired));

        // Сохранённая плашка остаётся как была, добранная встаёт на своё место в порядке вызовов.
        assertThat(metas).hasSize(2);
        assertThat(metas.get(0)).isSameAs(stored);
        assertThat(metas.get(1).name()).isEqualTo("runScript");
        assertThat(metas.get(1).status()).isEqualTo(ToolInvocationStatus.UNKNOWN);
        assertThat(metas.get(1).callId()).isEqualTo("id-1");
        assertThat(metas.get(1).hasDetails()).isTrue();
        assertThat(metas.get(1).arguments()).containsEntry("script", "x");
        assertThat(metas.get(1).resultGist()).contains("interrupted");
    }

    @Test
    void invocationsForKeepsPartialMetaWrittenWithoutCallIds() {
        // Старая мета без callId: сопоставить её с вызовами нечем, и синтез подменил бы известный
        // исход на UNKNOWN — отдаём как есть, пусть и не на каждый вызов сегмента.
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
                                                "id-0", "function", "searchDocuments", "{}"),
                                        new ToolData.Call("id-1", "function", "runScript", "{}")),
                                null));

        assertThat(service.invocationsFor(segment, List.of(segment))).containsExactly(stored);
    }

    @Test
    void invocationsForSynthesizesWhenMetaCarriesOnlyTheModel() {
        // Прогон оборвался до записи плашек, но модель на ответе всё же проставлена:
        // мета у сегмента есть, плашек в ней нет — синтез из tool_data обязан сработать,
        // иначе после перезагрузки пропали бы отметки о том, что модель вообще звала.
        final ChatMessageEntity segment =
                entity(
                        MessageType.ASSISTANT,
                        new ChatMessageMeta(null, false, List.of()).withRun(RUN, "gpt-5"),
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "id-0", "function", "searchDocuments", "{}")),
                                null));

        assertThat(service.invocationsFor(segment, List.of(segment)))
                .extracting(ToolInvocationMeta::name)
                .containsExactly("searchDocuments");
    }

    @Test
    void invocationsForPageAsksTheIndexOnceForTheWholePage() {
        // История, написанная до meta.invocations: добора просит каждый сегмент страницы, и запрос
        // на сегмент был бы N+1 на страницу.
        final List<ChatMessageEntity> page =
                List.of(
                        legacySegment("id-0", "searchDocuments"),
                        legacySegment("id-1", "searchCodebase"),
                        legacySegment("id-2", "readFile"));
        indexKnows("id-0", "id-1", "id-2");

        final List<List<ToolInvocationMeta>> metas = service.invocationsForPage(page);

        assertThat(metas)
                .extracting(rowMetas -> rowMetas.get(0).name())
                .containsExactly("searchDocuments", "searchCodebase", "readFile");
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<String>> callIds =
                ArgumentCaptor.forClass(Collection.class);
        verify(indexRepo, times(1)).findIndexedCallIds(eq(CONV), callIds.capture());
        assertThat(callIds.getValue()).containsExactly("id-0", "id-1", "id-2");
    }

    /** Страница без единого сегмента, которому нужен добор, обходится без запроса вовсе. */
    @Test
    void invocationsForPageSkipsTheIndexWhenNothingNeedsSynthesis() {
        final ChatMessageEntity plain = entity(MessageType.ASSISTANT, null, null);

        assertThat(service.invocationsForPage(List.of(plain)))
                .containsExactly((List<ToolInvocationMeta>) null);
        verify(indexRepo, never()).findIndexedCallIds(any(), any());
    }

    private static ChatMessageEntity legacySegment(String callId, String toolName) {
        return entity(
                MessageType.ASSISTANT,
                null,
                new ToolData(List.of(new ToolData.Call(callId, "function", toolName, "{}")), null));
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
