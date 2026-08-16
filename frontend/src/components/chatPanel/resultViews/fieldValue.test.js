import { formatFieldValue } from './fieldValue';

// Форматирование значения поля общее для списка записей, дерева и шапок, поэтому
// его правила проверяются здесь, а не в тестах каждого вида.

describe('formatFieldValue — даты', () => {
  it('дата без времени печатается датой, а не полуночью', () => {
    const shown = formatFieldValue('date', '2026-07-13', 'en-US');
    // Времени в ответе не было, и придумывать его нельзя: `new Date('2026-07-13')`
    // разбирается как полночь UTC, откуда и брались «00:00» и съехавшие сутки.
    expect(shown).not.toMatch(/\d{1,2}:\d{2}/);
    expect(shown).toBe(new Date(2026, 6, 13).toLocaleDateString('en-US'));
  });

  it('дата со временем печатается целиком', () => {
    const shown = formatFieldValue('updatedAt', '2026-07-13T14:30:00', 'en-US');
    expect(shown).toBe(new Date('2026-07-13T14:30:00').toLocaleString('en-US'));
  });

  it('несуществующая дата остаётся строкой', () => {
    expect(formatFieldValue('date', '2026-13-45', 'en-US')).toBe('2026-13-45');
    expect(formatFieldValue('date', '2026-02-30', 'en-US')).toBe('2026-02-30');
  });

  it('строка, похожая на дату лишь началом, не разбирается', () => {
    expect(formatFieldValue('title', '2026-07-13 отчёт по кварталу', 'en-US')).toBe('2026-07-13 отчёт по кварталу');
  });
});

describe('formatFieldValue — остальные значения', () => {
  it('байты — через размер файла, символы — как есть', () => {
    expect(formatFieldValue('fileSize', 5218, 'en-US')).toBe('5.1 KB');
    expect(formatFieldValue('chars', 210, 'en-US')).toBe('210');
  });

  it('список объектов читается по названиям', () => {
    expect(formatFieldValue('parentList', [{ title: 'Проект' }, { title: 'Разработка' }], 'en-US')).toBe(
      'Проект / Разработка',
    );
  });

  it('вложенный объект сворачивается, а не прячется', () => {
    expect(formatFieldValue('stats', { calls: 3 }, 'en-US')).toBe('{"calls":3}');
  });
});
