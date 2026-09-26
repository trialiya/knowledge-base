import { useEffect, useMemo, useState } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Коммит, снимок которого показывает панель: сообщение и изменённые им файлы
 * без патчей — их спрашивают по одному, открывая файл (см. useChangeDiff).
 *
 * Один запрос на двоих: вкладке «Коммит» справа и режиму «Изменения» слева.
 * Оба токена в ключе — потому что ревизия бывает веткой или `HEAD`, и назвать
 * она может уже другой коммит: после коммита, pull или switch (`refreshToken`)
 * и после fetch (`refsToken`, для remote-веток). Тот же ключ у патча открытого
 * файла (useChangeDiff) — иначе список и diff показывали бы разные коммиты.
 *
 * Возвращает то же, что useUncommittedChanges (`tracked`/`untracked`), чтобы
 * список слева не знал, чьи изменения показывает; неотслеживаемых у коммита
 * не бывает. `enabled: false` — запроса нет вовсе (вкладка закрыта, режим
 * «Изменения» выключен).
 */
export default function useSnapshotCommit({ project, rev, refreshToken, refsToken, enabled = true }) {
  const requestKey = rev && enabled ? `${refreshToken ?? 0} ${refsToken ?? 0} ${project ?? ''} ${rev}` : null;
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
