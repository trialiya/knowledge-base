import { useEffect, useState } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Именованные ревизии репозитория для выбора снимка — ветки и теги.
 *
 * Спрашиваются, только когда список открыли (`enabled`): в закрытом виде
 * контрол показывает то, что уже выбрано, и знать про остальные ревизии ему
 * незачем. Ответ хранится вместе с ключом, которому принадлежит, — как в
 * useOutgoingCommits: отдельный флаг loading был бы setState из эффекта.
 */
export default function useRevisions({ project, refsToken, enabled }) {
  const requestKey = enabled ? `${refsToken ?? 0} ${project ?? ''}` : null;
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    if (!requestKey) return undefined;
    const controller = new AbortController();
    gitApi
      .getRefs({ project, signal: controller.signal })
      .then((refs) => setAnswer({ key: requestKey, refs }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, refs: { branches: [], tags: [] }, error });
      });
    return () => controller.abort();
  }, [requestKey, project]);

  const fresh = answer?.key === requestKey ? answer : null;

  return {
    loading: !!requestKey && !fresh,
    error: fresh?.error ?? null,
    branches: fresh?.refs?.branches ?? [],
    tags: fresh?.refs?.tags ?? [],
  };
}
