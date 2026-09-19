import { describe, it, expect } from 'vitest';
import { parseChatCommand, chatCommandBlock, isCompactCommand, CHAT_COMMAND, COMMAND_BLOCK } from './chatCommands';

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

  // То же правило разводит две команды сжатия: на `/compact-1` первая отпадает по
  // непробельному хвосту, и порядок в реестре ничего не решает.
  it('не путает `/compact-1` с `/compact` и её хвостом', () => {
    expect(parseChatCommand('/compact-1')).toEqual({ name: CHAT_COMMAND.COMPACT_1, args: '', start: 0, end: 10 });
    expect(parseChatCommand('/compact-1 про миграции')).toEqual({
      name: CHAT_COMMAND.COMPACT_1,
      args: 'про миграции',
      start: 0,
      end: 10,
    });
    expect(parseChatCommand('/сжать-1')).toEqual({ name: CHAT_COMMAND.COMPACT_1, args: '', start: 0, end: 8 });
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

// Одно правило про «пройдёт ли команда сейчас» — на композер и на отправку. Свои
// поводы отказа есть у каждой из сторон, но названы они здесь, иначе поле обещало
// бы то, чего отправка не делает.
describe('chatCommandBlock', () => {
  const compact = parseChatCommand('/compact');

  it('в начатом свободном чате ничего не мешает', () => {
    expect(chatCommandBlock(compact, { running: false, chatStarted: true })).toBeNull();
  });

  it('во время ответа команда не пройдёт', () => {
    expect(chatCommandBlock(compact, { running: true, chatStarted: true })).toBe(COMMAND_BLOCK.RUNNING);
  });

  it('в ещё не начатом чате сжимать нечего', () => {
    expect(chatCommandBlock(compact, { running: false, chatStarted: false })).toBe(COMMAND_BLOCK.NOTHING_TO_COMPACT);
  });

  it('занятость важнее: она мешает любой команде, а не только сжатию', () => {
    expect(chatCommandBlock(compact, { running: true, chatStarted: false })).toBe(COMMAND_BLOCK.RUNNING);
  });

  it('обычному вопросу не мешает ничто — он не команда', () => {
    expect(chatCommandBlock(null, { running: true, chatStarted: false })).toBeNull();
  });

  // Правило «сжимать нечего» про обе команды сжатия сразу: `/compact-1` в пустом
  // чате так же бессмысленна, и отказывает ей тот же общий признак.
  it('в ещё не начатом чате не проходит и `/compact-1`', () => {
    const compact1 = parseChatCommand('/compact-1');

    expect(isCompactCommand(compact1.name)).toBe(true);
    expect(chatCommandBlock(compact1, { running: false, chatStarted: false })).toBe(COMMAND_BLOCK.NOTHING_TO_COMPACT);
    expect(chatCommandBlock(compact1, { running: false, chatStarted: true })).toBeNull();
  });
});
