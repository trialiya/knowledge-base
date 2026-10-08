package io.github.trialiya.kb.model.chat.dto;

import org.jspecify.annotations.Nullable;

/**
 * Что пользователь выбрал для прогона в момент отправки: модель, режим, уровень рассуждений,
 * проект. {@code null} в любом поле — «не названо», как отсутствующее поле запроса: решают память
 * чата ({@code chat_topic}) и за ней конфигурация (см. {@code RunOptionsResolver}).
 *
 * <p>Записью, а не четырьмя параметрами: все четыре — строки, и в позиционном вызове любые два
 * меняются местами без единой ошибки компиляции. Та же запись — снимок выбора у сообщения в
 * очереди: если доставка случится уже после завершения текущего прогона, follow-up обязан поехать
 * на нём, а не на том, что окажется у чата к тому моменту.
 */
public record RunChoice(
        @Nullable String model,
        @Nullable String mode,
        @Nullable String reasoning,
        @Nullable String project) {

    /** Ничего не выбрано — как отсутствующие поля запроса: решают память чата и конфиг. */
    public static final RunChoice NONE = new RunChoice(null, null, null, null);
}
