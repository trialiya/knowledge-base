import React, { useState, useRef, useEffect } from 'react';
import MarkdownEditor from './MarkdownEditor';
import { IconEdit, IconChevronRight } from './icons';

const MAX_LINES = 8;

/**
 * props:
 *   label        — section heading text
 *   description  — raw markdown / plain text
 *   onEdit       — called when pencil is clicked (switches to content tab)
 *   showMoreBtn  — show a › button (to switch to contents tab)
 *   onMore       — called when › is clicked
 *   children     — optional slot rendered inside the section card (e.g. ContentsTable)
 */
const SummarySection = ({ label, description, onEdit, showMoreBtn, onMore, children }) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [isTruncated, setIsTruncated] = useState(false);
  const contentRef = useRef(null);

  // Проверяем, нужно ли обрезать контент
  useEffect(() => {
    if (contentRef.current && description) {
      const lineHeight = parseFloat(getComputedStyle(contentRef.current).lineHeight);
      const maxHeight = lineHeight * MAX_LINES;
      const actualHeight = contentRef.current.scrollHeight;
      setIsTruncated(actualHeight > maxHeight + 2); // +2 для погрешности
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
        </div>
      </div>

      {children ? (
        children
      ) : (
        <div className={`summary-about ${!description ? 'summary-about--empty' : ''}`}>
          {description ? (
            <>
              {/* MarkdownEditor locked in preview mode, clipped to MAX_LINES */}
              <div
                ref={contentRef}
                className="summary-markdown summary-markdown--preview"
                style={{
                  maxHeight: isExpanded ? 'none' : `${MAX_LINES * 1.5}em`,
                  overflow: 'hidden',
                  position: 'relative',
                }}
              >
                <MarkdownEditor value={description} previewOnly onSave={() => {}} />
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
