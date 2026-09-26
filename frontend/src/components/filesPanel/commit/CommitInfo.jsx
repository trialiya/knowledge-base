import { useTranslation } from 'react-i18next';
import InfoList from '@/components/common/ui/InfoList';
import { formatDateTime } from '@/utils/formatting';
import './commitInfo.css';

/**
 * Вкладка «Коммит» правой панели — о снимке целиком, а не об открытом файле:
 * «Инфо» рассказывает, кто последним тронул выбранный путь, а здесь — что это
 * за коммит, зачем он и что в нём поменялось.
 *
 * Сами изменённые файлы — не здесь, а в режиме «Изменения» слева: там уже есть
 * и дерево, и плоский список, и открытие diff'а по клику, и в узкой правой
 * панели второй такой же список проигрывал бы первому. Отсюда — сводка и
 * переход к нему.
 *
 * `commit` — ответ useSnapshotCommit (GitCommit с `files`).
 */
const CommitInfo = ({ rev, commit, loading, error, changesShown, onShowChanges }) => {
  const { t, i18n } = useTranslation('files');

  if (loading) return <p className="info-list__hint">{t('loading')}</p>;
  if (error || !commit) return <p className="info-list__hint">{t('commit.loadError')}</p>;

  const files = commit.files ?? [];
  const additions = files.reduce((sum, file) => sum + file.additions, 0);
  const deletions = files.reduce((sum, file) => sum + file.deletions, 0);

  const rows = [
    { label: t('commit.hash'), value: commit.hash, mono: true },
    // Ревизию, названную не хешем (ветка, тег, HEAD~2), стоит показать рядом:
    // по адресу видно имя, а какой коммит оно сейчас значит — только здесь.
    { label: t('commit.revision'), value: commit.hash.startsWith(rev) ? null : rev, mono: true },
    { label: t('commit.author'), value: commit.email ? `${commit.author} <${commit.email}>` : commit.author },
    { label: t('commit.date'), value: formatDateTime(commit.date, i18n.language) },
    { label: t('commit.message'), value: commit.message, block: true },
    { label: t('commit.body'), value: commit.body, block: true, pre: true },
    {
      label: t('commit.files'),
      value: t('commit.filesSummary', { count: files.length, additions, deletions }),
    },
  ];

  // Кнопка под списком, а не в `note` InfoList: та плашка — для предупреждений.
  return (
    <>
      <InfoList rows={rows} />
      {files.length > 0 && !changesShown && (
        <div className="commit-info__actions">
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => onShowChanges(true)}>
            {t('commit.showChanges')}
          </button>
        </div>
      )}
    </>
  );
};

export default CommitInfo;
