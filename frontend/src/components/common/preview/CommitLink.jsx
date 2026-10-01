import { useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import useCommitPreview from './useCommitPreview';
import useLinkTooltip from './useLinkTooltip';
import CommitPreviewTooltip from './CommitPreviewTooltip';
import { navigateToCommit } from '@/navigation/fileNavigationBus';
import useProjectConfig from '@/components/common/config/useProjectConfig';
import { commitUrl } from '@/navigation/urlScheme';
import AppLink from '@/components/common/ui/AppLink';

/**
 * Ссылка на коммит в отрендеренном markdown (`/files?rev=HASH[&project=ID]`, разбор —
 * parseCommitLink). Наведение — карточка коммита; клик — переход в «Файлы» на
 * этот коммит: слева изменённые им файлы, справа вкладка «Коммит».
 *
 * В отличие от файловой ссылки, клик уводит из чата, а не открывает модалку на
 * месте: всё, ради чего открывают коммит, — список файлов и diff каждого — уже
 * есть в панели «Файлы», а беглый взгляд даёт карточка.
 *
 * `commitLink` — ответ parseCommitLink.
 */
const CommitLink = ({ commitLink, children, ...rest }) => {
  const { hash } = commitLink;
  const project = commitLink.project ?? null;
  const { defaultProjectId } = useProjectConfig();
  const { visible, pos, linkRef, tooltipRef, calcPos, onMouseEnter, onMouseLeave, keepOpen, hide } = useLinkTooltip();

  // Как у файловой ссылки: в адрес — что назвала ссылка, в запрос и ключ кэша —
  // разрешённый id, чтобы два написания одного проекта не делили кэш надвое.
  const { commit, loading, error } = useCommitPreview(hash, project ?? defaultProjectId, visible);

  useEffect(() => {
    if (visible) calcPos();
  }, [visible, commit, loading, calcPos]);

  const open = useCallback(() => {
    hide();
    navigateToCommit(hash, project);
  }, [hide, hash, project]);

  return (
    <>
      <AppLink
        ref={linkRef}
        href={commitUrl(hash, project)}
        className="doc-link doc-link--commit"
        onNavigate={open}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
        {...rest}
      >
        {children}
      </AppLink>

      {visible &&
        createPortal(
          <CommitPreviewTooltip
            ref={tooltipRef}
            commit={commit}
            loading={loading}
            error={error}
            pos={pos}
            onMouseEnter={keepOpen}
            onMouseLeave={onMouseLeave}
            onOpen={open}
          />,
          document.body,
        )}
    </>
  );
};

export default CommitLink;
