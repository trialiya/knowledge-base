// С каких версий открывается HistoryModal. Список истории — newest-first
// (index 0 = новейшая), инвариант выбора: база старее изменённой ⇒ baseIdx > compareIdx,
// а при baseIdx === compareIdx сравнивать не с чем и показывается одна версия.

/**
 * @param {Array<{ descriptionVersion: number }>} list история описания, newest-first
 * @param {number|null|undefined} initialVersion версия, на которую навестись
 *   (например, из правки ИИ в чате); не задана или не найдена — новейшая
 * @param {number|null|undefined} initialBaseVersion с какой версией её сравнить; не
 *   задана — с предыдущей. Задана, но в истории её нет (0 — документа ещё не было) —
 *   версия новая целиком и показывается сама, без diff
 * @returns {{ baseIdx: number, compareIdx: number, mode: 'diff' | 'compare' }}
 */
export const initialSelection = (list, initialVersion, initialBaseVersion) => {
  const lastIdx = list.length - 1;
  const indexOf = (v) => (v != null ? list.findIndex((e) => e.descriptionVersion === v) : -1);
  const targetIdx = indexOf(initialVersion);

  if (targetIdx >= 0) {
    if (initialBaseVersion != null) {
      const baseIdx = indexOf(initialBaseVersion);
      if (baseIdx > targetIdx) return { baseIdx, compareIdx: targetIdx, mode: 'diff' };
      if (baseIdx < 0) return { baseIdx: lastIdx, compareIdx: targetIdx, mode: 'compare' };
    }
    // Старейшая запись — предшественника нет, показываем её саму.
    if (targetIdx === lastIdx) return { baseIdx: targetIdx, compareIdx: targetIdx, mode: 'compare' };
    return { baseIdx: targetIdx + 1, compareIdx: targetIdx, mode: 'diff' };
  }
  if (list.length >= 2) return { baseIdx: 1, compareIdx: 0, mode: 'diff' };
  return { baseIdx: 0, compareIdx: 0, mode: 'compare' }; // сравнивать не с чем
};
