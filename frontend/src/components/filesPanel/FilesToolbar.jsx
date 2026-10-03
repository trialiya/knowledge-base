import { useTranslation } from 'react-i18next';
import { IconList, IconFolder } from '@/icons/index';
import RevisionPicker from '@/components/common/git/RevisionPicker';
import FileSearch from './FileSearch';
import GitBranchBar from './git/GitBranchBar';
import { FILE_MODE } from '@/constants/fileModes';

/**
 * Тулбар левой панели: какую ревизию показывает панель, на какой ветке
 * репозиторий, чем панель его показывает — деревом файлов, списком
 * незакоммиченных изменений или лентой коммитов, — поиск файла и, в режиме
 * изменений, раскладка списка.
 *
 * Оба переключателя — общие классы кнопок с `aria-pressed` (см. buttons.css):
 * включённое состояние в них уже нарисовано, своего семейства «сегментов»
 * заводить не за чем.
 */
const FilesToolbar = ({
  project,
  mode,
  onModeChange,
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
  // ветки с её командами и поиск по имени. Не «выключено и серо», а именно нет:
  // предлагать их значило бы обещать ответ про то, чего на экране нет.
  // Переключатель «Изменения» остаётся, но значит другое — файлы, изменённые
  // самим коммитом: незакоммиченных у снимка не бывает, а этот вопрос о нём
  // задают первым.
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
      <div className="files-toolbar__row">
        <div className="files-toolbar__modes" role="group" aria-label={t('panel.mode')}>
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            aria-pressed={mode === FILE_MODE.TREE}
            onClick={() => onModeChange(FILE_MODE.TREE)}
          >
            {t('panel.modeFiles')}
          </button>
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            aria-pressed={mode === FILE_MODE.CHANGES}
            title={snapshot ? t('panel.commitChanges') : undefined}
            onClick={() => onModeChange(FILE_MODE.CHANGES)}
          >
            {t('panel.modeChanges')}
          </button>
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            aria-pressed={mode === FILE_MODE.HISTORY}
            onClick={() => onModeChange(FILE_MODE.HISTORY)}
          >
            {t('panel.modeHistory')}
          </button>
        </div>
        {mode === FILE_MODE.CHANGES && (
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
      {/* Поиск по имени спрашивает рабочее дерево: в снимке он открывал бы
          пути, которых в нём может не быть. */}
      {!snapshot && <FileSearch project={project} onSelect={onSelect} />}
    </div>
  );
};

export default FilesToolbar;
