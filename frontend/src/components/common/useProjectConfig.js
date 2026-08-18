import chatApi from '../../api/chatApi';
import useConfigSnapshot from './useConfigSnapshot';

/**
 * Список проектов (репозиториев) и id дефолтного — того, на котором работает
 * всё, что проект не назвало.
 *
 * Общий на приложение, а не только на чат: тот же список нужен панели «Файлы»,
 * и через {@link useConfigSnapshot} обе стороны попадают в один запрос вместо
 * двух одинаковых.
 *
 * Дефолт приходит с бэка отдельным полем, а не подразумевается первым элементом:
 * иначе это правило пришлось бы повторить здесь и помнить о нём при каждой
 * правке порядка списка.
 *
 * @returns {{ projectOptions: [{id,label,editEnabled}], defaultProjectId: string|null }}
 */
export default function useProjectConfig() {
  const { data } = useConfigSnapshot(chatApi.getProjects);

  return {
    projectOptions: Array.isArray(data?.projects) ? data.projects : [],
    defaultProjectId: data?.defaultProject || null,
  };
}
