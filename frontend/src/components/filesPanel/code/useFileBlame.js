import { useEffect, useMemo, useState } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Авторство строк открытого файла (`GET /api/git/files/blame`): диапазоны
 * строк одного коммита для колонки blame в CodeView.
 *
 * Ключ запроса — тот же, по которому панель перечитывает содержимое
 * (`reloadToken`): после коммита, pull или отката правки строки принадлежат уже
 * другим коммитам, и колонка обязана перейти на них вместе с текстом. Ответ
 * хранится вместе со своим ключом, а `loading`/`error` читаются с него — без
 * отдельных флагов и без setState в эффекте (см. правила хуков).
 *
 * `enabled: false` — запроса нет вовсе: колонка выключена, файл бинарный,
 * усечённый или показан diff'ом.
 */
export default function useFileBlame({ path, project, rev, reloadToken, enabled }) {
  const requestKey = enabled && path ? `${reloadToken ?? 0} ${project ?? ''} ${rev ?? ''} ${path}` : null;
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    if (!requestKey) return undefined;
    const controller = new AbortController();
    gitApi
      .getBlame(path, { rev, project, signal: controller.signal })
      .then((blame) => {
        // Ответ на прошлый ключ пришёл после ответа на текущий: молча мимо,
        // иначе он затёр бы свежий, и колонка осталась бы в «загрузке».
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, blame });
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        setAnswer({ key: requestKey, blame: null, error });
      });
    return () => controller.abort();
  }, [requestKey, path, project, rev]);

  const fresh = answer?.key === requestKey ? answer : null;

  return useMemo(
    () => ({
      loading: !!requestKey && !fresh,
      error: fresh?.error ?? null,
      hunks: fresh?.blame?.hunks ?? [],
    }),
    [fresh, requestKey],
  );
}
