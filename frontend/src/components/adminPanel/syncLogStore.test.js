import { describe, it, expect } from 'vitest';
import { appendLine, logTotal, EMPTY_LOG, LOG_LIMIT } from './syncLogStore';

const progress = (path, action, message) => ({ type: 'PROGRESS', path, action, message });

const fill = (count, action = 'created') => {
  let log = EMPTY_LOG;
  for (let i = 0; i < count; i += 1) log = appendLine(log, progress(`doc-${i}`, action));
  return log;
};

describe('appendLine', () => {
  it('turns a progress frame into a log line', () => {
    const log = appendLine(EMPTY_LOG, progress('guides/setup', 'created'));

    expect(log.lines).toEqual([{ path: 'guides/setup', action: 'created', message: null }]);
    expect(log.dropped).toBe(0);
  });

  it('keeps the failure reason', () => {
    const log = appendLine(EMPTY_LOG, progress('intro', 'failed', 'System node cannot be renamed'));

    expect(log.lines[0].message).toBe('System node cannot be renamed');
  });

  // Прогресс экспорта — тот же кадр PROGRESS, но без действия: сказать о нём в
  // журнале импорта нечего.
  it('ignores a frame without an action', () => {
    expect(appendLine(EMPTY_LOG, progress('intro'))).toBe(EMPTY_LOG);
  });

  it('keeps the same node twice when it was written twice', () => {
    let log = appendLine(EMPTY_LOG, progress('intro', 'created'));
    log = appendLine(log, progress('intro', 'relinked'));

    expect(log.lines.map((l) => l.action)).toEqual(['created', 'relinked']);
  });

  it('counts successful lines past the limit instead of storing them', () => {
    const log = appendLine(fill(LOG_LIMIT), progress('one-too-many', 'created'));

    expect(log.lines).toHaveLength(LOG_LIMIT);
    expect(log.dropped).toBe(1);
    expect(logTotal(log)).toBe(LOG_LIMIT + 1);
  });

  // Ради отказов журнал и открывают — потерять их за успешными строками
  // значило бы обессмыслить раздел ровно в том прогоне, где он нужен.
  it('never drops a failure', () => {
    const log = appendLine(fill(LOG_LIMIT + 50), progress('broken', 'failed', 'boom'));

    expect(log.lines).toHaveLength(LOG_LIMIT + 1);
    expect(log.lines.at(-1)).toEqual({ path: 'broken', action: 'failed', message: 'boom' });
    expect(log.dropped).toBe(50);
  });
});
