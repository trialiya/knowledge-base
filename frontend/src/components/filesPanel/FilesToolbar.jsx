import { useTranslation } from 'react-i18next';
import { IconList, IconFolder } from '@/icons/index';
import FileSearch from './FileSearch';
import GitBranchBar from './git/GitBranchBar';

/**
 * Тулбар левой панели: на какой ветке репозиторий, чем панель его показывает —
 * деревом файлов или списком незакоммиченных изменений, — поиск файла и, в
 * режиме изменений, раскладка списка.
 *
 * Оба переключателя — общие классы кнопок с `aria-pressed` (см. buttons.css):
 * включённое состояние в них уже нарисовано, своего семейства «сегментов»
 * заводить не за чем.
 */
const FilesToolbar = ({ project, changes, onChangesToggle, flat, onFlatToggle, onSelect, git, onFetch }) => {
  const { t } = useTranslation('files');

  return (
    <div className="files-toolbar">
      <GitBranchBar status={git.status} capabilities={git.capabilities} running={git.running} onFetch={onFetch} />
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
      <FileSearch project={project} onSelect={onSelect} />
    </div>
  );
};

export default FilesToolbar;
