import useKeyedRequest from '@/components/common/preview/useKeyedRequest';
import gitApi from '@/api/gitApi';

/**
 * Патч одного открытого файла — то, что центральная панель показывает в режиме
 * diff. Запрос отдельный от списка: собрать diff всего рабочего дерева ради
 * одного открытого файла значит платить за него на каждом клике по списку.
 *
 * `rev` — панель показывает снимок ревизии: тогда изменение — это то, что с
 * файлом сделал сам коммит, а не незакоммиченная правка (у снимка их не бывает).
 * Тогда в ключ входит и `refsToken`: после fetch remote-ветка называет другой
 * коммит — ровно как у списка слева (useSnapshotCommit).
 *
 * `base` — панель сравнивает ревизии: изменение — то, чем файл в показанной
 * ревизии (без `rev` — в HEAD) отличается от базы (useComparison); ревизии
 * сравнения бывают ветками, поэтому `refsToken` тогда в ключе тоже.
 *
 * Ответ — `null`, если изменения у файла нет (открыли файл из обычного дерева,
 * а diff-режим остался включённым): это не ошибка, а «нечего показывать», и
 * центр говорит именно это.
 */
export default function useChangeDiff({
  project,
  path,
  rev = '',
  base = '',
  direct = false,
  refreshToken,
  refsToken,
  enabled,
}) {
  const refs = rev || base ? refsToken ?? 0 : 0;
  const compared = base ? `${direct ? 1 : 0} ${base}` : '';
  const requestKey =
    enabled && path ? `${refreshToken ?? 0} ${refs} ${project ?? ''} ${rev} ${compared}\n${path}` : null;
  const answer = useKeyedRequest(requestKey, (signal) => entriesOf({ project, path, rev, base, direct, signal }));

  return {
    rev,
    base,
    loading: answer.loading,
    error: answer.error,
    entry: answer.value?.[0] ?? null,
  };
}

/** Записи открытого файла у того, чьи изменения показаны: сравнения, коммита снимка или рабочего дерева. */
function entriesOf({ project, path, rev, base, direct, signal }) {
  if (base) {
    return gitApi
      .compare(base, { head: rev, direct, path, patch: true, project, signal })
      .then((comparison) => comparison.files ?? []);
  }
  if (rev) {
    return gitApi.getCommit(rev, { path, patch: true, project, signal }).then((commit) => commit.files ?? []);
  }
  return gitApi.getStatus({ path, patch: true, project, signal });
}
