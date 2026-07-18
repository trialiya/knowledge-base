import React, { useState, useRef, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import useDocPreview from './useDocPreview';
import useFilePreview from './useFilePreview';
import DocPreviewTooltip from './DocPreviewTooltip';
import FilePreviewTooltip from './FilePreviewTooltip';
import FileFullscreenModal from './FileFullscreenModal';
import FilePreviewModal from './FilePreviewModal';
import gitApi from '../../api/gitApi';
import FullscreenEditorModal from '../knowledgeBasePanel/FullscreenEditorModal';
import { navigateToFile } from '../../fileNavigationBus';
import { parseDocId, parseFileLink } from './docLinkParsing';
import { scrollToHeading } from './anchorScroll';
import { TOOLTIP_WIDTH, TOOLTIP_GAP, TOOLTIP_HEIGHT_ESTIMATE } from '../../constants/ui';

/**
 * Wraps a `/?doc=N` link with a hover-activated preview tooltip. Shared by the
 * Knowledge Base markdown renderer (MarkdownEditor, has a `tree` for instant
 * lookups) and the chat message renderer (Message, no tree — always goes
 * through useDocPreview's module cache). Previously these were two
 * near-identical components (DocLinkTooltip / ChatDocLink); the chat copy was
 * missing the anchor-link branch below and hardcoded a ru-RU/en-US locale
 * instead of the active UI language.
 *
 * Four link kinds are handled:
 *   1. In-document anchors (`#обзор`) — scroll to the heading WITHIN the same
 *      rendered-markdown container, no navigation, no URL change. Heading ids
 *      are produced by rehype-slug in MarkdownEditor (GitHub slugger), matching
 *      the slugs in `[Обзор](#обзор)` tables of contents.
 *   2. Internal KB doc links (`/?doc=N`) — hover preview + click navigation
 *      (or fullscreen preview via the tooltip's expand button).
 *   3. Internal repo file links (`/files?path=P[#Lx-Ly]`) — hover preview;
 *      clicking the link itself opens a read-only FilePreviewModal in place
 *      (doesn't navigate away from chat/KB), while the tooltip's "Open"
 *      button navigates to the Files tab with that file selected.
 *   4. Everything else — plain external `<a target="_blank">`.
 *
 * Navigation for (2) goes through the `onNavigate` prop (in KB this is
 * selectNode, in chat it's openDoc from useAppNavigation) — both accept an id.
 * Navigation for (3)'s "Open" button goes through fileNavigationBus instead,
 * since DocLinkTooltip is too many prop layers away from App (the sole owner
 * of Files-tab navigation state) to thread an equivalent prop through cleanly.
 */
const DocLinkTooltip = ({ href, children, tree = [], onNavigate, ...rest }) => {
  const [visible, setVisible] = useState(false);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  // Снимок узла для fullscreen-превью: useDocPreview сбрасывает node, когда
  // тултип прячется (enabled=false), а модалка живёт дольше тултипа.
  const [fullscreenNode, setFullscreenNode] = useState(null);
  const [fileFullscreenOpen, setFileFullscreenOpen] = useState(false);
  const [fileFullscreenPath, setFileFullscreenPath] = useState(null);
  const [fileFullscreenNode, setFileFullscreenNode] = useState(null);
  const [fileFullscreenLoading, setFileFullscreenLoading] = useState(false);
  const [fileFullscreenError, setFileFullscreenError] = useState(false);
  const [filePreviewOpen, setFilePreviewOpen] = useState(false);
  const enterTimer = useRef(null);
  const leaveTimer = useRef(null);
  const linkRef = useRef(null);
  const tooltipRef = useRef(null);

  const docId = parseDocId(href);
  const isDocLink = docId !== null;
  const fileLink = isDocLink ? null : parseFileLink(href);
  const isFileLink = fileLink !== null;
  const isHashLink = typeof href === 'string' && href.trim().startsWith('#');

  const { node, loading, error } = useDocPreview(docId, tree, visible && isDocLink);
  const { file, loading: fileLoading, error: fileError } = useFilePreview(fileLink?.path, visible && isFileLink);

  // ── Position ────────────────────────────────────────────────────────────

  const calcPos = useCallback(() => {
    if (!linkRef.current) return;
    const rect = linkRef.current.getBoundingClientRect();
    const left = Math.min(Math.max(rect.left, TOOLTIP_GAP), window.innerWidth - TOOLTIP_WIDTH - TOOLTIP_GAP);
    const tooltipH = tooltipRef.current ? tooltipRef.current.offsetHeight : TOOLTIP_HEIGHT_ESTIMATE;
    const spaceBelow = window.innerHeight - rect.bottom - TOOLTIP_GAP;

    const top =
      spaceBelow >= tooltipH || spaceBelow >= rect.top - TOOLTIP_GAP
        ? rect.bottom + TOOLTIP_GAP
        : rect.top - tooltipH - TOOLTIP_GAP;

    setPos({ top, left });
  }, []);

  // Re-measure after content arrives (height changes on load)
  useEffect(() => {
    if (visible) calcPos();
  }, [visible, node, loading, file, fileLoading, calcPos]);

  // ── Hover ───────────────────────────────────────────────────────────────

  const handleMouseEnter = useCallback(() => {
    clearTimeout(leaveTimer.current);
    enterTimer.current = setTimeout(() => {
      calcPos();
      setVisible(true);
    }, 180);
  }, [calcPos]);

  const handleMouseLeave = useCallback(() => {
    clearTimeout(enterTimer.current);
    leaveTimer.current = setTimeout(() => setVisible(false), 200);
  }, []);

  const keepOpen = useCallback(() => clearTimeout(leaveTimer.current), []);

  useEffect(
    () => () => {
      clearTimeout(enterTimer.current);
      clearTimeout(leaveTimer.current);
    },
    [],
  );

  // ── Navigate ─────────────────────────────────────────────────────────────
  // Навигация идёт через проп onNavigate (в KB это selectNode, в чате —
  // openDoc), оба принимают id документа.
  const navigateToDoc = useCallback(
    (id) => {
      onNavigate?.(id);
    },
    [onNavigate],
  );

  const handleClick = useCallback(
    (e) => {
      e.preventDefault();
      if (docId) navigateToDoc(docId);
    },
    [docId, navigateToDoc],
  );

  // Файловая ссылка не уходит из чата — клик всегда открывает read-only
  // модалку (FilePreviewModal), а не FilesPanel. Тултип при этом прячем,
  // иначе он остаётся смонтированным под оверлеем и «выглядывает» после
  // закрытия модалки.
  const openFilePreview = useCallback(() => {
    clearTimeout(enterTimer.current);
    setVisible(false);
    setFilePreviewOpen(true);
  }, []);

  const handleFileClick = useCallback(
    (e) => {
      e.preventDefault();
      openFilePreview();
    },
    [openFilePreview],
  );

  // "Open" в тултипе — в отличие от клика по самой ссылке (который держит
  // read-only модалку, не уводя из чата/KB) — ведёт себя как аналогичная
  // кнопка у doc-ссылок: полноценный переход, здесь — во вкладку Files с
  // выбранным файлом.
  const openInFilesPanel = useCallback(() => {
    clearTimeout(enterTimer.current);
    setVisible(false);
    navigateToFile(fileLink?.path);
  }, [fileLink]);

  // То же для fullscreen-превью документа, открываемого из тултипа. Узел
  // снимается в отдельный стейт, потому что node из useDocPreview обнуляется
  // вместе с visible.
  const openFullscreen = useCallback((n) => {
    clearTimeout(enterTimer.current);
    setVisible(false);
    setFullscreenNode(n);
  }, []);

  const openFileFullscreen = useCallback((f) => {
    clearTimeout(enterTimer.current);
    setVisible(false);
    setFileFullscreenOpen(true);
    setFileFullscreenPath(f.path);
    setFileFullscreenLoading(true);
    setFileFullscreenError(false);
    setFileFullscreenNode(null);
    gitApi
      .getFileContent(f.path)
      .then((full) => {
        setFileFullscreenNode(full);
        setFileFullscreenLoading(false);
      })
      .catch(() => {
        setFileFullscreenError(true);
        setFileFullscreenLoading(false);
      });
  }, []);

  // ── In-document anchor (#heading) ──────────────────────────────────────────
  // Scroll to the slugged heading within THIS rendered-markdown container.
  // STRICT matching: the anchor must equal the generated heading id verbatim
  // (rehype-slug / github-slugger). A mismatch (e.g. `#обзор` pointing at a
  // numbered `## 1. Обзор` whose id is `1-обзор`) intentionally does nothing —
  // it signals a bad anchor to fix in the source markdown, rather than being
  // silently "rescued". Container scoping avoids id clashes when an inline and
  // a fullscreen editor are mounted at once.
  const scrollToAnchor = useCallback(
    (e) => {
      e.preventDefault();
      const raw = (href || '').slice(1);
      if (!raw) return;
      let decoded = raw;
      try {
        decoded = decodeURIComponent(raw);
      } catch {
        /* keep raw */
      }
      const container = e.currentTarget.closest('.md-preview, .md-preview--embedded') || document;
      // Anchor targets are always headings; querying h1–h6[id] (instead of all
      // [id]) is both semantically correct and avoids matching any non-heading
      // element that might carry the same id.
      const target = Array.from(container.querySelectorAll('h1[id], h2[id], h3[id], h4[id], h5[id], h6[id]')).find(
        (el) => el.id === decoded || el.id === raw,
      );
      if (target) scrollToHeading(target);
    },
    [href],
  );

  // ── In-document anchor link ────────────────────────────────────────────────

  if (isHashLink) {
    return (
      <a href={href} className="doc-link doc-link--anchor" onClick={scrollToAnchor} {...rest}>
        {children}
      </a>
    );
  }

  // ── File link (/files?path=...) ────────────────────────────────────────────

  if (isFileLink) {
    return (
      <>
        <a
          ref={linkRef}
          href={href}
          className="doc-link"
          onClick={handleFileClick}
          onMouseEnter={handleMouseEnter}
          onMouseLeave={handleMouseLeave}
          {...rest}
        >
          {children}
        </a>

        {visible &&
          createPortal(
            <FilePreviewTooltip
              ref={tooltipRef}
              file={file}
              loading={fileLoading}
              error={fileError}
              pos={pos}
              onMouseEnter={keepOpen}
              onMouseLeave={handleMouseLeave}
              onOpen={openInFilesPanel}
              onExpand={openFileFullscreen}
            />,
            document.body,
          )}

        {filePreviewOpen && (
          <FilePreviewModal
            path={fileLink.path}
            fromLine={fileLink.fromLine}
            toLine={fileLink.toLine}
            onClose={() => setFilePreviewOpen(false)}
          />
        )}

        {fileFullscreenOpen && (
          <FileFullscreenModal
            path={fileFullscreenPath}
            file={fileFullscreenNode}
            loading={fileFullscreenLoading}
            error={fileFullscreenError}
            onClose={() => setFileFullscreenOpen(false)}
          />
        )}
      </>
    );
  }

  // ── Non-doc link ────────────────────────────────────────────────────────

  if (!isDocLink) {
    return (
      <a href={href} target="_blank" rel="noopener noreferrer" {...rest}>
        {children}
      </a>
    );
  }

  return (
    <>
      <a
        ref={linkRef}
        href={href}
        className="doc-link"
        onClick={handleClick}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
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
            onMouseLeave={handleMouseLeave}
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

export default DocLinkTooltip;
