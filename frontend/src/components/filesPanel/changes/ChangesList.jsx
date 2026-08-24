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
const ChangesSection = ({ title, entries, flat, selectedPath, collapsed, onToggle, onSelect }) => {
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
            />
          ))}
    </div>
  );
};

/**
 * Левый блок в режиме «Изменения». Раскладка (плоская/иерархия) приходит
 * пропом: её помнит панель, а не список, — переключатель живёт в тулбаре.
 */
const ChangesList = ({ tracked, untracked, flat, loading, error, selectedPath, onSelect }) => {
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

  const empty = !loading && !error && tracked.length === 0 && untracked.length === 0;

  return (
    <div
      className="file-changes ws-list"
      // Роль контейнера следует раскладке: плоский перечень — listbox,
      // иерархия — tree (см. правила левой панели).
      role={flat ? 'listbox' : 'tree'}
      aria-label={t('panel.changes')}
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
          {t('changes.empty')}
        </div>
      )}
      {tracked.length > 0 && (
        <ChangesSection
          title={t('changes.tracked')}
          entries={tracked}
          flat={flat}
          selectedPath={selectedPath}
          collapsed={collapsed}
          onToggle={onToggle}
          onSelect={onSelect}
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
        />
      )}
    </div>
  );
};

export default ChangesList;
