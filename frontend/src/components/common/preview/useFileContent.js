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
 * Функция обязана быть стабильной (модульной): она в зависимостях эффекта, и
 * лямбда на месте перечитывала бы файл на каждом рендере.
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
  // '' и null — один и тот же проект (по умолчанию) и одна и та же ревизия
  // (рабочее дерево): эффект следует нормализованным значениям, и другое
  // написание того же запроса файл не перечитывает.
  const proj = project || null;
  const revision = rev || null;
  const lo = from ?? null;
  const hi = to ?? null;
  const key = enabled ? JSON.stringify([path, proj, revision, lo, hi]) : null;
  const [answer, setAnswer] = useState(null); // { key, file, error } | null

  useEffect(() => {
    if (!key) return undefined;
    let cancelled = false;
    const done = (file, error) => {
      if (!cancelled) setAnswer({ key, file, error });
    };
    read(path, { from: lo ?? undefined, to: hi ?? undefined, rev: revision ?? undefined, project: proj })
      .then((file) => done(file, false))
      .catch(() => done(null, true));
    return () => {
      cancelled = true;
    };
  }, [key, read, path, proj, revision, lo, hi]);

  const current = answer?.key === key ? answer : null;
  return {
    loading: enabled && !current,
    file: current?.file ?? null,
    error: current?.error ?? false,
  };
}
