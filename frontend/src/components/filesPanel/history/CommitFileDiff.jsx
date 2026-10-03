import { useTranslation } from 'react-i18next';
import shortRev from '@/components/common/git/shortRev';
import Breadcrumb from '../Breadcrumb';
import ChangeDiffView from '../changes/ChangeDiffView';
import useChangeDiff from '../changes/useChangeDiff';

/**
 * Центр файлового браузера, когда файл открыт из ленты коммитов: изменение
 * этого файла в выбранном коммите, а не сам файл. Патч спрашивается тем же
 * запросом, что у снимка в режиме «Изменения» (useChangeDiff с ревизией).
 *
 * Отсюда два шага дальше: «Открыть файл» — сам файл в том, что показывает
 * панель, «Открыть снимок» — весь коммит, как его показывает ссылка на коммит.
 */
const CommitFileDiff = ({ project, path, commit, onNavigate, onOpenFile, onOpenSnapshot }) => {
  const { t } = useTranslation('files');
  const diff = useChangeDiff({ project, path, rev: commit, enabled: true });

  return (
    <div className="file-content">
      <Breadcrumb path={path} onNavigate={onNavigate} />
      <div className="file-content__body">
        <div className="file-view">
          <div className="file-view__meta" data-find-skip="">
            <span className="file-view__badge" title={commit}>
              {shortRev(commit)}
            </span>
            {diff.entry && (
              <span className="file-view__badge file-view__badge--warn">
                {t(`changes.status.${diff.entry.status}`, {
                  defaultValue: diff.entry.status,
                })}
              </span>
            )}
            <span className="commit-file-diff__spacer" />
            {diff.entry?.status !== 'D' && (
              <button type="button" className="btn btn--ghost btn--sm" onClick={() => onOpenFile(path)}>
                {t('history.openFile')}
              </button>
            )}
            <button type="button" className="btn btn--ghost btn--sm" onClick={() => onOpenSnapshot(commit)}>
              {t('history.openSnapshot')}
            </button>
          </div>
          <ChangeDiffView diff={diff} />
        </div>
      </div>
    </div>
  );
};

export default CommitFileDiff;
