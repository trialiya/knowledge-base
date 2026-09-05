import { useCallback, useMemo, useState } from 'react';
import { UNTRACKED_STATUS } from '@/components/filesPanel/changes/useUncommittedChanges';

/** Отмечено ли по умолчанию: всё, кроме неотслеживаемых файлов. */
const defaultChecked = (entry) => entry.status !== UNTRACKED_STATUS;

/**
 * Что из незакоммиченного войдёт в коммит.
 *
 * Хранится множеством отмеченных путей, а не флагом в записи: список приходит
 * из общего хука и перечитывается на каждую правку файла ассистентом, то есть
 * приезжает новым массивом. Решения человека переживают такое обновление —
 * снятая галочка остаётся снятой, — а появившийся файл получает своё значение
 * по умолчанию: неотслеживаемый (build-отчёт, черновик) не должен въехать в
 * коммит только потому, что окно было открыто.
 *
 * Пересчёт — в рендере под защитой prev-состояния, а не в эффекте: у эффекта
 * это был бы лишний проход и кадр со старым выбором (см. правила фронтенда).
 */
export default function useCommitSelection(entries) {
  const [prevEntries, setPrevEntries] = useState(entries);
  const [checked, setChecked] = useState(() => initial(entries));

  if (prevEntries !== entries) {
    setPrevEntries(entries);
    setChecked((prev) => reconcile(prev, prevEntries, entries));
  }

  const toggle = useCallback((paths, on) => {
    setChecked((prev) => {
      const next = new Set(prev);
      paths.forEach((path) => (on ? next.add(path) : next.delete(path)));
      return next;
    });
  }, []);

  return useMemo(() => {
    const picked = entries.filter((entry) => checked.has(entry.path));
    return {
      checked,
      picked: picked.map((entry) => entry.path),
      count: picked.length,
      total: entries.length,
      /** Отмечены ли все пути группы, часть или ни одного — для каталога и для «выбрать все». */
      stateOf: (paths) => {
        const on = paths.filter((path) => checked.has(path)).length;
        if (on === 0) return 'none';
        return on === paths.length ? 'all' : 'some';
      },
      toggle,
    };
  }, [entries, checked, toggle]);
}

const initial = (entries) => new Set(entries.filter(defaultChecked).map((entry) => entry.path));

/**
 * Прежний выбор, наложенный на новый список: путь, который уже видели, сохраняет
 * решение человека, новый — берёт значение по умолчанию.
 */
function reconcile(checked, before, after) {
  const known = new Set(before.map((entry) => entry.path));
  const next = new Set();
  after.forEach((entry) => {
    const keep = known.has(entry.path) ? checked.has(entry.path) : defaultChecked(entry);
    if (keep) next.add(entry.path);
  });
  return next;
}
