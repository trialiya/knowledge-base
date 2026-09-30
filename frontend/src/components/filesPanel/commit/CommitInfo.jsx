import { useTranslation } from 'react-i18next';
import InfoList from '@/components/common/ui/InfoList';
import { formatDateTime } from '@/utils/formatting';
import useCopyFeedback from '@/components/common/ui/useCopyFeedback';
import { commitUrl } from '@/navigation/urlScheme';
import CommitHashLink from '@/components/common/git/CommitHashLink';
import shortRev from '@/components/common/git/shortRev';
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
 * «Копировать ссылку» даёт адрес коммита, а не текущего экрана: открыт ли тут
 * файл, какая вкладка слева — дело смотрящего, а ссылка должна вести к коммиту
 * по полному хешу, даже когда снимок открыт по ветке, которая уйдёт вперёд.
 *
 * `commit` — ответ useSnapshotCommit (GitCommit с `files`).
 */
const CommitInfo = ({ rev, project, commit, loading, error, changesShown, onShowChanges }) => {
  const { t, i18n } = useTranslation('files');
  const [copied, copy] = useCopyFeedback();

  if (loading) return <p className="info-list__hint">{t('loading')}</p>;
  if (error || !commit) return <p className="info-list__hint">{t('commit.loadError')}</p>;

  const files = commit.files ?? [];
  const parents = commit.parents ?? [];
  const additions = files.reduce((sum, file) => sum + file.additions, 0);
  const deletions = files.reduce((sum, file) => sum + file.deletions, 0);

  const rows = [
    { label: t('commit.hash'), value: commit.hash, mono: true },
    // Ревизию, названную не хешем (ветка, тег, HEAD~2), стоит показать рядом:
    // по адресу видно имя, а какой коммит оно сейчас значит — только здесь.
    { label: t('commit.revision'), value: commit.hash.startsWith(rev) ? null : rev, mono: true },
    { label: t('commit.author'), value: commit.email ? `${commit.author} <${commit.email}>` : commit.author },
    { label: t('commit.date'), value: formatDateTime(commit.date, i18n.language) },
    // Родители — шаг назад по истории: снимок предыдущего коммита в том же виде.
    // У первого коммита их нет, у слияния — два, и первый из них тот, с которым
    // сравнивается список изменённых файлов.
    parents.length > 0 && {
      label: t('commit.parents', { count: parents.length }),
      value: (
        <span className="commit-info__parents">
          {parents.map((parent) => (
            <CommitHashLink key={parent} rev={parent} project={project}>
              {shortRev(parent)}
            </CommitHashLink>
          ))}
        </span>
      ),
      copy: parents.join(' '),
      mono: true,
    },
    { label: t('commit.message'), value: commit.message, block: true },
    { label: t('commit.body'), value: commit.body, block: true, pre: true },
    {
      label: t('commit.files'),
      value: t('commit.filesSummary', { count: files.length, additions, deletions }),
    },
  ];

  // Кнопки под списком, а не в `note` InfoList: та плашка — для предупреждений.
  return (
    <>
      <InfoList rows={rows} />
      <div className="commit-info__actions">
        {files.length > 0 && !changesShown && (
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => onShowChanges(true)}>
            {t('commit.showChanges')}
          </button>
        )}
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={() => copy(window.location.origin + commitUrl(commit.hash, project))}
        >
          {copied ? t('commit.linkCopied') : t('commit.copyLink')}
        </button>
      </div>
    </>
  );
};

export default CommitInfo;
