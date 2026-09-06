import { useTranslation } from 'react-i18next';
import { IconList, IconFolder } from '@/icons/index';
import FileSearch from './FileSearch';
import GitBranchBar from './git/GitBranchBar';
import RevisionPicker from './git/RevisionPicker';

/**
 * Тулбар левой панели: какую ревизию показывает панель, на какой ветке
 * репозиторий, чем панель его показывает — деревом файлов или списком
 * незакоммиченных изменений, — поиск файла и, в режиме изменений, раскладка
 * списка.
 *
 * Оба переключателя — общие классы кнопок с `aria-pressed` (см. buttons.css):
 * включённое состояние в них уже нарисовано, своего семейства «сегментов»
 * заводить не за чем.
 */
const FilesToolbar = ({
  project,
  changes,
  onChangesToggle,
  flat,
  onFlatToggle,
  onSelect,
  git,
  actions,
  rev,
  onRevChange,
  gitRefsToken,
}) => {
  const { t } = useTranslation('files');

  // В снимке ревизии из тулбара уходит всё, что про рабочее дерево: строка
  // ветки с её командами, переключатель «Изменения» и поиск по имени. Не
  // «выключено и серо», а именно нет: незакоммиченных изменений у коммита не
  // бывает, и предлагать их значило бы обещать ответ, которого не существует.
  const snapshot = !!rev;

  return (
    <div className="files-toolbar">
      <RevisionPicker project={project} rev={rev} refsToken={gitRefsToken} onChange={onRevChange} />
      {!snapshot && (
        <GitBranchBar
          status={git.status}
          capabilities={git.capabilities}
          running={git.running}
          onFetch={actions.fetch}
          onAbortMerge={actions.abortMerge}
          commands={{
            onSwitch: actions.switchBranch,
            onCreateBranch: actions.askNewBranch,
            onStashPush: actions.stashPush,
            onStashPop: actions.stashPop,
            onCommit: actions.askCommit,
            onPull: actions.pull,
            onPush: actions.askPush,
          }}
        />
      )}
      {!snapshot && (
        <div className="files-toolbar__row">
          <div className="files-toolbar__modes" role="group" aria-label={t('panel.mode')}>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              aria-pressed={!changes}
              onClick={() => onChangesToggle(false)}
            >
              {t('panel.modeFiles')}
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              aria-pressed={changes}
              onClick={() => onChangesToggle(true)}
            >
              {t('panel.modeChanges')}
            </button>
          </div>
          {changes && (
            <button
              type="button"
              className="icon-btn"
              aria-pressed={!flat}
              title={flat ? t('changes.layoutTree') : t('changes.layoutFlat')}
              onClick={() => onFlatToggle(!flat)}
            >
              {flat ? <IconFolder size={16} /> : <IconList size={15} />}
            </button>
          )}
        </div>
      )}
      {/* Поиск по имени спрашивает рабочее дерево: в снимке он открывал бы
          пути, которых в нём может не быть. */}
      {!snapshot && <FileSearch project={project} onSelect={onSelect} />}
    </div>
  );
};

export default FilesToolbar;
