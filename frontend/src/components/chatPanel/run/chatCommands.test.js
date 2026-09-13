import { describe, it, expect } from 'vitest';
import { parseChatCommand, CHAT_COMMAND } from './chatCommands';

describe('parseChatCommand', () => {
  it('распознаёт команду без хвоста', () => {
    expect(parseChatCommand('/compact')).toEqual({ name: CHAT_COMMAND.COMPACT, args: '', start: 0, end: 8 });
  });

  it('отдаёт хвост команды как аргументы', () => {
    expect(parseChatCommand('/compact разбор миграций')).toEqual({
      name: CHAT_COMMAND.COMPACT,
      args: 'разбор миграций',
      start: 0,
      end: 8,
    });
  });

  it('принимает русский синоним и не смотрит на регистр', () => {
    expect(parseChatCommand('/Сжать  подробнее про тесты')).toEqual({
      name: CHAT_COMMAND.COMPACT,
      args: 'подробнее про тесты',
      start: 0,
      end: 6,
    });
  });

  it('переносит хвост со следующей строки', () => {
    expect(parseChatCommand('/compact\nчто важно сохранить')).toEqual({
      name: CHAT_COMMAND.COMPACT,
      args: 'что важно сохранить',
      start: 0,
      end: 8,
    });
  });

  // Слово, начинающееся так же, командой не становится: иначе вопрос про сам
  // компактор молча превратился бы в сжатие чата.
  it('не срабатывает на слове с тем же началом', () => {
    expect(parseChatCommand('/compactor как устроен?')).toBeNull();
  });

  // Границы триггера — то, что подсвечивают композер и пузырь ленты: ведущие
  // пробелы в команду не входят, хвост тоже.
  it('отдаёт границы самого триггера, без ведущих пробелов', () => {
    const text = '  /compact про поиск';
    const { start, end } = parseChatCommand(text);

    expect(text.slice(start, end)).toBe('/compact');
  });

  it('не срабатывает посреди сообщения', () => {
    expect(parseChatCommand('расскажи, что делает /compact')).toBeNull();
    expect(parseChatCommand('')).toBeNull();
  });
});
