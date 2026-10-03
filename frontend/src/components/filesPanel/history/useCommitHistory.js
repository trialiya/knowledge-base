import { useEffect, useMemo, useState } from 'react';
import gitApi from '@/api/gitApi';
import useOutgoingCommits from '@/components/common/git/useOutgoingCommits';

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
 * Неотправленные коммиты помечаются по тому же списку, что у окна push
 * (useOutgoingCommits), отдельным запросом: лента его не ждёт, пометки
 * появляются, когда он ответит, а его отказ просто оставляет ленту без них. У
 * снимка вопроса «что уйдёт при push» нет, и там его не задают.
 */
export default function useCommitHistory({ project, rev = '', scope = '', refreshToken, refsToken }) {
  const requestKey = `${refreshToken ?? 0} ${refsToken ?? 0} ${project ?? ''} ${rev}\n${scope}`;
  // { key, commits, truncated, error, more: 'loading' | 'error' | null }
  const [answer, setAnswer] = useState(null);
  const out = useOutgoingCommits({ project, refreshToken: `${refreshToken ?? 0}.${refsToken ?? 0}`, enabled: !rev });
  const outgoing = useMemo(() => new Set(out.commits.map((c) => c.hash)), [out.commits]);

  useEffect(() => {
    const controller = new AbortController();
    const signal = controller.signal;
    gitApi
      .getCommits(scope, { limit: HISTORY_PAGE, rev, project, signal })
      .then((page) =>
        setAnswer({
          key: requestKey,
          commits: page.commits ?? [],
          truncated: !!page.truncated,
          error: null,
          more: null,
        }),
      )
      .catch((error) => {
        if (signal.aborted) return;
        setAnswer({ key: requestKey, commits: [], truncated: false, error, more: null });
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
          // Пустая страница — конец, даже если бэкенд говорит «могут быть ещё»: обход
          // ограничен, и смещение за его пределом отвечает пусто с truncated, так что
          // кнопка иначе осталась бы и спрашивала то же самое без конца.
          const truncated = !!page.truncated && added.length > 0;
          return { ...prev, commits: [...prev.commits, ...added], truncated, more: null };
        }),
      )
      .catch(() => setAnswer((prev) => (prev?.key === key ? { ...prev, more: 'error' } : prev)));
  };

  return {
    loading: !fresh,
    error: fresh?.error ?? null,
    commits: fresh?.commits ?? [],
    truncated: !!fresh?.truncated,
    outgoing,
    moreLoading: fresh?.more === 'loading',
    moreError: fresh?.more === 'error',
    loadMore,
  };
}
