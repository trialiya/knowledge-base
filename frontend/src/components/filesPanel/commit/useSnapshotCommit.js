import { useEffect, useMemo, useState } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Коммит, снимок которого показывает панель: сообщение и изменённые им файлы
 * без патчей — их спрашивают по одному, открывая файл (см. useChangeDiff).
 *
 * Один запрос на двоих: вкладке «Коммит» справа и режиму «Изменения» слева.
 * `refsToken` в ключе — потому что ревизия бывает веткой: после коммита или
 * pull она называет уже другой коммит.
 *
 * Возвращает то же, что useUncommittedChanges (`tracked`/`untracked`), чтобы
 * список слева не знал, чьи изменения показывает; неотслеживаемых у коммита
 * не бывает.
 */
export default function useSnapshotCommit({ project, rev, refsToken }) {
  const requestKey = rev ? `${refsToken ?? 0} ${project ?? ''} ${rev}` : null;
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    if (!requestKey) return undefined;
    const controller = new AbortController();
    gitApi
      .getCommit(rev, { project, signal: controller.signal })
      .then((commit) => setAnswer({ key: requestKey, commit }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, commit: null, error });
      });
    return () => controller.abort();
  }, [requestKey, project, rev]);

  const fresh = answer?.key === requestKey ? answer : null;

  return useMemo(() => {
    const entries = fresh?.commit?.files ?? [];
    return {
      loading: !!requestKey && !fresh,
      error: fresh?.error ?? null,
      commit: fresh?.commit ?? null,
      entries,
      tracked: entries,
      untracked: [],
    };
  }, [fresh, requestKey]);
}
