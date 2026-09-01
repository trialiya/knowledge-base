package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import org.jspecify.annotations.Nullable;

/**
 * Счёт токенов за весь чат — ответ {@code GET /api/chats/{id}/usage}.
 *
 * <p>Считает его бэкенд, а не фронт, по одной причине: фронт видит загруженную страницу ленты (по
 * умолчанию два десятка сообщений), и итог по ней — это итог по хвосту разговора, а не по чату.
 * Заполнение контекста фронт по-прежнему берёт из ленты сам: там нужен последний замер, и хвоста
 * для него достаточно.
 *
 * <p>Контекстных чисел в замерах здесь нет (см. {@link RunTokenUsage#spentTogether}): контекст у
 * прогонов общий и растёт, а не набирается, поэтому сумма по нему была бы числом ниоткуда.
 *
 * @param baseContextTokens системная часть контекста — {@code basePromptTokens} первого измеренного
 *     прогона чата: системный промпт со схемами инструментов плюс первый вопрос. {@code null} —
 *     первый прогон чата не измерен либо записан версией без этого поля
 * @param spent деньги модели чата: прогоны, раунды сжатия и унесённые сжатием сводки ({@code
 *     CompactMeta#carried}). {@code null} — в чате не измерено ни одного прогона, и ноль здесь был
 *     бы неправдой
 * @param subagentRuns сколько вызовов суб-агента принесли замер
 * @param subagentSpent деньги суб-агентов, отдельным числом. Отдельным, а не слагаемым в {@link
 *     #spent}: у суб-агента своя модель ({@code kb.search.subagent.model-id}) и свой тариф, и сумма
 *     по двум тарифам не сверяется со счётом провайдера ни по одной строке. {@code null} —
 *     суб-агент в этом чате не работал либо его эндпоинт usage не отдаёт
 */
public record ChatUsageTotals(
        @Nullable Long baseContextTokens,
        @Nullable RunTokenUsage spent,
        int subagentRuns,
        @Nullable RunTokenUsage subagentSpent) {}
