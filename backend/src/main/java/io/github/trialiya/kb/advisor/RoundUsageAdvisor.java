package io.github.trialiya.kb.advisor;

import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.chat.entity.TokenUsage;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

/**
 * Замер одного раунда — накопитель на объект вместо области прогона. Нужен там, где к модели ходит
 * фоновая операция без своего прогона: у неё нет ни {@code RunScope}, куда пишет {@link
 * TokenUsageAdvisor}, ни события {@code RUN_USAGE}, которое некому слушать, а деньги она тратит те
 * же — и без замера они не попали бы в итог по чату вовсе.
 *
 * <p>Одного {@code ChatResponse} для этого мало: раунд с инструментами делает несколько обращений,
 * и на руках у вызывающего остаётся ответ последнего. Отсюда {@link Ordered#LOWEST_PRECEDENCE} —
 * тот же расчёт, что и у {@link TokenUsageAdvisor}: внутри tool-цикла, который зовёт цепочку заново
 * на каждой итерации, поэтому каждое обращение попадает в накопитель само.
 *
 * <p>Экземпляр — на один раунд: накопитель в нём общий, и переиспользованный адвайзер складывал бы
 * разные раунды в одно число.
 */
public class RoundUsageAdvisor implements CallAdvisor {

    private final AtomicReference<RunTokenUsage.Tally> tally =
            new AtomicReference<>(RunTokenUsage.Tally.EMPTY);

    @Override
    public String getName() {
        return "roundUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        final ChatClientResponse response = chain.nextCall(request);
        final TokenUsage measured =
                TokenUsage.of(response == null ? null : response.chatResponse());
        tally.updateAndGet(accumulated -> accumulated.with(measured));
        return response;
    }

    /**
     * Итог раунда; правило сборки — общее с прогоном чата ({@link RunTokenUsage.Tally}), иначе одни
     * и те же деньги в двух местах статистики назывались бы разными числами.
     *
     * <p>Пустой замер означает «эндпоинт не измеряет» и в мету не идёт: «неизвестно» это не ноль.
     */
    public RunTokenUsage usage() {
        return tally.get().view();
    }
}
