import { useCallback } from 'react';

/** Строки, между которыми ходим: их помечает сам список (см. common/sidePanel.css). */
const ITEM = '[data-ws-item]';

/** Ближайший предок, который реально прокручивается по вертикали. */
function scrollParent(el) {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const { overflowY } = window.getComputedStyle(p);
    if ((overflowY === 'auto' || overflowY === 'scroll') && p.scrollHeight > p.clientHeight) return p;
  }
  return null;
}

/**
 * Доскроллить до строки ТОЛЬКО по вертикали.
 *
 * Ни scrollIntoView, ни focus() без preventScroll для этого не годятся: обе
 * двигают и горизонталь. В дереве файлов строка растянута на всю ширину
 * раскрытого дерева (.file-tree { width: max-content }) и заведомо шире панели,
 * а «nearest» для элемента шире вьюпорта означает «прижать к начальному краю» —
 * то есть каждый шаг стрелкой сбрасывал бы горизонтальную прокрутку в ноль и
 * уводил имена глубоко вложенных файлов за границу панели. Та же ловушка
 * описана в FileTreeNode, где скролл к выбранному узлу считается вручную.
 */
function scrollRowIntoView(row) {
  const scroller = scrollParent(row);
  if (!scroller) return;
  const rowRect = row.getBoundingClientRect();
  const boxRect = scroller.getBoundingClientRect();
  if (rowRect.bottom > boxRect.bottom) scroller.scrollTop += rowRect.bottom - boxRect.bottom;
  else if (rowRect.top < boxRect.top) scroller.scrollTop -= boxRect.top - rowRect.top;
}

/**
 * Клавиатурная навигация по списку/дереву левой панели.
 *
 * Строки списков — это `<li>`/`<div>` с onClick: табом до них не добраться, на
 * Enter они не реагируют. Разложить по строкам обработчики нельзя — внутри
 * строки уже есть настоящие кнопки действий (переименовать/удалить), и вложить
 * строку в `<button>` не даёт HTML. Поэтому «одна точка входа + стрелки»:
 * контейнер списка сам по себе tab-stop, а стрелки перемещают фокус между
 * строками (у них tabIndex=-1 — программно фокусируемы, в таб-порядке нет).
 *
 * Событие слушает контейнер, а не каждая строка: строк много, они
 * перерисовываются, и обработчик на каждой — лишние замыкания на каждый рендер.
 *
 * Раскладка клавиш (как в дереве файлов IDE):
 *   ↑ / ↓        — предыдущая/следующая видимая строка
 *   Home / End   — первая/последняя
 *   Enter/Space  — открыть строку (клик по ней)
 *   → (у папки)  — раскрыть; если уже раскрыта — шаг внутрь
 *   ← (у папки)  — свернуть; иначе — шаг к родителю (по aria-level)
 *
 * Раскрытием заведует сама строка: хук кликает по её шеврону
 * (`[data-ws-chevron]`), а не дублирует логику загрузки детей.
 *
 * @returns {(e: import('react').KeyboardEvent) => void} обработчик для onKeyDown контейнера
 */
export default function useListNavigation() {
  return useCallback((e) => {
    // Модификаторы отдаём браузеру и приложению: Ctrl+F, Cmd+↓ и т.п. — не наши.
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    // Переименование по месту: стрелки и Enter принадлежат полю ввода.
    if (e.target.closest('input, textarea, [contenteditable="true"]')) return;

    const container = e.currentTarget;
    const items = Array.from(container.querySelectorAll(ITEM));
    if (items.length === 0) return;

    const current = e.target.closest(ITEM);
    const index = current ? items.indexOf(current) : -1;

    const focusAt = (i) => {
      const next = items[Math.max(0, Math.min(i, items.length - 1))];
      if (!next) return;
      next.focus({ preventScroll: true });
      scrollRowIntoView(next);
    };

    const level = (el) => Number(el?.getAttribute('aria-level') || 1);
    const expanded = (el) => el?.getAttribute('aria-expanded'); // null у листьев

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        focusAt(index + 1);
        return;
      case 'ArrowUp':
        e.preventDefault();
        // С самого контейнера (index === -1) вверх — на последнюю строку.
        focusAt(index === -1 ? items.length - 1 : index - 1);
        return;
      case 'Home':
        e.preventDefault();
        focusAt(0);
        return;
      case 'End':
        e.preventDefault();
        focusAt(items.length - 1);
        return;
      case 'ArrowRight': {
        if (!current) return;
        e.preventDefault();
        if (expanded(current) === 'false') current.querySelector('[data-ws-chevron]')?.click();
        else if (expanded(current) === 'true') focusAt(index + 1);
        return;
      }
      case 'ArrowLeft': {
        if (!current) return;
        e.preventDefault();
        if (expanded(current) === 'true') {
          current.querySelector('[data-ws-chevron]')?.click();
          return;
        }
        // Не папка (или свёрнута) — поднимаемся к родителю: ближайшая строка выше
        // с меньшим уровнем вложенности.
        const myLevel = level(current);
        for (let i = index - 1; i >= 0; i--) {
          if (level(items[i]) < myLevel) {
            focusAt(i);
            return;
          }
        }
        return;
      }
      case 'Enter':
      case ' ':
        // У строки-кнопки (список групп настроек) Enter/Space отрабатывает сам
        // браузер — второй click() открыл бы раздел дважды.
        if (!current || current.tagName === 'BUTTON') return;
        // Событие с вложенной кнопки действия (удалить/переименовать) — её же.
        if (e.target !== current) return;
        e.preventDefault();
        current.click();
        return;
      default:
    }
  }, []);
}
