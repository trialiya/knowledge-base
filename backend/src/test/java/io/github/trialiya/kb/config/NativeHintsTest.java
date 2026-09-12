package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.chat.dto.ChatEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Списки в {@link NativeHints} не разошлись с кодом, который они описывают.
 *
 * <p>Пробел в метаданных не ломает ни старт, ни один тест: он всплывает в образе, у пользователя и
 * в стороне от причины. Проверяемые здесь два списка — держатели {@code @Tool} и носители гостевого
 * {@code kb} — ведутся руками, поэтому обе проверки ищут кандидатов сканированием: новый класс
 * попадает под них в день, когда его написали, а не после того, как о нём споткнулись.
 *
 * <p>Хинты строятся один раз на класс: {@code registerHints} обходит classpath SDK OpenAI, и второй
 * вызов стоил бы столько же, сколько первый.
 */
class NativeHintsTest {

    private static final String SCRIPT_PACKAGE = "io.github.trialiya.kb.service.chat.script";
    private static final String TOOL_PACKAGE = "io.github.trialiya.kb.functions";

    private static final RuntimeHints HINTS = new RuntimeHints();

    @BeforeAll
    static void registerHints() {
        new NativeHints().registerHints(HINTS, NativeHintsTest.class.getClassLoader());
    }

    /**
     * {@code HostAccess.EXPLICIT} строит список доступного гостю рефлексией по публичным методам
     * связанного объекта, а в образе такой обход видит только зарегистрированные методы.
     * Незамеченный метод для скрипта просто не существует — {@code Unknown identifier}.
     */
    @Test
    void everyExportedScriptMethodIsRegistered() {
        List<Method> exported = annotatedMethods(SCRIPT_PACKAGE, HostAccess.Export.class);
        assertThat(exported).as("методы под @HostAccess.Export").isNotEmpty();
        assertThat(exported)
                .allSatisfy(
                        method ->
                                assertThat(
                                                RuntimeHintsPredicates.reflection()
                                                        .onMethodInvocation(method))
                                        .as("%s#%s", method.getDeclaringClass(), method.getName())
                                        .accepts(HINTS));
    }

    /**
     * Аннотации {@code @Tool} Spring AI ищет рефлексией по методам класса. Незарегистрированный
     * держатель роняет сборку тулсета целиком: «No @Tool annotated methods found».
     */
    @Test
    void everyToolHolderIsRegistered() {
        List<Class<?>> holders =
                annotatedMethods(TOOL_PACKAGE, Tool.class).stream()
                        .<Class<?>>map(Method::getDeclaringClass)
                        .distinct()
                        .toList();
        assertThat(holders).as("классы с методами под @Tool").isNotEmpty();
        assertThat(holders)
                .allSatisfy(
                        holder ->
                                assertThat(
                                                RuntimeHintsPredicates.reflection()
                                                        .onType(holder)
                                                        .withMemberCategory(
                                                                MemberCategory
                                                                        .INVOKE_DECLARED_METHODS))
                                        .as("%s", holder)
                                        .accepts(HINTS));
    }

    /** Нагрузка события объявлена как {@code Object}, поэтому Jackson идёт по фактическому типу. */
    @Test
    void chatEventPayloadsAreRegistered() {
        assertThat(RuntimeHintsPredicates.reflection().onType(ChatEvent.class)).accepts(HINTS);
    }

    private List<Method> annotatedMethods(
            String basePackage, Class<? extends java.lang.annotation.Annotation> annotation) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        List<Method> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            Class<?> type =
                    ClassUtils.resolveClassName(
                            String.valueOf(definition.getBeanClassName()),
                            getClass().getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(annotation)) {
                    found.add(method);
                }
            }
        }
        return found;
    }
}
