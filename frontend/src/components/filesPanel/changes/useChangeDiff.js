import { useState, useEffect } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Патч одного открытого файла — то, что центральная панель показывает в режиме
 * diff. Запрос отдельный от списка: собрать diff всего рабочего дерева ради
 * одного открытого файла значит платить за него на каждом клике по списку.
 *
 * Ответ — `null`, если у файла нет незакоммиченных изменений (открыли файл из
 * обычного дерева, а diff-режим остался включённым): это не ошибка, а «нечего
 * показывать», и центр говорит именно это.
 */
export default function useChangeDiff({ project, path, refreshToken, enabled }) {
  const requestKey = enabled && path ? `${refreshToken ?? 0} ${project ?? ''} ${path}` : null;
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    if (!requestKey) return undefined;
    const controller = new AbortController();
    gitApi
      .getStatus({ path, patch: true, project, signal: controller.signal })
      .then((entries) => setAnswer({ key: requestKey, entry: entries[0] ?? null }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, entry: null, error });
      });
    return () => controller.abort();
  }, [requestKey, project, path]);

  const fresh = answer?.key === requestKey ? answer : null;

  return {
    loading: !!requestKey && !fresh,
    error: fresh?.error ?? null,
    entry: fresh?.entry ?? null,
  };
}
