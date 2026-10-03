import { useEffect, useState } from 'react';
import gitApi from '@/api/gitApi';

/** Страница ленты: столько коммитов приходит на старте и на каждое «Показать ещё». */
export const HISTORY_PAGE = 30;

/**
 * Лента коммитов левой панели: первая страница — по ключу, следующие — по
 * кнопке, тем же обходом со смещением (`skip`), так что страницы не теряют и не
 * повторяют коммиты боковых веток.
 *
 * `scope` — только коммиты, менявшие этот путь (пусто — вся история). `rev` —
 * лента снимка: обход идёт от него, а не от HEAD. Оба токена в ключе по той же
 * причине, что у списка изменений (useSnapshotCommit): после коммита, pull или
 * fetch голова ленты — уже другой коммит.
 *
 * Неотправленные коммиты помечаются по `/outgoing`: у снимка вопроса «что уйдёт
 * при push» нет, и там его не задают. Отказ этого запроса ленту не ломает —
 * пометок просто нет.
 */
export default function useCommitHistory({ project, rev = '', scope = '', refreshToken, refsToken }) {
  const requestKey = `${refreshToken ?? 0} ${refsToken ?? 0} ${project ?? ''} ${rev}\n${scope}`;
  // { key, commits, truncated, outgoing: Set, error, more: 'loading' | 'error' | null }
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    const controller = new AbortController();
    const signal = controller.signal;
    const outgoing = rev ? Promise.resolve([]) : gitApi.getOutgoing({ limit: 100, project, signal }).catch(() => []);
    Promise.all([gitApi.getCommits(scope, { limit: HISTORY_PAGE, rev, project, signal }), outgoing])
      .then(([page, out]) =>
        setAnswer({
          key: requestKey,
          commits: page.commits ?? [],
          truncated: !!page.truncated,
          outgoing: new Set(out.map((c) => c.hash)),
          error: null,
          more: null,
        }),
      )
      .catch((error) => {
        if (signal.aborted) return;
        setAnswer({ key: requestKey, commits: [], truncated: false, outgoing: new Set(), error, more: null });
      });
    return () => controller.abort();
  }, [requestKey, project, rev, scope]);

  const fresh = answer?.key === requestKey ? answer : null;

  const loadMore = () => {
    if (!fresh || fresh.more === 'loading') return;
    const key = fresh.key;
    setAnswer((prev) => (prev?.key === key ? { ...prev, more: 'loading' } : prev));
    gitApi
      .getCommits(scope, { limit: HISTORY_PAGE, skip: fresh.commits.length, rev, project })
      .then((page) =>
        setAnswer((prev) => {
          if (prev?.key !== key) return prev;
          const seen = new Set(prev.commits.map((c) => c.hash));
          const added = (page.commits ?? []).filter((c) => !seen.has(c.hash));
          return { ...prev, commits: [...prev.commits, ...added], truncated: !!page.truncated, more: null };
        }),
      )
      .catch(() => setAnswer((prev) => (prev?.key === key ? { ...prev, more: 'error' } : prev)));
  };

  return {
    loading: !fresh,
    error: fresh?.error ?? null,
    commits: fresh?.commits ?? [],
    truncated: !!fresh?.truncated,
    outgoing: fresh?.outgoing ?? null,
    moreLoading: fresh?.more === 'loading',
    moreError: fresh?.more === 'error',
    loadMore,
  };
}
