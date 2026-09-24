package io.github.trialiya.kb.service.chat.topic;

import io.github.trialiya.kb.config.model.ChatTopicProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Что из разговора читает запрос названия и что из его ответа становится названием. Чистые функции:
 * вся политика «что читать» здесь, {@link AiTopicService} только зовёт модель и пишет результат.
 *
 * <p><b>Окно</b> — хвост разговора, от свежего к старому: не больше {@value #MAX_MESSAGES}
 * сообщений, набор кончается на том, которым текст дорос до {@value #ENOUGH_CHARS} символов. У
 * каждого сообщения свой предел — у ответа модели ниже, чем у вопроса: иначе один ответ с таблицей
 * съедал бы весь бюджет, и в окно не попадал бы вопрос, который тему и задаёт. Пределы и держат
 * окно в границах: последнее взятое сообщение добавляет к почти {@value #ENOUGH_CHARS} не больше
 * {@value #USER_CHARS}. Длинное сообщение режется посередине: в начале обычно сама проблема, в
 * конце — к чему пришли, а середина — детали, без которых тему назвать можно.
 *
 * <p>Окно кончается последним ответом модели, а не последним рядом чата: запрос идёт в фоне, и к
 * его началу в историю уже может лечь следующий вопрос из очереди — ни ходом, ни текстом он к
 * только что законченному ответу не относится.
 *
 * <p>Номер хода, по которому считаются контрольные точки ({@link #lastCheckpoint}), сюда не
 * приезжает вовсе: его даёт {@code ChatMessageRepository.countTurns} одним запросом, без чтения
 * истории.
 *
 * <p>В окно не идут сводки (их не отдаёт и сама выборка, {@code
 * ChatMessageRepository.findConversationTurns}: они пересказывают начало разговора, а название —
 * про то, чем он занят сейчас), плашки сжатия, ряды событий (git, откат, скрипт), ряды слэш-команд
 * и ряды без текста — вызовы инструментов. Блоки кода сворачиваются до пометки с языком: для темы
 * они шум. Имена вложений к вопросу, наоборот, добавляются: «разбери этот отчёт» без имени файла
 * пуст.
 */
final class TopicPrompt {

    static final int MAX_MESSAGES = 6;
    static final int ENOUGH_CHARS = 2_000;
    static final int USER_CHARS = 1_000;
    static final int ASSISTANT_CHARS = 600;
    static final int MAX_TOPIC_CHARS = 80;

    /** Шов на месте вырезанной середины — его же описывает промпт {@code chat-topic.md}. */
    static final String CUT = " … ";

    private static final Pattern LABEL =
            Pattern.compile(
                    "^(title|topic|тема|название)\\s*:\\s*",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Обёртка названия: кавычки, заголовок markdown ({@code #} с пробелом) и жирный ({@code **}).
     * Одиночные {@code #}, {@code *} и {@code _} не трогаются — ими названия начинаются и кончаются
     * по делу: «Async в C#», {@code __init__}, {@code *.gradle}.
     */
    private static final Pattern LEADING_JUNK =
            Pattern.compile("^(?:#+\\s+|\\*\\*|[\\s\"'`«»“”„])+");

    /** Забор кода, его язык и всё до закрывающего забора той же длины (или до конца текста). */
    private static final Pattern CODE_BLOCK =
            Pattern.compile(
                    "(?ms)^[ \\t]*(`{3,}|~{3,})[ \\t]*([^\\n]*)$.*?(?:^[ \\t]*\\1[ \\t]*$|\\z)");

    private static final Pattern TRAILING_JUNK = Pattern.compile("(?:\\*\\*|[\\s\"'`«»“”„.!。])+$");

    private TopicPrompt() {}

    /** Одно сообщение окна. */
    record Line(MessageType type, String text) {}

    /**
     * Последняя контрольная точка не позже хода номер {@code turns}: первый, третий, десятый и
     * дальше каждый десятый; 0 — ходов нет. Чаще называть незачем — тема разговора меняется
     * медленнее, чем идут ответы, а название, которое переписывается на каждом, в списке чатов не
     * узнать.
     */
    static int lastCheckpoint(int turns) {
        if (turns < 1) {
            return 0;
        }
        if (turns < 3) {
            return 1;
        }
        return turns < 10 ? 3 : turns / 10 * 10;
    }

    /**
     * Пора ли назвать чат заново: после хода, на котором чат назван ({@code namedAt}), пройдена
     * новая контрольная точка. Именно «пройдена», а не «ход ровно на ней»: ответ на точке могли
     * остановить, а запрос по нему — пропустить, пока шёл предыдущий, и тогда точку берёт первый
     * следующий законченный ответ.
     */
    static boolean due(int turns, int namedAt) {
        return lastCheckpoint(turns) > namedAt;
    }

    /** Окно для запроса — от старого к свежему. Пустое, если читать нечего. */
    static List<Line> excerpt(List<ChatMessageEntity> rows) {
        final Deque<Line> picked = new ArrayDeque<>();
        int total = 0;
        for (int i = answeredEnd(rows) - 1;
                i >= 0 && picked.size() < MAX_MESSAGES && total < ENOUGH_CHARS;
                i--) {
            final ChatMessageEntity row = rows.get(i);
            if (!readable(row)) {
                continue;
            }
            final String text = text(row);
            if (text.isEmpty()) {
                continue;
            }
            final int own = row.getType() == MessageType.USER ? USER_CHARS : ASSISTANT_CHARS;
            final String kept = shorten(text, own);
            picked.addFirst(new Line(row.getType(), kept));
            total += kept.length();
        }
        return List.copyOf(picked);
    }

    /** Сообщение пользователя для запроса: текущее название, если есть, и окно. */
    static String request(@Nullable String currentTopic, List<Line> lines) {
        final String conversation =
                lines.stream()
                        .map(
                                line ->
                                        (line.type() == MessageType.USER ? "User: " : "Assistant: ")
                                                + line.text())
                        .collect(Collectors.joining("\n\n"));
        return (currentTopic == null ? "" : "Current title: " + currentTopic + "\n\n")
                + "Conversation, oldest first:\n\n"
                + conversation;
    }

    /**
     * Название из ответа модели: первая строка, в которой после снятия кавычек, markdown-разметки и
     * подписи «Title:» что-то осталось, без точки в конце, не длиннее {@value #MAX_TOPIC_CHARS}
     * символов. Подпись на отдельной строке ({@code **Title:**}, а название ниже) так пропускается,
     * как и подводка («Here is the title:»): названия двоеточием не кончаются. {@code null} — в
     * ответе названия нет.
     *
     * <p>Промпт просит одно название и ничего больше, но модель для этого запроса выбирают
     * подешевле ({@link ChatTopicProperties}) — а такая слушается хуже.
     */
    static @Nullable String clean(@Nullable String reply) {
        if (reply == null) {
            return null;
        }
        return reply.lines()
                .map(TopicPrompt::cleanLine)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private static String cleanLine(String line) {
        String topic = LEADING_JUNK.matcher(line).replaceAll("");
        topic = LABEL.matcher(topic).replaceAll("");
        topic = LEADING_JUNK.matcher(topic).replaceAll("");
        topic = TRAILING_JUNK.matcher(topic).replaceAll("");
        topic = topic.replaceAll("\\s+", " ").strip();
        if (topic.endsWith(":")) {
            return "";
        }
        if (topic.length() > MAX_TOPIC_CHARS) {
            final int space = topic.lastIndexOf(' ', MAX_TOPIC_CHARS);
            topic = topic.substring(0, space > 0 ? space : MAX_TOPIC_CHARS).strip();
        }
        return topic;
    }

    /**
     * Текст длиннее {@code limit} — начало и конец поровну, между ними {@link #CUT}. Итог не
     * длиннее {@code limit}; сам предел обязан быть больше шва, и пределы сообщений выше такие.
     */
    static String shorten(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        final int room = limit - CUT.length();
        int head = (room + 1) / 2;
        if (Character.isHighSurrogate(text.charAt(head - 1))) {
            head--;
        }
        int tailStart = text.length() - (room - head);
        if (tailStart < text.length() && Character.isLowSurrogate(text.charAt(tailStart))) {
            tailStart++;
        }
        return text.substring(0, head).stripTrailing()
                + CUT
                + text.substring(tailStart).stripLeading();
    }

    /**
     * Блоки кода — одной пометкой {@code [code: язык]}. Закрывает блок забор той же длины, поэтому
     * длинный забор переживает короткие внутри себя; незакрытый блок (ответ оборвали посреди кода)
     * сворачивается до конца текста.
     */
    static String collapseCode(String text) {
        final Matcher block = CODE_BLOCK.matcher(text);
        final StringBuilder out = new StringBuilder();
        while (block.find()) {
            final String info = block.group(2).strip();
            final String language = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            block.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            language.isEmpty() ? "[code]" : "[code: " + language + "]"));
        }
        block.appendTail(out);
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    /** Конец отвеченной части истории: индекс за последним ответом модели, 0 — ответов нет. */
    private static int answeredEnd(List<ChatMessageEntity> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (isAnswer(rows.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean isAnswer(ChatMessageEntity row) {
        return row.getType() == MessageType.ASSISTANT && !isPlaque(row);
    }

    private static boolean readable(ChatMessageEntity row) {
        return !isPlaque(row) && !ChatHistoryService.isEventRow(row) && !isCommand(row);
    }

    private static boolean isPlaque(ChatMessageEntity row) {
        return row.getMeta() != null && row.getMeta().compact() != null;
    }

    private static boolean isCommand(ChatMessageEntity row) {
        return row.getMeta() != null && row.getMeta().command();
    }

    private static String text(ChatMessageEntity row) {
        final String text = collapseCode(row.getContent());
        if (row.getType() != MessageType.USER) {
            return text;
        }
        final List<String> names =
                row.getContextItems().stream()
                        .filter(item -> item.kind() == ContextItemKind.ATTACHMENT)
                        .map(ContextItem::label)
                        .filter(Objects::nonNull)
                        .filter(label -> !label.isBlank())
                        .toList();
        if (names.isEmpty()) {
            return text;
        }
        final String attachments = "[attachments: " + String.join(", ", names) + "]";
        return text.isEmpty() ? attachments : text + "\n" + attachments;
    }
}
