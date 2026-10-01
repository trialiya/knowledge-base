import { useMemo } from 'react';
import gitApi from '@/api/gitApi';
import useKeyedRequest from '@/components/common/preview/useKeyedRequest';

/**
 * Авторство строк открытого файла (`GET /api/git/files/blame`): диапазоны
 * строк одного коммита для колонки blame в CodeView.
 *
 * Ключ запроса — тот же, по которому панель перечитывает содержимое
 * (`reloadToken`): после коммита, pull или отката правки строки принадлежат уже
 * другим коммитам, и колонка обязана перейти на них вместе с текстом. Свежесть
 * ответа и отмена прошлого запроса — useKeyedRequest.
 *
 * `enabled: false` — запроса нет вовсе: колонка выключена, файл бинарный,
 * усечённый или показан diff'ом.
 */
export default function useFileBlame({ path, project, rev, reloadToken, enabled }) {
  const key = enabled && path ? `${reloadToken ?? 0} ${project ?? ''} ${rev ?? ''} ${path}` : null;
  const { loading, value, error } = useKeyedRequest(key, (signal) => gitApi.getBlame(path, { rev, project, signal }));

  return useMemo(() => ({ loading, error, hunks: value?.hunks ?? [] }), [loading, error, value]);
}
