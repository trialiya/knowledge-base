package io.github.trialiya.kb.service.chat.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.dto.ChatUsageTotals;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatUsageRow;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

/**
 * Итог по токенам за весь чат (см. {@link ChatUsageTotals}).
 *
 * <p>Единственное место, где эти итоги считаются. Фронту остаётся вопрос «сколько занято сейчас»
 * ({@code contextUsageOf} в {@code tokenUsage.js}) — на него отвечает последний замер, и хвоста
 * загруженной ленты для него достаточно. Итог по чату так не собрать: лента — это страница, и счёт
 * по ней был бы счётом по хвосту разговора.
 */
@Service
public class ChatUsageService {

    private static final Logger log = LoggerFactory.getLogger(ChatUsageService.class);

    /**
     * Ключ замера в {@code resultMeta} вызова инструмента. Кладёт его {@code
     * SearchAgentResult#getResultMeta}; по ключу, а не по имени инструмента, потому что вопрос тут
     * не «звали ли searchCodebase», а «сходил ли инструмент к модели за наш счёт» — второй такой
     * инструмент попадёт в счёт сам, не дожидаясь правки этого списка.
     */
    private static final String USAGE_KEY = "usage";

    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public ChatUsageService(
            ChatMessageRepository chatMessageRepository, ObjectMapper objectMapper) {
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
    }

    public ChatUsageTotals totals(String conversationId) {
        final List<RunTokenUsage> spent = new ArrayList<>();
        final List<RunTokenUsage> subagent = new ArrayList<>();
        Long base = null;
        boolean baseDecided = false;

        for (ChatUsageRow row : chatMessageRepository.findUsageRows(conversationId)) {
            final ChatMessageMeta meta = row.meta();
            if (meta == null) {
                continue;
            }
            // Деньги сводок, которые сжатие выбросило вместе с их куском истории: своего ряда у
            // них не осталось, а заплачено было (см. CompactMeta#carried).
            final RunTokenUsage carried = meta.compact() == null ? null : meta.compact().carried();
            if (carried != null && !carried.isEmpty()) {
                spent.add(carried);
            }
            subagent.addAll(subagentUsage(meta.invocations()));

            final RunTokenUsage usage = meta.usage();
            if (usage == null || usage.isEmpty()) {
                continue;
            }
            spent.add(usage);
            if (!baseDecided && isBaseCandidate(row, meta)) {
                // Первый подходящий ряд решает, и дальше не ищем: у любого следующего прогона в
                // базу входит уже вся история до него.
                baseDecided = true;
                base = usage.basePromptTokens() > 0 ? usage.basePromptTokens() : null;
            }
        }
        return new ChatUsageTotals(
                base,
                spent.isEmpty() ? null : RunTokenUsage.spentTogether(spent),
                subagent.size(),
                subagent.isEmpty() ? null : RunTokenUsage.spentTogether(subagent));
    }

    /**
     * Годится ли замер этого ряда на роль системной части. Не годится замер на ряду ПОЛЬЗОВАТЕЛЯ
     * (там он описывает окно несостоявшегося сжатия — см. {@code ChatMessageEntity#getRunUsage}) и
     * замер на плашке сжатия: у {@code /compact} его {@code basePromptTokens} — всё прочитанное
     * раундом окно, у фоновой суммаризации — чужой системный промпт, суммаризатора.
     */
    private static boolean isBaseCandidate(ChatUsageRow row, ChatMessageMeta meta) {
        return row.type() != MessageType.USER && meta.compact() == null;
    }

    /** Замеры суб-агентов, поднятые из мет вызовов инструментов этого ряда. */
    private List<RunTokenUsage> subagentUsage(List<ToolInvocationMeta> invocations) {
        final List<RunTokenUsage> measured = new ArrayList<>();
        for (ToolInvocationMeta invocation : invocations) {
            final Map<String, ?> resultMeta = invocation.resultMeta();
            final Object raw = resultMeta == null ? null : resultMeta.get(USAGE_KEY);
            if (raw == null) {
                continue;
            }
            final RunTokenUsage usage = readUsage(raw, invocation.name());
            if (usage != null && !usage.isEmpty()) {
                measured.add(usage);
            }
        }
        return measured;
    }

    /**
     * Замер из {@code resultMeta}, которая в мете лежит разобранной картой, а не записью. Чужая
     * форма под тем же ключом — не повод отдать вместо всего счёта ошибку: пропускаем этот вызов и
     * оставляем след в логе.
     */
    private @Nullable RunTokenUsage readUsage(Object raw, String tool) {
        try {
            return objectMapper.convertValue(raw, RunTokenUsage.class);
        } catch (IllegalArgumentException e) {
            log.warn("Tool {} left an unreadable usage in its result meta: {}", tool, e.toString());
            return null;
        }
    }
}
