import { useMemo } from 'react';
import useKeyedRequest from '@/components/common/preview/useKeyedRequest';
import gitApi from '@/api/gitApi';

/**
 * Сравнение показанной ревизии с базой: файлы без патчей, общий предок и
 * коммиты между ними — одно `GET /api/git/compare` на двоих, список слева и
 * вкладку «Сравнение» справа. Патч открытого файла спрашивается отдельно
 * (useChangeDiff), как у снимка коммита.
 *
 * `rev` пустой — сравнивается HEAD рабочего дерева: незакоммиченное в
 * сравнение не входит, у него свой список (режим «Изменения» без базы).
 *
 * В ключе оба токена: и база, и ревизия бывают ветками, а ветка после коммита,
 * pull или switch (`refreshToken`) и remote-ветка после fetch (`refsToken`)
 * называют уже другой коммит.
 *
 * Отдаёт то же, что useUncommittedChanges (`tracked`/`untracked`), чтобы
 * список слева не знал, чьи изменения показывает.
 */
export default function useComparison({ project, base, rev, direct, refreshToken, refsToken, enabled = true }) {
  const requestKey =
    base && enabled
      ? `${refreshToken ?? 0} ${refsToken ?? 0} ${project ?? ''} ${direct ? 1 : 0} ${rev}\n${base}`
      : null;
  const answer = useKeyedRequest(requestKey, (signal) => gitApi.compare(base, { head: rev, direct, project, signal }));

  return useMemo(() => {
    const entries = answer.value?.files ?? [];
    return {
      loading: answer.loading,
      error: answer.error,
      comparison: answer.value,
      tracked: entries,
      untracked: [],
    };
  }, [answer.loading, answer.error, answer.value]);
}
