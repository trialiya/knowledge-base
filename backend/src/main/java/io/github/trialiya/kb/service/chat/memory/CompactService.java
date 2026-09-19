package io.github.trialiya.kb.service.chat.memory;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_DONE;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_ERROR;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_STARTED;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.USER_MESSAGE;
import static java.util.Objects.requireNonNull;

import io.github.trialiya.kb.advisor.MessageLoggingAdvisor;
import io.github.trialiya.kb.config.ChatModelRegistry;
import io.github.trialiya.kb.model.chat.dto.CompactDetail;
import io.github.trialiya.kb.model.chat.dto.CompactErrorPayload;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
import io.github.trialiya.kb.model.chat.dto.UserMessagePayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.chat.entity.TokenUsage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
import io.github.trialiya.kb.tools.ChatToolset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Сжатие живого контекста в одну сводку — в отличие от фоновой суммаризации ({@link
 * SummarizeService}), которая всегда сжимает только начало окна и никогда не трогает хвост.
 *
 * <p>Поводов три, и раунд у них общий: команды {@code /compact} и {@code /compact-1} (их ведёт этот
 * класс целиком — см. {@link #start}) и предел контекста ({@link AutoCompactService}). Что из окна
 * уедет сводке, решает {@link CompactWindow}: {@code /compact} отдаёт ей всё, {@code /compact-1}
 * бережёт последний ход. Всё остальное, чем поводы отличаются, собрано в {@link CompactTarget}; сам
 * {@link #compact} про повод не знает.
 *
 * <p><b>Историю никто не пересказывает.</b> Модель получает окно ровно тем же списком сообщений,
 * каким его получает чат ({@link ChatHistoryService#promptMessages}): протокольные {@code
 * tool_calls} с полными аргументами и TOOL-строки с полными результатами — внутри. Поэтому здесь
 * нет ни рендера истории в текст, ни усечения результатов до гистов, которыми живёт суммаризатор:
 * то, что уезжает модели, и есть история.
 *
 * <p><b>Запрос собран в форме обычного прогона — ради кэша промпта.</b> Провайдер отдаёт по
 * льготной ставке ту часть запроса, которая совпадает с предыдущим, и совпадение считается от
 * первого байта: системное сообщение, за ним схемы инструментов, за ними история. Раунд сжатия идёт
 * по тому же окну, которое чат только что возил модели, поэтому при совпадающем начале почти весь
 * его вход — это кэш. Свой системный промпт и отсутствие инструментов расходились бы с чатом с
 * нулевой позиции, и самая дорогая операция чата стала бы единственной, которая платит за весь
 * контекст по полной ставке. Отсюда четыре требования, каждое из которых обязательно: тот же {@code
 * sys.md} с теми же подстановками (их собирает {@link SystemPromptService#placeholders} — одно
 * место на оба запроса), тот же {@link ChatToolset}, та же модель и то же окно, собранное тем же
 * кодом (см. {@link ChatHistoryService#promptRowsBefore} — там про блок активного проекта, который
 * иначе уехал бы вместе с командой). Расходится с запросом чата только последнее сообщение — то
 * самое, которое просит сжать; всё, что перед ним, совпадает. Держит это не аккуратность правок, а
 * {@code CompactPromptCacheTest}: он сравнивает тела HTTP-запросов обоих путей по одному окну —
 * иначе разошедшееся начало видно только по счёту от провайдера.
 *
 * <p><b>Инструменты уезжают схемами, но исполнять их некому.</b> {@code
 * ChatClientAttributes#TOOL_CALLING_ADVISOR_AUTO_REGISTER} выключает адвайзер tool-цикла, который
 * {@code ChatClient} иначе подставляет сам: схемы нужны префиксу запроса, а вот выполнить вызов
 * посреди сжатия нельзя — {@code sys.md} требует начинать каждый ответ с {@code
 * recordChatInsights}, и без этого запрета сжатие ходило бы кругами вместо документа (а в
 * развёртывании с правом записи могло бы и тронуть репозиторий). Второй рубеж — сам {@code
 * compactor.md}, который явно снимает роль из системного промпта.
 *
 * <p><b>Адвайзеров памяти по-прежнему нет</b> — клиент из {@code ChatClientRegistry} подмешал бы то
 * же окно вторым слоем, а ответ записал бы в историю обычной репликой ассистента.
 *
 * <p><b>Токены раунда — обычный замер обращения</b> ({@link RunTokenUsage} на один вызов), и он
 * ложится в мету строки-плашки. Без него сжатие было бы единственной операцией чата, которая тратит
 * деньги и не попадает в статистику: итог по чату переставал бы сходиться со счётом провайдера
 * ровно на стоимость каждого {@code /compact}. Раунд, который сводки не дал, оплачен ровно так же —
 * его замер записывается на строку самой команды (см. {@link #spentRound}).
 *
 * <p><b>Команда остаётся в истории, но не участвует в сжатии.</b> Сообщение {@code /compact
 * <текст>} сохраняется обычной USER-строкой — так же видимой, как любая другая реплика, — но само
 * сжатие получает окно ровно таким, каким оно было ДО этого сообщения: команда не материал для
 * сжатия, а управляющий сигнал. Модели вместо неё в конец запроса уходит собранная здесь инструкция
 * — с хвостом команды в роли фокуса и справкой о самом чате. По завершении раунда команда тоже
 * помечается сжатой: дальше она видна пользователю в истории, но перестаёт ехать модели — как и
 * всё, что раунд заменил сводкой. У {@code /compact} её накрывает тот же размеченный {@code
 * summarized}-диапазон, что и окно; у {@code /compact-1} диапазон кончается раньше — между ним и
 * командой лежит сбережённый ход, — и команду помечает отдельная пометка (см. {@link
 * CompactTarget#detached}). Упавший раунд прячет её тем же способом (см. {@link #hideCommand}):
 * правило одно на оба исхода — <b>команда сжатия не едет модели никогда</b>.
 *
 * <p><b>След сжатия остаётся в истории.</b> Кроме самой сводки раунд пишет строку-плашку — ряд,
 * который видит только пользователь (см. {@code SummaryWriter#writeCompacted}). Без неё сжатие жило
 * бы одним событием: вкладка, открытая после перезагрузки, показывала бы команду, за которой ничего
 * не произошло. По id этой строки {@link #detail} отдаёт и текст сводки — иначе увидеть результат
 * сжатия нельзя вообще ниоткуда.
 */
@Slf4j
@Service
public class CompactService {

    private final ChatModelRegistry chatModelRegistry;
    private final ChatHistoryService chatHistory;
    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SummaryWriter summaryWriter;
    private final PendingSummaryService pendingSummaries;
    private final ConversationSlots slots;
    private final ChatEventService events;
    private final SystemPromptService systemPrompts;
    private final ChatToolset chatToolset;
    private final Resource sysPrompt;
    private final CompactPrompt compactPrompt;
    private final Executor executor;
    private final TransactionTemplate transactionTemplate;

    public CompactService(
            ChatModelRegistry chatModelRegistry,
            ChatHistoryService chatHistory,
            ChatTopicRepository chatTopicRepository,
            ChatMessageRepository chatMessageRepository,
            SummaryWriter summaryWriter,
            PendingSummaryService pendingSummaries,
            ConversationSlots slots,
            ChatEventService events,
            SystemPromptService systemPrompts,
            ChatToolset chatToolset,
            @Value("classpath:prompt/sys.md") Resource sysPrompt,
            CompactPrompt compactPrompt,
            @Qualifier("chatRunExecutor") Executor executor,
            PlatformTransactionManager transactionManager) {
        this.chatModelRegistry = chatModelRegistry;
        this.chatHistory = chatHistory;
        this.chatTopicRepository = chatTopicRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.summaryWriter = summaryWriter;
        this.pendingSummaries = pendingSummaries;
        this.slots = slots;
        this.events = events;
        this.systemPrompts = systemPrompts;
        this.chatToolset = chatToolset;
        this.sysPrompt = sysPrompt;
        this.compactPrompt = compactPrompt;
        this.executor = executor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** {@code runId} занятой операции и id сохранённой команды — параллель {@code StartedRun}. */
    public record StartedCompact(String runId, Long messageId) {}

    /**
     * Настройки запроса сжатия. Это те же настройки, на которых идёт сам чат ({@code
     * ChatRunService.RunOptions}) — все четыре поля влияют на начало запроса, а от него зависит,
     * попадёт ли раунд в кэш промпта (см. javadoc класса).
     *
     * <p>Своя запись, а не {@code RunOptions}: пакеты чата зависят в одну сторону ({@code event} ←
     * {@code runtime} ← {@code memory} ← {@code run}), и тип из {@code run} здесь был бы ссылкой
     * назад. Собирает её контроллер — тот же резолв, что и у прогона.
     *
     * @param model id модели, уже разрешённый вызывающим; {@code null} — модель из конфигурации
     * @param weakModel {@code ChatModelProperties#isWeak} от {@link #model}
     * @param project проект чата; {@code null} — дефолтный проект списка
     * @param modeInstructions инструкции режима чата; пустая строка — «без режима»
     */
    public record CompactOptions(
            @Nullable String model,
            boolean weakModel,
            @Nullable String project,
            String modeInstructions) {}

    /**
     * Чем раунд закрывается — всё, чем сжатие по команде отличается от автоматического ({@code
     * AutoCompactService}). Сам раунд у них общий: то же окно, тот же запрос, та же запись.
     *
     * @param kind вид для плашки (см. {@link CompactMeta.Kind})
     * @param boundaryPosition последняя позиция, попадающая в размеченный {@code
     *     summarized}-диапазон; она же позиция сводки. У команды это позиция самой команды — дальше
     *     та видна в истории, но модели не едет; у автоматического сжатия это последний ряд сжатого
     *     окна: вопрос, ради которого чат сжался, обязан остаться живым
     * @param createdAt время строки-сводки — то, что решает, где она встанет в промпте. Живого
     *     хвоста нет ({@code /compact}) — обгонять нечего, и это время конца раунда; хвост есть
     *     (автоматическое сжатие, {@code /compact-1}) — это время последнего сжатого ряда, иначе
     *     сводка уехала бы модели ПОСЛЕ сообщений, которых она не пересказывает
     * @param noticeAt время видимой плашки в ленте — отдельный вопрос от предыдущего (см. {@link
     *     SummaryWriter.CompactStats#noticeAt}). У команд это конец раунда: плашка встаёт под
     *     командой, которая её вызвала. У автоматического сжатия — время последнего сжатого ряда:
     *     под плашкой уже лежит вопрос этого прогона, и по времени конца раунда она встала бы после
     *     него, при том что его она не сжимала
     * @param spentRound куда лечь замеру раунда, который до модели дошёл, а сводки не дал;
     *     возвращает id ряда, на который замер записан, или {@code null}, если ряда такого нет
     * @param detached ряд, который тоже перестаёт ехать модели, но лежит ЗА размеченным диапазоном
     *     и потому помечается отдельно (см. {@link ChatMessageEntity#asSummarized}). Бывает один и
     *     только у {@code /compact-1}: его команда стоит после сбережённого живого хвоста, и
     *     дотянуть до неё границу значило бы сжать заодно и хвост. {@code null} — такого ряда нет
     */
    public record CompactTarget(
            CompactMeta.Kind kind,
            long boundaryPosition,
            LocalDateTime createdAt,
            LocalDateTime noticeAt,
            SpentRound spentRound,
            @Nullable ChatMessageEntity detached) {

        /**
         * Цель, у которой сводка и плашка подписаны одним временем, а размеченный диапазон
         * накрывает всё сам, — так устроены и {@code /compact}, и автоматическое сжатие.
         */
        public CompactTarget(
                CompactMeta.Kind kind,
                long boundaryPosition,
                LocalDateTime createdAt,
                SpentRound spentRound) {
            this(kind, boundaryPosition, createdAt, createdAt, spentRound, null);
        }
    }

    /**
     * Куда девать деньги за несостоявшийся раунд. Молча их терять нельзя: сжатие иначе становится
     * единственной тратой чата мимо его же статистики, и {@code Total} перестаёт сходиться со
     * счётом провайдера ровно на её стоимость.
     *
     * <p>Одни и те же деньги приезжают в двух видах, потому что мест для них тоже два и складывают
     * они по-разному: {@code call} — обращение к модели, в этом виде замер принимает накопитель
     * прогона ({@code RunScope#addCall}); {@code usage} — то же самое, уже свёрнутое в итог раунда,
     * в этом виде замер хранит мета ряда. Собирать второе из первого на стороне вызывающего нельзя:
     * правило сборки обязано остаться одним на весь чат.
     *
     * @return id ряда, на который замер записан, или {@code null}, если такого ряда нет
     */
    @FunctionalInterface
    public interface SpentRound {
        @Nullable Long record(TokenUsage call, RunTokenUsage usage);
    }

    /**
     * Занимает чат, сохраняет команду и запускает сжатие в фоне — HTTP-запрос не держим: раунд идёт
     * по всему контексту сразу и живёт десятки секунд, а таймаут прокси посреди него оставил бы
     * вкладку с висящей блокировкой при работающем сжатии.
     *
     * <p>Команда сохраняется ЗДЕСЬ, синхронно, а не в фоновой задаче: только так «сжимать нечего»
     * остаётся ответом этого запроса (422, без сохранённого сообщения — команда, которая ничего не
     * сделала, не должна маячить в истории), а сама команда получает {@code id} сразу, не дожидаясь
     * фонового раунда. Гонки с дописыванием истории при этом нет — чат уже занят.
     *
     * <p>Окно здесь читается только ради этой проверки: сжимаемое окно снимает уже сам раунд, под
     * общим с фоновой суммаризацией замком (см. {@link #run}).
     *
     * @param text сообщение {@code /compact <текст>} целиком — сохраняется как есть
     * @param keepLastRun оставить последний ход разговора живым — команда {@code /compact-1} (см.
     *     {@link CompactWindow}). От этого зависит и «сжимать нечего»: у {@code /compact-1}
     *     материал — только то, что лежит ДО сбережённого хода
     * @param options настройки запроса — те же, на которых идёт чат (см. {@link CompactOptions})
     * @param clientMsgId id вкладки-отправителя — тот же смысл, что и у {@code POST /runs}: своё
     *     эхо {@code USER_MESSAGE} вкладка гасит по нему, не дожидаясь второго пузыря
     * @return runId занятой операции и id сохранённой команды
     */
    public StartedCompact start(
            String conversationId,
            String text,
            @Nullable String instructions,
            boolean keepLastRun,
            CompactOptions options,
            @Nullable String clientMsgId) {
        final String runId = slots.claim(conversationId);
        final ChatMessageEntity commandRow;
        try {
            // Оборванный прошлый прогон мог оставить в хвосте assistant.tool_calls без TOOL-ответа
            // — такой диалог модель отвергает целиком, а здесь он уехал бы ей весь.
            chatHistory.repairDanglingToolCalls(conversationId);
            if (CompactWindow.of(chatHistory.liveRows(conversationId), keepLastRun).isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Nothing to compact");
            }
            // Проект команде не штампуется: первым сообщением чата она не бывает (сжимать было бы
            // нечего), а базовый штамп нужен только там.
            commandRow = chatHistory.saveUserMessage(conversationId, text, List.of(), null, null);
        } catch (RuntimeException e) {
            slots.release(conversationId, runId);
            throw e;
        }
        // Эхо для остальных вкладок — тот же payload, что и у обычного вопроса, поэтому фронту не
        // нужен отдельный обработчик: команда встаёт в ленту точно так же, как любое сообщение.
        events.publish(
                conversationId,
                USER_MESSAGE,
                runId,
                clientMsgId,
                new UserMessagePayload(
                        commandRow.getId(),
                        commandRow.getContent(),
                        commandRow.getCreatedAt(),
                        commandRow.getContextItems(),
                        null,
                        null,
                        null));
        events.publish(conversationId, COMPACT_STARTED, runId, null, null);
        try {
            executor.execute(
                    () ->
                            run(
                                    conversationId,
                                    runId,
                                    commandRow,
                                    instructions,
                                    keepLastRun,
                                    options));
        } catch (RuntimeException e) {
            // COMPACT_STARTED уже ушёл всем вкладкам, и своя — та, что получит здесь ошибку —
            // уже под блокировкой. Снять её ответом на этот запрос нельзя: остальные вкладки
            // остались бы на плашке «сжимаю…» навсегда. Значит, гасим тем же событием, каким
            // гасит упавший раунд.
            failed(conversationId, runId, commandRow.getId(), e);
            slots.release(conversationId, runId);
            throw e;
        }
        return new StartedCompact(runId, commandRow.getId());
    }

    /**
     * Фоновая обёртка раунда: замок, окно, сжатие, событие исхода, освобождение чата.
     *
     * <p>Замок общий с фоновой суммаризацией ({@link SummaryWriter#inConversation}) и обязан
     * охватывать чтение окна, а не только запись сводки. Занятость чата тут не помогает: фоновый
     * раунд стартует по RUN_DONE, вне занятого слота, и без общего замка успел бы прочитать то же
     * окно и записать вторую сводку поверх материала, который эта уже заменила.
     *
     * <p>Поэтому окно снимается ЗДЕСЬ, под замком, а не переносится из {@link #start}: та читала
     * его только ради ответа «сжимать нечего». Отсекает хвост от позиции команды и дальше сам
     * {@link ChatHistoryService#promptRowsBefore} — сама команда не материал для сжатия, а сигнал к
     * нему, и попади она в окно, раунд принял бы собственный вызов за часть разговора. Что из
     * оставшегося уедет сводке, а что останется жить, решает {@link CompactWindow}.
     */
    private void run(
            String conversationId,
            String runId,
            ChatMessageEntity commandRow,
            @Nullable String instructions,
            boolean keepLastRun,
            CompactOptions options) {
        try {
            summaryWriter.inConversation(
                    conversationId,
                    () -> {
                        final CompactWindow window =
                                CompactWindow.of(
                                        chatHistory.liveRowsBefore(
                                                conversationId, commandRow.getPosition()),
                                        keepLastRun);
                        if (window.isEmpty()) {
                            // Пока команда ждала своей очереди, окно сжал кто-то другой.
                            throw new IllegalStateException("Nothing left to compact");
                        }
                        final CompactPayload payload =
                                compact(
                                        conversationId,
                                        // Промпт-вид — уже ПОСЛЕ деления и только у своей
                                        // половины: блок активного проекта обязан сесть на ряд
                                        // внутри того окна, которое уедет модели (см.
                                        // CompactWindow).
                                        chatHistory.promptRowsFor(
                                                conversationId, window.compacted()),
                                        commandTarget(commandRow, window),
                                        instructions,
                                        options);
                        events.publish(conversationId, COMPACT_DONE, runId, null, payload);
                    });
        } catch (Exception e) {
            failed(conversationId, runId, commandRow.getId(), e);
        } finally {
            slots.release(conversationId, runId);
        }
    }

    /**
     * Сжатие не состоялось: прячем команду от модели, пишем в лог и снимаем блокировку со всех
     * вкладок разом. Раунд, который до модели дошёл, отдаёт вкладкам ещё и свой замер (см. {@link
     * #spentRound}) — до сюда он доезжает самой ошибкой.
     */
    private void failed(String conversationId, String runId, long commandId, Exception e) {
        log.error("[{}] Compaction failed: {}", conversationId, e.getMessage(), e);
        hideCommand(commandId);
        final @Nullable CompactRoundFailed round =
                e instanceof CompactRoundFailed failed ? failed : null;
        events.publish(
                conversationId,
                COMPACT_ERROR,
                runId,
                null,
                new CompactErrorPayload(
                        String.valueOf(e.getMessage()),
                        round == null ? null : round.messageId(),
                        round == null ? null : round.usage()));
    }

    /**
     * Снимает с модели строку команды упавшего раунда. Удавшийся прячет её сам — разметкой или
     * отдельной пометкой (см. {@link CompactTarget#detached}), — и правило обязано быть одним на
     * оба исхода: <b>команда сжатия не едет модели никогда</b>. Иначе она остаётся в контексте
     * неотвеченным вопросом «/compact ...», на который модель попробует ответить, и — хуже —
     * открывает ход: следующая {@code /compact-1} приняла бы её за последний ход и сберегла бы
     * мёртвую строку вместо разговора, который просили сберечь.
     *
     * <p>Ряд перечитывается, а не пишется по снимку: замер несостоявшегося раунда мог лечь на него
     * секундой раньше (см. {@link #spentRoundOn}), и запись устаревшей копии стёрла бы эти деньги
     * из итога по чату.
     *
     * <p>Видимой строка при этом остаётся: {@code summarized} убирает ряд из промпта, а не из
     * ленты, — как и у всего, что заменила сводка.
     */
    private void hideCommand(long commandId) {
        chatMessageRepository
                .findById(commandId)
                .ifPresent(row -> chatMessageRepository.save(row.asSummarized()));
    }

    /**
     * Сам раунд: окно → модель → строка-сводка вместо всего окна. Публичный, чтобы его можно было
     * позвать без фонового пуска и без событий.
     *
     * @param rows окно, которое уходит модели. Ряда, которым раунд вызван, в нём нет: команда
     *     {@code /compact} не часть сжимаемого разговора, а вопрос, ради которого чат сжался сам,
     *     обязан остаться живым
     * @param target чем раунд закрывается — граница разметки, время рядов и вид плашки (см. {@link
     *     CompactTarget})
     */
    public CompactPayload compact(
            String conversationId,
            List<PromptRow> rows,
            CompactTarget target,
            @Nullable String instructions,
            CompactOptions options) {
        final List<Message> history = rows.stream().map(PromptRow::toMessage).toList();
        final long startPosition = rows.getFirst().entity().getPosition();
        final long oldEndPosition = rows.getLast().entity().getPosition();
        final @Nullable String model = options.model();
        log.info(
                "[{}] Compacting positions {}-{} ({}, boundary at {}): {} messages, ~{} chars,"
                        + " model {}",
                conversationId,
                startPosition,
                oldEndPosition,
                target.kind(),
                target.boundaryPosition(),
                rows.size(),
                rows.stream().mapToInt(row -> row.text().length()).sum(),
                model == null ? "default" : model);

        ChatClient.ChatClientRequestSpec spec =
                ChatClient.builder(chatModelRegistry.forModel(model))
                        .defaultSystem(sysPrompt)
                        .defaultTools((Object[]) chatToolset.all())
                        .build()
                        .prompt()
                        .system(
                                sp ->
                                        sp.params(
                                                systemPrompts.placeholders(
                                                        options.weakModel(),
                                                        options.project(),
                                                        options.modeInstructions())))
                        .messages(history)
                        .user(compactPrompt.instruction(conversationId, rows, instructions))
                        .advisors(
                                a ->
                                        a.advisors(new MessageLoggingAdvisor())
                                                // Чат запроса — только чтобы лог сжатия можно было
                                                // положить рядом с логом прогона того же чата: без
                                                // него обе строки подписаны «?», а сравнивают их
                                                // ровно тогда, когда кэш промпта не сошёлся.
                                                // Памяти на этом клиенте нет, подмешивать по этому
                                                // параметру историю здесь некому.
                                                .param(ChatMemory.CONVERSATION_ID, conversationId)
                                                .param(
                                                        ChatClientAttributes
                                                                .TOOL_CALLING_ADVISOR_AUTO_REGISTER
                                                                .getKey(),
                                                        false));
        if (model != null) {
            spec = spec.options(OpenAiChatOptions.builder().model(model));
        }
        final @Nullable ChatResponse response = spec.call().chatResponse();
        // Замер обращения — накопителем прогона на один вызов: правило сборки у сжатия и у ответа
        // обязано быть одним, иначе одни и те же деньги в двух местах статистики назывались бы
        // разными числами. Снимается он ДО проверок ниже: раунд, который сводки не дал, провайдер
        // посчитал так же, как удавшийся (см. spentRound). Пустой замер — «эндпоинт не измеряет», и
        // в мету он не идёт ни там, ни здесь: «неизвестно» это не ноль.
        final TokenUsage call = TokenUsage.of(response);
        final RunTokenUsage usage = RunTokenUsage.Tally.EMPTY.with(call).view();
        final @Nullable String content = answerOf(response);
        // Ответ с вызовом инструмента не годится в сводку, даже когда текст в нём есть. Модель
        // прочла схемы (они в запросе ради кэша) и не послушалась запрета из compactor.md, а
        // sys.md как раз требует начинать ответ с recordChatInsights — то есть вероятная форма
        // такого ответа не пустая, а «сейчас запишу» плюс сам вызов. Пропусти мы её по непустому
        // тексту, этой одной фразой был бы заменён весь контекст чата, и вернуть его уже неоткуда.
        // Исполнять вызов всё равно некому, так что раунд кончается здесь.
        if (calledTools(response)) {
            throw spentRound(
                    target,
                    call,
                    usage,
                    "The model called a tool instead of writing the compaction");
        }
        if (content == null || content.isBlank()) {
            // Разметить окно сжатым, не сохранив сводку, значит стереть чат целиком. Сама команда
            // при этом уже сохранена и никуда не денется — останется в истории неотвеченной, как
            // любой упавший вопрос.
            throw spentRound(target, call, usage, "The model returned an empty compaction");
        }

        // Сколько рядов перестало ехать модели: всё сжатое окно и, у команды, она сама. У
        // /compact её накрывает разметка — позиция команды лежит за окном (см.
        // CompactTarget#boundaryPosition); у /compact-1 разметка до неё не дотягивается, и её
        // помечает отдельная пометка (detached). У автоматического сжатия лишнего ряда нет вовсе.
        final int messages =
                rows.size()
                        + (target.boundaryPosition() > oldEndPosition || target.detached() != null
                                ? 1
                                : 0);
        final ChatMessageEntity notice =
                requireNonNull(
                        transactionTemplate.execute(
                                s ->
                                        writeNotice(
                                                conversationId,
                                                rows,
                                                target,
                                                startPosition,
                                                messages,
                                                content,
                                                usage)));
        log.info(
                "[{}] Compaction finished: {} messages -> {} chars; input {} ({} from cache),"
                        + " output {}",
                conversationId,
                messages,
                content.length(),
                usage.promptTokens(),
                usage.cacheReadTokens(),
                usage.outputTokens());
        return CompactPayload.of(notice);
    }

    /**
     * Записывает сводку и её плашку, выбросив перед этим очередь отложенных сводок: после этого
     * раунда их кусок истории заменён целиком, и описывают они то, чего в промпте больше нет.
     * Деньги их раундов уезжают на плашку — другого ряда у этих денег не остаётся (см. {@link
     * CompactMeta#carried}).
     *
     * <p>Заодно помечает сжатым ряд, до которого разметка диапазона не дотягивается (см. {@link
     * CompactTarget#detached}).
     *
     * <p>Звать только внутри транзакции: удаление, пережившее неудавшуюся запись, стёрло бы
     * написанные моделью сводки насовсем, а второй раз их никто не напишет — сжатый ими кусок к
     * тому времени уже не живой.
     */
    private ChatMessageEntity writeNotice(
            String conversationId,
            List<PromptRow> rows,
            CompactTarget target,
            long startPosition,
            int messages,
            String content,
            RunTokenUsage usage) {
        final @Nullable RunTokenUsage carried = pendingSummaries.discard(conversationId);
        final @Nullable ChatMessageEntity detached = target.detached();
        if (detached != null) {
            chatMessageRepository.save(detached.asSummarized());
        }
        return summaryWriter.writeCompacted(
                new SummaryWriter.SummaryRow(
                        conversationId,
                        startPosition,
                        // У команды диапазон захватывает и её саму — не только сжатое окно, —
                        // поэтому дальше она видна в истории, но модели больше не едет. Где
                        // проходит граница, решает вызвавший (см. CompactTarget).
                        target.boundaryPosition(),
                        target.boundaryPosition(),
                        target.createdAt(),
                        CompactPrompt.wrap(content, target.kind()),
                        // Сводка остаётся единственной памятью разговора, и следом проектов —
                        // тоже: маркеры смены уезжают вместе с окном.
                        ProjectTrace.of(
                                entities(rows, true),
                                entities(rows, false),
                                () ->
                                        chatTopicRepository
                                                .findById(conversationId)
                                                .map(ChatTopicEntity::getProject)
                                                .orElse(null),
                                target.boundaryPosition())),
                new SummaryWriter.CompactStats(
                        target.kind(),
                        messages,
                        content.length(),
                        usage.isEmpty() ? null : usage,
                        carried,
                        target.noticeAt()));
    }

    /**
     * Раунд не состоялся — но провайдер его посчитал. Отдаёт замер вызвавшему и возвращает ошибку,
     * которой раунд кончится.
     *
     * <p>Без этого сжатие оставалось бы единственной операцией чата, которая тратит деньги молча:
     * удавшийся раунд кладёт свои токены на плашку ({@code SummaryWriter.CompactStats}), а
     * несостоявшемуся плашки нет — сводки он не написал, и историю трогать нельзя. Куда их деть
     * вместо этого, знает вызвавший, а не раунд (см. {@link SpentRound}).
     */
    private CompactRoundFailed spentRound(
            CompactTarget target, TokenUsage call, RunTokenUsage usage, String reason) {
        final @Nullable Long messageId = target.spentRound().record(call, usage);
        return new CompactRoundFailed(reason, messageId, usage.isEmpty() ? null : usage);
    }

    /**
     * Цель раунда по команде. Замер несостоявшегося раунда у обеих команд ложится на строку самой
     * команды: она уже сохранена и остаётся в истории неотвеченной, как любой упавший вопрос, а
     * замер на ней читается тем же полем меты, что и у ответа, — итог по чату считается по всем
     * рядам одним правилом.
     *
     * <p>Контекстом чата этот замер не является ни в каком виде: он описывает окно, которое раунд
     * прочитал, вместе с его собственной инструкцией, а само окно осталось на месте. Отсюда правило
     * на фронте: замер на USER-ряду идёт только в итог (см. {@code tokenUsage.js}).
     *
     * <p>Всё остальное решает деление окна, а не набранная команда. Живого хвоста нет — граница
     * доходит до самой команды, и оба ряда подписаны концом раунда: обгонять сводке нечего. Хвост
     * есть ({@code /compact-1}) — граница кончается на последнем сжатом ряду, и его временем
     * подписана сводка: иначе она уехала бы модели ПОСЛЕ сбережённого хода, которого не
     * пересказывает. Плашка при этом остаётся подписанной концом раунда — видит её человек, и её
     * место в ленте под командой, которая её вызвала. Сама команда тогда лежит за границей и
     * помечается отдельно (см. {@link CompactTarget#detached}).
     *
     * <p>{@code /compact-1} в окне без единого хода беречь нечего — деление выходит полным, и
     * плашка так и говорит: обещать сбережённый хвост, которого нет, она не вправе.
     */
    CompactTarget commandTarget(ChatMessageEntity commandRow, CompactWindow window) {
        final SpentRound spentRound = spentRoundOn(commandRow);
        if (window.kept().isEmpty()) {
            return new CompactTarget(
                    CompactMeta.Kind.COMPACT,
                    commandRow.getPosition(),
                    // Время раунда, а не команды: плашка со сводкой встаёт под ней отдельным
                    // сообщением, и её время — это время, когда сжатие закончилось, иногда через
                    // десятки секунд после команды.
                    LocalDateTime.now(),
                    spentRound);
        }
        final ChatMessageEntity lastCompacted = window.compacted().getLast();
        return new CompactTarget(
                CompactMeta.Kind.COMPACT_KEEP_LAST,
                lastCompacted.getPosition(),
                lastCompacted.getCreatedAt(),
                LocalDateTime.now(),
                spentRound,
                commandRow);
    }

    /** Замер несостоявшегося раунда — на строку самой команды (см. {@link #commandTarget}). */
    private SpentRound spentRoundOn(ChatMessageEntity commandRow) {
        return (call, usage) -> {
            if (!usage.isEmpty()) {
                final @Nullable ChatMessageMeta meta = commandRow.getMeta();
                chatMessageRepository.save(
                        commandRow.withMeta(
                                meta == null
                                        ? ChatMessageMeta.ofUsage(usage)
                                        : meta.withUsage(usage)));
            }
            return commandRow.getId();
        };
    }

    /**
     * Раунд сжатия, который обращение к модели сделал, а сводки не дал. Несёт замер и id команды,
     * на которую он записан: {@code COMPACT_ERROR} отдаёт их вкладкам, чтобы те досчитали итог чата
     * сразу, а не после перезагрузки.
     */
    public static class CompactRoundFailed extends IllegalStateException {

        /**
         * {@code null} — записать замер было некуда, и адреса у него нет (см. {@link SpentRound}).
         */
        private final @Nullable Long messageId;

        /** {@code transient} — {@link RunTokenUsage} не {@code Serializable}, а исключение да. */
        private final transient @Nullable RunTokenUsage usage;

        CompactRoundFailed(String reason, @Nullable Long messageId, @Nullable RunTokenUsage usage) {
            super(reason);
            this.messageId = messageId;
            this.usage = usage;
        }

        public @Nullable Long messageId() {
            return messageId;
        }

        public @Nullable RunTokenUsage usage() {
            return usage;
        }
    }

    /** Модель ответила вызовом инструмента — см. разбор отказа в {@link #compact}. */
    private static boolean calledTools(@Nullable ChatResponse response) {
        return response != null && response.hasToolCalls();
    }

    /**
     * Текст ответа модели; {@code null} — ответа нет вовсе (или в нём одни вызовы инструментов).
     */
    private static @Nullable String answerOf(@Nullable ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(result -> result.getOutput().getText())
                .orElse(null);
    }

    /**
     * Детали сжатия по id его строки-плашки: числа с самой плашки и текст сводки, которую она
     * заменила. {@code Optional.empty()} — плашки нет, она из другого чата или сводка, на которую
     * она ссылается, не нашлась (чат мог быть удалён между запросами).
     */
    public Optional<CompactDetail> detail(String conversationId, long messageId) {
        final @Nullable ChatMessageEntity notice =
                chatMessageRepository.findById(messageId).orElse(null);
        if (notice == null || !notice.getConversationId().equals(conversationId)) {
            return Optional.empty();
        }
        final @Nullable CompactMeta compact =
                notice.getMeta() == null ? null : notice.getMeta().compact();
        if (compact == null) {
            return Optional.empty();
        }
        return chatMessageRepository
                .findById(compact.summaryId())
                .filter(summary -> summary.getConversationId().equals(conversationId))
                .map(
                        summary ->
                                new CompactDetail(
                                        notice.getId(),
                                        compact.messages(),
                                        compact.summaryChars(),
                                        notice.getCreatedAt(),
                                        CompactPrompt.unwrap(summary.getContent())));
    }

    /**
     * Ряды окна — сводки отдельно от живых: {@link ProjectTrace} читает у них разное (спаны против
     * носителей на вопросах), и смешанный список дал бы отрезок там, где чат никуда не переходил.
     */
    private static List<ChatMessageEntity> entities(List<PromptRow> rows, boolean summaries) {
        return rows.stream()
                .map(PromptRow::entity)
                .filter(entity -> entity.isSummary() == summaries)
                .toList();
    }
}
