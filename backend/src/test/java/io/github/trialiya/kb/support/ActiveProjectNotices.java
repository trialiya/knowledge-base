package io.github.trialiya.kb.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.service.chat.memory.ActiveProjectNotice;

/**
 * Блок активного проекта для тестов, которые проверяют не его.
 *
 * <p>{@link ChatHistoryService#promptRow} склеивает текст ряда из нескольких кусков, и этот —
 * последний. Здесь его нет вовсе: {@code null} значит «ставить некуда», и тексты рядов остаются
 * такими, какими их ждут тесты про опись вложений и нотисы. Тесту, которому важен сам блок, нужен
 * не этот помощник, а свой {@code ActiveProjectNotice}.
 */
public final class ActiveProjectNotices {

    private ActiveProjectNotices() {}

    public static ActiveProjectNotice silent() {
        final ActiveProjectNotice notice = mock(ActiveProjectNotice.class);
        when(notice.place(any(), any())).thenReturn(null);
        return notice;
    }
}
