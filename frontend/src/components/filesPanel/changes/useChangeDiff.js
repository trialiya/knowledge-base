import { useState, useEffect } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Патч одного открытого файла — то, что центральная панель показывает в режиме
 * diff. Запрос отдельный от списка: собрать diff всего рабочего дерева ради
 * одного открытого файла значит платить за него на каждом клике по списку.
 *
 * `rev` — панель показывает снимок ревизии: тогда изменение — это то, что с
 * файлом сделал сам коммит, а не незакоммиченная правка (у снимка их не бывает).
 *
 * Ответ — `null`, если изменения у файла нет (открыли файл из обычного дерева,
 * а diff-режим остался включённым): это не ошибка, а «нечего показывать», и
 * центр говорит именно это.
 */
export default function useChangeDiff({ project, path, rev = '', refreshToken, enabled }) {
  const requestKey = enabled && path ? `${refreshToken ?? 0} ${project ?? ''} ${rev}\n${path}` : null;
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    if (!requestKey) return undefined;
    const controller = new AbortController();
    const signal = controller.signal;
    const entries = rev
      ? gitApi.getCommit(rev, { path, patch: true, project, signal }).then((commit) => commit.files ?? [])
      : gitApi.getStatus({ path, patch: true, project, signal });
    entries
      .then((found) => setAnswer({ key: requestKey, entry: found[0] ?? null }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, entry: null, error });
      });
    return () => controller.abort();
  }, [requestKey, project, path, rev]);

  const fresh = answer?.key === requestKey ? answer : null;

  return {
    rev,
    loading: !!requestKey && !fresh,
    error: fresh?.error ?? null,
    entry: fresh?.entry ?? null,
  };
}
