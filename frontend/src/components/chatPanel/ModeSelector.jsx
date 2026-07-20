import React, { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import ListboxSelect from './ListboxSelect';

/** Синтетический пункт «без режима»: пустой id → бэк не подставляет фрагмент. */
export const NO_MODE = '';

/**
 * Выбор готового режима ассистента (Аналитик / Разработчик / Тестировщик / …).
 * Тонкая обёртка над общим {@link ListboxSelect}: добавляет сверху пункт
 * «Без режима» (пустой id).
 *
 * Props:
 *   value    — выбранный id режима ('' — без режима)
 *   options  — [{ id, label }] — режимы из конфига (без «без режима»)
 *   onChange — (id) => void  ('' — сброс режима)
 *   disabled — блокировка во время стриминга
 */
const ModeSelector = ({ value, options, onChange, disabled = false }) => {
  const { t } = useTranslation('chat');

  const withNone = useMemo(
    () => [{ id: NO_MODE, label: t('mode.none') }, ...(options || [])],
    [options, t],
  );

  return (
    <ListboxSelect
      value={value || NO_MODE}
      options={withNone}
      onChange={onChange}
      disabled={disabled}
      ariaLabel={t('mode.aria')}
      className="chat-select--mode"
    />
  );
};

export default ModeSelector;
