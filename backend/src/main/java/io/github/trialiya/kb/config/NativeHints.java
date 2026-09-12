package io.github.trialiya.kb.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.trialiya.kb.functions.AttachmentFunction;
import io.github.trialiya.kb.functions.DocumentFunction;
import io.github.trialiya.kb.functions.GitEditFunction;
import io.github.trialiya.kb.functions.GitFunction;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.functions.ScriptFunction;
import io.github.trialiya.kb.functions.SearchAgentFunction;
import io.github.trialiya.kb.functions.SkillFunction;
import io.github.trialiya.kb.functions.TopicFunction;
import io.github.trialiya.kb.service.chat.script.KbEditScriptApi;
import io.github.trialiya.kb.service.chat.script.KbScriptApi;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;

/**
 * Метаданные для native-image, которые статический анализ вывести не может.
 *
 * <p>Как собирается образ и чем он отличается от обычной сборки — {@code
 * docs/проект/нативный-образ-graalvm.md}.
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
 * <p>Объект {@code kb} в скриптах. {@code HostAccess.EXPLICIT} собирает список того, что видно
 * гостю, обходя рефлексией публичные методы связанного объекта и проверяя на каждом
 * {@code @HostAccess.Export}. В образе этот обход видит только зарегистрированные методы, а {@code
 * KbScriptApi} бином не является — его создаёт {@code ScriptRunner} через {@code new}, — так что
 * Spring AOT о нём не знает и не регистрирует ничего. Список выходит пустой, и любой вызов падает с
 * {@code Unknown identifier: <метод>} — притом уже в скрипте, а не на старте.
 *
 * <p>Полезные нагрузки событий SSE. {@code payload} у {@code ChatEvent} объявлен как {@code
 * Object}, поэтому Jackson сериализует фактический тип, а он не встречается ни в одной сигнатуре
 * бина — Spring AOT такие записи не регистрирует, и событие молча не уходит в браузер ({@code
 * ConversationHub#send} гасит ошибку отправки). Регистрируем пакет нагрузок целиком: какой тип
 * окажется в конверте, видно только в точке публикации.
 *
 * <p>Перечисления JGit. Конфигурацию репозитория JGit читает через {@code Config.getEnum}, а тот
 * зовёт {@code getEnumConstants()}, которому в образе нужен зарегистрированный {@code values()};
 * без него чтение падает с «Enumerated values of type ... not available». Какое перечисление
 * понадобится, решает конфигурация репозитория, а не наш код: метаданные GraalVM закрывают {@code
 * core.autocrlf} и соседей, но не {@code diff.algorithm}. Поэтому регистрируем {@code values()} у
 * всех перечислений JGit.
 *
 * <p>Своих метаданных Spring AI не поставляет, поэтому и конвертеры, и держателей приходится
 * описывать здесь. Подключено через {@code META-INF/spring/aot.factories}, а не как
 * {@code @Configuration}: хинты нужны только на этапе сборки образа, и лишний бин в контексте ради
 * них заводить незачем. На обычной JVM класс не исполняется вовсе.
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

    /** Классы, чьи объекты связываются с гостевым {@code kb} — см. {@code ScriptRunner}. */
    private static final List<Class<?>> SCRIPT_APIS =
            List.of(KbScriptApi.class, KbEditScriptApi.class);

    /** Полезные нагрузки событий чата — см. {@code ConversationHub}. */
    private static final String CHAT_EVENT_PAYLOADS =
            "classpath*:io/github/trialiya/kb/model/chat/dto/*.class";

    /** Классы JGit, среди которых ищем перечисления. */
    private static final String JGIT_CLASSES = "classpath*:org/eclipse/jgit/**/*.class";

    /** Классы SDK OpenAI, которые вообще могут прийти из ответа модели. */
    private static final String OPENAI_CLASSES = "classpath*:com/openai/**/*.class";

    private final BindingReflectionHintsRegistrar binding = new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        ClassLoader loader = classLoader == null ? ClassUtils.getDefaultClassLoader() : classLoader;
        for (Class<?> holder : TOOL_HOLDERS) {
            hints.reflection()
                    .registerType(
                            holder,
                            MemberCategory.INVOKE_DECLARED_METHODS,
                            MemberCategory.INVOKE_PUBLIC_METHODS);
            registerToolSignatures(hints, holder);
        }
        hints.reflection()
                .registerType(
                        io.github.trialiya.kb.tools.CompactToolResultConverter.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                .registerType(
                        DefaultToolCallResultConverter.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        for (Class<?> api : SCRIPT_APIS) {
            hints.reflection().registerType(api, MemberCategory.INVOKE_PUBLIC_METHODS);
        }
        registerChatEventPayloads(hints, loader);
        registerJGitEnums(hints, loader);
        registerOpenAiAnySetters(hints, loader);
    }

    /**
     * Классы по шаблону ресурсов: читаем байт-код, не загружая их.
     *
     * <p>Сканирование, а не список: и нагрузки событий, и перечисления JGit — это «всё, что лежит
     * там», а список пришлось бы сверять глазами при каждой правке соседнего кода.
     */
    private List<MetadataReader> scan(String pattern, @Nullable ClassLoader loader) {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(loader);
        MetadataReaderFactory metadataReaders = new CachingMetadataReaderFactory(resolver);
        List<MetadataReader> readers = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources(pattern)) {
                readers.add(metadataReaders.getMetadataReader(resource));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + pattern, e);
        }
        return readers;
    }

    /**
     * Что кладётся в {@code payload} события чата.
     *
     * <p>Обходим тип целиком, как и сигнатуры инструментов: нагрузки — записи, а вложенные в них
     * типы ({@code ToolInvocationMeta} и соседи) Jackson сериализует по той же рефлексии.
     */
    private void registerChatEventPayloads(RuntimeHints hints, @Nullable ClassLoader loader) {
        for (MetadataReader reader : scan(CHAT_EVENT_PAYLOADS, loader)) {
            String className = reader.getClassMetadata().getClassName();
            if (className.endsWith("package-info")) {
                continue;
            }
            binding.registerReflectionHints(
                    hints.reflection(), ClassUtils.resolveClassName(className, loader));
        }
    }

    /** {@code values()} у всех перечислений JGit — через них читается конфигурация репозитория. */
    private void registerJGitEnums(RuntimeHints hints, @Nullable ClassLoader loader) {
        for (MetadataReader reader : scan(JGIT_CLASSES, loader)) {
            ClassMetadata metadata = reader.getClassMetadata();
            if (!Enum.class.getName().equals(metadata.getSuperClassName())) {
                continue;
            }
            hints.reflection()
                    .registerType(
                            TypeReference.of(metadata.getClassName()),
                            type -> type.withMethod("values", List.of(), ExecutableMode.INVOKE));
        }
    }

    /**
     * Типы в сигнатурах {@code @Tool}: что инструмент принимает и что возвращает.
     *
     * <p>Через них ходит Jackson — разбирает аргументы вызова и сериализует результат, — и ходит
     * рефлексией, а Spring AOT их не видит: в сигнатурах бинов этих типов нет, они только здесь.
     * Для записей это ощущается особенно резко: {@code getRecordComponents()} в образе не просто
     * возвращает пустое, а бросает {@code UnsupportedFeatureError}, и ответ модели обрывается на
     * первом же вызове инструмента.
     *
     * <p>{@code BindingReflectionHintsRegistrar} обходит тип целиком, включая параметры дженериков
     * ({@code ToolResult<T>} бесполезен без своего {@code T}) и вложенные записи. {@code
     * ToolContext} пропускаем: он приходит от Spring AI, в схему инструмента не входит и через JSON
     * не проходит.
     */
    private void registerToolSignatures(RuntimeHints hints, Class<?> holder) {
        for (Method method : holder.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            if (method.getReturnType() != void.class) {
                binding.registerReflectionHints(hints.reflection(), method.getGenericReturnType());
            }
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getType() != ToolContext.class) {
                    binding.registerReflectionHints(
                            hints.reflection(), parameter.getParameterizedType());
                }
            }
        }
    }

    /**
     * Приёмники неизвестных полей в моделях SDK OpenAI.
     *
     * <p>Своих метаданных SDK поставляет много ({@code META-INF/native-image/reflect-config.json}
     * на несколько мегабайт), но сняты они агентом трассировки, и вызываемыми объявлены только те
     * методы, которые прогон агента задел. Приватный {@code putAdditionalProperty} под
     * {@code @JsonAnySetter} в этот список не попал: он срабатывает лишь тогда, когда ответ
     * содержит поле, которого в модели SDK нет. С самим OpenAI такого может не случиться никогда, а
     * вот OpenAI-совместимые провайдеры кладут в ответ свои поля постоянно — и тогда образ падает с
     * {@code MissingReflectionRegistrationError} посреди стрима.
     *
     * <p>Ищем по аннотации, а не по имени метода, и только в пакете SDK: имя — деталь его
     * реализации, а аннотация — контракт Jackson, по которому этот метод и вызывается.
     */
    private void registerOpenAiAnySetters(RuntimeHints hints, @Nullable ClassLoader loader) {
        for (MetadataReader reader : scan(OPENAI_CLASSES, loader)) {
            AnnotationMetadata metadata = reader.getAnnotationMetadata();
            if (metadata.getAnnotatedMethods(JsonAnySetter.class.getName()).isEmpty()) {
                continue;
            }
            registerAnySetters(hints, metadata.getClassName(), loader);
        }
    }

    private void registerAnySetters(
            RuntimeHints hints, String className, @Nullable ClassLoader loader) {
        final Class<?> type;
        try {
            type = ClassUtils.forName(className, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            // Класс из необязательной части SDK: нет зависимости — нет и вызова через рефлексию.
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(JsonAnySetter.class)) {
                hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
            }
        }
    }
}
