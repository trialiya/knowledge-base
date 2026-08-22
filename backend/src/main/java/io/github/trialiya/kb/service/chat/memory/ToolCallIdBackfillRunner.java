package io.github.trialiya.kb.service.chat.memory;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Run-once бэкафилл для чатов, записанных до появления {@code tool_call_index}/{@code
 * ToolInvocationMeta#callId} (см. {@link ToolCallBackfillService#backfillToolCallIds}). Вызывается
 * при каждом старте, но реальную работу делает один раз: маркер в {@code backfill_state} (ключ
 * {@link ToolCallBackfillService#TOOL_CALL_ID_BACKFILL_KEY}) ставится в той же транзакции, что и
 * сам бэкафилл, поэтому повторные старты — дешёвый no-op. Флага конфигурации больше нет.
 */
@Slf4j
@AllArgsConstructor
@Component
public class ToolCallIdBackfillRunner implements CommandLineRunner {

    private final ToolCallBackfillService backfillService;

    @Override
    public void run(String... args) {
        ToolCallBackfillService.BackfillResult result =
                backfillService.backfillToolCallIdsIfNeeded();
        log.info(
                "Tool-call id backfill: {} conversation(s) touched, {} invocation(s) filled",
                result.conversationsTouched(),
                result.invocationsFilled());
    }
}
