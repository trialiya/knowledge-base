package io.github.trialiya.kb.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Одноразовый бэкафилл для чатов, записанных до появления {@code tool_call_index}/{@code
 * ToolInvocationMeta#callId} (см. {@link ChatMemoryService#backfillToolCallIds}). Выключен по
 * умолчанию — включается на один прогон переменной {@code KB_BACKFILL_TOOL_CALL_IDS=true} (или
 * {@code kb.backfill.tool-call-ids: true}), после чего флаг снова выключают: повторный запуск
 * безопасен (идемпотентно), но бессмыслен.
 */
@Slf4j
@AllArgsConstructor
@Component
@ConditionalOnProperty(prefix = "kb.backfill", name = "tool-call-ids", havingValue = "true")
public class ToolCallIdBackfillRunner implements CommandLineRunner {

    private final ChatMemoryService chatMemoryService;

    @Override
    public void run(String... args) {
        log.info("Starting tool-call id backfill (kb.backfill.tool-call-ids=true)...");
        ChatMemoryService.BackfillResult result = chatMemoryService.backfillToolCallIds();
        log.info(
                "Tool-call id backfill done: {} conversation(s) touched, {} invocation(s) filled",
                result.conversationsTouched(),
                result.invocationsFilled());
    }
}
