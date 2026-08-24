import { useState, useEffect, useMemo } from 'react';
import gitApi from '@/api/gitApi';

/** Статус неотслеживаемого файла — тот же, что отдаёт бэкенд (см. GitDiffEntry). */
export const UNTRACKED_STATUS = 'U';

/**
 * Незакоммиченные изменения рабочего дерева для левой панели.
 *
 * Список приходит без патчей: строке нужны только статус и счётчики, а патч
 * запрашивается по одному файлу при открытии (см. useChangeDiff). Отслеживаемые
 * и неотслеживаемые разделены здесь, а не в разметке: разделение — это правило
 * про данные (статус 'U'), и обеим раскладкам, плоской и иерархической, оно
 * нужно одинаковым.
 *
 * `refreshToken` — тот же внешний сигнал «репозиторий мог измениться», что и у
 * дерева файлов (правка файла инструментом чата): рабочее дерево меняется под
 * панелью чаще, чем что-либо ещё в ней, и список обязан это переспросить.
 */
export default function useUncommittedChanges({ project, refreshToken, enabled }) {
  const requestKey = enabled ? `${refreshToken ?? 0} ${project ?? ''}` : null;
  // Ответ вместе с ключом, которому он принадлежит: отдельный флаг loading был
  // бы setState из эффекта, то есть лишний проход рендера на каждое обновление.
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    if (!requestKey) return undefined;
    const controller = new AbortController();
    gitApi
      .getStatus({ project, signal: controller.signal })
      .then((entries) => setAnswer({ key: requestKey, entries }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, entries: [], error });
      });
    return () => controller.abort();
  }, [requestKey, project]);

  const fresh = answer?.key === requestKey ? answer : null;

  return useMemo(() => {
    const entries = fresh?.entries ?? [];
    return {
      loading: !!requestKey && !fresh,
      error: fresh?.error ?? null,
      entries,
      tracked: entries.filter((e) => e.status !== UNTRACKED_STATUS),
      untracked: entries.filter((e) => e.status === UNTRACKED_STATUS),
    };
  }, [fresh, requestKey]);
}
