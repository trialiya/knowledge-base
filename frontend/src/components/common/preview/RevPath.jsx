import CommitHashLink from '@/components/common/git/CommitHashLink';
import shortRev from '@/components/common/git/shortRev';

/**
 * Путь файла и, если файл показан в снимке, ревизия — ссылкой на сам коммит:
 * `docs/a.md @ 1a2b3c4`. Ссылка открывается в новой вкладке: все места, где
 * она стоит, — модалки и карточка поверх чата, и переход внутри приложения
 * увёл бы раздел из-под них (см. CommitHashLink).
 */
const RevPath = ({ path, rev, project = null }) =>
  rev ? (
    <>
      {path} @{' '}
      <CommitHashLink rev={rev} project={project} newTab>
        {shortRev(rev)}
      </CommitHashLink>
    </>
  ) : (
    path
  );

export default RevPath;
