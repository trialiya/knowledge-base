package io.github.trialiya.kb.support;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.LoggerFactory;

/**
 * Привязывает SLF4J к Logback до того, как начнётся прогон.
 *
 * <p>SLF4J ищет провайдера при первом обращении за логгером, и пока один поток этим занят,
 * остальным выдаётся {@code SubstituteLogger} — подмена, у которой нет ни уровня, ни аппендеров, и
 * дождаться настоящего логгера через API нельзя. Тесты идут параллельно, так что в это окно может
 * попасть класс, который читает лог, — и падает на приведении типа, а не на том, что проверяет.
 *
 * <p>Регистрируется через {@code META-INF/services}, вызывается один раз при открытии сессии
 * запуска — то есть до первого тестового класса и до появления вторых потоков.
 */
public class Slf4jBinding implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        LoggerFactory.getLogger(Slf4jBinding.class);
    }
}
