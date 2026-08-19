import { useTranslation } from 'react-i18next';
import ListboxSelect from '../common/ListboxSelect';

/**
 * Выбор репозитория в шапке левой панели «Файлы».
 *
 * С панелью, а не с чатом: раздел «Файлы» — не чат, и жёстко тянуть его за
 * активным диалогом значило бы уводить пользователя из того дерева, которое он
 * открыл. Обратное направление при этом обязано работать: переход по ссылке из
 * чата открывает файл в проекте ЭТОЙ ссылки и переключает панель на него (см.
 * fileNavigationBus).
 *
 * Props:
 *   value    — id выбранного проекта
 *   options  — [{ id, label }] — проекты из конфига
 *   onChange — (id) => void
 */
const ProjectPicker = ({ value, options, onChange }) => {
  const { t } = useTranslation('files');

  return (
    <ListboxSelect
      value={value}
      options={options}
      onChange={onChange}
      ariaLabel={t('project.aria')}
      className="files-project-picker"
    />
  );
};

export default ProjectPicker;
