import { useState, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import useDocPreview from './useDocPreview';
import useLinkTooltip, { isBrowserClick } from './useLinkTooltip';
import DocPreviewTooltip from './DocPreviewTooltip';
import FileLink from './FileLink';
import CommitLink from './CommitLink';
import documentsApi from '@/api/documentsApi';
import FullscreenEditorModal from '@/components/knowledgeBasePanel/editor/FullscreenEditorModal';
import { parseCommitLink, parseDocId, parseFileLink } from './docLinkParsing';
import { docPath } from '@/navigation/urlScheme';
import { scrollToHeading } from './anchorScroll';

/**
 * Every link in rendered markdown goes through here. Shared by the Knowledge Base
 * markdown renderer (MarkdownEditor, has a `tree` for instant lookups) and the chat
 * message renderer (Message, no tree — always goes through useDocPreview's module
 * cache).
 *
 * Five link kinds are handled:
 *   1. In-document anchors (`#обзор`) — scroll to the heading WITHIN the same
 *      rendered-markdown container, no navigation, no URL change. Heading ids
 *      are produced by rehype-slug in MarkdownEditor (GitHub slugger), matching
 *      the slugs in `[Обзор](#обзор)` tables of contents.
 *   2. Internal KB doc links (`/?doc=N`) — hover preview + click navigation
 *      (or fullscreen preview via the tooltip's expand button). Rendered here.
 *   3. Internal repo file links (`/files?path=P[&rev=R][#Lx-Ly]`) — FileLink.
 *   4. Internal commit links (`/files?rev=HASH`, no path) — CommitLink.
 *   5. Everything else — plain external `<a target="_blank">`.
 *
 * Navigation for (2) goes through the `onNavigate` prop (in KB this is
 * selectNode, in chat it's openDoc from useAppNavigation) — both accept an id.
 * (3) and (4) navigate through fileNavigationBus instead, since this component is
 * too many prop layers away from App (the sole owner of Files-tab navigation
 * state) to thread an equivalent prop through cleanly.
 *
 * ⚠️ The rendered `href` is NOT the one from the markdown. Links are stored in
 * their historical form (`/?doc=N`, `/files?path=P`, `/files?rev=H`), which a left
 * click never follows — but the middle button and Ctrl/Cmd+click do, and they open
 * a real browser tab on that address. So the anchor always carries the CANONICAL
 * address (`/knowledge/doc/N`, `/files/P`, urlScheme.commitUrl) while parsing keeps
 * accepting both forms; the stored markdown is untouched.
 */
// Дерева нет (чат) — но значение по умолчанию должно быть одним и тем же
// массивом: useDocPreview строит по нему затравку и держит её в зависимостях.
const NO_TREE = [];

const DocLinkTooltip = ({ href, children, tree = NO_TREE, onNavigate, ...rest }) => {
  const docId = parseDocId(href);
  const fileLink = docId === null ? parseFileLink(href) : null;
  const commitLink = docId === null && !fileLink ? parseCommitLink(href) : null;

  if (typeof href === 'string' && href.trim().startsWith('#')) {
    return (
      <AnchorLink href={href} {...rest}>
        {children}
      </AnchorLink>
    );
  }

  if (fileLink) {
    return (
      <FileLink fileLink={fileLink} {...rest}>
        {children}
      </FileLink>
    );
  }

  if (commitLink) {
    return (
      <CommitLink commitLink={commitLink} {...rest}>
        {children}
      </CommitLink>
    );
  }

  if (docId === null) {
    return (
      <a href={href} target="_blank" rel="noopener noreferrer" {...rest}>
        {children}
      </a>
    );
  }

  return (
    <DocLink docId={docId} href={href} tree={tree} onNavigate={onNavigate} {...rest}>
      {children}
    </DocLink>
  );
};

/** Ссылка на документ базы знаний: карточка при наведении, переход по клику. */
const DocLink = ({ docId, href, tree, onNavigate, children, ...rest }) => {
  // Снимок узла для fullscreen-превью: useDocPreview сбрасывает node, когда
  // тултип прячется (enabled=false), а модалка живёт дольше тултипа.
  const [fullscreenNode, setFullscreenNode] = useState(null);
  const { visible, pos, linkRef, tooltipRef, calcPos, onMouseEnter, onMouseLeave, keepOpen, hide } =
    useLinkTooltip();

  // Адрес для «настоящего» перехода браузера (средняя кнопка / Ctrl+Cmd-клик):
  // всегда каноническая схема, независимо от того, в какой форме ссылка лежит в
  // markdown. Якорь раздела сохраняем — он часть адреса.
  const docHref = docPath(docId) + fragmentOf(href);

  const { node, loading, error } = useDocPreview(docId, tree, visible);

  useEffect(() => {
    if (visible) calcPos();
  }, [visible, node, loading, calcPos]);

  // Навигация идёт через проп onNavigate (в KB это selectNode, в чате —
  // openDoc), оба принимают id документа.
  const navigateToDoc = useCallback((id) => onNavigate?.(id), [onNavigate]);

  const handleClick = useCallback(
    (e) => {
      // Клик с модификатором (или не левой кнопкой) — отдаём браузеру: он откроет
      // href в новой вкладке/окне. Средняя кнопка сюда не приходит вовсе (auxclick).
      if (isBrowserClick(e)) return;
      e.preventDefault();
      navigateToDoc(docId);
    },
    [docId, navigateToDoc],
  );

  // `_stub` — узел, взятый из уже загруженного дерева KB: бэкенд отдаёт в дереве
  // только первые 150 символов описания (DocumentService.SNIPPET_LENGTH). Для
  // тултипа этого хватает, а «развернуть» показывает документ целиком, поэтому
  // полный текст дотягиваем (обычно он уже приехал фоном — тогда ветка не нужна).
  const openFullscreen = useCallback(
    (n) => {
      hide();
      setFullscreenNode(n);
      if (!n?._stub) return;
      documentsApi
        .fetchById(n.id)
        .then((full) => setFullscreenNode((cur) => (cur && cur.id === full.id ? full : cur)))
        .catch(() => {
          /* оставляем то, что есть */
        });
    },
    [hide],
  );

  return (
    <>
      <a
        ref={linkRef}
        href={docHref}
        className="doc-link"
        onClick={handleClick}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
        {...rest}
      >
        {children}
      </a>

      {visible &&
        createPortal(
          <DocPreviewTooltip
            ref={tooltipRef}
            node={node}
            loading={loading}
            error={error}
            pos={pos}
            onMouseEnter={keepOpen}
            onMouseLeave={onMouseLeave}
            onNavigate={navigateToDoc}
            onExpand={openFullscreen}
          />,
          document.body,
        )}

      {fullscreenNode && (
        <FullscreenEditorModal
          title={fullscreenNode.title}
          value={fullscreenNode.description || ''}
          previewOnly
          tree={tree}
          onNavigate={navigateToDoc}
          onClose={() => setFullscreenNode(null)}
        />
      )}
    </>
  );
};

/**
 * Якорь внутри документа: прокрутка к заголовку в ЭТОМ же контейнере разметки.
 *
 * Совпадение строгое: якорь обязан быть ровно id заголовка (rehype-slug /
 * github-slugger). Промах (`#обзор` при нумерованном `## 1. Обзор` с id
 * `1-обзор`) намеренно ничего не делает — это сигнал исправить исходник, а не
 * повод молча «спасать» ссылку. Поиск внутри контейнера не даёт столкнуться id,
 * когда встроенный и полноэкранный редакторы смонтированы одновременно.
 */
const AnchorLink = ({ href, children, ...rest }) => {
  const scrollToAnchor = (e) => {
    e.preventDefault();
    const raw = href.trim().slice(1);
    if (!raw) return;
    let decoded = raw;
    try {
      decoded = decodeURIComponent(raw);
    } catch {
      /* keep raw */
    }
    const container = e.currentTarget.closest('.md-preview, .md-preview--embedded') || document;
    // Цель якоря — всегда заголовок: h1–h6[id] вместо любого [id] не зацепит
    // посторонний элемент с тем же id.
    const target = Array.from(container.querySelectorAll('h1[id], h2[id], h3[id], h4[id], h5[id], h6[id]')).find(
      (el) => el.id === decoded || el.id === raw,
    );
    if (target) scrollToHeading(target);
  };

  return (
    <a href={href} className="doc-link doc-link--anchor" onClick={scrollToAnchor} {...rest}>
      {children}
    </a>
  );
};

/** Якорь исходной ссылки (`/?doc=76#сводка` → `#сводка`), либо ''. */
function fragmentOf(href) {
  const i = String(href || '').indexOf('#');
  return i === -1 ? '' : String(href).slice(i);
}

export default DocLinkTooltip;
