import { useTranslation } from 'react-i18next';
import { DiffStats } from '@/components/chatPanel/messages/diffRender';
import { buildChangeTree, sortByName } from '@/components/filesPanel/changes/changeTree';
import { IconCheck, IconFolder, IconList } from '@/icons/index';

/**
 * Левый столбец окна коммита: что менялось и что из этого коммитить.
 *
 * Строка — та же, что в режиме «Изменения» панели «Файлы»: буква статуса из
 * `git status`, имя, приглушённый каталог, счётчики строк. Добавлена ровно одна
 * колонка — галочка, и ради неё строка здесь своя: строка панели `.ws-item`
 * живёт в списке с клавиатурной навигацией по контейнеру, а здесь у каждой
 * строки два независимых действия (открыть diff и включить в коммит), и оба
 * должны доставаться табом.
 *
 * Раскладка — тот же выбор «плоско или деревом», что и в панели, и хранится он
 * там же (`changesLayout`): предпочтение принадлежит человеку, а не окну.
 */
const CommitFileList = ({ entries, selection, flat, onLayoutChange, openPath, onOpen }) => {
  const { t } = useTranslation(['chat', 'files']);
  const allPaths = entries.map((entry) => entry.path);
  const allState = selection.stateOf(allPaths);

  return (
    <div className="commit-files">
      <div className="commit-files__toolbar">
        <button
          type="button"
          className="btn btn--ghost btn--sm commit-files__layout"
          aria-pressed={!flat}
          title={flat ? t('files:changes.layoutTree') : t('files:changes.layoutFlat')}
          aria-label={flat ? t('files:changes.layoutTree') : t('files:changes.layoutFlat')}
          onClick={() => onLayoutChange(!flat)}
        >
          {flat ? <IconFolder size={15} /> : <IconList size={15} />}
        </button>
        <label className="commit-files__all">
          <Box state={allState} onToggle={(on) => selection.toggle(allPaths, on)} label={t('commit.selectAll')} />
          <span className="commit-files__all-label">{t('commit.selectAll')}</span>
        </label>
        <span className="commit-files__count">
          {t('commit.ofTotal', { count: selection.count, total: entries.length })}
        </span>
      </div>

      <div className="commit-files__list">
        {flat
          ? sortByName(entries).map((entry) => (
              <Row
                key={entry.path}
                entry={entry}
                showDir
                depth={0}
                selection={selection}
                open={openPath === entry.path}
                onOpen={onOpen}
              />
            ))
          : buildChangeTree(entries).map((node) => (
              <Node key={node.path} node={node} depth={0} selection={selection} openPath={openPath} onOpen={onOpen} />
            ))}
      </div>
    </div>
  );
};

/** Узел дерева: каталог со своей галочкой на всё содержимое, либо файл. */
const Node = ({ node, depth, selection, openPath, onOpen }) => {
  const { t } = useTranslation('chat');
  if (node.type === 'file') {
    return <Row entry={node.entry} depth={depth} selection={selection} open={openPath === node.path} onOpen={onOpen} />;
  }
  const paths = filePaths(node);
  return (
    <>
      <div className="commit-files__row commit-files__row--dir" style={{ '--depth': depth }}>
        {/* Галочка каталога — единственный способ отметить его содержимое разом:
            иначе в дереве на полсотни файлов пришлось бы щёлкать по одному. */}
        <Box
          state={selection.stateOf(paths)}
          onToggle={(on) => selection.toggle(paths, on)}
          label={t('commit.selectDir', { dir: node.path })}
        />
        <span className="commit-files__dir-name">{node.name}</span>
      </div>
      {node.children.map((child) => (
        <Node
          key={child.path}
          node={child}
          depth={depth + 1}
          selection={selection}
          openPath={openPath}
          onOpen={onOpen}
        />
      ))}
    </>
  );
};

/** Строка файла: галочка, статус, имя, каталог (в плоской раскладке) и счётчики. */
const Row = ({ entry, depth = 0, showDir = false, selection, open, onOpen }) => {
  const { t } = useTranslation(['chat', 'files']);
  const name = entry.path.split('/').pop();
  const dir = entry.path.slice(0, entry.path.length - name.length - 1);
  const statusLabel = t(`files:changes.status.${entry.status}`, { defaultValue: entry.status });

  return (
    <div className={`commit-files__row${open ? ' commit-files__row--open' : ''}`} style={{ '--depth': depth }}>
      <Box
        state={selection.checked.has(entry.path) ? 'all' : 'none'}
        onToggle={(on) => selection.toggle([entry.path], on)}
        label={t('commit.selectFile', { path: entry.path })}
      />
      <button
        type="button"
        className="commit-files__open"
        title={`${statusLabel} · ${entry.path}`}
        aria-pressed={open}
        onClick={() => onOpen(entry.path)}
      >
        <span className={`commit-files__status commit-files__status--${entry.status}`}>{entry.status}</span>
        {/* Имя раньше каталога, хотя в пути порядок обратный: сжимать приходится
            каталог — обрезанное имя файла не опознать вовсе. */}
        <span className="commit-files__name">{name}</span>
        {showDir && dir && <span className="commit-files__dir">{dir}</span>}
        <span className="commit-files__stats">
          <DiffStats additions={entry.additions} deletions={entry.deletions} />
        </span>
      </button>
    </div>
  );
};

/**
 * Галочка с третьим состоянием — «часть содержимого каталога». Свой `<button>`,
 * а не `<input type="checkbox">`: `indeterminate` у нативного ставится только
 * из JS по ссылке на узел, то есть записью в DOM мимо рендера.
 */
const Box = ({ state, onToggle, label }) => (
  <button
    type="button"
    className={`commit-box commit-box--${state}`}
    role="checkbox"
    aria-checked={state === 'all' ? 'true' : state === 'some' ? 'mixed' : 'false'}
    aria-label={label}
    onClick={() => onToggle(state !== 'all')}
  >
    {state === 'all' && <IconCheck size={11} />}
    {state === 'some' && <span className="commit-box__dash" />}
  </button>
);

/** Все файлы под узлом каталога — то, что отмечает его галочка. */
function filePaths(node) {
  if (node.type === 'file') return [node.path];
  return node.children.flatMap(filePaths);
}

export default CommitFileList;
