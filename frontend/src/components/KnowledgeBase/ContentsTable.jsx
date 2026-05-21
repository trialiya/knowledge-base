import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { IconFolder, IconDoc } from './icons';
import { makeSnippet } from '../Utils/utils';

const ContentsTable = ({ children, onNavigate }) => (
  <div className="contents-table">
    <div className="contents-table__header">
      <span />
      <span>Название</span>
      <span>Тип</span>
      <span>Обновлено</span>
    </div>
    {children.map((child) => {
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
                    // Рендерим всё inline: параграфы → span, убираем блочные элементы
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
          <span className="contents-row__type">{child.type === 'folder' ? 'Folder' : 'Document'}</span>
          <span className="contents-row__date">{new Date(child.updatedAt).toLocaleDateString('ru-RU')}</span>
        </div>
      );
    })}
  </div>
);

export default ContentsTable;
