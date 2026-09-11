package io.github.trialiya.kb.config;

import io.github.trialiya.kb.functions.AttachmentFunction;
import io.github.trialiya.kb.functions.DocumentFunction;
import io.github.trialiya.kb.functions.GitEditFunction;
import io.github.trialiya.kb.functions.GitFunction;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.functions.ScriptFunction;
import io.github.trialiya.kb.functions.SearchAgentFunction;
import io.github.trialiya.kb.functions.SkillFunction;
import io.github.trialiya.kb.functions.TopicFunction;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Метаданные для native-image, которые статический анализ вывести не может.
 *
 * <p>Конвертеры результата. Spring AI создаёт тот, что назван в {@code @Tool(resultConverter =
 * ...)}, через {@code getDeclaredConstructor().newInstance()} (см. {@code
 * ToolUtils#getToolCallResultConverter}). Класс в образ попадает, но конструктор без регистрации не
 * вызывается, и запуск падает с {@code NoSuchMethodException} на первом же инструменте.
 *
 * <p>Держатели инструментов. Аннотации {@code @Tool} Spring AI ищет рефлексией по методам класса, а
 * Spring AOT регистрирует методы только у тех классов, которые на этапе обработки были бинами.
 * Часть держателей бинами не является: {@code ChatConfig} создаёт их прямо в теле бин-метода
 * ({@code new TopicFunction(...)} и соседи), а {@code ScriptFunction} объявлен под
 * {@code @ConditionalOnProperty}, и при выключенном {@code kb.script.enabled} на этапе AOT в граф
 * не попадает — хотя в рантайме создаётся. Без регистрации методы в образе есть, а аннотаций на них
 * нет, и сборка инструментов падает с «No @Tool annotated methods found». Регистрируем весь
 * пакет-держатель целиком: дешевле, чем сверять список с условиями бинов при каждой правке.
 *
 * <p>Своих метаданных Spring AI не поставляет, поэтому обе группы приходится описывать здесь.
 * Подключено через {@code META-INF/spring/aot.factories}, а не как {@code @Configuration}: хинты
 * нужны только на этапе сборки образа, и лишний бин в контексте ради них заводить незачем. На
 * обычной JVM класс не исполняется вовсе.
 */
public class NativeHints implements RuntimeHintsRegistrar {

    /** Классы с методами под {@code @Tool} — см. {@code ChatConfig#chatToolset}. */
    private static final List<Class<?>> TOOL_HOLDERS =
            List.of(
                    AttachmentFunction.class,
                    DocumentFunction.class,
                    GitEditFunction.class,
                    GitFunction.class,
                    MessageLookupFunction.class,
                    ScriptFunction.class,
                    SearchAgentFunction.class,
                    SkillFunction.class,
                    TopicFunction.class);

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        for (Class<?> holder : TOOL_HOLDERS) {
            hints.reflection()
                    .registerType(
                            holder,
                            MemberCategory.INVOKE_DECLARED_METHODS,
                            MemberCategory.INVOKE_PUBLIC_METHODS);
        }
        hints.reflection()
                .registerType(
                        io.github.trialiya.kb.tools.CompactToolResultConverter.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                .registerType(
                        DefaultToolCallResultConverter.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
}
