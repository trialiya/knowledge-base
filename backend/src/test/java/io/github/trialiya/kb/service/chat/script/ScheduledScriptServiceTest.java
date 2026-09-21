package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.config.model.ScriptProperties.Schedule;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptStats;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * Расписание — решение развёртки, а не репозитория, и проверяется здесь именно это: что кривую
 * запись видно на старте, что прогон идёт read-only и что упавшая задача не уносит с собой само
 * расписание.
 */
class ScheduledScriptServiceTest {

    private final SavedScriptResolver resolver = mock(SavedScriptResolver.class);
    private final ScriptRunner runner = mock(ScriptRunner.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);

    @Test
    void registersEveryScheduleAndRunsItReadOnly() {
        givenResolvedRequest();
        when(runner.run(any(ScriptRequest.class), any())).thenReturn(result(null));
        ScheduledScriptService service =
                service(
                        new Schedule(
                                "nightly",
                                "kb",
                                "report",
                                Map.of("area", "docs"),
                                "0 0 3 * * *",
                                20));

        service.register();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Trigger.class));
        task.getValue().run();

        // Никто не смотрит — значит писать нельзя: ни диффа, ни сообщения, к которому его отнести.
        verify(resolver).resolve(eq("kb"), eq("report"), any(), eq(20), eq(false), eq(null));
        assertThat(service.statuses())
                .singleElement()
                .satisfies(
                        status -> {
                            assertThat(status.name()).isEqualTo("nightly");
                            assertThat(status.lastRun()).isNotNull();
                            assertThat(status.lastRun().ok()).isTrue();
                        });
    }

    /** Упавший скрипт — обычный исход, и расписание после него живёт дальше. */
    @Test
    void aFailedRunIsRememberedRatherThanThrown() {
        givenResolvedRequest();
        when(runner.run(any(ScriptRequest.class), any()))
                .thenReturn(result(new ScriptError(ScriptError.Kind.TIMEOUT, "Timed out", null)));
        ScheduledScriptService service = service(schedule("0 0 3 * * *"));
        service.register();

        runScheduledTask();

        assertThat(service.statuses().getFirst().lastRun()).isNotNull();
        assertThat(service.statuses().getFirst().lastRun().ok()).isFalse();
    }

    /**
     * Задача, бросившая исключение, выбрасывается планировщиком навсегда — расписание, тихо умершее
     * после одной плохой ночи, хуже того, которое каждую ночь пишет в лог отказ.
     */
    @Test
    void aThrowingRunDoesNotEscapeTheTask() {
        when(resolver.resolve(any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("Unknown script \"report\""));
        ScheduledScriptService service = service(schedule("0 0 3 * * *"));
        service.register();

        runScheduledTask();

        assertThat(service.statuses().getFirst().lastRun()).isNotNull();
        assertThat(service.statuses().getFirst().lastRun().error()).contains("Unknown script");
    }

    /** Конфигурация развёртки: кривую запись видно на старте, а не через месяц по её молчанию. */
    @Test
    void aBadEntryFailsTheStart() {
        assertThatThrownBy(() -> service(schedule("каждую ночь")).register())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cron");

        assertThatThrownBy(
                        () ->
                                service(
                                                new Schedule(
                                                        null,
                                                        null,
                                                        "attachment:12",
                                                        Map.of(),
                                                        "0 0 3 * * *",
                                                        null))
                                        .register())
                .hasMessageContaining("not to a clock");

        assertThatThrownBy(
                        () -> service(schedule("0 0 3 * * *"), schedule("0 0 4 * * *")).register())
                .hasMessageContaining("duplicate name");
    }

    /** Расписание без песочницы — опечатка в конфигурации, а не молчаливо мёртвая задача. */
    @Test
    void schedulesWithoutTheSandboxFailTheStart() {
        ScheduledScriptService service =
                new ScheduledScriptService(
                        properties(false, List.of(schedule("0 0 3 * * *"))),
                        resolver,
                        runner,
                        taskScheduler);

        assertThatThrownBy(service::register).hasMessageContaining("kb.script.enabled=false");
    }

    @Test
    void noSchedulesMeansNothingIsRegistered() {
        service().register();

        assertThat(service().statuses()).isEmpty();
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private void runScheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Trigger.class));
        task.getValue().run();
    }

    private void givenResolvedRequest() {
        when(resolver.resolve(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(
                        new ScriptRequest(
                                new ScriptSource("return 1;", "tools/report.js", null),
                                ScriptArgs.none(),
                                null,
                                true,
                                null,
                                "kb"));
    }

    private static Schedule schedule(String cron) {
        return new Schedule("nightly", "kb", "report", Map.of(), cron, null);
    }

    private ScheduledScriptService service(Schedule... schedules) {
        return new ScheduledScriptService(
                properties(true, List.of(schedules)), resolver, runner, taskScheduler);
    }

    private static ScriptProperties properties(boolean enabled, List<Schedule> schedules) {
        return new ScriptProperties(
                enabled, true, true, false, null, null, null, null, schedules, null, null, null,
                null);
    }

    private static ScriptResult result(ScriptError error) {
        return new ScriptResult(
                "kb",
                null,
                error == null ? "ok" : null,
                List.of(),
                new ScriptStats(1, 10, 1, 0, 5),
                error,
                List.of(),
                List.of());
    }
}
