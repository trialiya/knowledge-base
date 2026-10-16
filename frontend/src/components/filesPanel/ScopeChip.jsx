import { useTranslation } from 'react-i18next';
import { IconX } from '@/icons/index';

/**
 * Сужение левого списка до открытого пути — у ленты «История» и у сравнения с
 * базой. Пока не сужено, это кнопка «Только <имя>» для `path`; сужено — чип с
 * крестиком, который снимает сужение (`clearLabel` говорит, что тогда покажут).
 *
 * Сужение — своё состояние вызывающего, а не сам путь: иначе клик по файлу в
 * суженном списке перестраивал бы список, из которого по нему кликнули.
 * Ни пути, ни сужения — ничего не рисуется.
 */
const ScopeChip = ({ path, scope, onScopeChange, clearLabel }) => {
  const { t } = useTranslation('files');
  const shown = scope || path;
  if (!shown) return null;
  const label = t('scope.only', { name: shown.replace(/\/+$/, '').split('/').pop() });

  return (
    <div className="files-scope">
      {scope ? (
        <span className="files-scope__chip" title={scope}>
          <span className="files-scope__chip-label">{label}</span>
          <button
            type="button"
            className="icon-btn icon-btn--xs icon-btn--quiet"
            title={clearLabel}
            aria-label={clearLabel}
            onClick={() => onScopeChange('')}
          >
            <IconX size={10} />
          </button>
        </span>
      ) : (
        <button type="button" className="btn btn--ghost btn--xs" title={path} onClick={() => onScopeChange(path)}>
          {label}
        </button>
      )}
    </div>
  );
};

export default ScopeChip;
