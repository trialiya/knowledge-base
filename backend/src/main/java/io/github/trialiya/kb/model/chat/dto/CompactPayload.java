package io.github.trialiya.kb.model.chat.dto;

/**
 * Нагрузка события {@link ChatEventType#COMPACT_DONE}: чем закончилось сжатие контекста.
 *
 * @param messages сколько сообщений перестало ехать модели — живое окно (включая протокольные
 *     TOOL-строки и уже существовавшие сводки) плюс сама команда {@code /compact}: её текст в
 *     сжатие не попал, но помечается сжатой вместе с окном, и дальше модель её тоже не увидит
 * @param summaryChars длина получившейся сводки в символах — вместе с {@code messages} это ответ на
 *     «во сколько раз сжали», который иначе виден только в логах
 */
public record CompactPayload(int messages, int summaryChars) {}
