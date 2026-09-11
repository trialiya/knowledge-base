package io.github.trialiya.kb.config;

import io.github.trialiya.kb.tools.CompactToolResultConverter;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Метаданные для native-image, которые статический анализ вывести не может.
 *
 * <p>Spring AI создаёт конвертер, названный в {@code @Tool(resultConverter = ...)}, через {@code
 * getDeclaredConstructor().newInstance()} (см. {@code ToolUtils#getToolCallResultConverter}). Класс
 * в образ попадает, но конструктор без регистрации не вызывается, и запуск падает с {@code
 * NoSuchMethodException} на первом же инструменте. Своих метаданных Spring AI не поставляет —
 * отсюда этот регистратор.
 *
 * <p>Подключён через {@code META-INF/spring/aot.factories}, а не как {@code @Configuration}: хинты
 * нужны только на этапе сборки образа, и лишний бин в контексте ради них заводить незачем. На
 * обычной JVM класс не исполняется вовсе.
 */
public class NativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        hints.reflection()
                .registerType(
                        CompactToolResultConverter.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                .registerType(
                        DefaultToolCallResultConverter.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
}
