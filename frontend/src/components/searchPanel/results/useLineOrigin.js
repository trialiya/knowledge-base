import useKeyedRequest from '@/components/common/preview/useKeyedRequest';
import gitApi from '@/api/gitApi';
import { originKey } from './originAnswer';

/**
 * «Когда появилось» для одной строки совпадения. Хук живёт в панели ответа,
 * а панель смонтирована, только пока её открыли: функция дорогая (цепочка
 * git blame), и звать её за каждую показанную строку значило бы делать работу,
 * о которой не просили.
 */
export default function useLineOrigin({ path, line, query, rev, project }) {
  return useKeyedRequest(originKey({ path, line, query, rev, project }), (signal) =>
    gitApi.getLineOrigin(path, line, query, { rev, project, signal }),
  );
}
