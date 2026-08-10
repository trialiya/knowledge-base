import { parsePlaceholders, fillPlaceholders, splitPhrase, PLACEHOLDER_TYPES } from './phrasePlaceholders';

describe('parsePlaceholders', () => {
  it('returns nothing for text without placeholders', () => {
    expect(parsePlaceholders('просто текст')).toEqual([]);
    expect(parsePlaceholders('')).toEqual([]);
    expect(parsePlaceholders(null)).toEqual([]);
  });

  it('reads an untyped placeholder as a string field', () => {
    expect(parsePlaceholders('Проверь {{файл}}')).toEqual([{ raw: '{{файл}}', label: 'файл', type: 'string' }]);
  });

  it('reads the type after the colon and trims the label', () => {
    expect(parsePlaceholders('{{ Имя файла : file }}')).toEqual([
      { raw: '{{ Имя файла : file }}', label: 'Имя файла', type: 'file' },
    ]);
  });

  it.each(PLACEHOLDER_TYPES)('recognises the %s type', (type) => {
    expect(parsePlaceholders(`{{X:${type}}}`)[0].type).toBe(type);
  });

  it('matches the type case-insensitively', () => {
    expect(parsePlaceholders('{{X:FILE}}')[0].type).toBe('file');
  });

  // Опечатка в типе не должна ронять плейсхолдер в невидимку: он остаётся полем,
  // просто текстовым.
  it('falls back to a string field on an unknown type, keeping the whole label', () => {
    expect(parsePlaceholders('{{Файл:fiel}}')).toEqual([{ raw: '{{Файл:fiel}}', label: 'Файл:fiel', type: 'string' }]);
  });

  it('keeps a colon that is part of the label', () => {
    expect(parsePlaceholders('{{Время 10:30}}')).toEqual([
      { raw: '{{Время 10:30}}', label: 'Время 10:30', type: 'string' },
    ]);
  });

  it('collapses repeats of the same literal into one field', () => {
    expect(parsePlaceholders('{{A:file}} и снова {{A:file}}')).toHaveLength(1);
  });

  it('keeps the same label with different types apart', () => {
    expect(parsePlaceholders('{{A}} {{A:file}}')).toEqual([
      { raw: '{{A}}', label: 'A', type: 'string' },
      { raw: '{{A:file}}', label: 'A', type: 'file' },
    ]);
  });

  it('preserves the order of first appearance', () => {
    expect(parsePlaceholders('{{второй}} {{первый}}').map((p) => p.label)).toEqual(['второй', 'первый']);
  });

  it('ignores empty and unclosed braces', () => {
    expect(parsePlaceholders('{{}} {{ незакрытый')).toEqual([]);
  });

  // Одинокая «{{» не должна съедать абзац до следующей «}}» этажом ниже.
  it('does not let a placeholder span a line break', () => {
    expect(parsePlaceholders('{{начало\nконец}}')).toEqual([]);
  });
});

describe('splitPhrase', () => {
  it('keeps the text around a placeholder', () => {
    expect(splitPhrase('до {{A:number}} после')).toEqual([
      { text: 'до ' },
      { raw: '{{A:number}}', label: 'A', type: 'number' },
      { text: ' после' },
    ]);
  });

  // В отличие от parsePlaceholders повторы здесь нужны: превью рисует фразу
  // целиком, и второе вхождение должно встать на своё место.
  it('repeats a placeholder that occurs twice', () => {
    expect(splitPhrase('{{A}} и {{A}}').filter((p) => p.raw)).toHaveLength(2);
  });

  it('returns the whole text as one part when there is nothing to substitute', () => {
    expect(splitPhrase('просто текст')).toEqual([{ text: 'просто текст' }]);
    expect(splitPhrase('')).toEqual([]);
  });
});

describe('fillPlaceholders', () => {
  it('substitutes every occurrence of a literal', () => {
    expect(fillPlaceholders('{{A:file}} и {{A:file}}', { '{{A:file}}': 'X' })).toBe('X и X');
  });

  it('leaves placeholders without a value untouched', () => {
    expect(fillPlaceholders('{{A}} {{B}}', { '{{A}}': 'X' })).toBe('X {{B}}');
  });

  it('treats an empty string as a value, not as a gap', () => {
    expect(fillPlaceholders('до {{A}} после', { '{{A}}': '' })).toBe('до  после');
  });

  it('returns the text unchanged when there is nothing to fill', () => {
    expect(fillPlaceholders('просто текст', {})).toBe('просто текст');
  });
});
