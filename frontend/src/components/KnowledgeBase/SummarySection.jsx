import React, { useState, useRef, useEffect } from 'react';
import MarkdownEditor from './MarkdownEditor';
import { IconEdit, IconChevronRight, IconExpand } from './icons';

const MAX_LINES = 8;

/**
 * props:
 *   label        — section heading text
 *   description  — raw markdown / plain text
 *   onEdit       — called when pencil is clicked (switches to content tab)
 *   onExpand     — called when expand button is clicked (opens fullscreen modal)
 *   showMoreBtn  — show a › button (to switch to contents tab)
 *   onMore       — called when › is clicked
 *   children     — optional slot rendered inside the section card (e.g. ContentsTable)
 *   tree         — KB tree array (forwarded to MarkdownEditor for DocLinkTooltip)
 *   onNavigate   — (node) => void (forwarded to MarkdownEditor for DocLinkTooltip)
 */
const SummarySection = ({
  label,
  description,
  onEdit,
  onExpand,
  showMoreBtn,
  onMore,
  children,
  tree = [],
  onNavigate,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [isTruncated, setIsTruncated] = useState(false);
  const contentRef = useRef(null);

  useEffect(() => {
    if (contentRef.current && description) {
      const lineHeight = parseFloat(getComputedStyle(contentRef.current).lineHeight);
      const maxHeight = lineHeight * MAX_LINES;
      const actualHeight = contentRef.current.scrollHeight;
      setIsTruncated(actualHeight > maxHeight + 2);
    }
  }, [description]);

  return (
    <section className="summary-section">
      <div className="summary-section__head">
        <span className="summary-section__label">{label}</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          {onEdit && (
            <button className="detail-icon-btn" title="Редактировать" onClick={onEdit}>
              <IconEdit />
            </button>
          )}
          {showMoreBtn && (
            <button className="detail-icon-btn" title="Открыть вкладку" onClick={onMore}>
              <IconChevronRight size={13} />
            </button>
          )}
          {onExpand && (
            <button className="detail-icon-btn" title="Развернуть" onClick={onExpand}>
              <IconExpand />
            </button>
          )}
        </div>
      </div>

      {children ? (
        children
      ) : (
        <div className={`summary-about ${!description ? 'summary-about--empty' : ''}`}>
          {description ? (
            <>
              <div
                ref={contentRef}
                className="summary-markdown summary-markdown--preview"
                style={{
                  maxHeight: isExpanded ? 'none' : `${MAX_LINES * 1.5}em`,
                  overflow: 'hidden',
                  position: 'relative',
                }}
              >
                {/* tree + onNavigate enable DocLinkTooltip inside rendered markdown */}
                <MarkdownEditor value={description} previewOnly onSave={() => {}} tree={tree} onNavigate={onNavigate} />
              </div>
              {isTruncated && (
                <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
                  <button className="summary-read-more" onClick={() => setIsExpanded(!isExpanded)}>
                    {isExpanded ? 'Свернуть' : 'Развернуть'}
                  </button>
                </div>
              )}
            </>
          ) : (
            <p style={{ margin: 0 }}>Нет содержимого — нажмите ✏️ чтобы добавить</p>
          )}
        </div>
      )}
    </section>
  );
};

export default SummarySection;
