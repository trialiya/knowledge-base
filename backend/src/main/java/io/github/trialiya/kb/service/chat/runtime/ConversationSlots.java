package io.github.trialiya.kb.service.chat.runtime;

import io.github.trialiya.kb.service.chat.event.ChatEventService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
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
 * <p>Взять чат можно двумя способами, отдать — одним:
 *
 * <ul>
 *   <li>{@link #claim} — заявка вместе с хабом событий, то есть чат сразу выглядит занятым и для
 *       вкладок. Это то, что нужно операции без собственного прогона;
 *   <li>{@link #take} — только заявка. Для генерации: хаб она заводит позже, вместе с RUN_STARTED,
 *       — вопрос к тому моменту уже записан (см. {@code ChatRunService.start});
 *   <li>{@link #release} — и то и другое обратно, в правильном порядке. Отдают чат им оба.
 * </ul>
 *
 * <p>{@link #free} — половина {@code release} без хаба: она нужна там, где хаба ещё нет — заявка
 * взята, а генерация до RUN_STARTED так и не дошла.
 */
@Component
public class ConversationSlots {

    private final ChatEventService events;

    /** conversationId -&gt; заявка: гарантирует не более одной операции на чат. */
    private final ConcurrentHashMap<String, Hold> byConversation = new ConcurrentHashMap<>();

    /**
     * Кто держит чат: id операции, генерация ли это и когда её взяли. Вид нужен ровно на то окно,
     * когда прогон уже покинул реестр, а заявку ещё держит (терминальная обработка пишет в БД и
     * доставляет очередь): реестра для ответа «что это было» там уже нет, а вкладка спрашивает.
     *
     * <p>Момент взятия — по тем же монотонным часам, что и у прогона ({@code RunScope}): его читает
     * таймер во вкладке, открытой посреди операции, а системное время между двумя чтениями умеет
     * прыгнуть назад.
     */
    private record Hold(String runId, boolean generation, long startedAtNanos) {

        Hold(String runId, boolean generation) {
            this(runId, generation, System.nanoTime());
        }

        long elapsedMs() {
            return (System.nanoTime() - startedAtNanos) / 1_000_000;
        }
    }

    public ConversationSlots(ChatEventService events) {
        this.events = events;
    }

    /**
     * Занимает чат под уже выбранный id операции — хаб событий при этом не трогает.
     *
     * @throws ResponseStatusException 409, если чат уже занят
     */
    public void take(String conversationId, String runId) {
        hold(conversationId, new Hold(runId, true));
    }

    private void hold(String conversationId, Hold hold) {
        if (byConversation.putIfAbsent(conversationId, hold) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A response is already being generated for this chat");
        }
    }

    /**
     * Занимает чат под новую операцию, не трогая хаб, — для прогона, который заводит хаб позже
     * ({@code ChatRunService.start} шлёт RUN_STARTED уже с сохранённым вопросом на руках).
     *
     * @return id занятой операции — его же нужно вернуть в {@link #release} (или в {@link #free},
     *     если до хаба дело так и не дошло)
     * @throws ResponseStatusException 409, если чат уже занят
     */
    public String take(String conversationId) {
        final String runId = UUID.randomUUID().toString();
        take(conversationId, runId);
        return runId;
    }

    /**
     * Снимает заявку, не трогая хаб. Идемпотентно и снимает только СВОЮ заявку: чат, занятый уже
     * следующей операцией, остаётся за ней.
     */
    public void free(String conversationId, String runId) {
        byConversation.computeIfPresent(
                conversationId, (id, hold) -> hold.runId().equals(runId) ? null : hold);
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
        hold(conversationId, new Hold(runId, false));
        events.startRun(conversationId, runId);
        return runId;
    }

    /**
     * Освобождает чат и закрывает хаб операции — им заканчивают и {@link #claim}, и генерация,
     * взявшая чат через {@link #take}. Идемпотентно.
     *
     * <p>Точное обратное {@link #claim}, и порядок в нём обратный: сначала хаб, потом заявка. Иначе
     * следующая операция успевает занять освободившийся чат и записаться в хаб, который этот {@code
     * endRun} как раз закрывает, — её событие потерялось бы, а вкладки увидели бы чат свободным
     * посреди её работы. Этим же и заканчивается прогон ({@code ChatRunService.cleanup}), чтобы
     * порядок жил в одном месте.
     */
    public void release(String conversationId, String runId) {
        events.endRun(conversationId, runId);
        free(conversationId, runId);
    }

    /**
     * Занят ли чат, и кем — id активной операции, <b>каким его видят вкладки</b>. Отвечает хаб
     * событий, а не карта заявок, и это намеренно: между {@link #take} и {@code startRun} прогон
     * успевает записать вопрос в БД, и всё это время заявка удержана, а вкладкам показывать ещё
     * нечего. Поэтому «занят» здесь и {@link #claimedConversationCount()} — не одно и то же.
     */
    public Optional<String> activeRun(String conversationId) {
        return events.activeRunId(conversationId);
    }

    /**
     * Генерация ли держит чат под этим {@code runId} — вопрос для тех, у кого прогона в реестре уже
     * нет. Пустой реестр сам по себе означает не «операция»: генерация покидает его до конца
     * терминальной обработки, а заявку всё это время держит, и вкладке её надо показать генерацией,
     * а не операцией без кнопки «Стоп». Чужой (или уже снятый) {@code runId} — {@code false}: чат
     * за кем-то другим.
     */
    public boolean holdsGeneration(String conversationId, String runId) {
        final Hold hold = byConversation.get(conversationId);
        return hold != null && hold.runId().equals(runId) && hold.generation();
    }

    /**
     * Сколько миллисекунд заявка держится — длительность для таймера во вкладке, открытой посреди
     * операции. Параллель {@code RunScope#elapsedMs}, и другого источника у операции нет: своей
     * области прогона она не заводит.
     *
     * <p>{@code null} — чат держит кто-то другой (или уже никто): показать таймер не от чего.
     */
    public @Nullable Long elapsedMs(String conversationId, String runId) {
        final Hold hold = byConversation.get(conversationId);
        return hold != null && hold.runId().equals(runId) ? hold.elapsedMs() : null;
    }

    /**
     * Число чатов с удержанной заявкой — для мониторинга утечек (см. {@code ChatRuntimeMonitor}).
     */
    public int claimedConversationCount() {
        return byConversation.size();
    }
}
