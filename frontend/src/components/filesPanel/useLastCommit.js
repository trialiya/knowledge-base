import { useEffect, useState } from 'react';
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
  const [state, setState] = useState({ commit: null, loading: enabled, error: false });

  useEffect(() => {
    if (!enabled) {
      setState({ commit: null, loading: false, error: false });
      return undefined;
    }
    const controller = new AbortController();
    setState({ commit: null, loading: true, error: false });

    gitApi
      .getCommits(path, 1, controller.signal)
      .then((commits) => {
        if (controller.signal.aborted) return;
        setState({ commit: commits?.[0] || null, loading: false, error: false });
      })
      .catch((err) => {
        if (controller.signal.aborted || err.name === 'AbortError') return;
        setState({ commit: null, loading: false, error: true });
      });

    return () => controller.abort();
  }, [path, enabled]);

  return state;
}
