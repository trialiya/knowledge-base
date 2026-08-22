package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Вся арифметика границы: где проходит раздел между живым хвостом и срезом, что весит срез и когда
 * раунд вообще стоит запускать. Тесты бьют прямо в {@link SummarizeWindow} — класс без side
 * effects, поэтому ни LLM, ни транзакций, ни репозитория здесь не нужно; проводка через сервис
 * проверяется отдельно в {@code SummarizeServiceTest}.
 *
 * <p>Цена ошибки в границе молчаливая и односторонняя: сжатие не падает, а увозит в сводку
 * последние вопросы пользователя, после чего модель отвечает на них по пересказу вместо оригинала.
 */
class SummarizeWindowTest {

    private static final String CONV = "conv-1";

    /** Боевые значения из {@code application.yaml}. */
    private static final SummarizeProperties PRODUCTION =
            new SummarizeProperties(30_000, 50, 30, 5, 5, 4);

    /**
     * Порог по токенам, до которого срезы в тестах про границу заведомо не дотягиваются: они про
     * то, где проходит граница, а не про то, что запускает раунд.
     */
    private static final int UNREACHABLE_TOKENS = 100_000;

    // -------------------------------------------------------------------------
    // Правила живого хвоста
    // -------------------------------------------------------------------------

    /**
     * Правило по числу USER-сообщений сдвигает границу раньше правила по общему числу: последние 20
     * сообщений — один вопрос и длинный хвост ответов, поэтому резать по {@code size - overlap}
     * значило бы оставить живым ровно один вопрос из пяти требуемых.
     */
    @Test
    void userOverlapMovesTheCutoffEarlierThanTheCountOverlap() {
        // Правило по числу сообщений дало бы границу 50 (после выравнивания на USER — 38), но
        // пятый с конца вопрос стоит на 30: он и становится первым живым сообщением.
        final SummarizeWindow window = window(alternatingThenAssistants(), properties(10, 5, 1));

        assertThat(window.endPosition()).isEqualTo(29L);
        assertThat(window.kept().getFirst().entity().getPosition()).isEqualTo(30L);
    }

    /**
     * Вопросов в окне меньше, чем требует {@code overlap-user-messages}: удержать пять там, где
     * есть один, невозможно, поэтому правило отступает и границу задаёт число сообщений. Иначе один
     * вопрос с бесконечным tool-марафоном навсегда заблокировал бы сжатие.
     */
    @Test
    void tooFewUserMessagesFallBackToTheCountBoundary() {
        final List<PromptRow> live = new ArrayList<>(List.of(row(0, MessageType.USER)));
        for (int i = 1; i < 60; i++) {
            live.add(row(i, MessageType.ASSISTANT));
        }

        // Единственный вопрос стоит нулевым — отступать назад некуда, и хвост открывается
        // серединой хода. Это то самое исключение из выравнивания, см.
        // SummarizeWindow#turnBoundary.
        assertThat(window(live, properties(10, 5, 1)).endPosition()).isEqualTo(49L);
    }

    /**
     * Второе правило — потолок, а не замена первому: когда вопросы идут ровно, граница по числу
     * сообщений оказывается раньше и она же побеждает, а хвост всё равно уносит больше
     * USER-сообщений, чем требует {@code overlap-user-messages}.
     */
    @Test
    void countOverlapStillWinsWhenItIsTheEarlierBoundary() {
        // Чередование вопрос/ответ на всём окне: USER стоят на чётных позициях. Правило по вопросам
        // дало бы границу 54, по числу сообщений — 50; 50 и само по себе USER.
        final List<PromptRow> live = alternating(0, 60);

        assertThat(window(live, properties(10, 3, 1)).endPosition()).isEqualTo(49L);
    }

    /**
     * {@code overlap-user-messages: 0} выключает правило целиком и возвращает поведение до его
     * появления — только граница по числу сообщений, выровненная на ближайшее USER-сообщение.
     */
    @Test
    void zeroUserOverlapDisablesTheRule() {
        // То же окно, что и в первом тесте, где правило по вопросам сдвигало границу на 30.
        // Граница 50 выравнивается вниз до ближайшего USER — это позиция 38.
        final SummarizeWindow window = window(alternatingThenAssistants(), properties(10, 0, 1));

        assertThat(window.endPosition()).isEqualTo(37L);
    }

    /**
     * Короткий диалог: сжимать нечего. Пять сообщений при {@code overlap-messages: 30} дают
     * отрицательную границу — она обязана прижиматься к нулю, иначе срез уехал бы в отрицательный
     * индекс.
     */
    @Test
    void aShortWindowHasNothingToCompress() {
        final SummarizeWindow window = window(alternating(0, 5), PRODUCTION);

        assertThat(window.toCompress()).isEmpty();
        assertThat(window.kept()).hasSize(5);
        assertThat(window.worthARound()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Пороги раунда
    // -------------------------------------------------------------------------

    /**
     * Обратная сторона правила по вопросам: сузив срез, оно может увести его под порог запуска —
     * тогда раунд не стартует вовсе. Осознанный размен, живой хвост важнее лишнего раунда сжатия.
     */
    @Test
    void userOverlapCanShrinkTheSliceBelowTheThresholds() {
        // Все вопросы — в начале диалога: пятый с конца стоит на позиции 5.
        final List<PromptRow> live = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            live.add(row(i, MessageType.USER));
        }
        for (int i = 10; i < 60; i++) {
            live.add(row(i, MessageType.ASSISTANT));
        }

        assertThat(window(live, properties(10, 5, 50)).worthARound()).isFalse();
    }

    /**
     * Тысяча коротких сообщений: срез дорастает до порога по числу сообщений задолго до того, как
     * наберёт токены. Этим порог по числу и полезен — он ловит длинные дешёвые диалоги.
     */
    @Test
    void aLongCheapDialogueIsTriggeredByTheMessageCount() {
        // 1000 - 30 = 970 по числу сообщений; правило по вопросам дало бы 990. Граница 970 — USER.
        final SummarizeWindow window = window(alternating(0, 1000), PRODUCTION);

        assertThat(window.worthARound()).isTrue();
        assertThat(window.trigger()).isEqualTo("message count");
        assertThat(window.endPosition()).isEqualTo(969L);
    }

    /**
     * Заявленная граница политики, а не упущение: tool-марафон внутри последних пяти вопросов
     * остаётся живым целиком. Все три правила хвоста двигают границу только назад, поэтому шесть
     * вопросов с тысячей строк ответов дают срез в одно сообщение — ни один порог до него не
     * дотягивается, ведь оба меряют срез.
     *
     * <p>Окно не заперто навсегда: следующий тест — та же история, ушедшая дальше.
     */
    @Test
    void aToolMarathonInsideTheLastFiveTurnsStaysLive() {
        // 6 вопросов подряд, дальше — только ответы. По 500 символов на сообщение: живое окно
        // весит ~129 000 токенов при пороге 30 000, но срез — ровно одно сообщение (~129 токенов).
        final List<PromptRow> live = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            live.add(row(i, MessageType.USER, 500));
        }
        for (int i = 6; i < 1000; i++) {
            live.add(row(i, MessageType.ASSISTANT, 500));
        }

        final SummarizeWindow window = window(live, PRODUCTION);

        assertThat(window.worthARound()).isFalse();
        assertThat(window.toCompress()).hasSize(1);
        assertThat(window.windowTokens()).isGreaterThan(PRODUCTION.tokenThreshold());
    }

    /**
     * Тот же марафон, но диалог пошёл дальше: новые вопросы вытеснили его из хвоста, и он целиком
     * стал срезом. Окно освобождается само по ходу разговора — из-за этого отсутствие жёсткого
     * потолка не означает, что оно растёт вечно.
     */
    @Test
    void theMarathonIsCompressedOnceLaterQuestionsPushItOutOfTheTail() {
        // 0 — вопрос, 1..99 — марафон ответов, дальше 30 сообщений обычного диалога. Правило по
        // числу сообщений даёт 130 - 30 = 100, правило по вопросам — 120; побеждает раннее, а 100
        // и так открывает ход.
        final List<PromptRow> live = new ArrayList<>(List.of(row(0, MessageType.USER)));
        for (int i = 1; i < 100; i++) {
            live.add(row(i, MessageType.ASSISTANT));
        }
        live.addAll(alternating(100, 130));

        final SummarizeWindow window = window(live, PRODUCTION);

        assertThat(window.worthARound()).isTrue();
        assertThat(window.endPosition()).isEqualTo(99L);
    }

    // -------------------------------------------------------------------------
    // Вес среза
    // -------------------------------------------------------------------------

    /**
     * К вопросу с вложением при чтении истории дописывается блок {@code <attached-context>} — опись
     * приложенного. Она уходит в каждый запрос, но в {@code chat_message.content} её нет, а длина
     * сводки вложения ничем не ограничена: {@code AttachmentService#summarize} кладёт в поле ответ
     * модели как есть. Считать сохранённую колонку значит не видеть описи вовсе, а с ней —
     * произвольную долю окна.
     *
     * <p>Пара с тестом ниже: одно и то же окно из 58 вопросов срабатывает по порогу с описью и не
     * срабатывает без неё. По сохранённому тексту оба весят ~3 600 токенов при пороге 30 000.
     */
    @Test
    void theTokenTriggerCountsTheAttachmentInventoryTheModelActuallyReceives() {
        // 58 вопросов при overlap-messages: 30 дают срез в 28 сообщений — меньше
        // message-count-threshold, поэтому о раунде может попросить только оценка токенов.
        final SummarizeWindow window = window(questions(true), PRODUCTION);

        assertThat(window.toCompress()).hasSize(28);
        assertThat(window.worthARound()).isTrue();
        assertThat(window.trigger()).isEqualTo("token estimate");
        assertThat(window.endPosition()).isEqualTo(27L);
    }

    /**
     * То же окно и тот же сохранённый текст, но без вложений: описи нет — и раунда быть не должно.
     */
    @Test
    void theSameWindowWithoutAttachmentsStaysBelowTheTrigger() {
        final SummarizeWindow window = window(questions(false), PRODUCTION);

        assertThat(window.toCompress()).hasSize(28);
        assertThat(window.worthARound()).isFalse();
    }

    /**
     * Протокольные строки в оценку входят, хотя суммаризатор их не видит: полезная нагрузка
     * tool_calls и ответов инструмента уезжает модели в каждом запросе. Плюс фиксированная
     * протокольная надбавка с каждого сообщения — без неё пустая TOOL-строка стоила бы ровно ноль,
     * и дробление контекста на большее число сообщений делало бы его «дешевле».
     */
    @Test
    void theSliceWeighsToolPayloadsAndPerMessageOverhead() {
        final List<PromptRow> live =
                new ArrayList<>(
                        List.of(
                                row(0, MessageType.USER, 100),
                                toolCallSegment(1, 200),
                                toolResponse(2, 400)));
        live.addAll(alternating(3, 7));

        final SummarizeWindow window = window(live, properties(4, 0, 1));

        // Срез — позиции 0..2: (16+100) + (16+200) + (16+400) = 748 символов / 4.
        assertThat(window.toCompress()).hasSize(2); // пустая TOOL-строка в промпт не входит
        assertThat(window.sliceTokens()).isEqualTo(187);
    }

    /**
     * Метить сжатое по последнему сообщению среза нельзя: {@code prompt} не содержит пустых
     * TOOL-строк, и хвост сжатого хода остался бы живым и осиротевшим. Диапазон обязан идти до
     * первого ОСТАВЛЕННОГО сообщения — здесь это на одну позицию дальше последнего сжатого.
     */
    @Test
    void theMarkedRangeCoversTheTrailingProtocolRowsOfTheLastCompressedTurn() {
        // 20 ходов «вопрос → сегмент с tool_calls → ответ инструмента», по 3 позиции на ход.
        final List<PromptRow> live = new ArrayList<>();
        for (int turn = 0; turn < 20; turn++) {
            live.add(row(turn * 3, MessageType.USER));
            live.add(toolCallSegment(turn * 3 + 1, 10));
            live.add(toolResponse(turn * 3 + 2, 10));
        }

        final SummarizeWindow window = window(live, properties(10, 5, 1));

        assertThat(window.toCompress().getLast().entity().getPosition()).isEqualTo(43L);
        assertThat(window.kept().getFirst().entity().getPosition()).isEqualTo(45L);
        assertThat(window.endPosition()).isEqualTo(44L);
    }

    /**
     * Сводки в арифметике не участвуют: они уже сжатое прошлое. Ни в срез, ни в его вес они не
     * попадают, но остаются доступны суммаризатору как контекст.
     */
    @Test
    void summariesAreContextOnlyAndWeighNothing() {
        final List<PromptRow> live = new ArrayList<>(List.of(summary(0)));
        live.addAll(alternating(1, 61));

        final SummarizeWindow withSummary = window(live, properties(10, 0, 1));
        final SummarizeWindow withoutSummary = window(alternating(1, 61), properties(10, 0, 1));

        assertThat(withSummary.summaries()).hasSize(1);
        assertThat(withSummary.sliceTokens()).isEqualTo(withoutSummary.sliceTokens());
        assertThat(withSummary.toCompress()).hasSameSizeAs(withoutSummary.toCompress());
    }

    // -------------------------------------------------------------------------

    private static SummarizeWindow window(List<PromptRow> rows, SummarizeProperties properties) {
        return new SummarizeWindow(rows, properties);
    }

    private static SummarizeProperties properties(
            int overlapMessages, int overlapUserMessages, int messageCountThreshold) {
        return new SummarizeProperties(
                UNREACHABLE_TOKENS,
                messageCountThreshold,
                overlapMessages,
                overlapUserMessages,
                5,
                4);
    }

    /** 0..39 — чередование вопрос/ответ, 40..59 — только ответы модели. */
    private static List<PromptRow> alternatingThenAssistants() {
        final List<PromptRow> live = new ArrayList<>(alternating(0, 40));
        for (int i = 40; i < 60; i++) {
            live.add(row(i, MessageType.ASSISTANT));
        }
        return live;
    }

    private static List<PromptRow> alternating(int from, int toExclusive) {
        final List<PromptRow> live = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) {
            live.add(row(i, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT));
        }
        return live;
    }

    /** 58 вопросов по 500 символов, с описью приложенного или без неё. */
    private static List<PromptRow> questions(boolean withAttachment) {
        final List<PromptRow> live = new ArrayList<>();
        for (int i = 0; i < 58; i++) {
            final ChatMessageEntity entity = entity(i, MessageType.USER, text(500), null, null);
            live.add(
                    new PromptRow(
                            entity,
                            withAttachment
                                    ? entity.getContent() + inventory()
                                    : entity.getContent()));
        }
        return live;
    }

    /**
     * Слепок того, что вернёт {@code ContextItemService#renderAll} для одного вложения — рамка
     * блока и строка вложения собраны дословно, чтобы вес описи был виден глазами, а не взят
     * константой с потолка. Сводка вложения — единственная её часть, размер которой задаёт модель,
     * а не формат.
     */
    private static String inventory() {
        return "\n\n<attached-context>\nThe user attached the following to this message:\n"
                + "- attachment id=1 name=\"spec.md\" type=text/markdown size=12345 summary=\""
                + "x".repeat(3_800)
                + "\"\nUse getAttachmentContent(attachmentId) to read the full text of an"
                + " attachment.\n</attached-context>";
    }

    private static PromptRow row(long position, MessageType type) {
        return row(position, type, 3);
    }

    /** {@code chars} — длина текста: она и есть вес сообщения для оценки токенов. */
    private static PromptRow row(long position, MessageType type, int chars) {
        final ChatMessageEntity entity = entity(position, type, text(chars), null, null);
        return new PromptRow(entity, entity.getContent());
    }

    private static String text(int chars) {
        return "x".repeat(chars);
    }

    /** ASSISTANT-сегмент вызова инструмента: текста нет, есть tool_calls и мета для промпта. */
    private static PromptRow toolCallSegment(long position, int argumentChars) {
        final ToolData toolData =
                new ToolData(
                        List.of(
                                new ToolData.Call(
                                        "call-" + position,
                                        "function",
                                        "search",
                                        "x".repeat(argumentChars))),
                        null);
        final ChatMessageMeta meta =
                new ChatMessageMeta(
                        List.of(
                                new ToolInvocationMeta(
                                        "search",
                                        Map.of(),
                                        ToolInvocationStatus.OK,
                                        null,
                                        null,
                                        true,
                                        0,
                                        "gist",
                                        "call-" + position)));
        return new PromptRow(entity(position, MessageType.ASSISTANT, "", meta, toolData), "");
    }

    /** Протокольная TOOL-строка: текст пустой, полезная нагрузка — в {@code tool_data}. */
    private static PromptRow toolResponse(long position, int responseChars) {
        final ToolData toolData =
                new ToolData(
                        null,
                        List.of(
                                new ToolData.Response(
                                        "call-" + (position - 1),
                                        "search",
                                        "x".repeat(responseChars))));
        return new PromptRow(entity(position, MessageType.TOOL, "", null, toolData), "");
    }

    private static PromptRow summary(long position) {
        final ChatMessageEntity entity =
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        "Earlier conversation summary",
                        MessageType.ASSISTANT,
                        position,
                        false,
                        true,
                        LocalDateTime.now(),
                        null,
                        null);
        return new PromptRow(entity, entity.getContent());
    }

    private static ChatMessageEntity entity(
            long position,
            MessageType type,
            String content,
            @Nullable ChatMessageMeta meta,
            @Nullable ToolData toolData) {
        return new ChatMessageEntity(
                position + 1,
                CONV,
                content,
                type,
                position,
                false,
                false,
                LocalDateTime.now(),
                meta,
                toolData);
    }
}
