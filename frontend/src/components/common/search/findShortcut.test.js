import { isFindShortcut, isTypingTarget } from './findShortcut';

const key = (init) => ({ ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, key: '', code: '', ...init });

describe('isFindShortcut', () => {
  test('Ctrl+F и Cmd+F', () => {
    expect(isFindShortcut(key({ ctrlKey: true, key: 'f', code: 'KeyF' }))).toBe(true);
    expect(isFindShortcut(key({ metaKey: true, key: 'F', code: 'KeyF' }))).toBe(true);
  });

  /** На русской раскладке e.key даёт «а» — спасает только физическая клавиша. */
  test('нелатинская раскладка', () => {
    expect(isFindShortcut(key({ ctrlKey: true, key: 'а', code: 'KeyF' }))).toBe(true);
  });

  test('с лишними модификаторами и без модификаторов — не шорткат', () => {
    expect(isFindShortcut(key({ ctrlKey: true, shiftKey: true, key: 'f', code: 'KeyF' }))).toBe(false);
    expect(isFindShortcut(key({ key: 'f', code: 'KeyF' }))).toBe(false);
  });
});

/**
 * Слушатель бара стоит на перехвате и видит Escape раньше полей, которые ждут
 * его на всплытии: инлайн-переименование, @mention-подсказка композера.
 */
describe('isTypingTarget', () => {
  const on = (html) => {
    document.body.innerHTML = html;
    return { target: document.body.firstElementChild };
  };

  afterEach(() => {
    document.body.innerHTML = '';
  });

  test('поле ввода и contenteditable — не наши', () => {
    expect(isTypingTarget(on('<input />'))).toBe(true);
    expect(isTypingTarget(on('<textarea></textarea>'))).toBe(true);
    expect(isTypingTarget(on('<div contenteditable="true"><span>текст</span></div>'))).toBe(true);
  });

  test('поле самого бара — наше', () => {
    document.body.innerHTML = '<div data-find-bar=""><input class="find-bar__input" /></div>';
    expect(isTypingTarget({ target: document.querySelector('input') })).toBe(false);
  });

  test('обычное содержимое — наше', () => {
    expect(isTypingTarget(on('<div>строка файла</div>'))).toBe(false);
    expect(isTypingTarget({ target: null })).toBe(false);
  });
});
