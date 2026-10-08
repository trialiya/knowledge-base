import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import ListboxSelect from '@/components/common/ui/ListboxSelect';
import { DEFAULT_REASONING, reasoningLevelName } from '../run/reasoningChoice';

/**
 * Выбор уровня рассуждений модели. Тонкая обёртка над общим {@link ListboxSelect}:
 * уровни приходят из конфигурации модели, подписи — из неё же (label) или из словаря
 * по известным id (reasoningLevelName); пункт «по умолчанию» появляется, только когда
 * умолчания у модели нет — тогда он и означает «ничего не отправлять».
 *
 * Props:
 *   reasoning — { default, levels: [{ id, label }] } модели (reasoningOf)
 *   value     — id уровня, на котором пойдёт прогон (effectiveReasoning)
 *   onChange  — (id) => void ('' — сброс к умолчанию модели)
 *   disabled  — блокировка во время прогона
 */
const ReasoningSelector = ({ reasoning, value, onChange, disabled = false }) => {
  const { t } = useTranslation('chat');

  const options = useMemo(() => {
    // Подпись триггера стоит рядом с моделью и режимом, поэтому называет, что выбрано:
    // голое «high» под полем ввода не прочесть.
    const option = (id) => ({ id, label: t('reasoning.option', { level: reasoningLevelName(t, reasoning, id) }) });
    const levels = reasoning.levels.map((l) => option(l.id));
    return reasoning.default ? levels : [option(DEFAULT_REASONING), ...levels];
  }, [reasoning, t]);

  return (
    <ListboxSelect
      value={value}
      options={options}
      onChange={onChange}
      disabled={disabled}
      ariaLabel={t('reasoning.aria')}
      placement="up"
    />
  );
};

export default ReasoningSelector;
