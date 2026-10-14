import useKeyedRequest from '@/components/common/preview/useKeyedRequest';
import gitApi from '@/api/gitApi';

/**
 * «Когда появилось» для одной строки совпадения. Хук живёт в панели ответа,
 * а панель смонтирована, только пока её открыли: функция дорогая (цепочка
 * git blame), и звать её за каждую показанную строку значило бы делать работу,
 * о которой не просили.
 *
 * Ключ — всё, от чего зависит ответ: тот же файл и строка в другой ревизии или
 * под другим запросом — другой ответ.
 */
export default function useLineOrigin({ path, line, query, rev, project }) {
  const key = JSON.stringify([project ?? '', rev ?? '', path, line, query]);
  return useKeyedRequest(key, (signal) => gitApi.getLineOrigin(path, line, query, { rev, project, signal }));
}
