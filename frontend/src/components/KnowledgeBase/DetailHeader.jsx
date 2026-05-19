import React, { useState, useRef, useEffect } from 'react';
import { IconFolder, IconDoc, IconChevronRight, IconEdit, IconTrash, IconArrowLeft, IconCheck, IconX } from './icons';

// ─── Inline rename ────────────────────────────────────────────────────────────

const InlineRename = ({ value, onSave, onCancel }) => {
  const [val, setVal] = useState(value);
  const ref = useRef(null);
  useEffect(() => {
    ref.current?.focus();
    ref.current?.select();
  }, []);

  return (
    <span className="inline-rename">
      <input
        ref={ref}
        className="inline-rename__input"
        value={val}
        onChange={(e) => setVal(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') onSave(val);
          if (e.key === 'Escape') onCancel();
        }}
      />
      <button className="detail-icon-btn" onClick={() => onSave(val)}>
        <IconCheck />
      </button>
      <button className="detail-icon-btn" onClick={onCancel}>
        <IconX />
      </button>
    </span>
  );
};

// ─── Breadcrumb ───────────────────────────────────────────────────────────────

const Breadcrumb = ({ path, onNavigate }) => {
  if (!path || path.length === 0) return null;
  return (
    <div className="detail-breadcrumb">
      {path.map((node, i) => (
        <React.Fragment key={node.id}>
          <span className="detail-breadcrumb__icon">
            {node.type === 'folder' ? <IconFolder size={12} /> : <IconDoc size={12} />}
          </span>
          <button className="detail-breadcrumb__item" onClick={() => onNavigate(node)}>
            {node.title}
          </button>
          {i < path.length - 1 && (
            <span className="detail-breadcrumb__sep">
              <IconChevronRight />
            </span>
          )}
        </React.Fragment>
      ))}
    </div>
  );
};

// ─── DetailHeader ─────────────────────────────────────────────────────────────

const DetailHeader = ({ node, path, onNavigate, onRename, onDelete }) => {
  const [renaming, setRenaming] = useState(false);
  const isFolder = node.type === 'folder';
  const parent = path && path.length > 0 ? path[path.length - 1] : null;

  return (
    <div className="detail-header">
      <div className="detail-header__top">
        {parent && (
          <button className="detail-back-btn" title="На уровень выше" onClick={() => onNavigate(parent)}>
            <IconArrowLeft />
          </button>
        )}

        <div className="detail-header__main">
          <span
            className={`detail-header__icon ${isFolder ? 'detail-header__icon--folder' : 'detail-header__icon--doc'}`}
          >
            {isFolder ? <IconFolder size={16} /> : <IconDoc size={14} />}
          </span>

          {renaming ? (
            <InlineRename
              value={node.title}
              onSave={(name) => {
                onRename(node.id, name);
                setRenaming(false);
              }}
              onCancel={() => setRenaming(false)}
            />
          ) : (
            <h2 className="detail-header__title">
              {node.title}
              <button
                className="detail-icon-btn detail-header__rename-btn"
                title="Переименовать"
                onClick={() => setRenaming(true)}
              >
                <IconEdit />
              </button>
            </h2>
          )}
        </div>

        <div className="detail-header__actions">
          <span className={`detail-badge ${isFolder ? 'detail-badge--folder' : 'detail-badge--doc'}`}>
            {isFolder ? 'Folder' : 'Document'}
          </span>
          <span className="detail-date">{new Date(node.updatedAt).toLocaleDateString('ru-RU')}</span>
          <button className="detail-icon-btn" title="Удалить" onClick={() => onDelete(node.id)}>
            <IconTrash />
          </button>
        </div>
      </div>

      <Breadcrumb path={path} onNavigate={onNavigate} />
    </div>
  );
};

export default DetailHeader;
