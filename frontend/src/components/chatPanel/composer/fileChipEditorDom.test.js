import {
  serialize,
  renderValue,
  normalizeTrailingSentinel,
  makeChipEl,
  relabelChips,
  insertPlainText,
  getCaretOffset,
  placeCaretAtOffset,
} from './fileChipEditorDom';

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

  it('keeps the very same sentinel node instead of re-creating it', () => {
    // Ключевое свойство для Ctrl+Z: sentinel в хвосте мог создать сам браузер
    // (filler после insertLineBreak, см. insertPlainText). Если удалять его и
    // подставлять свой на каждом изменении, нативный стек отмены рвётся и
    // вставка перестаёт отменяться до конца.
    const root = makeRoot();
    root.innerHTML = 'hello<br><br data-sentinel="1">';
    const sentinel = root.lastChild;
    normalizeTrailingSentinel(root);
    expect(root.lastChild).toBe(sentinel);
  });

  it('drops a stale sentinel that is no longer in the tail but keeps the trailing one', () => {
    const root = makeRoot();
    root.innerHTML = 'a<br data-sentinel="1">b<br><br data-sentinel="1">';
    const tail = root.lastChild;
    normalizeTrailingSentinel(root);
    expect(root.querySelectorAll('br[data-sentinel]')).toHaveLength(1);
    expect(root.lastChild).toBe(tail);
    expect(root.innerHTML).toBe('ab<br><br data-sentinel="1">');
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

describe('relabelChips', () => {
  const labelOf = (root) => root.querySelector('.file-chip__label').textContent;

  it('names a foreign project and drops the name of the current one', () => {
    const root = makeRoot();
    root.appendChild(makeChipEl('⟦file@billing:src/a.js⟧', 'billing'));
    expect(labelOf(root)).toBe('a.js');

    relabelChips(root, 'kb');
    expect(labelOf(root)).toBe('billing · a.js');

    relabelChips(root, 'billing');
    expect(labelOf(root)).toBe('a.js');
  });

  it('keeps the token and the node itself — the caret and the undo stack live there', () => {
    const root = makeRoot();
    root.appendChild(makeChipEl('⟦file@billing:src/a.js⟧', 'billing'));
    root.appendChild(document.createTextNode(' hello'));
    const chip = root.querySelector('.file-chip');

    relabelChips(root, 'kb');

    expect(root.querySelector('.file-chip')).toBe(chip);
    expect(serialize(root)).toBe('⟦file@billing:src/a.js⟧ hello');
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

describe('insertPlainText', () => {
  // Модель поведения Chrome, снятая в самом Chromium: insertLineBreak ставит
  // <br> и добавляет за ним свой filler-<br>, а следующая вставка текста этот
  // filler убирает. Отсюда формы "a<br>b" для 'a\nb' и "a<br><br>" для 'a\n'.
  function stubExecCommand(root) {
    const calls = [];
    const dropFiller = () => {
      if (root.lastChild?.dataset?.filler) root.lastChild.remove();
    };
    document.queryCommandSupported = () => true;
    document.execCommand = (cmd, _ui, arg) => {
      calls.push(cmd === 'insertText' ? `insertText:${arg}` : cmd);
      dropFiller();
      if (cmd === 'insertText') {
        root.appendChild(document.createTextNode(arg));
      } else {
        root.appendChild(document.createElement('br'));
        const filler = document.createElement('br');
        filler.dataset.filler = '1';
        root.appendChild(filler);
      }
      return true;
    };
    return calls;
  }

  afterEach(() => {
    delete document.execCommand;
    delete document.queryCommandSupported;
  });

  it('inserts a single-line paste with one insertText and reports success', () => {
    const root = makeRoot();
    const calls = stubExecCommand(root);
    expect(insertPlainText(root, 'hello')).toBe(true);
    expect(calls).toEqual(['insertText:hello']);
    expect(serialize(root)).toBe('hello');
  });

  it('splits a multi-line paste into insertText / insertLineBreak commands', () => {
    // Каждая строка — отдельная команда execCommand, чтобы вся вставка легла в
    // нативный стек отмены и при этом оставила плоский DOM (текст + <br>), а не
    // блочные <div>, которыми execCommand('insertText') оформляет переносы.
    const root = makeRoot();
    const calls = stubExecCommand(root);
    expect(insertPlainText(root, 'a\nb\nc')).toBe(true);
    expect(calls).toEqual(['insertText:a', 'insertLineBreak', 'insertText:b', 'insertLineBreak', 'insertText:c']);
    expect(serialize(root)).toBe('a\nb\nc');
  });

  it('marks the filler <br> after a trailing newline as the sentinel', () => {
    // Без этого serialize прочитала бы filler вторым '\n', а
    // normalizeTrailingSentinel добавила бы поверх ещё один <br>.
    const root = makeRoot();
    stubExecCommand(root);
    insertPlainText(root, 'a\n');
    expect(root.lastChild.dataset.sentinel).toBe('1');
    expect(serialize(root)).toBe('a\n');
    normalizeTrailingSentinel(root);
    expect(serialize(root)).toBe('a\n');
  });

  it('leaves an untouched trailing <br> alone when the paste has no trailing newline', () => {
    const root = makeRoot();
    stubExecCommand(root);
    insertPlainText(root, 'a\nb');
    expect(root.querySelector('br[data-sentinel]')).toBeNull();
    expect(serialize(root)).toBe('a\nb');
  });

  it('declines a multi-line paste where insertLineBreak is unsupported, without inserting anything', () => {
    // Firefox: вызывающий код должен уйти на резервный путь (одна insertText +
    // перерисовка), поэтому важно, чтобы до отказа в DOM ничего не попало.
    const root = makeRoot();
    const calls = stubExecCommand(root);
    document.queryCommandSupported = () => false;
    expect(insertPlainText(root, 'a\nb')).toBe(false);
    expect(calls).toEqual([]);
    expect(root.childNodes).toHaveLength(0);
  });

  it('still handles a single-line paste where insertLineBreak is unsupported', () => {
    const root = makeRoot();
    stubExecCommand(root);
    document.queryCommandSupported = () => false;
    expect(insertPlainText(root, 'hello')).toBe(true);
    expect(serialize(root)).toBe('hello');
  });
});

function setCaret(node, offset) {
  const sel = window.getSelection();
  const range = document.createRange();
  range.setStart(node, offset);
  range.collapse(true);
  sel.removeAllRanges();
  sel.addRange(range);
}

describe('getCaretOffset + placeCaretAtOffset (paste normalization round-trip)', () => {
  it('measures the caret at the end of a <div>-wrapped multi-line paste (execCommand shape)', () => {
    const root = makeRoot();
    root.innerHTML = 'a<div>b</div>';
    document.body.appendChild(root);
    setCaret(root.querySelector('div').firstChild, 1); // caret right after "b"
    expect(getCaretOffset(root)).toBe(serialize(root).length); // "a\nb" → offset 3
    root.remove();
  });

  it('after re-flattening via renderValue, restores the caret at the same value offset', () => {
    const root = makeRoot();
    root.innerHTML = 'a<div>b</div>';
    document.body.appendChild(root);
    setCaret(root.querySelector('div').firstChild, 1);

    const offset = getCaretOffset(root);
    const v = serialize(root);
    renderValue(root, v);
    placeCaretAtOffset(root, offset);

    const sel = window.getSelection();
    const range = sel.getRangeAt(0);
    const pre = document.createRange();
    pre.selectNodeContents(root);
    pre.setEnd(range.endContainer, range.endOffset);
    // Range#toString() drops the <br> itself (no text content), so the text
    // before the caret is "a" + "b" — the caret sits right after "b", same as
    // before flattening.
    expect(pre.toString()).toBe('ab');
    root.remove();
  });

  it('puts the caret BEFORE a leading chip at offset 0, not after it', () => {
    // Значение начинается с чипа, каретка — в самом начале. Текстовой ноды
    // слева нет, поэтому этот случай ловит только ветка чипа; без явной
    // проверки нуля курсор уезжал за чип.
    const root = makeRoot();
    document.body.appendChild(root);
    renderValue(root, '⟦file:a.js⟧ tail');
    placeCaretAtOffset(root, 0);

    const range = window.getSelection().getRangeAt(0);
    expect(range.startContainer).toBe(root);
    expect(range.startOffset).toBe(0);
    root.remove();
  });

  it('a Shift+Enter right after such a paste produces a visible line on the flattened DOM', () => {
    // Pasting "a\nb" via execCommand('insertText') leaves the caret inside a
    // <div>b</div> block. normalizeTrailingSentinel only inspects root's direct
    // children, so a <br> inserted inside that block stays invisible until a
    // second Shift+Enter — the flattening (serialize → renderValue →
    // placeCaretAtOffset) is what makes the first one work.
    const root = makeRoot();
    root.innerHTML = 'a<div>b</div>';
    document.body.appendChild(root);
    setCaret(root.querySelector('div').firstChild, 1);

    const offset = getCaretOffset(root);
    renderValue(root, serialize(root));
    placeCaretAtOffset(root, offset);

    // Shift+Enter, as ChipEditor does it: a <br> at the caret, then the sentinel pass.
    const range = window.getSelection().getRangeAt(0);
    range.insertNode(document.createElement('br'));
    normalizeTrailingSentinel(root);

    expect(root.querySelector('br[data-sentinel]')).not.toBeNull();
    expect(serialize(root)).toBe('a\nb\n');
    root.remove();
  });
});
