import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import ListboxSelect from '../common/ui/ListboxSelect';
import { markUnavailable } from '../common/config/projectChoice';

/**
 * Выбор репозитория в шапке левой панели «Файлы» — он же её заголовок: панель
 * показывает ровно один репозиторий, и его имя и есть имя панели.
 *
 * С панелью, а не с чатом: раздел «Файлы» — не чат, и жёстко тянуть его за
 * активным диалогом значило бы уводить пользователя из того дерева, которое он
 * открыл. Обратное направление при этом обязано работать: переход по ссылке из
 * чата открывает файл в проекте ЭТОЙ ссылки и переключает панель на него (см.
 * fileNavigationBus).
 *
 * Props:
 *   value    — id выбранного проекта
 *   options  — [{ id, label, available }] — проекты из конфига
 *   onChange — (id) => void
 */
const ProjectPicker = ({ value, options, onChange }) => {
  const { t } = useTranslation('files');
  const decorated = useMemo(() => markUnavailable(options, t('project.unavailable')), [options, t]);

  return (
    <ListboxSelect
      value={value}
      options={decorated}
      onChange={onChange}
      ariaLabel={t('project.aria')}
      className="files-project-picker"
    />
  );
};

export default ProjectPicker;
