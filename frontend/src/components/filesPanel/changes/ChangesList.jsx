import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import useListNavigation from '@/components/common/search/useListNavigation';
import ChangeRow from './ChangeRow';
import ChangeTreeNode from './ChangeTreeNode';
import { buildChangeTree, sortByName } from './changeTree';

/**
 * Секция списка изменений: отслеживаемые файлы, ниже — неотслеживаемые,
 * допущенные `allow-globs` проекта. Вторая секция появляется, только если
 * такие файлы есть: без настроенных глобов их нет никогда, и пустой заголовок
 * рассказывал бы про возможность, которой у проекта не включено.
 *
 * Заголовок секции не строка списка: он ничего не открывает, поэтому стрелками
 * не достаётся и в `listbox`/`tree` входит группой (`role="group"`).
 */
const ChangesSection = ({ title, entries, flat, selectedPath, collapsed, onToggle, onSelect, onDiscard }) => {
  const tree = useMemo(() => (flat ? null : buildChangeTree(entries)), [flat, entries]);
  const rows = useMemo(() => (flat ? sortByName(entries) : null), [flat, entries]);

  return (
    <div className="file-changes__section" role="group" aria-label={title}>
      <div className="file-changes__section-title">
        {title}
        <span className="file-changes__count">{entries.length}</span>
      </div>
      {flat
        ? rows.map((entry) => (
            <ChangeRow
              key={entry.path}
              entry={entry}
              showDir
              selected={entry.path === selectedPath}
              onSelect={onSelect}
              onDiscard={onDiscard}
            />
          ))
        : tree.map((node) => (
            <ChangeTreeNode
              key={node.path}
              node={node}
              level={0}
              selectedPath={selectedPath}
              collapsed={collapsed}
              onToggle={onToggle}
              onSelect={onSelect}
              onDiscard={onDiscard}
            />
          ))}
    </div>
  );
};

/**
 * Левый блок в режиме «Изменения». Раскладка (плоская/иерархия) приходит
 * пропом: её помнит панель, а не список, — переключатель живёт в тулбаре.
 *
 * `snapshot` — список показывает не незакоммиченное, а файлы, изменённые
 * коммитом снимка; `compare` — файлы, которыми показанная ревизия отличается
 * от базы сравнения. Строки те же, меняются только подписи.
 */
const ChangesList = ({
  tracked,
  untracked,
  flat,
  loading,
  error,
  selectedPath,
  onSelect,
  onDiscard,
  snapshot = false,
  compare = false,
}) => {
  const { t } = useTranslation('files');
  const handleKeyDown = useListNavigation();
  // Свёрнутые каталоги, а не раскрытые: по умолчанию раскрыто всё, и набор
  // пуст ровно в этом обычном случае.
  const [collapsed, setCollapsed] = useState(() => new Set());

  const onToggle = (dirPath) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(dirPath)) next.delete(dirPath);
      else next.add(dirPath);
      return next;
    });

  const text = TEXT[listKind(snapshot, compare)];
  const empty = !loading && !error && tracked.length === 0 && untracked.length === 0;

  return (
    <div
      className="file-changes ws-list"
      // Роль контейнера следует раскладке: плоский перечень — listbox,
      // иерархия — tree (см. правила левой панели).
      role={flat ? 'listbox' : 'tree'}
      aria-label={t(text.aria)}
      tabIndex={0}
      onKeyDown={handleKeyDown}
    >
      {loading && (
        <div className="ws-hint" role="none">
          {t('tree.loading')}
        </div>
      )}
      {error && (
        <div className="ws-hint" role="none">
          {t('changes.loadError')}
        </div>
      )}
      {empty && (
        <div className="ws-hint" role="none">
          {t(text.empty)}
        </div>
      )}
      {tracked.length > 0 && (
        <ChangesSection
          title={t(text.section)}
          entries={tracked}
          flat={flat}
          selectedPath={selectedPath}
          collapsed={collapsed}
          onToggle={onToggle}
          onSelect={onSelect}
          onDiscard={onDiscard}
        />
      )}
      {untracked.length > 0 && (
        <ChangesSection
          title={t('changes.untracked')}
          entries={untracked}
          flat={flat}
          selectedPath={selectedPath}
          collapsed={collapsed}
          onToggle={onToggle}
          onSelect={onSelect}
          onDiscard={onDiscard}
        />
      )}
    </div>
  );
};

function listKind(snapshot, compare) {
  if (compare) return 'compare';
  return snapshot ? 'commit' : 'uncommitted';
}

/** Подписи списка по тому, чьи изменения он показывает. */
const TEXT = {
  uncommitted: { aria: 'panel.changes', empty: 'changes.empty', section: 'changes.tracked' },
  commit: { aria: 'panel.commitChanges', empty: 'changes.commitEmpty', section: 'changes.inCommit' },
  compare: { aria: 'panel.compareChanges', empty: 'changes.compareEmpty', section: 'changes.inComparison' },
};

export default ChangesList;
