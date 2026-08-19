import { useTranslation } from 'react-i18next';
import { CONTEXT_KIND } from '../../../constants/contextKind';

// Иконка по виду контекста. Неизвестный вид (запись из более новой версии) рисуем
// нейтрально, а не прячем: пользователь приложил его осознанно.
const ICONS = {
  [CONTEXT_KIND.ATTACHMENT]: '📎',
};

/**
 * Ряд чипов приложенного к сообщению контекста ([{ kind, ref, label }]).
 *
 * Один и тот же ряд стоит над полем ввода — там у чипа есть «×», — и под вопросом
 * в ленте, где клик открывает сам объект. Вид общий, различаются только действия,
 * поэтому это один компонент с двумя необязательными обработчиками.
 */
const ContextChips = ({ items, onRemove, onOpen, ariaLabel }) => {
  const { t } = useTranslation('chat');
  if (!items || items.length === 0) return null;
  return (
    <ul className="context-chips" aria-label={ariaLabel}>
      {items.map((item) => {
        const label = item.label || item.ref;
        return (
          <li key={`${item.kind}:${item.ref}`} className="context-chip">
            {onOpen ? (
              <button type="button" className="context-chip__open" onClick={() => onOpen(item)} title={label}>
                <span className="context-chip__icon">{ICONS[item.kind] || '🔗'}</span>
                <span className="context-chip__label">{label}</span>
              </button>
            ) : (
              <span className="context-chip__open context-chip__open--static" title={label}>
                <span className="context-chip__icon">{ICONS[item.kind] || '🔗'}</span>
                <span className="context-chip__label">{label}</span>
              </span>
            )}
            {onRemove && (
              <button
                type="button"
                className="context-chip__remove"
                onClick={() => onRemove(item)}
                title={t('contextItems.remove', { label })}
                aria-label={t('contextItems.remove', { label })}
              >
                ×
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
};

export default ContextChips;
