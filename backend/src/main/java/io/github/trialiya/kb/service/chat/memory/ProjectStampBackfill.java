package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.backfill.BackfillStateEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
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

    public ProjectStampBackfill(
            ChatMessageRepository chatMessageRepository,
            ChatTopicRepository chatTopicRepository,
            BackfillStateRepository backfillStateRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatTopicRepository = chatTopicRepository;
        this.backfillStateRepository = backfillStateRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    void backfillIfNeeded() {
        if (backfillStateRepository.existsById(NAME)) {
            return;
        }
        final List<String> conversations = chatTopicRepository.findAllConversationIds();
        int touched = 0;
        for (String conversationId : conversations) {
            try {
                touched += backfill(conversationId);
            } catch (RuntimeException e) {
                // Один сорвавшийся чат не повод оставить без следа проектов остальные: отметка
                // не ставится, и на следующем старте проход повторится целиком.
                log.warn("Project stamp backfill failed for {}", conversationId, e);
                return;
            }
        }
        backfillStateRepository.save(new BackfillStateEntity(NAME, LocalDateTime.now(), true));
        log.info(
                "Project stamp backfill done: {} chats scanned, {} rows updated",
                conversations.size(),
                touched);
    }

    /**
     * Один чат. Транзакции вокруг него нет намеренно: {@code saveAll} и так уходит одной пачкой, а
     * оборвавшийся проход повторится целиком — отметка «сделано» до конца не ставится.
     *
     * @return сколько рядов чата пришлось переписать
     */
    int backfill(String conversationId) {
        final List<ChatMessageEntity> stored =
                chatMessageRepository.findByConversationIdOrderByPositionAsc(conversationId);
        if (stored.isEmpty()) {
            return 0;
        }
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
            final ChatMessageEntity updated =
                    row.withMeta(ChatMessageMeta.ofProject(trace.lastProject(), trace.spans()));
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
