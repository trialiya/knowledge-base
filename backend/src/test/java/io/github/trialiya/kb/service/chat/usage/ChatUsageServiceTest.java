package io.github.trialiya.kb.service.chat.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.dto.ChatUsageTotals;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatUsageRow;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Правила счёта за весь чат. Те же, что у фронта в {@code tokenUsage.js}: что складывается, что
 * никогда не складывается и что значит «не измерено».
 */
class ChatUsageServiceTest {

    private static final String CONV = "conv-1";

    private final ChatMessageRepository repo = mock(ChatMessageRepository.class);
    private final ChatUsageService service = new ChatUsageService(repo, new ObjectMapper());

    /**
     * Контекст у прогонов общий и растёт, а не набирается: сумма по нему была бы числом ниоткуда.
     */
    @Test
    void addsUpTheMoneyOfEveryRunAndNeverTheContext() {
        rows(answer(run(9_000, 1_000, 300, 12_000, 2)), answer(run(21_000, 1_000, 700, 33_000, 3)));

        final ChatUsageTotals totals = service.totals(CONV);

        assertThat(totals.spent().outputTokens()).isEqualTo(1_000);
        assertThat(totals.spent().promptTokens()).isEqualTo(45_000);
        assertThat(totals.spent().cacheReadTokens()).isEqualTo(1_000);
        assertThat(totals.spent().modelCalls()).isEqualTo(5);
        assertThat(totals.spent().contextTokens()).isZero();
    }

    /** Reasoning-токены провайдер считает сверх видимого выхода — иначе итог занижен на них. */
    @Test
    void keepsTheProvidersOwnTotalAboveTheSumOfParts() {
        rows(answer(new RunTokenUsage(9_000, 1_000, 0, 300, 8_000, 0, 0, 12_000, 1)));

        assertThat(service.totals(CONV).spent().totalTokens()).isEqualTo(12_000);
    }

    /**
     * Прогон, записанный до появления {@code totalTokens}, несёт по этому полю ноль. Складывать
     * такие нули с чужими итогами нельзя: сумма вышла бы меньше суммы входов с выходами, и
     * читающему осталось бы взять большее из двух — стерев reasoning-токены всех остальных.
     */
    @Test
    void doesNotLetAnOldRunEraseTheReasoningTokensOfTheRest() {
        rows(
                answer(new RunTokenUsage(0, 0, 0, 300, 8_000, 0, 0, 12_000, 1)),
                answer(new RunTokenUsage(0, 0, 0, 200, 5_000, 0, 0, 0, 1)));

        // 12 000 у первого плюс 5 200 у второго — а не max(12 000, 13 500).
        assertThat(service.totals(CONV).spent().totalTokens()).isEqualTo(17_200);
    }

    /** Системная часть — {@code basePromptTokens} ПЕРВОГО измеренного прогона, и только его. */
    @Test
    void takesTheSystemPartFromTheFirstMeasuredRun() {
        rows(answer(run(9_000, 1_400, 0, 9_000, 1)), answer(run(21_000, 12_000, 0, 21_000, 1)));

        assertThat(service.totals(CONV).baseContextTokens()).isEqualTo(1_400);
    }

    /**
     * Замер на ряду пользователя описывает окно несостоявшегося сжатия, а не контекст чата: в
     * деньги он идёт наравне со всеми, в системную часть — нет.
     */
    @Test
    void neverReadsTheSystemPartOffAUserRow() {
        rows(question(run(50_000, 49_000, 0, 50_000, 1)), answer(run(9_000, 1_400, 0, 9_000, 1)));

        final ChatUsageTotals totals = service.totals(CONV);

        assertThat(totals.baseContextTokens()).isEqualTo(1_400);
        assertThat(totals.spent().promptTokens()).isEqualTo(59_000);
    }

    /** У плашки сжатия в базе лежит прочитанное раундом окно, а не системная часть чата. */
    @Test
    void neverReadsTheSystemPartOffACompactNotice() {
        rows(
                compact(run(60_000, 58_000, 0, 60_000, 1), null),
                answer(run(9_000, 1_400, 0, 9_000, 1)));

        assertThat(service.totals(CONV).baseContextTokens()).isEqualTo(1_400);
    }

    /** Деньги сводок, которые сжатие выбросило: своего ряда у них не осталось, а заплачено было. */
    @Test
    void addsUpTheRoundsCarriedByACompaction() {
        rows(compact(run(60_000, 58_000, 0, 60_000, 1), run(0, 0, 0, 40_000, 2)));

        final ChatUsageTotals totals = service.totals(CONV);

        assertThat(totals.spent().promptTokens()).isEqualTo(100_000);
        assertThat(totals.spent().modelCalls()).isEqualTo(3);
    }

    /** У суб-агента своя модель и свой тариф, поэтому его деньги стоят отдельным числом. */
    @Test
    void countsSubagentSpendingApartFromTheChatModel() {
        rows(
                answerWithSubagents(
                        run(9_000, 1_000, 0, 12_000, 2),
                        Map.of("usage", run(0, 0, 400, 20_000, 4)),
                        Map.of("usage", run(0, 0, 100, 5_000, 1))));

        final ChatUsageTotals totals = service.totals(CONV);

        assertThat(totals.spent().promptTokens()).isEqualTo(12_000);
        assertThat(totals.subagentRuns()).isEqualTo(2);
        assertThat(totals.subagentSpent().promptTokens()).isEqualTo(25_000);
        assertThat(totals.subagentSpent().outputTokens()).isEqualTo(500);
        assertThat(totals.subagentSpent().modelCalls()).isEqualTo(5);
    }

    /** Из БД замер приезжает разобранной картой, а не записью, — читаться обязан так же. */
    @Test
    void readsASubagentMeasurementWrittenAsAPlainMap() {
        rows(
                answerWithSubagents(
                        run(9_000, 1_000, 0, 12_000, 1),
                        Map.of("usage", Map.of("promptTokens", 20_000, "modelCalls", 4))));

        assertThat(service.totals(CONV).subagentSpent().promptTokens()).isEqualTo(20_000);
    }

    /** Чужая форма под тем же ключом стоит своего вызова, а не всего счёта. */
    @Test
    void survivesAnUnreadableMeasurementInAToolResult() {
        rows(
                answerWithSubagents(
                        run(9_000, 1_000, 0, 12_000, 1), Map.of("usage", "не запись вовсе")));

        final ChatUsageTotals totals = service.totals(CONV);

        assertThat(totals.spent().promptTokens()).isEqualTo(12_000);
        assertThat(totals.subagentSpent()).isNull();
    }

    /** Ноль здесь был бы неправдой: чат мог идти на эндпоинте, который usage не отдаёт. */
    @Test
    void tellsNothingMeasuredApartFromZero() {
        rows(answer(null), question(null));

        final ChatUsageTotals totals = service.totals(CONV);

        assertThat(totals.spent()).isNull();
        assertThat(totals.subagentSpent()).isNull();
        assertThat(totals.baseContextTokens()).isNull();
    }

    private void rows(ChatUsageRow... rows) {
        when(repo.findUsageRows(CONV)).thenReturn(List.of(rows));
    }

    private static RunTokenUsage run(long context, long base, long output, long prompt, int calls) {
        return new RunTokenUsage(context, base, 0, output, prompt, 500, 0, prompt + output, calls);
    }

    private static ChatUsageRow answer(RunTokenUsage usage) {
        return new ChatUsageRow(
                MessageType.ASSISTANT, usage == null ? null : ChatMessageMeta.ofUsage(usage));
    }

    private static ChatUsageRow question(RunTokenUsage usage) {
        return new ChatUsageRow(
                MessageType.USER, usage == null ? null : ChatMessageMeta.ofUsage(usage));
    }

    private static ChatUsageRow compact(RunTokenUsage usage, RunTokenUsage carried) {
        return new ChatUsageRow(
                MessageType.ASSISTANT,
                ChatMessageMeta.ofCompact(
                                new CompactMeta(12, 3_000, 7L, CompactMeta.Kind.COMPACT, carried))
                        .withUsage(usage));
    }

    @SafeVarargs
    private static ChatUsageRow answerWithSubagents(
            RunTokenUsage usage, Map<String, ?>... resultMetas) {
        final List<ToolInvocationMeta> invocations =
                Arrays.stream(resultMetas)
                        .map(
                                meta ->
                                        new ToolInvocationMeta(
                                                "searchCodebase",
                                                Map.of(),
                                                ToolInvocationCollector.ToolInvocationStatus.OK,
                                                null,
                                                meta,
                                                null,
                                                null,
                                                null,
                                                null))
                        .toList();
        return new ChatUsageRow(
                MessageType.ASSISTANT, new ChatMessageMeta(invocations).withUsage(usage));
    }
}
