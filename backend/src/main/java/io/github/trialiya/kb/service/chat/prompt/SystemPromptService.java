package io.github.trialiya.kb.service.chat.prompt;

import io.github.trialiya.kb.config.model.SystemPromptProperties;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Provides the extended system prompt guidance for weak models. The base system prompt (reference
 * rules and tool selection) is loaded as {@code defaultSystem} in {@code ChatConfig#chatClient} and
 * applies to all models. This service provides only the extended guidance (decision trees, workflow
 * examples, common mistakes), which is injected via the {@code {system_extended}} placeholder in
 * the base system prompt.
 *
 * <p>For weak models, the extended guidance is appended; for strong models, an empty string is
 * returned, saving context.
 */
@Service
public class SystemPromptService {

    private final ScriptGuideService scriptGuide;
    private final String extendedForWeakModel;
    private final String extendedForStrongModel;

    public SystemPromptService(SystemPromptProperties properties, ScriptGuideService scriptGuide) {
        this.scriptGuide = scriptGuide;
        this.extendedForWeakModel = read(properties.extendedPrompt());
        this.extendedForStrongModel = "";
    }

    /**
     * Все подстановки шаблона {@code sys.md} разом — и это единственное место, где они собираются.
     *
     * <p>Собираются вместе не ради краткости вызова: системное сообщение обычного прогона и
     * системное сообщение раунда {@code /compact} обязаны получиться посимвольно одинаковыми. Оба
     * запроса идут по одной и той же истории, и провайдер отдаёт повторную часть по ставке кэша
     * ровно до первого расхождения — а системное сообщение стоит в самом начале, так что разойдись
     * они хоть на одной подстановке, сжатие оплатит по полной ставке весь контекст, который оно
     * пришло сократить. Двумя копиями списка это правило не держится: подстановка, добавленная в
     * одну из них, тихо обнуляет кэш второй.
     *
     * @param weakModel {@code ChatModelProperties#isWeak} модели запроса
     * @param project проект запроса; {@code null} — проект по умолчанию
     * @param modeInstructions инструкции режима чата ({@code ChatModeService})
     */
    public Map<String, Object> placeholders(
            boolean weakModel, @Nullable String project, String modeInstructions) {
        return Map.of(
                "mode_instructions", modeInstructions,
                "script_instructions", scriptGuide.instructions(weakModel, project),
                "system_extended", systemExtended(weakModel));
    }

    /**
     * The extended system prompt (decision trees, examples, common mistakes) for the given model.
     * Returns the extended guidance for weak models, or empty string for strong models.
     *
     * @param weak {@code ChatModelProperties.ModelOption#weak} of the model being used — picks
     *     extended guidance or empty string
     */
    public String systemExtended(boolean weak) {
        return weak ? extendedForWeakModel : extendedForStrongModel;
    }

    private static String read(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read system prompt: " + resource, e);
        }
    }
}
