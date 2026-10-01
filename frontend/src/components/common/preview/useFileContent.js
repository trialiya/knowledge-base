import gitApi from '@/api/gitApi';
import useKeyedRequest from './useKeyedRequest';

/**
 * Содержимое файла целиком (или диапазон `from`–`to`) для модалок просмотра —
 * в отличие от useFilePreview, который читает только голову файла под карточку.
 * Свежесть ответа и отмена — useKeyedRequest.
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
  // '' и null — один и тот же проект (по умолчанию) и одна и та же ревизия
  // (рабочее дерево): другое написание того же запроса файл не перечитывает.
  const proj = project || null;
  const revision = rev || null;
  const key = enabled ? JSON.stringify([path, proj, revision, from ?? null, to ?? null]) : null;
  const { loading, value, error } = useKeyedRequest(key, (signal) =>
    read(path, { from, to, rev: revision ?? undefined, project: proj, signal }),
  );
  return { loading, file: value, error: !!error };
}
