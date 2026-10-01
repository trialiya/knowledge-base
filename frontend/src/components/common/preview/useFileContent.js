import { useEffect, useState } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Содержимое файла целиком (или диапазон `from`–`to`) для модалок просмотра —
 * в отличие от useFilePreview, который читает только голову файла под карточку.
 *
 * Ответ держится вместе с ключом запроса, который его получил: `loading` — это
 * «ответа на текущий ключ ещё нет», поэтому кадра с содержимым предыдущего файла
 * не бывает, а опоздавший ответ отменённого запроса отброшен в cleanup.
 *
 * `read` — чем читать; по умолчанию gitApi.getFileContent. Превью чипа читает
 * через кэш fileChips.fetchContent: в сообщение уйдёт ровно то, что показали.
 * `enabled: false` — не читать вовсе (чип в режиме «только путь»).
 */
export default function useFileContent({
  path,
  project = null,
  rev = null,
  from,
  to,
  enabled = true,
  read = gitApi.getFileContent,
}) {
  const key = enabled ? JSON.stringify([path, project, rev || null, from ?? null, to ?? null]) : null;
  const [answer, setAnswer] = useState(null); // { key, file, error } | null

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    const done = (file, error) => {
      if (!cancelled) setAnswer({ key, file, error });
    };
    read(path, { from, to, rev: rev || undefined, project })
      .then((file) => done(file, false))
      .catch(() => done(null, true));
    return () => {
      cancelled = true;
    };
  }, [key, enabled, read, path, project, rev, from, to]);

  const current = answer?.key === key ? answer : null;
  return {
    loading: enabled && !current,
    file: current?.file ?? null,
    error: current?.error ?? false,
  };
}
