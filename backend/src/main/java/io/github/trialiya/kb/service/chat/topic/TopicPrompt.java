package io.github.trialiya.kb.service.chat.topic;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Что из разговора читает запрос названия и что из его ответа становится названием. Чистые функции:
 * вся политика «что читать» здесь, {@link AiTopicService} только зовёт модель и пишет результат.
 *
 * <p><b>Окно</b> — хвост разговора, от свежего к старому: не больше {@value #MAX_MESSAGES}
 * сообщений, набор кончается на том, которым текст дорос до {@value #ENOUGH_CHARS} символов, и
 * никогда не длиннее {@value #MAX_CHARS}. У каждого сообщения ещё и свой предел — у ответа модели
 * ниже, чем у вопроса: иначе один ответ с таблицей съедал бы весь бюджет, и в окно не попадал бы
 * вопрос, который тему и задаёт. Длинное сообщение режется посередине: в начале обычно сама
 * проблема, в конце — к чему пришли, а середина — детали, без которых тему назвать можно.
 *
 * <p>Окно кончается последним ответом модели, а не последним рядом чата: запрос идёт в фоне, и к
 * его началу в историю уже может лечь следующий вопрос из очереди — ни ходом, ни текстом он к
 * только что законченному ответу не относится.
 *
 * <p>В окно не идут сводки (они пересказывают начало разговора, а название — про то, чем он занят
 * сейчас), плашки сжатия, ряды событий (git, откат, скрипт), сама команда сжатия и ряды без текста
 * — вызовы инструментов. Блоки кода сворачиваются до пометки с языком: для темы они шум. Имена
 * вложений к вопросу, наоборот, добавляются: «разбери этот отчёт» без имени файла пуст.
 */
final class TopicPrompt {

    static final int MAX_MESSAGES = 6;
    static final int ENOUGH_CHARS = 2_000;
    static final int MAX_CHARS = 4_000;
    static final int USER_CHARS = 1_000;
    static final int ASSISTANT_CHARS = 600;
    static final int MAX_TOPIC_CHARS = 80;

    /** Шов на месте вырезанной середины — его же описывает промпт {@code chat-topic.md}. */
    static final String CUT = " … ";

    /**
     * Команда сжатия пишется в историю обычным вопросом — с тем текстом, что набрали. Триггеры те
     * же, что у слэш-команд фронта ({@code chatCommands.js}).
     */
    private static final Pattern COMPACT_COMMAND =
            Pattern.compile(
                    "^/(compact|сжать)(-1)?(\\s|$)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Размышления, которые часть эндпоинтов отдаёт прямо в тексте ответа. */
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?</think>");

    private static final Pattern LABEL =
            Pattern.compile(
                    "^(title|topic|тема|название)\\s*:\\s*",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern LEADING_JUNK = Pattern.compile("^[\\s\"'`«»“”„*#_]+");

    /**
     * Без {@code #} и {@code _}: ими названия кончаются по делу — «Async в C#», {@code __init__}.
     */
    private static final Pattern TRAILING_JUNK = Pattern.compile("[\\s\"'`«»“”„*.!。]+$");

    private TopicPrompt() {}

    /** Одно сообщение окна. */
    record Line(MessageType type, String text) {}

    /**
     * Пора ли назвать чат заново после ответа номер {@code turns}: на первом, третьем, десятом и
     * дальше на каждом десятом. Чаще незачем — тема разговора меняется медленнее, чем идут ответы,
     * а название, которое переписывается на каждом, в списке чатов не узнать.
     */
    static boolean isCheckpoint(int turns) {
        return turns == 1 || turns == 3 || (turns > 0 && turns % 10 == 0);
    }

    /**
     * Сколько ходов в разговоре уже отвечено: вопросы, открывающие ход ({@link
     * ChatHistoryService#opensATurn}), до последнего ответа модели. Команда сжатия ходом не
     * считается — отвечает на неё не модель.
     */
    static int turns(List<ChatMessageEntity> rows) {
        int turns = 0;
        for (final ChatMessageEntity row : rows.subList(0, answeredEnd(rows))) {
            if (ChatHistoryService.opensATurn(row) && !isCompactCommand(row)) {
                turns++;
            }
        }
        return turns;
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
            final String kept = shorten(text, Math.min(own, MAX_CHARS - total));
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
     * Название из ответа модели: первая непустая строка без кавычек, markdown-разметки, подписи
     * «Title:» и точки в конце, не длиннее {@value #MAX_TOPIC_CHARS} символов. {@code null} — в
     * ответе названия нет.
     */
    static @Nullable String clean(@Nullable String reply) {
        if (reply == null) {
            return null;
        }
        final String firstLine =
                THINKING.matcher(reply)
                        .replaceAll("")
                        .lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty())
                        .findFirst()
                        .orElse("");
        String topic = LEADING_JUNK.matcher(firstLine).replaceAll("");
        topic = LABEL.matcher(topic).replaceAll("");
        topic = LEADING_JUNK.matcher(topic).replaceAll("");
        topic = TRAILING_JUNK.matcher(topic).replaceAll("");
        topic = topic.replaceAll("\\s+", " ");
        if (topic.length() > MAX_TOPIC_CHARS) {
            final int space = topic.lastIndexOf(' ', MAX_TOPIC_CHARS);
            topic = topic.substring(0, space > 0 ? space : MAX_TOPIC_CHARS).strip();
        }
        return topic.isEmpty() ? null : topic;
    }

    /**
     * Текст длиннее {@code limit} — начало и конец поровну, между ними {@link #CUT}. Итог не
     * длиннее {@code limit}.
     */
    static String shorten(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        final int room = limit - CUT.length();
        if (room <= 0) {
            return text.substring(0, Math.max(0, limit));
        }
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
     * Блоки кода — одной пометкой {@code [code: язык]}. Незакрытый блок (ответ оборвали посреди
     * кода) сворачивается до конца текста.
     */
    static String collapseCode(String text) {
        final StringBuilder out = new StringBuilder();
        @Nullable String fence = null;
        for (final String line : text.split("\n", -1)) {
            final String trimmed = line.strip();
            if (fence == null) {
                fence = openingFence(trimmed);
                if (fence == null) {
                    out.append(line).append('\n');
                } else {
                    final String info = trimmed.substring(fence.length()).strip();
                    final String language = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
                    out.append(language.isEmpty() ? "[code]" : "[code: " + language + "]")
                            .append('\n');
                }
            } else if (closes(fence, trimmed)) {
                fence = null;
            }
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static @Nullable String openingFence(String trimmed) {
        if (!trimmed.startsWith("```") && !trimmed.startsWith("~~~")) {
            return null;
        }
        final char mark = trimmed.charAt(0);
        int length = 0;
        while (length < trimmed.length() && trimmed.charAt(length) == mark) {
            length++;
        }
        return trimmed.substring(0, length);
    }

    private static boolean closes(String fence, String trimmed) {
        final char mark = fence.charAt(0);
        return trimmed.length() >= fence.length() && trimmed.chars().allMatch(c -> c == mark);
    }

    /** Конец отвеченной части истории: индекс за последним ответом модели, 0 — ответов нет. */
    private static int answeredEnd(List<ChatMessageEntity> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            final ChatMessageEntity row = rows.get(i);
            if (row.getType() == MessageType.ASSISTANT && !row.isSummary() && !isPlaque(row)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean readable(ChatMessageEntity row) {
        return (row.getType() == MessageType.USER || row.getType() == MessageType.ASSISTANT)
                && !row.isSummary()
                && !isPlaque(row)
                && !ChatHistoryService.isEventRow(row)
                && !isCompactCommand(row);
    }

    private static boolean isPlaque(ChatMessageEntity row) {
        return row.getMeta() != null && row.getMeta().compact() != null;
    }

    private static boolean isCompactCommand(ChatMessageEntity row) {
        return row.getType() == MessageType.USER
                && COMPACT_COMMAND.matcher(row.getContent().strip()).find();
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
