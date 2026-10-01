import { useState, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import useFilePreview from './useFilePreview';
import useLinkTooltip, { isBrowserClick } from './useLinkTooltip';
import FilePreviewTooltip from './FilePreviewTooltip';
import FilePreviewModal from './FilePreviewModal';
import { navigateToFile } from '@/navigation/fileNavigationBus';
import useProjectConfig from '@/components/common/config/useProjectConfig';
import { filesUrl } from '@/navigation/urlScheme';

/**
 * Ссылка на файл репозитория в отрендеренном markdown (`/files?path=P[&rev=R][#Lx-Ly]`,
 * разбор — parseFileLink). Наведение — карточка с началом файла; клик по самой
 * ссылке открывает read-only FilePreviewModal на месте, не уводя из чата или
 * базы знаний, а «Открыть» в карточке — полноценный переход в «Файлы».
 *
 * Ревизия ссылки — версия, которую процитировала модель: её читает и карточка,
 * и модалка, и переход открывает файл в том же снимке. Без неё рабочее дерево.
 *
 * `fileLink` — ответ parseFileLink.
 */
const FileLink = ({ fileLink, children, ...rest }) => {
  const { path, rev, fromLine, toLine } = fileLink;
  // Модалка файла: `range` — по клику на саму ссылку (её строки), `whole` — по
  // «развернуть» в карточке (она показывает голову файла, развёрнутый вид — его целиком).
  const [modal, setModal] = useState(null); // 'range' | 'whole' | null
  const { visible, pos, linkRef, tooltipRef, calcPos, onMouseEnter, onMouseLeave, keepOpen, hide } = useLinkTooltip();

  // Два написания одного и того же проекта не должны разъезжаться. В АДРЕС идёт
  // то, что назвала ссылка (дефолтный проект в схеме не пишется), а в ЗАПРОСЫ и
  // ключи кэша — разрешённый id: сброс превью после правки файла приходит именно
  // с ним, и ссылка без проекта иначе висела бы устаревшей весь TTL.
  const project = fileLink.project ?? null;
  const { defaultProjectId } = useProjectConfig();
  const projectResolved = project ?? defaultProjectId;
  // Адрес для «настоящего» перехода браузера (средняя кнопка / Ctrl+Cmd-клик):
  // каноническая схема, диапазон строк сохраняем — он часть адреса.
  const href = filesUrl(path, project, { rev }) + lineHash(fileLink);

  const { file, loading, error } = useFilePreview(path, projectResolved, visible, rev);

  // Карточка меняет высоту, когда приезжает содержимое.
  useEffect(() => {
    if (visible) calcPos();
  }, [visible, file, loading, calcPos]);

  const handleClick = useCallback(
    (e) => {
      if (isBrowserClick(e)) return;
      e.preventDefault();
      hide();
      setModal('range');
    },
    [hide],
  );

  const openInFilesPanel = useCallback(() => {
    hide();
    // Ревизию передаём и пустой: ссылка на рабочее дерево, нажатая в снимке,
    // должна открыть рабочее дерево, а не тот же путь в чужом коммите. Слева —
    // дерево: режим «Изменения» мог остаться включённым от перехода к коммиту.
    navigateToFile(path, project, { rev: rev || '', changes: false });
  }, [hide, path, project, rev]);

  const openWhole = useCallback(() => {
    hide();
    setModal('whole');
  }, [hide]);

  return (
    <>
      <a
        ref={linkRef}
        href={href}
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
          <FilePreviewTooltip
            ref={tooltipRef}
            file={file}
            rev={rev}
            project={project}
            loading={loading}
            error={error}
            pos={pos}
            onMouseEnter={keepOpen}
            onMouseLeave={onMouseLeave}
            onOpen={openInFilesPanel}
            onExpand={openWhole}
          />,
          document.body,
        )}

      {modal && (
        <FilePreviewModal
          path={path}
          project={project}
          rev={rev}
          fromLine={modal === 'range' ? fromLine : undefined}
          toLine={modal === 'range' ? toLine : undefined}
          onClose={() => setModal(null)}
        />
      )}
    </>
  );
};

/** `#L42` / `#L42-L58` для файловой ссылки, либо '' — если строки не заданы. */
function lineHash({ fromLine, toLine }) {
  if (!fromLine) return '';
  return toLine && toLine !== fromLine ? `#L${fromLine}-L${toLine}` : `#L${fromLine}`;
}

export default FileLink;
