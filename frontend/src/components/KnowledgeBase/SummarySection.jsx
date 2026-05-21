import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { IconEdit, IconChevronRight } from './icons';
import { makeSnippet } from '../Utils/utils';

const LIMIT = 300;

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
  const snippet = makeSnippet(description, LIMIT);
  const truncated =
    description &&
    description
      .replace(/^#{1,6}\s+/gm, '')
      .replace(/[*_`~>]/g, '')
      .trim().length > LIMIT;

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
              <div className="summary-markdown">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{snippet}</ReactMarkdown>
              </div>
              {truncated && (
                <button className="summary-read-more" onClick={onEdit}>
                  …читать далее
                </button>
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
