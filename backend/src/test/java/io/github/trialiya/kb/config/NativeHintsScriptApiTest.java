package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Каждый метод, видимый скрипту, зарегистрирован для native-image.
 *
 * <p>{@code HostAccess.EXPLICIT} строит список доступного гостю рефлексией по публичным методам
 * связанного объекта, а в образе такой обход видит только зарегистрированные методы. Незамеченный
 * метод не ломает ни старт, ни один тест — он просто не существует для скрипта, и обнаруживается
 * это как {@code Unknown identifier} у пользователя.
 *
 * <p>Классы ищем сканированием пакета, а не списком: новый носитель {@code @HostAccess.Export}
 * попадает под проверку в день, когда его написали.
 */
class NativeHintsScriptApiTest {

    private static final String SCRIPT_PACKAGE = "io.github.trialiya.kb.service.chat.script";

    @Test
    void everyExportedScriptMethodIsRegistered() {
        RuntimeHints hints = new RuntimeHints();
        new NativeHints().registerHints(hints, getClass().getClassLoader());

        List<Method> exported = exportedMethods();
        assertThat(exported).as("методы под @HostAccess.Export").isNotEmpty();
        assertThat(exported)
                .allSatisfy(
                        method ->
                                assertThat(
                                                RuntimeHintsPredicates.reflection()
                                                        .onMethodInvocation(method))
                                        .as("%s#%s", method.getDeclaringClass(), method.getName())
                                        .accepts(hints));
    }

    private List<Method> exportedMethods() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        List<Method> exported = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(SCRIPT_PACKAGE)) {
            Class<?> type =
                    ClassUtils.resolveClassName(
                            String.valueOf(definition.getBeanClassName()),
                            getClass().getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(HostAccess.Export.class)) {
                    exported.add(method);
                }
            }
        }
        return exported;
    }
}
