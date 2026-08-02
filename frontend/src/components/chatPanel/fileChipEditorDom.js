// ─── DOM ⇄ chip-editor model ───────────────────────────────────────────────
// The composer is a contentEditable div; this module builds/reads its DOM so
// the component only wires events, never touches nodes directly.

import { parseToken, parseDocToken, parseDocRefToken, baseName, TOKEN_RE } from './fileChips';

// ── Сериализация DOM ⇄ плоская строка с токенами ───────────────────────────────

function serializeNode(node) {
  if (node.nodeType === Node.TEXT_NODE) return node.nodeValue;
  if (node.nodeType !== Node.ELEMENT_NODE) return '';
  if (node.classList?.contains('file-chip')) return node.dataset.token || '';
  if (node.tagName === 'BR') return node.dataset?.sentinel ? '' : '\n';
  let inner = '';
  node.childNodes.forEach((c) => (inner += serializeNode(c)));
  if (/^(DIV|P)$/.test(node.tagName)) {
    // Блок с единственным <br> = пустая строка: ведущий '\n' её уже задаёт, а сам
    // <br> — filler, который браузер вставляет для видимости строки (так, например,
    // execCommand('insertText') оформляет хвостовой/пустой перенос). Без этой ветки
    // и блок, и вложенный <br> дали бы по '\n' → двойной перевод строки.
    if (node.childNodes.length === 1 && node.firstChild.nodeName === 'BR' && !node.firstChild.dataset?.sentinel) {
      return '\n';
    }
    return '\n' + inner;
  }
  return inner;
}

/** Плоская строка-значение (с токенами) из DOM редактора. */
export function serialize(root) {
  let out = '';
  root.childNodes.forEach((c) => (out += serializeNode(c)));
  return out.replace(/^\n/, '');
}

function makeDocChipEl(token, { id, title }, refOnly) {
  const chip = document.createElement('span');
  chip.className = 'file-chip file-chip--doc' + (refOnly ? ' file-chip--ref' : '');
  chip.contentEditable = 'false';
  chip.dataset.token = token;
  chip.title = `${title} (#${id})`;

  const icon = document.createElement('span');
  icon.className = 'file-chip__icon';
  icon.textContent = refOnly ? '📎' : '📋';

  const label = document.createElement('span');
  label.className = 'file-chip__label';
  label.textContent = title;

  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'file-chip__remove';
  remove.textContent = '×';
  remove.tabIndex = -1;

  chip.append(icon, label, remove);
  return chip;
}

/** Построить DOM-элемент чипа из строки-токена. */
export function makeChipEl(token) {
  const docRefParsed = parseDocRefToken(token);
  if (docRefParsed) return makeDocChipEl(token, docRefParsed, true);

  const docParsed = parseDocToken(token);
  if (docParsed) return makeDocChipEl(token, docParsed, false);

  const parsed = parseToken(token);
  const path = parsed?.path ?? token;
  const range = parsed?.from != null ? `:${parsed.from}-${parsed.to}` : '';
  const refOnly = parsed?.refOnly ?? false;

  const chip = document.createElement('span');
  chip.className = 'file-chip' + (refOnly ? ' file-chip--ref' : '');
  chip.contentEditable = 'false';
  chip.dataset.token = token;
  chip.dataset.path = path;
  chip.title = path + range;

  const icon = document.createElement('span');
  icon.className = 'file-chip__icon';
  icon.textContent = refOnly ? '📎' : '📄';

  const label = document.createElement('span');
  label.className = 'file-chip__label';
  label.textContent = baseName(path) + range;

  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'file-chip__remove';
  remove.textContent = '×';
  remove.tabIndex = -1;

  chip.append(icon, label, remove);
  return chip;
}

/** Вставить текст с переносами как чередование text-нодов и &lt;br&gt;. */
function appendWithBreaks(parent, text) {
  const parts = text.split('\n');
  for (let i = 0; i < parts.length; i++) {
    if (parts[i]) parent.appendChild(document.createTextNode(parts[i]));
    if (i < parts.length - 1) parent.appendChild(document.createElement('br'));
  }
}

/**
 * Держим ровно один хвостовой sentinel-<br> — когда последний узел редактора
 * это обычный <br>. Хвостовой <br> сам по себе не создаёт видимой пустой
 * строки: браузеру нужен следующий узел, на котором «стоит» новая строка;
 * sentinel и есть этот filler (в сериализации он игнорируется). Для <br> в
 * середине (за ним есть контент) filler не нужен — иначе он рисует лишнюю
 * пустую строку (например, Shift+Enter в начале второй строки давал две).
 *
 * Пустой редактор с одиноким <br> (заглушка браузера после удаления всего
 * текста) тоже получит sentinel, но его убирает очистка пустого поля в
 * ChipEditor.handleInput — она срабатывает только на реальный input, тогда
 * как Shift+Enter (input не порождает) оставляет sentinel и первый перенос
 * строки виден сразу.
 */
export function normalizeTrailingSentinel(root) {
  // Идём с конца, пропуская то, что не считается хвостом:
  //  • пустые текстовые узлы — Range#insertNode при каретке внутри текста
  //    расщепляет его, оставляя после вставленного <br> пустой #text (при
  //    реальной печати каретка всегда внутри текста, и без пропуска хвостовой
  //    <br> оставался без sentinel, то есть невидимым);
  //  • уже стоящие sentinel'ы — их наличие и решаем ниже.
  let existing = null;
  let last = root.lastChild;
  while (last) {
    if (last.nodeType === Node.TEXT_NODE && !last.nodeValue) {
      last = last.previousSibling;
      continue;
    }
    if (last.nodeName === 'BR' && last.dataset?.sentinel) {
      if (!existing) existing = last;
      last = last.previousSibling;
      continue;
    }
    break;
  }
  const needed = last?.nodeName === 'BR';

  // Уже стоящий в хвосте sentinel оставляем как есть, а не пересоздаём:
  // удалить его — значит выдернуть узел, который мог создать сам браузер в ходе
  // редактирующей команды (filler после insertLineBreak, см. insertPlainText).
  // Такое удаление рвёт нативный стек отмены, и Ctrl+Z перестаёт доматывать
  // вставку до конца.
  const keep = needed ? existing : null;
  root.querySelectorAll('br[data-sentinel]').forEach((s) => {
    if (s !== keep) s.remove();
  });

  if (needed && !keep) {
    const sentinel = document.createElement('br');
    sentinel.dataset.sentinel = '1';
    root.appendChild(sentinel);
  }
}

/** Отрисовать плоскую строку value в DOM editor (текстовые узлы + чипы). */
export function renderValue(root, value) {
  root.textContent = '';
  let last = 0;
  for (const m of value.matchAll(TOKEN_RE)) {
    if (m.index > last) appendWithBreaks(root, value.slice(last, m.index));
    root.appendChild(makeChipEl(m[0]));
    last = m.index + m[0].length;
  }
  if (last < value.length) appendWithBreaks(root, value.slice(last));
  // Trailing \n needs a sentinel <br> so the cursor sits visibly on the new line.
  normalizeTrailingSentinel(root);
}

/**
 * Вставить plain-text в позицию каретки, не потеряв нативный стек отмены.
 *
 * Вставка идёт ТОЛЬКО через execCommand: браузер выбрасывает стек отмены, как
 * только скрипт сам пересобирает узлы, на которые ссылаются его шаги, — а
 * renderValue со своим `textContent = ''` делает ровно это, и Ctrl+Z после
 * вставки переставал что-либо отменять.
 *
 * Переносы строк тоже ставит execCommand — 'insertLineBreak' вместо '\n' в
 * тексте, потому что на многострочном тексте execCommand('insertText')
 * заворачивает строки в блочные <div>, а плоский DOM (текст + <br>) нужен и
 * normalizeTrailingSentinel, и Shift+Enter-обработчику, и placeCaretAtOffset.
 * Идущие подряд команды браузер склеивает в один шаг отмены, так что вся
 * вставка отменяется одним Ctrl+Z.
 *
 * @returns {boolean} false — многострочный текст там, где 'insertLineBreak' не
 *   поддержан (Firefox); вставку в этом случае делает вызывающий код сам.
 */
export function insertPlainText(root, text) {
  const lines = text.split('\n');
  if (lines.length > 1 && !document.queryCommandSupported?.('insertLineBreak')) return false;

  const tailBefore = root.lastChild;
  lines.forEach((line, i) => {
    if (i > 0) document.execCommand('insertLineBreak');
    if (line) document.execCommand('insertText', false, line);
  });

  // Хвостовой перенос браузер дополняет своим filler-<br> — тем же по смыслу,
  // что наш sentinel (без следующего узла пустая строка не видна). Помечаем его
  // как sentinel, иначе serialize прочитает filler вторым '\n', а
  // normalizeTrailingSentinel добавит поверх ещё один <br>. Filler узнаём по
  // тому, что хвостом редактора этот <br> раньше не был.
  const tail = root.lastChild;
  if (text.endsWith('\n') && tail !== tailBefore && tail?.nodeName === 'BR') {
    tail.dataset.sentinel = '1';
  }

  return true;
}

export function placeCaretEnd(root) {
  const sel = window.getSelection();
  const range = document.createRange();
  range.selectNodeContents(root);
  range.collapse(false);
  sel.removeAllRanges();
  sel.addRange(range);
}

/**
 * Смещение каретки в терминах serialize()-значения: сколько символов
 * итоговой value-строки лежит перед текущей позицией курсора. Используется
 * ДО перерисовки DOM (см. handlePaste в ChipEditor) — поэтому фрагмент может
 * содержать блочные <div>, которые оставляет execCommand('insertText'), и мы
 * переиспользуем serialize() (она их уже умеет читать), а не пишем отдельный
 * плоский обход.
 */
export function getCaretOffset(root) {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0) return null;
  const range = sel.getRangeAt(0);
  if (!root.contains(range.endContainer)) return null;

  const preRange = document.createRange();
  preRange.selectNodeContents(root);
  preRange.setEnd(range.endContainer, range.endOffset);

  const tmp = document.createElement('div');
  tmp.appendChild(preRange.cloneContents());
  return serialize(tmp).length;
}

/**
 * Поставить каретку в позицию, соответствующую смещению в serialize()-значении,
 * ПОСЛЕ renderValue — то есть в заведомо плоском DOM (текстовые узлы, <br>,
 * чип-спаны прямо под root, без блочных обёрток). Пара к getCaretOffset:
 * снимаем смещение на «грязном» DOM, рисуем плоский DOM из value, ставим
 * курсор обратно по тому же смещению.
 */
export function placeCaretAtOffset(root, offset) {
  const sel = window.getSelection();
  if (!sel) return;
  const range = document.createRange();
  let remaining = offset;

  for (const node of root.childNodes) {
    if (node.nodeType === Node.TEXT_NODE) {
      const len = node.nodeValue.length;
      if (remaining <= len) {
        range.setStart(node, remaining);
        range.collapse(true);
        sel.removeAllRanges();
        sel.addRange(range);
        return;
      }
      remaining -= len;
      continue;
    }
    if (node.nodeType === Node.ELEMENT_NODE && node.classList?.contains('file-chip')) {
      const len = (node.dataset.token || '').length;
      // Ровно на границе перед чипом каретка встаёт ПЕРЕД ним: иначе вставка в
      // самое начало значения, которое начинается с чипа, уводила курсор за чип
      // (текстовой ноды слева, которая поймала бы этот случай раньше, там нет).
      if (remaining <= 0) {
        range.setStartBefore(node);
        range.collapse(true);
        sel.removeAllRanges();
        sel.addRange(range);
        return;
      }
      if (remaining <= len) {
        range.setStartAfter(node);
        range.collapse(true);
        sel.removeAllRanges();
        sel.addRange(range);
        return;
      }
      remaining -= len;
      continue;
    }
    if (node.nodeName === 'BR') {
      if (node.dataset?.sentinel) continue; // не считается символом value
      if (remaining <= 0) {
        range.setStartBefore(node);
        range.collapse(true);
        sel.removeAllRanges();
        sel.addRange(range);
        return;
      }
      remaining -= 1; // '\n'
      continue;
    }
  }
  placeCaretEnd(root);
}
