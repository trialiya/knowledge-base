package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Сравнение двух ревизий — режим «Изменения» панели «Файлы» с выбранной базой сравнения ({@code GET
 * /api/git/compare}): чем ревизия {@code head} отличается от {@code base}.
 *
 * <p>Чем именно — решает {@code diffBase}. По умолчанию это общий предок обеих ({@code git diff
 * base...head}): так читается ветка относительно той, от которой её отвели, — работа, сделанная на
 * {@code base} после развилки, не выдаётся за откат её на {@code head}. Прямое сравнение ({@code
 * git diff base head}) — по просьбе, и оно же — когда общего предка нет вовсе.
 *
 * @param base ревизия, с которой сравнивают, — без файлов и описания
 * @param head ревизия, которую сравнивают, — без файлов и описания
 * @param mergeBase полный хеш общего предка; {@code null}, если у историй его нет
 * @param diffBase полный хеш коммита, против которого посчитан {@code files}: {@code mergeBase} или
 *     сам {@code base}
 * @param log коммиты между ревизиями; {@code null}, когда запрошен один файл — его патчу история
 *     не нужна, а обход её стоит на каждом клике
 * @param files файлы, которыми {@code head} отличается от {@code diffBase}; патчи — только по
 *     просьбе, как у {@code GET /api/git/commit}
 */
public record GitComparison(
        GitCommit base,
        GitCommit head,
        @Nullable String mergeBase,
        String diffBase,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) GitComparisonLog log,
        List<GitDiffEntry> files) {}
