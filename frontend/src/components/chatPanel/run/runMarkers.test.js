import { describe, test, expect } from 'vitest';
import { errorLabel, interruptedAnswer, interruptedNote, stoppedLabel } from './runMarkers';
import { transformPage } from './messagesPage';

/**
 * После перезагрузки оборванный ответ обязан выглядеть так же, как его показывал живой поток:
 * бэкенд хранит служебную метку, а не подпись.
 */
describe('interruptedAnswer', () => {
  test('остановленный ответ с текстом — текст и подпись через пробел, как у RUN_STOPPED', () => {
    expect(interruptedAnswer('смотрю логи\n\n[stopped]')).toEqual({ text: `смотрю логи ${stoppedLabel()}` });
  });

  test('ряд из одной метки остановки — одна подпись', () => {
    expect(interruptedAnswer('[stopped]')).toEqual({ text: stoppedLabel() });
  });

  test('упавший ответ с текстом — текст, примечание об обрыве и флаг ошибки', () => {
    expect(interruptedAnswer('смотрю логи\n\n[error]')).toEqual({
      text: `смотрю логи${interruptedNote()}`,
      error: true,
    });
  });

  test('ряд из одной метки ошибки — подпись ошибки и флаг', () => {
    expect(interruptedAnswer('[error]')).toEqual({ text: errorLabel(), error: true });
  });

  test('метка не в конце или без пустой строки перед ней — обычный текст', () => {
    expect(interruptedAnswer('про [stopped] в логах')).toBeNull();
    expect(interruptedAnswer('статус: [error]')).toBeNull();
    expect(interruptedAnswer('')).toBeNull();
  });
});

describe('transformPage — оборванный прогон', () => {
  test('метка ответа становится подписью; у вопроса текст не трогается', () => {
    const { bubbles } = transformPage([
      { id: 1, content: '[error]', type: 'USER' },
      { id: 2, content: '[error]', type: 'ASSISTANT', runId: 'r1', usage: { contextTokens: 900 } },
    ]);

    expect(bubbles[0].text).toBe('[error]');
    expect(bubbles[1]).toMatchObject({ text: errorLabel(), error: true, usage: { contextTokens: 900 } });
    expect(bubbles[1].retryMode).toBeUndefined();
  });
});
