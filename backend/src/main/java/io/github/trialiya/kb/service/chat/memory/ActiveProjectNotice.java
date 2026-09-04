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
 * дописывает к одному из вопросов окна. Собирается на чтении и в БД не попадает — как опись
 * вложений: проект может смениться, а переписывать ради этого сохранённый текст сообщений было бы
 * нечестно и дорого.
 *
 * <p>Ставится ровно на один ряд — первый вопрос, прочитанный уже в активном репозитории: тот, с
 * которого начинается последний отрезок следа ({@link ProjectTrace}), а если след начат за границей
 * сжатия — первый вопрос живого окна. Копия на каждом вопросе стоила бы места в каждом запросе и,
 * что хуже, врала бы: у сообщения из середины истории активный проект был другим. Ряды git-команд и
 * вопросы, доставленные посреди прогона, хода не открывают и нотиса не получают.
 *
 * <p><b>Место выбрано ради кэша промпта.</b> Кэш у провайдера префиксный, поэтому любой ряд,
 * который меняет текст от хода к ходу, обрывает кэш на себе и заставляет заново оплатить всё, что
 * ниже, — а ниже лежит хвост предыдущего хода, то есть ответ модели и все его TOOL-ряды, самая
 * тяжёлая часть окна. Здесь же и ряд, и текст блока от хода к ходу не меняются: номер, с которого
 * идёт последний отрезок, зафиксирован, а его верхняя граница печатается открытой («message N
 * onward», см. {@code ProjectPromptService#range}). Двинуться блоку есть от чего ровно дважды — при
 * смене проекта и при сжатии, — и оба раза окно и без него переписано.
 *
 * <p>Плата за это — расстояние: в длинном окне блок стоит далеко от текущего вопроса, и справка о
 * том, что каждая ссылка на файл обязана нести id проекта, читается моделью не рядом с местом
 * ответа. Держать её у последнего вопроса значило бы платить хвостом предыдущего хода за каждый ход
 * чата.
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
     * Куда встаёт блок и что в нём написано.
     *
     * @param anchor позиция ряда-носителя
     * @param text готовый текст блока
     */
    public record Placement(long anchor, String text) {}

    /**
     * Блок для окна: активный проект, список остальных и — если чат репозиторий менял — какие
     * сообщения к какому относятся. {@code null} — ставить некуда: в окне нет ни одного ряда,
     * открывающего ход (одни ответы, или доставка посреди прогона, где все ряды — вставки).
     *
     * <p>След собирается тем же {@link ProjectTrace}, что и на записи сводки: спаны последней
     * сводки плюс носители живых рядов. Хвост открыт — последний отрезок кончается на последнем
     * ряду окна и в тексте печатается как «с сообщения N и дальше».
     *
     * @param rows окно целиком в порядке позиций
     */
    public @Nullable Placement place(String conversationId, List<ChatMessageEntity> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        final ProjectTrace trace =
                ProjectTrace.of(
                        rows.stream().filter(ChatMessageEntity::isSummary).toList(),
                        rows.stream().filter(row -> !row.isSummary()).toList(),
                        () -> storedProject(conversationId),
                        rows.getLast().getPosition());
        final long anchor =
                anchor(
                        rows,
                        trace.spans().isEmpty() ? Long.MIN_VALUE : trace.spans().getLast().from());
        if (anchor < 0) {
            return null;
        }
        return new Placement(
                anchor,
                "<active-project>\n"
                        + projectPrompt.context(trace.lastProject(), trace.spans())
                        + "\nThis block is rebuilt for every request from the chat's own history."
                        + " When summarizing, do not preserve it — unlike <project-switched>, which"
                        + " must be kept verbatim.\n"
                        + "</active-project>");
    }

    /**
     * Носитель блока: первый ряд, открывающий ход ({@link ChatHistoryService#opensATurn}), начиная
     * с позиции {@code from} — там, где активный проект вступил в силу.
     *
     * <p>Открывателя за этой границей может и не быть: отрезок начат вопросом, который уже сжат, а
     * живыми остались одни ответы. Тогда блок садится на последний открыватель окна — сказать «в
     * каком репозитории мы сейчас» важнее, чем сделать это в идеальном месте.
     */
    private static long anchor(List<ChatMessageEntity> rows, long from) {
        long fallback = -1;
        for (ChatMessageEntity row : rows) {
            if (!ChatHistoryService.opensATurn(row)) {
                continue;
            }
            if (row.getPosition() >= from) {
                return row.getPosition();
            }
            fallback = row.getPosition();
        }
        return fallback;
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
