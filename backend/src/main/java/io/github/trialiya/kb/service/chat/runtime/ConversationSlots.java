package io.github.trialiya.kb.service.chat.runtime;

import io.github.trialiya.kb.service.chat.event.ChatEventService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Кто прямо сейчас занял чат. Заявка одна на все длительные операции с историей — генерация ответа,
 * сжатие контекста, git-команда пользователя, восстановление очереди после падения процесса: все
 * они читают и переписывают одну и ту же историю, и вторая поверх первой сломала бы её протокольно.
 *
 * <p>Занятость не проверяют, а <b>занимают</b>: {@code putIfAbsent} делает «свободен» и «занял»
 * одним атомарным действием. Проверки было бы мало — между ней и первой записью в историю успевает
 * вклиниться чужая операция.
 *
 * <p>Две пары методов, и путать их нельзя:
 *
 * <ul>
 *   <li>{@link #claim} / {@link #release} — заявка вместе с хабом событий: вкладки видят чат
 *       занятым. Это то, что нужно операции без собственного прогона;
 *   <li>{@link #take} / {@link #free} — только заявка. Для генерации, которая заводит и закрывает
 *       хаб сама, в своём порядке относительно остальной терминальной обработки (см. {@code
 *       ChatRunService}).
 * </ul>
 */
@Component
public class ConversationSlots {

    private final ChatEventService events;

    /** conversationId -&gt; runId: гарантирует не более одной операции на чат. */
    private final ConcurrentHashMap<String, String> byConversation = new ConcurrentHashMap<>();

    public ConversationSlots(ChatEventService events) {
        this.events = events;
    }

    /**
     * Занимает чат под уже выбранный id операции — хаб событий при этом не трогает.
     *
     * @throws ResponseStatusException 409, если чат уже занят
     */
    public void take(String conversationId, String runId) {
        if (byConversation.putIfAbsent(conversationId, runId) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A response is already being generated for this chat");
        }
    }

    /**
     * Снимает заявку, не трогая хаб. Идемпотентно и снимает только СВОЮ заявку: чат, занятый уже
     * следующей операцией, остаётся за ней.
     */
    public void free(String conversationId, String runId) {
        byConversation.remove(conversationId, runId);
    }

    /**
     * Занимает чат под фоновую операцию, которая генерацией не является, — сжатие контекста ({@code
     * CompactService}), git-команда ({@code ChatGitLog}), восстановление очереди на старте
     * приложения ({@code PendingMessageRecovery}). Заявка та же самая, что у прогона, и это здесь
     * главное: пока операция идёт, вопрос в этот чат получает 409, а сама она не начнётся поверх
     * идущей генерации.
     *
     * <p>Для вкладок такая операция — тоже прогон: {@link ChatEventService#startRun} держит {@code
     * runId} активным, и вкладка, вошедшая в чат посреди неё, увидит его занятым. Своего
     * дескриптора прогона она не заводит, поэтому остановить её нечем — {@code ChatRunService.stop}
     * про неё не знает.
     *
     * @return id занятой операции — его же нужно вернуть в {@link #release}
     * @throws ResponseStatusException 409, если чат уже занят
     */
    public String claim(String conversationId) {
        final String runId = UUID.randomUUID().toString();
        take(conversationId, runId);
        events.startRun(conversationId, runId);
        return runId;
    }

    /** Освобождает чат, занятый {@link #claim}, и закрывает хаб операции. Идемпотентно. */
    public void release(String conversationId, String runId) {
        free(conversationId, runId);
        events.endRun(conversationId, runId);
    }

    /** Занят ли чат, и кем — id активной операции, каким его видят вкладки. */
    public Optional<String> activeRun(String conversationId) {
        return events.activeRunId(conversationId);
    }

    /**
     * Число чатов с удержанной заявкой — для мониторинга утечек (см. {@code ChatRuntimeMonitor}).
     */
    public int claimedConversationCount() {
        return byConversation.size();
    }
}
