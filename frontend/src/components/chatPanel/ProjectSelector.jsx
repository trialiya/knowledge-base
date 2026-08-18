import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import ListboxSelect from '../common/ListboxSelect';

/**
 * Выбор проекта (репозитория) активного чата — в каких файлах работают инструменты
 * модели. Тонкая обёртка над общим {@link ListboxSelect}, как {@link ModelSelector}:
 * добавляет пометку «(по умолчанию)» дефолтному проекту.
 *
 * Props:
 *   value     — выбранный id проекта
 *   defaultId — id дефолтного проекта (для пометки «(по умолчанию)»)
 *   options   — [{ id, label }] — проекты из конфига
 *   onChange  — (id) => void
 *   disabled  — блокировка во время стриминга
 */
const ProjectSelector = ({ value, defaultId, options, onChange, disabled = false }) => {
  const { t } = useTranslation('chat');

  const decorated = useMemo(
    () => (options || []).map((p) => (p.id === defaultId ? { ...p, note: `(${t('project.default')})` } : p)),
    [options, defaultId, t],
  );

  return (
    <ListboxSelect
      value={value}
      options={decorated}
      onChange={onChange}
      disabled={disabled}
      ariaLabel={t('project.aria')}
      placement="up"
    />
  );
};

export default ProjectSelector;
