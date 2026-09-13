import { renderHook } from '@testing-library/react';
import { useHighlightOwner } from './useMatchHighlight';

/**
 * Реестр подсветки: имена глобальны для документа, поэтому владельцы объявляют
 * свои Range'и, а в имена уходит объединение. Проверяется само это свойство и
 * то, что объявление без изменений не перекрашивает — `paint` пересобирает
 * Highlight по всем именам сразу, а композер шлёт своё на каждый символ.
 */

class FakeHighlight {
  constructor(...ranges) {
    this.ranges = ranges;
  }
}

let host;

beforeEach(() => {
  window.CSS = { ...window.CSS, highlights: new Map() };
  window.Highlight = FakeHighlight;
  host = document.createElement('div');
  host.textContent = 'один два три';
  document.body.append(host);
});

afterEach(() => host.remove());

/** Свежий Range на тех же узле и смещениях — как его пересобрал бы вызывающий. */
const range = (from, to) => {
  const r = document.createRange();
  r.setStart(host.firstChild, from);
  r.setEnd(host.firstChild, to);
  return r;
};

const owner = () => renderHook(() => useHighlightOwner()).result.current;
const painted = (name) => window.CSS.highlights.get(name);

describe('useHighlightOwner', () => {
  it('объявленное уходит в имя, снятое — стирается', () => {
    const publish = owner();

    publish({ 'kb-find': [range(0, 4)] });
    expect(painted('kb-find').ranges).toHaveLength(1);

    publish({ 'kb-find': [] });
    expect(painted('kb-find')).toBeUndefined();
  });

  it('в имени лежит объединение владельцев, и уход одного не гасит другого', () => {
    const first = owner();
    const second = owner();

    first({ 'kb-find': [range(0, 4)] });
    second({ 'kb-find': [range(5, 8)] });
    expect(painted('kb-find').ranges).toHaveLength(2);

    first({ 'kb-find': [] });
    expect(painted('kb-find').ranges).toHaveLength(1);
  });

  it('имена владельцев не смешиваются', () => {
    const publish = owner();

    publish({ 'kb-find': [range(0, 4)], 'kb-composer-command': [range(5, 8)] });

    expect(painted('kb-find').ranges).toHaveLength(1);
    expect(painted('kb-composer-command').ranges).toHaveLength(1);
  });

  it('повторное объявление того же не перекрашивает', () => {
    const publish = owner();

    publish({ 'kb-composer-command': [range(0, 4)] });
    const first = painted('kb-composer-command');
    publish({ 'kb-composer-command': [range(0, 4)] });

    expect(painted('kb-composer-command')).toBe(first);
  });

  it('пустое объявление от владельца, которому нечего было снимать, тоже не перекрашивает', () => {
    const other = owner();
    other({ 'kb-find': [range(0, 4)] });
    const first = painted('kb-find');

    owner()({ 'kb-composer-command': [] });

    expect(painted('kb-find')).toBe(first);
  });
});
