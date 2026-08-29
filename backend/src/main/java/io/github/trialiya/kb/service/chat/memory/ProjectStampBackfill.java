package io.github.trialiya.kb.service.chat.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.model.BackfillProperties;
import io.github.trialiya.kb.model.backfill.BackfillStateEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Разовый проход по истории: расставляет в старых чатах то, чего в них не могло появиться, —
 * базовый штамп проекта на первом сообщении и след проектов ({@code visitedProjects}) на
 * строках-сводках.
 *
 * <p>Ради чего: чтобы у чтения не было второй ветки. Без бэкфилла {@link ActiveProjectNotice}
 * пришлось бы уметь и «спаны есть», и «спанов нет, но есть одинокий {@code project} на сводке», и
 * «нет ничего» — три формы одного ответа, две из которых со временем перестали бы встречаться, а
 * поддерживались бы вечно. Данные дешевле привести к одному виду один раз.
 *
 * <p>Старое поле при этом остаётся: одинокий {@code project} на сводке пишется и дальше (см. {@code
 * SummaryWriter}), чтобы откат приложения читал свои же сводки. Просто в промпт оно больше не идёт.
 *
 * <p>Проход идемпотентен — штамп ставится только там, где проекта ещё нет, а спаны считаются из тех
 * же данных тем же {@link ProjectTrace}, что и на записи сводки. Поэтому отметка «сделано» ставится
 * одна на всё и в конце: оборвавшийся посередине проход просто повторится на следующем старте, а не
 * оставит половину чатов в промежуточном виде.
 *
 * <p>Перед первой записью проход снимает {@link ProjectStampDump} — прежние значения {@code meta}
 * тех рядов, которые собирается переписать. Без каталога под снимок проход не выполняется вовсе:
 * спаны он не читает, а вычисляет, и ошибка вычисления на форме, которой нет ни в одной фикстуре,
 * без снимка неотменяема. Отказ безопасен — чтение переживает отсутствие спанов (см. {@link
 * ProjectTrace} и {@link ActiveProjectNotice}), просто отвечает грубее.
 *
 * <p>Идёт он уже на работающем сервере ({@link ApplicationReadyEvent}), поэтому каждый чат
 * переписывается под замком этого чата ({@code SummaryWriter#inConversation}) — тем же, под которым
 * идёт раунд сжатия. Без замка сжатие, попавшее в секунды прохода, прочитало бы сводки ещё без
 * спанов, записало бы по ним свою — и она осталась бы с обрезанным следом навсегда: отметка
 * «сделано» после этого уже стоит.
 *
 * <p>SQL-миграцией это не делается: {@code meta} — JSON в текстовой колонке, и на H2 разбирать его
 * пришлось бы регулярками, а собирать отрезки — оконными функциями, отдельно для двух диалектов.
 * Здесь тот же код, что и на горячем пути, и он один на обе СУБД.
 */
@Slf4j
@Component
public class ProjectStampBackfill {

    /**
     * Имя отметки в {@code backfill_state}; менять нельзя — по нему проход и считается сделанным.
     */
    private static final String NAME = "chat-message-project-spans";

    private final ChatMessageRepository chatMessageRepository;
    private final ChatTopicRepository chatTopicRepository;
    private final BackfillStateRepository backfillStateRepository;
    private final SummaryWriter summaryWriter;
    private final ObjectMapper objectMapper;
    private final BackfillProperties properties;

    public ProjectStampBackfill(
            ChatMessageRepository chatMessageRepository,
            ChatTopicRepository chatTopicRepository,
            BackfillStateRepository backfillStateRepository,
            SummaryWriter summaryWriter,
            ObjectMapper objectMapper,
            BackfillProperties properties) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatTopicRepository = chatTopicRepository;
        this.backfillStateRepository = backfillStateRepository;
        this.summaryWriter = summaryWriter;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void backfillIfNeeded() {
        if (backfillStateRepository.existsById(NAME)) {
            return;
        }
        final @Nullable Path dir = dumpDir();
        if (dir == null) {
            log.warn(
                    "Project stamp backfill skipped: kb.chat.backfill.dump-path is not set, and the"
                            + " pass will not rewrite chat_message without a snapshot to restore from."
                            + " Old chats keep answering \"which repository\" from chat_topic, without"
                            + " per-message ranges, until it is set and the app restarted.");
            return;
        }
        final List<String> conversations = chatTopicRepository.findAllConversationIds();
        int touched = 0;
        int failed = 0;
        try (ProjectStampDump dump = ProjectStampDump.open(dir, objectMapper)) {
            for (String conversationId : conversations) {
                try {
                    touched += inLock(conversationId, dump);
                } catch (RuntimeException e) {
                    // Один сорвавшийся чат не повод оставить без следа проектов остальные — их
                    // проход и дальше делает. Отметка при этом не ставится: на следующем старте
                    // проход повторится и попробует сорвавшийся чат ещё раз.
                    failed++;
                    log.warn("Project stamp backfill failed for {}", conversationId, e);
                }
            }
            log.info(
                    "Project stamp backfill: {} chats scanned, {} rows updated, {} rows dumped to {}",
                    conversations.size(),
                    touched,
                    dump.rows(),
                    dump.path());
        } catch (IOException | UncheckedIOException e) {
            log.error("Project stamp backfill aborted: cannot write the snapshot into {}", dir, e);
            return;
        }
        if (failed > 0) {
            log.warn(
                    "Project stamp backfill not marked done: {} chat(s) failed, retrying on the next"
                            + " start",
                    failed);
            return;
        }
        backfillStateRepository.save(new BackfillStateEntity(NAME, LocalDateTime.now(), true));
    }

    /** Каталог снимка; {@code null} — не настроен, и тогда проход не идёт (см. javadoc класса). */
    private @Nullable Path dumpDir() {
        final @Nullable String configured = properties.dumpPath();
        return configured == null || configured.isBlank() ? null : Path.of(configured.trim());
    }

    /**
     * Замок чата вокруг всего, что проход с ним делает, — {@code inConversation} принимает {@link
     * Runnable}, поэтому счётчик переписанных рядов возвращается через ячейку.
     */
    private int inLock(String conversationId, ProjectStampDump dump) {
        final int[] touched = {0};
        summaryWriter.inConversation(
                conversationId, () -> touched[0] = backfill(conversationId, dump));
        return touched[0];
    }

    /**
     * Один чат. Транзакции вокруг него нет намеренно: {@code saveAll} и так уходит одной пачкой, а
     * оборвавшийся проход повторится целиком — отметка «сделано» до конца не ставится.
     *
     * <p>Ряды читаются целиком, а не проекцией из нужных колонок: переписываются они через {@code
     * save()}, и сущность, собранная без {@code content} и {@code tool_data}, обнулила бы их в
     * базе. Цена ограничена одним чатом — обход идёт чат за чатом, и следующий читается, когда
     * предыдущий уже не нужен.
     *
     * @return сколько рядов чата пришлось переписать
     */
    int backfill(String conversationId, ProjectStampDump dump) {
        final List<ChatMessageEntity> stored =
                chatMessageRepository.findByConversationIdOrderByPositionAsc(conversationId);
        if (stored.isEmpty()) {
            return 0;
        }
        dump.write(stored);
        final @Nullable String leading = leadingProject(conversationId, stored);
        final List<ChatMessageEntity> changed = new ArrayList<>();
        final List<ChatMessageEntity> rows = new ArrayList<>(stored);

        stampFirstQuestion(rows, leading).ifPresent(changed::add);
        changed.addAll(traceSummaries(rows, leading));

        chatMessageRepository.saveAll(changed);
        return changed.size();
    }

    /**
     * Базовый штамп на первый вопрос чата — и подмена ряда в списке, чтобы спаны ниже считались уже
     * по нему.
     *
     * <p>Ставится только там, где проекта ещё нет: у вопроса, который сам является маркером смены,
     * репозиторий уже назван, и переписывать его нечем.
     */
    private Optional<ChatMessageEntity> stampFirstQuestion(
            List<ChatMessageEntity> rows, @Nullable String leading) {
        if (leading == null) {
            return Optional.empty();
        }
        for (int i = 0; i < rows.size(); i++) {
            final ChatMessageEntity row = rows.get(i);
            if (!ChatHistoryService.opensATurn(row)) {
                continue;
            }
            final @Nullable ChatMessageMeta meta = row.getMeta();
            if (meta != null && meta.project() != null) {
                return Optional.empty();
            }
            final ChatMessageEntity stamped =
                    row.withMeta(
                            (meta == null
                                            ? new ChatMessageMeta(null, false, List.of(), List.of())
                                            : meta)
                                    .withProjectSwitch(leading, null));
            rows.set(i, stamped);
            return Optional.of(stamped);
        }
        return Optional.empty();
    }

    /**
     * Спаны на каждую строку-сводку. Считаются по возрастанию позиций и накопительно — ровно так
     * же, как их пишет очередной раунд сжатия: сводка наследует спаны предыдущей и дописывает
     * отрезки своего куска.
     *
     * <p>Диапазон сводки нигде не хранится, поэтому кусок берётся как «всё до её позиции»: спаны
     * предыдущей сводки уже закрыли начало, а {@link ProjectTrace} пропускает ряды до своего
     * курсора.
     *
     * <p>Мета сводки дополняется, а не собирается заново: сейчас {@code SummaryWriter} не кладёт на
     * неё ничего, кроме следа проектов, но проход идёт по рядам, записанным версиями, которых уже
     * нет, и стирать в них поле, о котором он ничего не знает, ему незачем.
     */
    private List<ChatMessageEntity> traceSummaries(
            List<ChatMessageEntity> rows, @Nullable String leading) {
        final List<ChatMessageEntity> live = rows.stream().filter(row -> !row.isSummary()).toList();
        final List<ChatMessageEntity> summaries = new ArrayList<>();
        final List<ChatMessageEntity> changed = new ArrayList<>();
        for (ChatMessageEntity row : rows) {
            if (!row.isSummary()) {
                continue;
            }
            final ProjectTrace trace =
                    ProjectTrace.of(
                            summaries,
                            live.stream()
                                    .filter(r -> r.getPosition() <= row.getPosition())
                                    .toList(),
                            () -> leading,
                            row.getPosition());
            final @Nullable ChatMessageMeta meta = row.getMeta();
            final ChatMessageEntity updated =
                    row.withMeta(
                            meta == null
                                    ? ChatMessageMeta.ofProject(trace.lastProject(), trace.spans())
                                    : meta.withProjectTrace(trace.lastProject(), trace.spans()));
            summaries.add(updated);
            if (!trace.spans().isEmpty()) {
                changed.add(updated);
            }
        }
        return changed;
    }

    /**
     * С какого репозитория чат начинался. Первый маркер смены отвечает на это точно — его {@code
     * from} и есть «чем была история выше»; у чата без смен история вся на одном проекте, и это
     * текущий проект чата. {@code null} — чат никогда не называл проекта: штамповать нечего,
     * дефолтный из каталога и так верен.
     */
    private @Nullable String leadingProject(String conversationId, List<ChatMessageEntity> rows) {
        for (ChatMessageEntity row : rows) {
            final @Nullable ChatMessageMeta meta = row.getMeta();
            if (meta != null && meta.projectSwitchFrom() != null) {
                return meta.projectSwitchFrom();
            }
        }
        return chatTopicRepository
                .findById(conversationId)
                .map(ChatTopicEntity::getProject)
                .orElse(null);
    }
}
