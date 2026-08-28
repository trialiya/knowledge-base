package io.github.trialiya.kb.model.chat.entity;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Токены одного обращения к модели, как их измерил {@code TokenUsageAdvisor}. Итог прогона из них
 * собирает область прогона ({@code RunScope}) — и собирает не одной лишь суммой, см. {@link
 * RunTokenUsage}.
 *
 * <p>Складывается в два уровня, и оба правила разные, потому что разные провайдеры отдают usage
 * по-разному:
 *
 * <ul>
 *   <li><b>внутри одного обращения к модели</b> — {@link #merge}, по-полевой максимум. OpenAI
 *       присылает usage единственным финальным чанком, Anthropic — нарастающим итогом в каждом, а
 *       часть прокси разносит prompt и completion по разным чанкам. Максимум верен во всех трёх
 *       случаях, тогда как «первый непустой» терял бы completion, а «последний непустой» — prompt.
 *   <li><b>между обращениями</b> — {@link #plus}, сумма: каждая итерация tool-цикла оплачивается
 *       отдельно, и prompt в ней считается заново от начала диалога. Это total input, и наверх она
 *       идёт только в расширенной статистике — почему, см. {@link RunTokenUsage}.
 * </ul>
 *
 * <p>Поля — {@code long}: провайдер отдаёт {@code Integer}, но сумма по длинному прогону с большим
 * контекстом переполнение {@code int} вполне достаёт.
 *
 * @param promptTokens вход (в терминах OpenAI — prompt, в терминах Anthropic — input)
 * @param completionTokens выход
 * @param totalTokens итог, как его назвал провайдер; не меньше суммы двух предыдущих (см. {@link
 *     #normalized}) — у провайдеров с reasoning-токенами он больше
 * @param cacheReadTokens прочитано из кэша промпта; часть {@link #promptTokens}, а не добавка к
 *     нему — тариф у них разный, поэтому цифра нужна отдельно
 * @param cacheWriteTokens записано в кэш промпта
 */
public record TokenUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long cacheReadTokens,
        long cacheWriteTokens) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0, 0);

    /**
     * Замер из ответа модели; {@link #EMPTY}, если провайдер его не прислал.
     *
     * <p>Разбор провайдерского {@link Usage} живёт здесь, а не у вызывающих: их двое и они не
     * похожи — стриминговый advisor чата и синхронный цикл суб-агента, — а поля и их {@code null} у
     * обоих одни и те же. Вторая копия этого метода разошлась бы с первой на первом же поле,
     * которое провайдер начнёт отдавать (кэш уже был таким полем).
     */
    public static TokenUsage of(@Nullable ChatResponse response) {
        final ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        final Usage measured = metadata == null ? null : metadata.getUsage();
        if (measured == null) {
            return EMPTY;
        }
        return new TokenUsage(
                nz(measured.getPromptTokens()),
                nz(measured.getCompletionTokens()),
                nz(measured.getTotalTokens()),
                nz(measured.getCacheReadInputTokens()),
                nz(measured.getCacheWriteInputTokens()));
    }

    private static long nz(@Nullable Number value) {
        return value == null ? 0L : value.longValue();
    }

    /**
     * Ничего не насчитано. Именно «все нули», а не «объекта нет»: провайдер без поддержки usage в
     * стриме (и {@code EmptyUsage} Spring AI) отдаёт ровно нули, и такой замер нельзя ни
     * публиковать, ни складывать — иначе фронт показал бы уверенный ноль вместо честного
     * «неизвестно».
     */
    public boolean isEmpty() {
        return promptTokens == 0
                && completionTokens == 0
                && totalTokens == 0
                && cacheReadTokens == 0
                && cacheWriteTokens == 0;
    }

    /** Замеры одного обращения к модели: по-полевой максимум. */
    public TokenUsage merge(TokenUsage other) {
        return new TokenUsage(
                        Math.max(promptTokens, other.promptTokens),
                        Math.max(completionTokens, other.completionTokens),
                        Math.max(totalTokens, other.totalTokens),
                        Math.max(cacheReadTokens, other.cacheReadTokens),
                        Math.max(cacheWriteTokens, other.cacheWriteTokens))
                .normalized();
    }

    /** Замеры разных обращений к модели: сумма. */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                        promptTokens + other.promptTokens,
                        completionTokens + other.completionTokens,
                        totalTokens + other.totalTokens,
                        cacheReadTokens + other.cacheReadTokens,
                        cacheWriteTokens + other.cacheWriteTokens)
                .normalized();
    }

    /**
     * Итог не меньше суммы частей. Нужно после {@link #merge}: провайдер, разносящий prompt и
     * completion по разным чанкам, в каждом из них присылает свой {@code total}, равный только этой
     * половине, — по-полевой максимум взял бы большую из половин и потерял вторую.
     */
    private TokenUsage normalized() {
        final long parts = promptTokens + completionTokens;
        return totalTokens >= parts
                ? this
                : new TokenUsage(
                        promptTokens, completionTokens, parts, cacheReadTokens, cacheWriteTokens);
    }
}
