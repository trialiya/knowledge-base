import { serialize, renderValue, normalizeTrailingSentinel, makeChipEl } from './fileChipEditorDom';

function makeRoot() {
  return document.createElement('div');
}

describe('normalizeTrailingSentinel', () => {
  it('adds a sentinel <br> after a trailing <br>', () => {
    const root = makeRoot();
    root.innerHTML = 'hello<br>';
    normalizeTrailingSentinel(root);
    expect(root.innerHTML).toBe('hello<br><br data-sentinel="1">');
  });

  it('does not add a sentinel when the trailing <br> has following content', () => {
    const root = makeRoot();
    root.innerHTML = 'line1<br>line2';
    normalizeTrailingSentinel(root);
    expect(root.innerHTML).toBe('line1<br>line2');
  });

  it('replaces a stale sentinel instead of duplicating it', () => {
    const root = makeRoot();
    root.innerHTML = 'hello<br><br data-sentinel="1">';
    normalizeTrailingSentinel(root);
    expect(root.querySelectorAll('br[data-sentinel]')).toHaveLength(1);
    expect(root.innerHTML).toBe('hello<br><br data-sentinel="1">');
  });

  it('removes the sentinel entirely when the trailing <br> gets more text after it', () => {
    const root = makeRoot();
    root.innerHTML = 'hello<br><br data-sentinel="1">';
    // Пользователь допечатал текст после sentinel-строки — она больше не хвостовая.
    root.appendChild(document.createTextNode('world'));
    normalizeTrailingSentinel(root);
    expect(root.innerHTML).toBe('hello<br>world');
  });

  it('skips a trailing empty text node left by Range#insertNode when the caret was mid-text', () => {
    // Печать внутри текстового узла расщепляет его: после вставленного <br>
    // остаётся пустой #text. Без пропуска такого узла normalizeTrailingSentinel
    // не видел бы хвостовой <br> и не проставлял sentinel — перенос строки
    // становился невидимым до следующего нажатия Shift+Enter.
    const root = makeRoot();
    root.appendChild(document.createTextNode('hello'));
    root.appendChild(document.createElement('br'));
    root.appendChild(document.createTextNode('')); // хвостовой пустой #text
    normalizeTrailingSentinel(root);
    expect(root.childNodes).toHaveLength(4);
    expect(root.lastChild.nodeName).toBe('BR');
    expect(root.lastChild.dataset.sentinel).toBe('1');
  });
});

describe('serialize', () => {
  it('serializes plain text unchanged', () => {
    const root = makeRoot();
    root.textContent = 'foo';
    expect(serialize(root)).toBe('foo');
  });

  it('serializes multi-line text with <div> blocks (execCommand insertText shape)', () => {
    const root = makeRoot();
    root.innerHTML = 'foo<div>bar</div><div>baz</div>';
    expect(serialize(root)).toBe('foo\nbar\nbaz');
  });

  it('serializes a trailing newline without doubling it (block holding only a bare <br>)', () => {
    const root = makeRoot();
    root.innerHTML = 'foo<div><br></div>';
    expect(serialize(root)).toBe('foo\n');
  });

  it('serializes a lone trailing newline from Shift+Enter (no wrapping block)', () => {
    const root = makeRoot();
    root.innerHTML = 'foo<br>';
    expect(serialize(root)).toBe('foo\n');
  });

  it('serializes a mid-text blank line as a single newline, not doubled', () => {
    const root = makeRoot();
    root.innerHTML = 'line1<br><br>line2';
    expect(serialize(root)).toBe('line1\n\nline2');
  });

  it('ignores sentinel <br> nodes', () => {
    const root = makeRoot();
    root.innerHTML = 'foo<br><br data-sentinel="1">';
    expect(serialize(root)).toBe('foo\n');
  });

  it('serializes a file chip by its data-token', () => {
    const root = makeRoot();
    root.appendChild(makeChipEl('⟦file:a.js⟧'));
    root.appendChild(document.createTextNode(' hello'));
    expect(serialize(root)).toBe('⟦file:a.js⟧ hello');
  });
});

describe('renderValue + serialize round-trip', () => {
  it.each(['foo', 'foo\nbar\nbaz', 'foo\n', 'line1\n\nline2'])('round-trips %j', (value) => {
    const root = makeRoot();
    renderValue(root, value);
    expect(serialize(root)).toBe(value);
  });

  it('strips a single leading newline (pre-existing serialize() behaviour)', () => {
    // serialize() unconditionally strips one leading '\n' — a leading blank
    // line isn't a round-trip invariant, so this is asserted explicitly rather
    // than via the round-trip table above.
    const root = makeRoot();
    renderValue(root, '\nfoo');
    expect(serialize(root)).toBe('foo');
  });

  it('round-trips a value containing a file chip token', () => {
    const root = makeRoot();
    const value = 'see ⟦file:src/App.js⟧ please';
    renderValue(root, value);
    expect(serialize(root)).toBe(value);
  });
});
