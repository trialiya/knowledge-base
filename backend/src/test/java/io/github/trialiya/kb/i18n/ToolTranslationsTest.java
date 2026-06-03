package io.github.trialiya.kb.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Проверяет синхронность инструментов (@Tool) с переводами на фронте.
 *
 * <p>Источник правды — аннотации {@link Tool} в пакете {@code functions}. Имена инструментов (явное
 * {@code name} или имя метода) сверяются с ключами {@code tools.*} в каждом {@code
 * frontend/src/i18n/locales/*.json}. Чистый unit-тест без Spring-контекста.
 */
class ToolTranslationsTest {

    private static final String FUNCTIONS_PACKAGE = "io.github.trialiya.kb.functions";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Каждый @Tool должен иметь перевод во всех локалях. */
    @Test
    void everyToolHasLabelInEveryLocale() throws Exception {
        Set<String> toolNames = discoverToolNames();
        assertThat(toolNames)
                .as("В пакете %s не найдено ни одного @Tool", FUNCTIONS_PACKAGE)
                .isNotEmpty();

        Map<String, Set<String>> localeKeys = loadLocaleToolKeys();
        assertThat(localeKeys).as("Не найдено ни одного *.json в locales/").isNotEmpty();

        List<String> problems = new ArrayList<>();
        localeKeys.forEach(
                (locale, keys) ->
                        toolNames.stream()
                                .filter(tool -> !keys.contains(tool))
                                .forEach(
                                        tool ->
                                                problems.add(
                                                        locale
                                                                + ": нет перевода для tool '"
                                                                + tool
                                                                + "'")));

        assertThat(problems)
                .as("Пропущенные переводы инструментов:%n%s", String.join("\n", problems))
                .isEmpty();
    }

    /**
     * В словарях нет «осиротевших» ключей tools.* без соответствующего @Tool. Ловит опечатки и
     * устаревшие записи (например, после удаления инструмента).
     *
     * <p>Если намеренно держишь перевод про запас (под закомментированный инструмент) — удали этот
     * тест или вынеси исключения в allowlist.
     */
    @Test
    void localesHaveNoOrphanToolKeys() throws Exception {
        Set<String> toolNames = discoverToolNames();
        Map<String, Set<String>> localeKeys = loadLocaleToolKeys();

        List<String> orphans = new ArrayList<>();
        localeKeys.forEach(
                (locale, keys) ->
                        keys.stream()
                                .filter(key -> !toolNames.contains(key))
                                .forEach(
                                        key ->
                                                orphans.add(
                                                        locale
                                                                + ": лишний ключ tools."
                                                                + key
                                                                + " (нет такого @Tool)")));

        assertThat(orphans)
                .as("Устаревшие ключи переводов:%n%s", String.join("\n", orphans))
                .isEmpty();
    }

    /**
     * Все локали должны содержать одинаковый набор ключей tools.* (нет рассинхрона между языками).
     */
    @Test
    void allLocalesShareTheSameToolKeys() throws Exception {
        Map<String, Set<String>> localeKeys = loadLocaleToolKeys();

        Set<String> union = new TreeSet<>();
        localeKeys.values().forEach(union::addAll);

        List<String> problems = new ArrayList<>();
        localeKeys.forEach(
                (locale, keys) ->
                        union.stream()
                                .filter(key -> !keys.contains(key))
                                .forEach(
                                        key ->
                                                problems.add(
                                                        locale
                                                                + ": отсутствует ключ tools."
                                                                + key)));

        assertThat(problems)
                .as("Локали рассинхронизированы:%n%s", String.join("\n", problems))
                .isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Собирает имена всех @Tool в пакете (явное name или имя метода). */
    private static Set<String> discoverToolNames() throws ClassNotFoundException {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        // Функции не помечены @Component (создаются вручную в ChatConfig) —
        // отключаем дефолтные фильтры и берём все классы пакета.
        provider.addIncludeFilter((_, _) -> true);

        Set<String> names = new TreeSet<>();
        for (BeanDefinition bd : provider.findCandidateComponents(FUNCTIONS_PACKAGE)) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            for (Method method : clazz.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                String name = tool.name();
                names.add(name.isBlank() ? method.getName() : name);
            }
        }
        return names;
    }

    /** Читает {@code tools.*} из каждого locales/*.json. Ключ карты — код локали (имя файла). */
    private static Map<String, Set<String>> loadLocaleToolKeys() throws IOException {
        Path localesDir = locateLocalesDir();

        List<Path> jsonFiles;
        try (Stream<Path> files = Files.list(localesDir)) {
            jsonFiles =
                    files.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .toList();
        }

        Map<String, Set<String>> result = new TreeMap<>();
        for (Path file : jsonFiles) {
            JsonNode tools = MAPPER.readTree(file.toFile()).path("tools");
            Set<String> keys = new TreeSet<>();
            tools.fieldNames().forEachRemaining(keys::add);
            String locale = file.getFileName().toString().replaceFirst("\\.json$", "");
            result.put(locale, keys);
        }
        return result;
    }

    /**
     * Находит frontend/src/i18n/locales, поднимаясь вверх от рабочего каталога теста. Устойчиво к
     * запуску из корня репо или из модуля backend.
     */
    private static Path locateLocalesDir() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("frontend/src/i18n/locales");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Не найден каталог frontend/src/i18n/locales (искал вверх от " + start + ")");
    }
}
