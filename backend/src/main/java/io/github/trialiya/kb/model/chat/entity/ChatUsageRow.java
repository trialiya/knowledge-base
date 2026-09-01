package io.github.trialiya.kb.model.chat.entity;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Ряд чата в том виде, в каком его читает счёт токенов: тип и мета, и <b>ничего больше</b>.
 *
 * <p>Смысл — в отсутствующей колонке. Замеры лежат в мете, а рядом с ней в том же ряду лежит {@code
 * content} — целый ответ модели, иногда в десятки килобайт. Итог считается по всей истории чата
 * сразу, поэтому чтение её сущностями подняло бы в память весь разговор ради восьми чисел с ряда.
 *
 * @param type тип ряда; нужен ровно для одного правила — замер на ряду ПОЛЬЗОВАТЕЛЯ описывает не
 *     контекст чата, а окно несостоявшегося сжатия (см. {@link ChatMessageEntity#getRunUsage()}), и
 *     системной частью такой замер быть не может
 * @param meta мета ряда; {@code null} у рядов, которым её не проставляли
 */
public record ChatUsageRow(MessageType type, @Nullable ChatMessageMeta meta) {}
