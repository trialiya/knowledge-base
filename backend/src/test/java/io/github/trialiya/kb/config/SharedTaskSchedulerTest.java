package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.ClassUtils;

/**
 * Приложению нужен планировщик Spring Boot, и только он.
 *
 * <p>Общий {@code taskScheduler} автоконфигурация поднимает под условием «нет своего бина типа
 * {@link TaskScheduler} или {@link ScheduledExecutorService}». Поэтому свой бин такого типа не
 * встаёт рядом с ним, а <em>заменяет</em> его: автоконфигурация отключается целиком, наш бин
 * остаётся единственным подходящим по типу, и {@code @Scheduled} по всему коду — опрос очереди
 * эмбеддингов раз в секунду, проверка зависших, чистка кэша, обновление MCP — начинает исполняться
 * на нём. Достаточно, чтобы один из его потоков занялся чем-то долгим, и встаёт всё остальное.
 *
 * <p>Ничего в компиляторе этого не замечает, а в логах это выглядит как «эмбеддинги почему-то
 * встали». Кому нужен свой планировщик — заводит его сам и держит в себе (так делают {@code
 * ScheduledScriptService} и {@code ChatRuntimeMonitor}), а не публикует бином.
 */
class SharedTaskSchedulerTest {

    private static final String BASE_PACKAGE = "io.github.trialiya.kb";

    @Test
    void noBeanOfOursReplacesTheApplicationsTaskScheduler() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : typesIn(BASE_PACKAGE)) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Bean.class)) {
                    continue;
                }
                Class<?> returned = method.getReturnType();
                if (TaskScheduler.class.isAssignableFrom(returned)
                        || ScheduledExecutorService.class.isAssignableFrom(returned)) {
                    offenders.add(type.getSimpleName() + '#' + method.getName());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private List<Class<?>> typesIn(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            found.add(
                    ClassUtils.resolveClassName(
                            String.valueOf(definition.getBeanClassName()),
                            getClass().getClassLoader()));
        }
        return found;
    }
}
