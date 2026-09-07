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
      .then((entries) => setAnswer({ key: requestKey, project, entries }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, project, entries: [], error });
      });
    return () => controller.abort();
  }, [requestKey, project]);

  const fresh = answer?.key === requestKey ? answer : null;
  // Пока перезапрос не вернулся, на экране остаётся прежний список — как в
  // useGitBranch, и по той же причине: сигнал обновления поднимает каждая правка
  // файла инструментом чата, и список, обнуляемый на время round-trip'а, мигал
  // бы на каждую из них. Но только про тот же репозиторий: ответ другого
  // проекта не устаревший, а чужой.
  const known = fresh ?? (answer?.project === project ? answer : null);

  return useMemo(() => {
    const entries = known?.entries ?? [];
    return {
      loading: !!requestKey && !fresh,
      error: fresh?.error ?? null,
      // Был ли ответ вообще: «ещё не знаем» и «изменений нет» — разные вещи, и
      // отличить их по пустому списку нельзя.
      answered: !!known,
      entries,
      tracked: entries.filter((e) => e.status !== UNTRACKED_STATUS),
      untracked: entries.filter((e) => e.status === UNTRACKED_STATUS),
    };
  }, [known, fresh, requestKey]);
}
