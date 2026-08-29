package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.prompt.ProjectPromptService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Нотис «в каком репозитории идёт этот чат», который {@link ChatHistoryService#promptRow}
 * дописывает к последнему вопросу окна. Собирается на чтении и в БД не попадает — как опись
 * вложений: проект может смениться, а переписывать ради этого сохранённый текст сообщений было бы
 * нечестно и дорого.
 *
 * <p>Ставится ровно на один ряд — последний, который открывает ход ({@link
 * ChatHistoryService#opensATurn}), то есть на текущий вопрос. Копия на каждом вопросе стоила бы
 * места в каждом запросе и, что хуже, врала бы: у сообщения из середины истории активный проект был
 * другим. Ряды git-команд и вопросы, доставленные посреди прогона, хода не открывают и нотиса не
 * получают.
 *
 * <p><b>Чем это оплачено.</b> Блок стоит на ряду, который меняется от хода к ходу, а значит текст
 * прошлого вопроса от хода к ходу меняется тоже: на прогоне N он с блоком, на прогоне N+1 — уже
 * без. Кэш промпта у провайдера префиксный, и расходятся префиксы ровно там, поэтому каждый прогон
 * заново оплачивает хвост предыдущего хода — ответ модели и все его TOOL-ряды. Внутри прогона
 * такого нет (см. {@code ProjectPromptService#range}), и накопления тоже нет: платится один ход, не
 * вся история. Системный промпт этой платы не требовал — он не менялся вовсе, — но и сказанное в
 * нём относилось ко всей истории разом, включая чужие репозитории, что и было причиной переезда.
 *
 * <p>Дешевле не выходит ни в одну сторону. Заморозить блок на том ряду, где он появился, — это
 * копия в каждом вопросе окна, то есть постоянная добавка вместо разовой. Убрать его из истории в
 * отдельный ряд последним сообщением — значит поставить USER-ряд после TOOL-ответов и сломать
 * протокол вызовов.
 *
 * <p>Отдельный класс, а не пара методов в {@link ChatHistoryService}: тот и без того самый большой
 * файл этой области, а здесь своя связка — каталог проектов, {@code chat_topic} и сборка следа.
 */
@Service
public class ActiveProjectNotice {

    private final ProjectPromptService projectPrompt;
    private final ChatTopicRepository chatTopicRepository;

    public ActiveProjectNotice(
            ProjectPromptService projectPrompt, ChatTopicRepository chatTopicRepository) {
        this.projectPrompt = projectPrompt;
        this.chatTopicRepository = chatTopicRepository;
    }

    /**
     * Позиция ряда, который несёт нотис, — последний открыватель хода в окне; {@code -1}, если
     * такого нет (окно из одних ответов, или доставка посреди прогона, где все ряды — вставки).
     */
    public static long anchor(List<ChatMessageEntity> rows) {
        long anchor = -1;
        for (ChatMessageEntity row : rows) {
            if (ChatHistoryService.opensATurn(row)) {
                anchor = row.getPosition();
            }
        }
        return anchor;
    }

    /**
     * Текст нотиса для окна: активный проект, список остальных и — если чат репозиторий менял —
     * какие сообщения к какому относятся.
     *
     * <p>След собирается тем же {@link ProjectTrace}, что и на записи сводки: спаны последней
     * сводки плюс носители живых рядов. Хвост открыт — последний отрезок кончается на последнем
     * ряду окна и в тексте печатается как «с сообщения N и дальше».
     *
     * @param rows окно целиком, не пустое: зовут отсюда только когда {@link #anchor} нашёл, на что
     *     ставить блок, а нашёл он его среди этих же рядов
     */
    public String render(String conversationId, List<ChatMessageEntity> rows) {
        final ProjectTrace trace =
                ProjectTrace.of(
                        rows.stream().filter(ChatMessageEntity::isSummary).toList(),
                        rows.stream().filter(row -> !row.isSummary()).toList(),
                        () -> storedProject(conversationId),
                        rows.getLast().getPosition());
        return "<active-project>\n"
                + projectPrompt.context(trace.lastProject(), trace.spans())
                + "\nThis block is rebuilt for every request from the chat's own history. When"
                + " summarizing, do not preserve it — unlike <project-switched>, which must be"
                + " kept verbatim.\n"
                + "</active-project>";
    }

    /**
     * Проект чата — ответ, который верен всегда: колонку пишет разрешение прогона, и она означает
     * «на каком проекте чат реально работал». Спрашивается только когда носителя в истории нет
     * вовсе (чат, начатый до базового штампа и не переживший бэкфилл, или начатый с git-команды),
     * поэтому лишнего запроса на итерацию tool-цикла не появляется.
     */
    private @Nullable String storedProject(String conversationId) {
        return chatTopicRepository
                .findById(conversationId)
                .map(ChatTopicEntity::getProject)
                .orElse(null);
    }
}
