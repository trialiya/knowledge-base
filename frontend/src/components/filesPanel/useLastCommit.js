import { useMemo } from 'react';
import gitApi from '@/api/gitApi';
import useKeyedRequest from '@/components/common/preview/useKeyedRequest';

/**
 * Последний коммит, затронувший `path` (пустой путь — весь репозиторий).
 *
 * `rev` — ревизия, от которой идёт история ('' — рабочее дерево, то есть HEAD).
 * В режиме снимка спрашивать от HEAD нельзя: коммит, сделанный после выбранной
 * ревизии, к тому, что показано в центре, отношения не имеет, а у пути,
 * удалённого позже, ответ был бы про его удаление.
 *
 * С телом сообщения: коммит здесь ровно один, и на нём тело стоит запроса —
 * во «что здесь меняли последним» объяснение правки и есть главное.
 *
 * Отдельным запросом, а не полем в дереве: `git log` по пути стоит заметно
 * дороже листинга, а нужен он только когда раскрыта вкладка «Инфо» — компонент
 * с этим хуком до раскрытия панели не смонтирован.
 *
 * @returns {{ commit: object|null, loading: boolean, error: boolean }}
 */
export default function useLastCommit(path, project, enabled = true, rev = '') {
  const key = enabled ? JSON.stringify([path, project ?? null, rev || null]) : null;
  const { loading, value, error } = useKeyedRequest(key, (signal) =>
    gitApi.getCommits(path, { limit: 1, body: true, rev, project, signal }),
  );
  // Мемо, а не литерал: результат хука уходит в зависимости у вызывающих.
  return useMemo(() => ({ commit: value?.commits?.[0] ?? null, loading, error: !!error }), [value, loading, error]);
}
