import { useTranslation } from 'react-i18next';
import { IconDoc, IconFolder } from '../../icons';
import { isActionable } from './syncSelection';

// ─── Список различий папки экспорта и базы ───────────────────────────────────
// Результат сравнения: что появилось на диске, что разошлось по содержимому, а
// чего в папке больше нет. Галочка — это и есть ответ на «а дальше выбирать,
// что загрузить»: применится ровно отмеченное.
//
// unchanged по умолчанию скрыт: сразу после экспорта таких записей все сто
// процентов, и они прячут те несколько, ради которых сравнение и запускали.

const SyncDiffList = ({ entries, selected, onToggle, showUnchanged, onShowUnchanged, disabled }) => {
  const { t } = useTranslation('settings');
  const visible = showUnchanged ? entries : entries.filter((e) => e.status !== 'unchanged');
  const hidden = entries.length - visible.length;

  if (!entries.length) {
    return <div className="sync-diff__empty">{t('admin.bulk.diff.empty')}</div>;
  }

  return (
    <div className="sync-diff">
      <div className="sync-diff__toolbar">
        <label className="admin-check">
          <input type="checkbox" checked={showUnchanged} onChange={(e) => onShowUnchanged(e.target.checked)} />
          {t('admin.bulk.diff.showUnchanged', { count: hidden })}
        </label>
      </div>

      <ul className="sync-diff__list" role="list">
        {visible.map((entry) => (
          <li
            key={entry.path}
            className={`sync-diff__row sync-diff__row--${entry.status}`}
            style={{ '--sync-depth': entry.depth }}
          >
            <input
              type="checkbox"
              className="sync-diff__check"
              checked={selected.has(entry.path)}
              disabled={disabled || !isActionable(entry)}
              onChange={() => onToggle(entry.path)}
              aria-label={entry.title}
            />
            <span className="sync-diff__icon">
              {entry.type === 'folder' ? <IconFolder size={14} /> : <IconDoc size={14} />}
            </span>
            <span className="sync-diff__title">{entry.title}</span>
            <span className="sync-diff__path">{entry.path}</span>
            <span className={`sync-diff__badge sync-diff__badge--${entry.status}`}>
              {t(`admin.bulk.diff.status.${entry.status}`)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
};

export default SyncDiffList;
