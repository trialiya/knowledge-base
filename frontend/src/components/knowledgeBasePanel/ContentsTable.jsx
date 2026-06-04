import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { IconFolder, IconDoc } from './icons';
import { makeSnippet } from '../common/utils';

const PAGE_SIZE = 10;

const ContentsTable = ({ children, onNavigate }) => {
  const { t, i18n } = useTranslation('knowledgeBase');
  const [page, setPage] = useState(0);

  // Reset to page 0 when the data changes (e.g. navigating to another folder)
  useEffect(() => {
    setPage(0);
  }, [children]);

  const totalPages = Math.ceil(children.length / PAGE_SIZE);
  const pageItems = children.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  return (
    <div className="contents-table">
      <div className="contents-table__header">
        <span />
        <span>{t('contentsTable.name')}</span>
        <span>{t('contentsTable.type')}</span>
        <span>{t('contentsTable.updated')}</span>
      </div>

      <div className="contents-table__body">
        {pageItems.map((child) => {
          // description is already a server-side snippet (≤150 chars); makeSnippet
          // just strips markdown syntax and trims to the display limit.
          const snippet = makeSnippet(child.description, 120);
          return (
            <div key={child.id} className="contents-row" onClick={() => onNavigate(child)}>
              <span
                className={`contents-row__icon ${
                  child.type === 'folder' ? 'contents-row__icon--folder' : 'contents-row__icon--doc'
                }`}
              >
                {child.type === 'folder' ? <IconFolder /> : <IconDoc />}
              </span>
              <span className="contents-row__name-wrap">
                <span className="contents-row__name">{child.title}</span>
                {snippet && (
                  <span className="contents-row__snippet contents-row__snippet--md">
                    <ReactMarkdown
                      remarkPlugins={[remarkGfm]}
                      components={{
                        p: ({ children }) => <span>{children}</span>,
                        h1: ({ children }) => <strong>{children}</strong>,
                        h2: ({ children }) => <strong>{children}</strong>,
                        h3: ({ children }) => <strong>{children}</strong>,
                        h4: ({ children }) => <strong>{children}</strong>,
                        h5: ({ children }) => <strong>{children}</strong>,
                        h6: ({ children }) => <strong>{children}</strong>,
                        ul: ({ children }) => <span>{children}</span>,
                        ol: ({ children }) => <span>{children}</span>,
                        li: ({ children }) => <span>{children}; </span>,
                        blockquote: ({ children }) => <span>{children}</span>,
                        pre: ({ children }) => <code>{children}</code>,
                      }}
                    >
                      {snippet}
                    </ReactMarkdown>
                  </span>
                )}
              </span>
              <span className="contents-row__type">
                {child.type === 'folder' ? t('contentsTable.folder') : t('contentsTable.document')}
              </span>
              <span className="contents-row__date">{new Date(child.updatedAt).toLocaleDateString(i18n.language)}</span>
            </div>
          );
        })}
      </div>

      {/* Pagination controls — always at bottom, outside scrollable area */}
      {totalPages > 1 && (
        <div className="contents-pagination">
          <button
            className="contents-pagination__btn"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
          >
            ‹
          </button>

          {Array.from({ length: totalPages }, (_, i) => (
            <button
              key={i}
              className={`contents-pagination__page ${i === page ? 'contents-pagination__page--active' : ''}`}
              onClick={() => setPage(i)}
            >
              {i + 1}
            </button>
          ))}

          <button
            className="contents-pagination__btn"
            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1}
          >
            ›
          </button>
        </div>
      )}
    </div>
  );
};

export default ContentsTable;
