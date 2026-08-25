import { useTranslation } from 'react-i18next';
import { DiffStats } from '@/components/chatPanel/messages/diffRender';
import { IconEraser } from '@/icons/index';

/**
 * Строка изменённого файла — одна на обе раскладки: в плоской перед именем
 * стоит приглушённый каталог, в иерархической его показывает само дерево.
 *
 * Буква статуса (A/M/D/R/C/U) — те же обозначения, что печатает `git status`:
 * подписывать их словом в строке шириной с панель негде. Цвет её не заменяет —
 * расшифровка идёт подсказкой строки, как и у неотслеживаемых в дереве файлов.
 *
 * `role` задаёт вызывающий: плоский список — `option` в `listbox`,
 * иерархия — `treeitem` в `tree`.
 */
const ChangeRow = ({ entry, depth = 0, showDir = false, selected, onSelect, onDiscard, role = 'option', level }) => {
  const { t } = useTranslation('files');
  const status = entry.status;
  const name = entry.path.split('/').pop();
  const dir = entry.path.slice(0, entry.path.length - name.length);
  const statusLabel = t(`changes.status.${status}`, { defaultValue: status });

  return (
    <div
      data-ws-item
      role={role}
      aria-selected={!!selected}
      aria-level={role === 'treeitem' ? level : undefined}
      tabIndex={-1}
      className={`ws-item file-changes__row${selected ? ' ws-item--active' : ''}`}
      style={{ '--depth': depth }}
      title={`${statusLabel} · ${entry.path}`}
      onClick={() => onSelect(entry)}
    >
      <span className={`file-changes__status file-changes__status--${status}`}>{status}</span>
      {/* Имя раньше каталога, хотя в пути порядок обратный: строка списка уже
          уже пути, и сжимать в ней приходится именно каталог — обрезанное имя
          файла не опознать вовсе. */}
      <span className="ws-item__label file-changes__name">{name}</span>
      {showDir && dir && <span className="file-changes__dir">{dir.slice(0, -1)}</span>}
      {/* Переименование: старое имя иначе видно только в diff'е, а по списку
          файл выглядел бы просто новым. */}
      {entry.oldPath && <span className="file-changes__from">← {entry.oldPath}</span>}
      <span className="file-changes__stats">
        <DiffStats additions={entry.additions} deletions={entry.deletions} />
      </span>
      {/* Откат только у отслеживаемого файла: у неотслеживаемого нет
          закоммиченного состояния, к которому его возвращать, и команда
          ответила бы отказом. Клик не должен открывать файл заодно. */}
      {onDiscard && entry.status !== 'U' && (
        <button
          type="button"
          className="ws-item__action icon-btn--danger"
          title={t('git.discard')}
          aria-label={t('git.discard')}
          onClick={(e) => {
            e.stopPropagation();
            onDiscard(entry.path);
          }}
        >
          <IconEraser size={13} />
        </button>
      )}
    </div>
  );
};

export default ChangeRow;
