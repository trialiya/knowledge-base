import { useEffect, useState } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Коммиты, которые отправит push, — то, что перечисляет окно push.
 *
 * Спрашивается только при открытом окне: до него ответ на вопрос «что уедет»
 * никому не нужен, а счётчик «↑» рядом с веткой приходит вместе со статусом и
 * стоит ноль дополнительных запросов.
 *
 * Ответ вместе с ключом, которому он принадлежит, — как в useUncommittedChanges:
 * отдельный флаг loading был бы setState из эффекта, то есть лишний проход
 * рендера на каждое обновление.
 */
export default function useOutgoingCommits({ project, refreshToken, enabled }) {
  const requestKey = enabled ? `${refreshToken ?? 0} ${project ?? ''}` : null;
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    if (!requestKey) return undefined;
    const controller = new AbortController();
    gitApi
      // Столько же, сколько отдаёт бэкенд максимум: список — это ответ на «что
      // именно уедет», и обрезанный вдвое он отвечает на него неправдой.
      .getOutgoing({ limit: 100, project, signal: controller.signal })
      .then((commits) => setAnswer({ key: requestKey, commits }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, commits: [], error });
      });
    return () => controller.abort();
  }, [requestKey, project]);

  const fresh = answer?.key === requestKey ? answer : null;

  return {
    loading: !!requestKey && !fresh,
    error: fresh?.error ?? null,
    commits: fresh?.commits ?? [],
  };
}
