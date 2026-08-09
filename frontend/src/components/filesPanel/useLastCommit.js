import { useEffect, useMemo, useState } from 'react';
import gitApi from '../../api/gitApi';

/**
 * Последний коммит, затронувший `path` (пустой путь — весь репозиторий).
 *
 * Отдельным запросом, а не полем в дереве: `git log` по пути стоит заметно
 * дороже листинга, а нужен он только когда раскрыта вкладка «Инфо» — компонент
 * с этим хуком до раскрытия панели не смонтирован.
 *
 * @returns {{ commit: object|null, loading: boolean, error: boolean }}
 */
export default function useLastCommit(path, enabled = true) {
  // Ответ сервера; null — запрос ещё не завершён. Пока его нет, состояние
  // выводится из пропсов при рендере: сброс эффектом дал бы лишний проход и
  // кадр с коммитом от предыдущего пути.
  const [answer, setAnswer] = useState(null);

  const [prev, setPrev] = useState({ path, enabled });
  if (prev.path !== path || prev.enabled !== enabled) {
    setPrev({ path, enabled });
    setAnswer(null);
  }

  useEffect(() => {
    if (!enabled) return undefined;
    const controller = new AbortController();

    gitApi
      .getCommits(path, 1, controller.signal)
      .then((commits) => {
        if (controller.signal.aborted) return;
        setAnswer({ commit: commits?.[0] || null, loading: false, error: false });
      })
      .catch((err) => {
        if (controller.signal.aborted || err.name === 'AbortError') return;
        setAnswer({ commit: null, loading: false, error: true });
      });

    return () => controller.abort();
  }, [path, enabled]);

  // Мемо, а не литерал: результат хука уходит в зависимости у вызывающих.
  const pending = useMemo(() => ({ commit: null, loading: enabled, error: false }), [enabled]);
  return answer ?? pending;
}
