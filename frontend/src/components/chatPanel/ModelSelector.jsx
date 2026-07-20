import React, { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import ListboxSelect from './ListboxSelect';

/**
 * Выбор модели активного чата. Тонкая обёртка над общим {@link ListboxSelect}:
 * добавляет пометку «(по умолчанию)» дефолтной модели.
 *
 * Props:
 *   value     — выбранный id модели
 *   defaultId — id дефолтной модели (для пометки «(по умолчанию)»)
 *   options   — [{ id, label }] — список моделей (с дефолтной включительно)
 *   onChange  — (id) => void
 *   disabled  — блокировка во время стриминга
 */
const ModelSelector = ({ value, defaultId, options, onChange, disabled = false }) => {
  const { t } = useTranslation('chat');

  const decorated = useMemo(
    () =>
      (options || []).map((m) =>
        m.id === defaultId ? { ...m, note: `(${t('model.default')})` } : m,
      ),
    [options, defaultId, t],
  );

  return (
    <ListboxSelect
      value={value}
      options={decorated}
      onChange={onChange}
      disabled={disabled}
      ariaLabel={t('model.aria')}
      className="chat-select--model"
    />
  );
};

export default ModelSelector;
