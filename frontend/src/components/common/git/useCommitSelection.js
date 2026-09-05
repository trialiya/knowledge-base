import { useCallback, useMemo, useState } from 'react';
import { UNTRACKED_STATUS } from '@/components/filesPanel/changes/useUncommittedChanges';

/** Отмечено ли по умолчанию: всё, кроме неотслеживаемых файлов. */
const defaultChecked = (entry) => entry.status !== UNTRACKED_STATUS;

/**
 * Что из незакоммиченного войдёт в коммит.
 *
 * Хранятся не отмеченные пути, а решения человека — «этот путь я включил», «этот
 * выключил», — и отмеченное собирается из них поверх текущего списка. Список
 * приходит из общего хука и перечитывается на каждую правку файла ассистентом,
 * причём на время запроса он пуст: хранить сам выбор значило бы терять его на
 * каждом обновлении и возвращать снятые галочки обратно — а следующая попытка
 * коммита унесла бы с собой ровно то, что человек только что исключил.
 *
 * Путь, о котором решения не было, берёт значение по умолчанию: неотслеживаемый
 * (build-отчёт, черновик) не должен въехать в коммит только потому, что окно
 * было открыто.
 */
export default function useCommitSelection(entries) {
  // path → включён ли. Решения о путях, которых в списке уже нет, остаются:
  // файл, вернувшийся в список, возвращается и к тому, что о нём решили.
  const [decided, setDecided] = useState(() => new Map());

  const toggle = useCallback((paths, on) => {
    setDecided((prev) => {
      const next = new Map(prev);
      paths.forEach((path) => next.set(path, on));
      return next;
    });
  }, []);

  return useMemo(() => {
    const checked = new Set(
      entries.filter((entry) => decided.get(entry.path) ?? defaultChecked(entry)).map((entry) => entry.path),
    );
    return {
      checked,
      picked: [...checked],
      count: checked.size,
      total: entries.length,
      /** Отмечены ли все пути группы, часть или ни одного — для каталога и для «выбрать все». */
      stateOf: (paths) => {
        const on = paths.filter((path) => checked.has(path)).length;
        if (on === 0) return 'none';
        return on === paths.length ? 'all' : 'some';
      },
      toggle,
    };
  }, [entries, decided, toggle]);
}
