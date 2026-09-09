package io.github.trialiya.kb.service.chat.topic;

import io.github.trialiya.kb.model.chat.dto.ChatSearchGroups;
import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.dto.MessageSearchHit;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

/**
 * Два независимых поиска: по одному чату (find-бар, Ctrl+F) и по всем чатам пользователя (название
 * + содержимое сообщений). Оба читают ту же историю, что показывает чат, поэтому фильтр видимости
 * ({@link #isSearchable}) здесь один на оба.
 */
@AllArgsConstructor
@Service
public class ChatSearchService {

    /** Сколько сырых совпадений сообщений просматриваем при поиске по всем чатам пользователя. */
    private static final int MESSAGE_SEARCH_SCAN_LIMIT = 200;

    /**
     * Контекст (в символах) до и после вхождения при построении сниппета. Префикс намеренно
     * короткий: сниппет в дропдауне обрезается справа, и при длинном префиксе само совпадение
     * оказывалось за границей видимой области.
     */
    private static final int SNIPPET_PREFIX = 30;

    private static final int SNIPPET_SUFFIX = 90;

    /**
     * Сколько строк одного сообщения показывает страница поиска. Ответ модели на несколько экранов,
     * где запрос встречается в каждой строке, иначе вытеснил бы из выдачи все остальные сообщения;
     * дальше этого числа важно уже не «где именно», а что совпадений много.
     */
    private static final int FRAGMENTS_PER_MESSAGE = 10;

    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * Сообщение видно пользователю и осмысленно для поиска: не служебное SYSTEM и не
     * legacy-«крошка» вызовов инструментов (meta.toolCalls=true у старых записей) — те же критерии,
     * что и в отображении истории чата.
     */
    private static boolean isSearchable(ChatMessageEntity entity) {
        if (entity.getType() == MessageType.SYSTEM) {
            return false;
        }
        ChatMessageMeta meta = entity.getMeta();
        return meta == null || !meta.toolCalls();
    }

    /** Поиск сообщений внутри одного чата — для локального find-бара (Ctrl+F). */
    public List<MessageSearchHit> searchMessages(String conversationId, String q) {
        String pattern = q == null ? "" : q.trim();
        if (pattern.isEmpty()) {
            return List.of();
        }
        return chatMessageRepository.searchInConversation(conversationId, pattern).stream()
                .filter(ChatSearchService::isSearchable)
                .map(e -> new MessageSearchHit(e.getId(), e.getCreatedAt()))
                .toList();
    }

    /**
     * Поиск чатов пользователя по названию и/или содержимому сообщений. Результат объединяет оба
     * вида совпадений по чату; сниппет строится вокруг самого свежего совпавшего сообщения.
     */
    public List<ChatSearchResult> searchChats(String user, String q, int limit) {
        String pattern = q == null ? "" : q.trim();
        if (pattern.isEmpty()) {
            return List.of();
        }
        Hits hits = collect(user, pattern);
        return hits.topics().stream()
                .map(
                        topic -> {
                            String id = topic.getConversationId();
                            List<ChatMessageEntity> messages =
                                    hits.messagesByConversation().getOrDefault(id, List.of());
                            // От новых к старым: первое и есть самое свежее.
                            ChatMessageEntity latest =
                                    messages.isEmpty() ? null : messages.getFirst();
                            return new ChatSearchResult(
                                    id,
                                    topic.getDisplayTopic(),
                                    topic.getUpdatedAt(),
                                    hits.titleMatchIds().contains(id),
                                    messages.size(),
                                    latest != null
                                            ? buildSnippet(latest.getContent(), pattern)
                                            : null);
                        })
                .limit(limit)
                .toList();
    }

    /**
     * Тот же поиск, что {@link #searchChats}, но с каждым совпавшим сообщением и каждым вхождением
     * внутри него, а не только самым свежим сообщением — для страницы поиска, где чат раскрывается
     * в список своих сообщений.
     */
    public ChatSearchGroups searchChatsGrouped(String user, String q, int limit) {
        String pattern = q == null ? "" : q.trim();
        if (pattern.isEmpty()) {
            return new ChatSearchGroups(0, false, List.of());
        }
        Hits hits = collect(user, pattern);
        List<ChatSearchGroups.Group> groups =
                hits.topics().stream()
                        .limit(limit)
                        .map(
                                topic -> {
                                    String id = topic.getConversationId();
                                    List<ChatSearchGroups.Message> messages =
                                            hits
                                                    .messagesByConversation()
                                                    .getOrDefault(id, List.of())
                                                    .reversed()
                                                    .stream()
                                                    .map(e -> message(e, pattern))
                                                    .toList();
                                    return new ChatSearchGroups.Group(
                                            id,
                                            topic.getDisplayTopic(),
                                            topic.getUpdatedAt(),
                                            hits.titleMatchIds().contains(id),
                                            messages);
                                })
                        .toList();
        int total =
                groups.stream()
                        .flatMap(g -> g.messages().stream())
                        .mapToInt(m -> m.fragments().size())
                        .sum();
        return new ChatSearchGroups(total, hits.scanCapped(), groups);
    }

    private static ChatSearchGroups.Message message(ChatMessageEntity e, String pattern) {
        return new ChatSearchGroups.Message(
                e.getId(),
                e.getType().name(),
                e.getCreatedAt(),
                fragments(e.getContent(), pattern));
    }

    /**
     * Все вхождения запроса в сообщении — по фрагменту на строку, как страница поиска показывает
     * строки документа. Строка целиком сюда не годится: сообщение чата пишут абзацами, и один абзац
     * занял бы всю карточку, поэтому у каждой строки берётся тот же сниппет вокруг вхождения.
     */
    private static List<String> fragments(@Nullable String content, String query) {
        if (content == null) {
            return List.of();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (found.size() >= FRAGMENTS_PER_MESSAGE) {
                break;
            }
            if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                addIfPresent(found, buildSnippet(line, query));
            }
        }
        if (found.isEmpty()) {
            // Ни одна строка не содержит запрос целиком: он совпал в БД через перенос строки.
            // Показываем начало сообщения — то же, что делает сниппет в этом случае.
            addIfPresent(found, buildSnippet(content, query));
        }
        return List.copyOf(found);
    }

    private static void addIfPresent(List<String> target, @Nullable String fragment) {
        if (fragment != null && !fragment.isBlank()) {
            target.add(fragment);
        }
    }

    /**
     * Что нашлось по запросу, до отбора и лимита.
     *
     * @param topics найденные чаты, новые первыми — общий порядок обоих поисков
     * @param titleMatchIds чаты, у которых совпало название
     * @param messagesByConversation совпавшие сообщения каждого чата от новых к старым; чат,
     *     найденный только по названию, здесь отсутствует
     * @param scanCapped просмотр сообщений упёрся в {@link #MESSAGE_SEARCH_SCAN_LIMIT}: списки
     *     неполны, и более старые совпадения в них не попали
     */
    private record Hits(
            List<ChatTopicEntity> topics,
            Set<String> titleMatchIds,
            Map<String, List<ChatMessageEntity>> messagesByConversation,
            boolean scanCapped) {}

    private Hits collect(String user, String pattern) {
        List<ChatTopicEntity> titleHits = chatTopicRepository.searchByTopic(user, pattern);
        Set<String> titleMatchIds = new LinkedHashSet<>();
        Map<String, ChatTopicEntity> topicsById = new LinkedHashMap<>();
        for (ChatTopicEntity t : titleHits) {
            titleMatchIds.add(t.getConversationId());
            topicsById.put(t.getConversationId(), t);
        }

        List<ChatMessageEntity> rawHits =
                chatMessageRepository.searchForUser(user, pattern, MESSAGE_SEARCH_SCAN_LIMIT);
        // rawHits идёт от новых к старым — списки по чату наследуют этот порядок.
        Map<String, List<ChatMessageEntity>> messagesByConversation = new LinkedHashMap<>();
        for (ChatMessageEntity e : rawHits) {
            if (!isSearchable(e)) {
                continue;
            }
            messagesByConversation
                    .computeIfAbsent(e.getConversationId(), id -> new ArrayList<>())
                    .add(e);
        }

        List<String> missingTopics =
                messagesByConversation.keySet().stream()
                        .filter(id -> !topicsById.containsKey(id))
                        .toList();
        if (!missingTopics.isEmpty()) {
            // Безопасно без повторной фильтрации по user: эти id уже пришли из
            // searchForUser(user, ...), т.е. и так принадлежат этому пользователю.
            chatTopicRepository
                    .findAllById(missingTopics)
                    .forEach(t -> topicsById.put(t.getConversationId(), t));
        }

        Set<String> allIds = new LinkedHashSet<>(titleMatchIds);
        allIds.addAll(messagesByConversation.keySet());
        List<ChatTopicEntity> topics =
                allIds.stream()
                        .map(topicsById::get)
                        .filter(Objects::nonNull)
                        .sorted(
                                Comparator.comparing(
                                                (ChatTopicEntity t) ->
                                                        t.getUpdatedAt() != null
                                                                ? t.getUpdatedAt()
                                                                : LocalDateTime.MIN)
                                        .reversed())
                        .toList();
        return new Hits(
                topics,
                titleMatchIds,
                messagesByConversation,
                rawHits.size() >= MESSAGE_SEARCH_SCAN_LIMIT);
    }

    /**
     * Фрагмент текста вокруг первого вхождения query (без учёта регистра), с многоточиями. Переносы
     * строк и повторные пробелы схлопываются: сниппет — одна плотная строка, а не первая (часто
     * пустая или заголовочная) строка markdown-сообщения. Package-private для юнит-теста.
     */
    static @Nullable String buildSnippet(@Nullable String content, String query) {
        if (content == null) {
            return null;
        }
        String flat = content.strip().replaceAll("\\s+", " ");
        int idx = flat.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            // Запрос с пробелами мог совпасть в сыром тексте через перенос строки — после
            // схлопывания его не найти; показываем начало сообщения.
            int cap = SNIPPET_PREFIX + SNIPPET_SUFFIX;
            return flat.length() > cap ? flat.substring(0, cap) + "…" : flat;
        }
        int start = Math.max(0, idx - SNIPPET_PREFIX);
        int end = Math.min(flat.length(), idx + query.length() + SNIPPET_SUFFIX);
        String prefix = start > 0 ? "…" : "";
        String suffix = end < flat.length() ? "…" : "";
        return prefix + flat.substring(start, end).strip() + suffix;
    }
}
