import { useMemo } from 'react';
import ListboxSelect from '../ui/ListboxSelect';

/**
 * {@link ListboxSelect}, в котором один пункт помечен как дефолтный — тот, на который
 * уедет чат, своего выбора не сделавший. Так показываются и модель, и проект.
 *
 * Подписи принимает готовыми, а не переводит сам: у каждого селектора и пометка, и
 * доступное имя лежат в своих ключах, и разбирать их здесь значило бы держать внутри
 * список тех, кто этим компонентом пользуется.
 *
 * Props:
 *   value       — id выбранного пункта
 *   defaultId   — id пункта, который получит пометку
 *   options     — [{ id, label }]
 *   onChange    — (id) => void
 *   disabled    — блокировка (например, во время стриминга)
 *   defaultNote — текст пометки, уже переведённый и без скобок
 *   ariaLabel   — доступное имя триггера и списка
 *   placement   — как у ListboxSelect
 */
const DefaultNoteSelect = ({
  value,
  defaultId,
  options,
  onChange,
  disabled = false,
  defaultNote,
  ariaLabel,
  placement,
}) => {
  const decorated = useMemo(
    () => (options || []).map((o) => (o.id === defaultId ? { ...o, note: `(${defaultNote})` } : o)),
    [options, defaultId, defaultNote],
  );

  return (
    <ListboxSelect
      value={value}
      options={decorated}
      onChange={onChange}
      disabled={disabled}
      ariaLabel={ariaLabel}
      placement={placement}
    />
  );
};

export default DefaultNoteSelect;
