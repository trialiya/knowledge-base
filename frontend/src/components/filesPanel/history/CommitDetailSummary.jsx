import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/utils/formatting';
import CopyButton from '@/components/common/ui/CopyButton';
import useCopyFeedback from '@/components/common/ui/useCopyFeedback';
import CommitHashLink from '@/components/common/git/CommitHashLink';
import shortRev from '@/components/common/git/shortRev';
import { commitUrl } from '@/navigation/urlScheme';
import { IconCommit } from '@/icons/index';

/**
 * Сводка коммита в левой колонке окна деталей: название — заголовком, под ним
 * описание и короткие строки «автор / дата / родитель». В отличие от вкладки
 * «Коммит» снимка (CommitInfo) это не таблица подписей: колонка узкая, и
 * полный хеш, почта автора и итог по файлам в ней только отодвигали бы
 * список — хеш копируется кнопкой, итог стоит над списком.
 *
 * Родитель открывается в новой вкладке: переход внутри приложения увёл бы
 * раздел из-под окна (см. CommitHashLink).
 */
const CommitDetailSummary = ({ hash, project, commit, loading, error, onOpenSnapshot }) => {
  const { t, i18n } = useTranslation('files');
  const [copied, copy] = useCopyFeedback();

  const parents = commit?.parents ?? [];

  return (
    <div className="commit-summary">
      <div className="commit-summary__hash">
        <IconCommit size={14} />
        <span>{shortRev(hash)}</span>
        <CopyButton value={hash} title={t('history.copyHash')} className="icon-btn--xs icon-btn--quiet" />
      </div>
      {loading && <p className="commit-summary__hint">{t('loading')}</p>}
      {!loading && (error || !commit) && <p className="commit-summary__hint">{t('commit.loadError')}</p>}
      {!loading && commit && (
        <>
          <h2 className="commit-summary__title">{commit.message}</h2>
          {commit.body && <p className="commit-summary__body">{commit.body}</p>}
          {/* Не InfoList: тот — таблица вкладки «Инфо» с копированием каждой
              строки, а здесь три короткие строки под заголовком. */}
          <div className="commit-summary__facts">
            <span className="commit-summary__label">{t('commit.author')}</span>
            <span title={commit.email || undefined}>{commit.author}</span>
            <span className="commit-summary__label">{t('commit.date')}</span>
            <span>{formatDateTime(commit.date, i18n.language)}</span>
            {parents.length > 0 && (
              <>
                <span className="commit-summary__label">{t('commit.parents', { count: parents.length })}</span>
                <span className="commit-summary__parents">
                  {parents.map((parent) => (
                    <CommitHashLink key={parent} rev={parent} project={project} newTab>
                      {shortRev(parent)}
                    </CommitHashLink>
                  ))}
                </span>
              </>
            )}
          </div>
        </>
      )}
      <div className="commit-summary__actions">
        <button type="button" className="btn btn--ghost btn--sm" onClick={() => onOpenSnapshot(hash)}>
          {t('history.openSnapshot')}
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={() => copy(window.location.origin + commitUrl(hash, project))}
        >
          {copied ? t('commit.linkCopied') : t('commit.copyLink')}
        </button>
      </div>
    </div>
  );
};

export default CommitDetailSummary;
