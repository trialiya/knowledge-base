package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * Слова раунда полного сжатия: чем он просит модель сжать окно и во что заворачивается её ответ.
 * Отдельно от {@code CompactService}, потому что это два разных предмета — что происходит с
 * историей и что при этом читает модель, — и текстов здесь больше, чем механики.
 */
@Service
public class CompactPrompt {

    /** Границы обёртки сводки — общие у {@link #wrap} и {@link #unwrap}. */
    private static final String OPEN = "<summary>\n";

    private static final String CLOSE = "\n</summary>\n";

    private final ChatTopicRepository chatTopicRepository;
    private final String compactorPrompt;

    public CompactPrompt(
            ChatTopicRepository chatTopicRepository,
            @Value("classpath:prompt/compactor.md") Resource compactorPrompt) {
        this.chatTopicRepository = chatTopicRepository;
        // Читается один раз: руководство по сжатию — часть последнего сообщения запроса, и
        // перечитывать его с диска на каждый /compact незачем (так же поступает
        // SystemPromptService).
        this.compactorPrompt = read(compactorPrompt);
    }

    private static String read(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the compaction prompt: " + resource, e);
        }
    }

    /**
     * Последнее сообщение запроса — на месте невыполненной команды пользователя: руководство по
     * сжатию ({@code compactor.md}) плюс справка о самом чате.
     *
     * <p>Руководство едет здесь, а не системным сообщением, и это то же требование кэша, что и в
     * javadoc {@code CompactService}: системное место занято {@code sys.md} чата, и разойдись оно —
     * не совпал бы весь префикс. Место в конце руководству не мешает, а помогает: оно последнее,
     * что читает модель перед ответом, и оттуда же снимает роль, назначенную ей системным промптом.
     *
     * <p>Справка о чате не украшение: сжатое окно останется единственной памятью разговора, а
     * какому проекту принадлежат пути в нём и на каком языке шёл диалог, из самих сообщений видно
     * не всегда.
     *
     * @param rows окно, которое уходит модели, — по нему считаются числа справки
     * @param instructions хвост команды в роли фокуса сжатия; пустой — без фокуса
     */
    public String instruction(
            String conversationId, List<PromptRow> rows, @Nullable String instructions) {
        final @Nullable ChatTopicEntity chat =
                chatTopicRepository.findById(conversationId).orElse(null);
        final StringBuilder prompt = new StringBuilder();
        prompt.append(compactorPrompt).append("\n\n").append("About this conversation:\n");
        append(prompt, "Topic", chat == null ? null : chat.getDisplayTopic());
        append(prompt, "Project", chat == null ? null : chat.getProject());
        append(prompt, "Assistant mode", chat == null ? null : chat.getMode());
        prompt.append("- Messages above: ").append(rows.size()).append('\n');
        prompt.append("- Of them USER messages: ")
                .append(countOf(rows, MessageType.USER))
                .append(" (`## User requests` must have exactly this many bullets)\n");
        prompt.append("- Of them tool protocol messages: ")
                .append(countOf(rows, MessageType.TOOL))
                .append('\n');
        if (StringUtils.hasText(instructions)) {
            prompt.append(
                            """

                            The user asked to focus the compaction on the following. Give this \
                            material more detail than anything else and never let the focus cut a \
                            section short: everything else still has to survive, in full section \
                            format.
                            <focus>
                            """)
                    .append(instructions.strip())
                    .append("\n</focus>\n");
        }
        return prompt.toString();
    }

    private static void append(StringBuilder prompt, String label, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            prompt.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static long countOf(List<PromptRow> rows, MessageType type) {
        return rows.stream().filter(row -> row.entity().getMessageType() == type).count();
    }

    /**
     * Обёртка вокруг ответа модели — та же роль, что у заголовка фоновой сводки: сказать модели,
     * что перед ней не реплика ассистента, а память разговора. Диапазона «продолжай с N» здесь нет
     * намеренно: номерами позиций чат нигде больше не разговаривает, а что разговор сводкой не
     * кончается, видно и без них — по самим сообщениям под ней.
     *
     * <p>Но умолчать о живом хвосте нельзя: сводка, назвавшая себя памятью «всего разговора» над
     * непересказанными сообщениями, противоречит тому, что модель тут же прочитает ниже. У {@code
     * /compact} хвоста не остаётся; у автоматического сжатия он есть — это вопрос, ради которого
     * чат сжался, — и у {@code /compact-1} есть: сбережённый последний ход.
     *
     * @param kind чем сжатие вызвано: от него зависят обе половины заголовка — кто сжатие просил и
     *     кончается ли разговор этой сводкой (см. {@link CompactMeta.Kind})
     */
    public static String wrap(String content, CompactMeta.Kind kind) {
        final String requestedBy =
                kind == CompactMeta.Kind.AUTO_COMPACT
                        ? "automatically, at the model's context limit"
                        : "requested by the user";
        final String scope =
                kind == CompactMeta.Kind.COMPACT
                        ? "Treat this as authoritative context for the entire conversation so far:"
                                + " the messages it covers are no longer in the context and cannot"
                                + " be re-read."
                        : "Treat this as authoritative context for everything it covers: those"
                                + " messages are no longer in the context and cannot be re-read."
                                + " The conversation continues in the messages below, which are"
                                + " still there in full.";
        return "Compacted conversation summary ("
                + requestedBy
                + "):\n"
                + OPEN
                + content
                + CLOSE
                + scope;
    }

    /**
     * Обратное {@link #wrap}: документ модели без адресованной ей обёртки — то, что читает человек,
     * открывший детали сжатия. Строка не той формы отдаётся как есть: сводки, записанные до
     * появления обёртки (или другой её версией), обязаны показываться, а не превращаться в пустой
     * экран.
     */
    public static String unwrap(String stored) {
        final int start = stored.indexOf(OPEN);
        final int end = stored.lastIndexOf(CLOSE);
        return start < 0 || end < start ? stored : stored.substring(start + OPEN.length(), end);
    }
}
