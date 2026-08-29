package io.github.trialiya.kb.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.service.chat.memory.ActiveProjectNotice;

/**
 * Блок активного проекта для тестов, которые проверяют не его.
 *
 * <p>{@link ChatHistoryService#promptRow} склеивает текст ряда из нескольких кусков, и этот —
 * последний. Голый мок отдал бы {@code null}, то есть уронил бы сборку текста в каждом тесте,
 * которому нужны опись вложений или нотисы; поэтому здесь он замолчан явно. Тесту, которому важен
 * сам блок, нужен не этот помощник, а свой {@code ActiveProjectNotice}.
 */
public final class ActiveProjectNotices {

    private ActiveProjectNotices() {}

    public static ActiveProjectNotice silent() {
        final ActiveProjectNotice notice = mock(ActiveProjectNotice.class);
        when(notice.render(any(), any())).thenReturn("");
        return notice;
    }
}
