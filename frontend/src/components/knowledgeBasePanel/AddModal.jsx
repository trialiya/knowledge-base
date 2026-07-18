import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconChevron } from '../../icons';
import { findNodeById } from '../common/utils';
import ModalShell from '../common/ModalShell';
import '../common/buttons.css';

// ─── Folder tree picker ───────────────────────────────────────────────────────

const FolderTreeItem = ({ node, level, selectedId, onSelect }) => {
  const isFolder = node.type === 'folder';
  const hasChildren = isFolder && node.children && node.children.length > 0;
  const isSelected = node.id === selectedId;

  // Auto-open if this node or a descendant is the default selection
  const [open, setOpen] = useState(() => isSelected || (hasChildren && !!findNodeById(node.children, selectedId)));

  if (!isFolder) return null;

  return (
    <div className="fp-node">
      <div
        className={`fp-row ${isSelected ? 'fp-row--selected' : ''}`}
        style={{ '--depth': level }}
        onClick={() => onSelect(node.id)}
      >
        <span
          className="fp-row__chevron"
          onClick={(e) => {
            e.stopPropagation();
            setOpen((o) => !o);
          }}
        >
          {hasChildren ? <IconChevron open={open} /> : <span style={{ display: 'inline-block', width: 12 }} />}
        </span>
        <span className="fp-row__icon">
          <IconFolder size={13} />
        </span>
        <span className="fp-row__label">{node.title}</span>
      </div>
      {hasChildren &&
        open &&
        node.children.map((child) => (
          <FolderTreeItem key={child.id} node={child} level={level + 1} selectedId={selectedId} onSelect={onSelect} />
        ))}
    </div>
  );
};

// ─── Modal ────────────────────────────────────────────────────────────────────

const AddModal = ({ tree, defaultParentId, onClose, onCreate }) => {
  const { t } = useTranslation('knowledgeBase');
  const [name, setName] = useState('');
  const [type, setType] = useState('document');
  const [parentId, setParentId] = useState(defaultParentId ?? null);
  // Блокировка на время запроса к бэку, чтобы повторный клик/Enter не создал
  // несколько документов. Снимается в finally (на ошибке модалка остаётся
  // открытой и кнопка снова активна; на успехе модалка размонтируется).
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef(null);
  // Защита от setState после размонтирования (на успехе onCreate закрывает модалку).
  const mountedRef = useRef(true);

  useEffect(() => {
    inputRef.current?.focus();
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const handleCreate = async () => {
    if (submitting || !name.trim()) return;
    setSubmitting(true);
    try {
      await onCreate({
        title: name.trim(),
        type,
        parentId,
        description: type === 'document' ? `# ${name.trim()}\n\n` : null,
      });
    } finally {
      if (mountedRef.current) setSubmitting(false);
    }
  };

  // Не даём закрыть модалку (фон/Отмена), пока запрос в полёте.
  const handleClose = () => {
    if (!submitting) onClose();
  };

  // Label for the selected folder
  const selectedFolder = parentId ? findNodeById(tree, parentId) : null;
  const locationLabel = selectedFolder ? selectedFolder.title : t('add.root');

  return (
    <ModalShell onClose={handleClose} variant="wide" className="add-modal">
      <h3>{t('add.title')}</h3>

      {/* Name */}
      <input
        ref={inputRef}
        className="add-modal__input"
        type="text"
        placeholder={t('add.namePlaceholder')}
        value={name}
        disabled={submitting}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
      />

      {/* Type toggle */}
      <div className="modal-type-row">
        {['document', 'folder'].map((tp) => (
          <button
            key={tp}
            className={`modal-type-btn ${type === tp ? 'modal-type-btn--active' : ''}`}
            disabled={submitting}
            onClick={() => setType(tp)}
          >
            {tp === 'document' ? t('add.typeDocument') : t('add.typeFolder')}
          </button>
        ))}
      </div>

      {/* Folder tree picker */}
      <div className="fp-wrap">
        <div className="fp-header">
          <span className="fp-header__label">{t('add.location')}</span>
          <span className="fp-header__selected">{locationLabel}</span>
        </div>
        <div className="fp-tree">
          {/* Root option */}
          <div
            className={`fp-row fp-row--root ${parentId === null ? 'fp-row--selected' : ''}`}
            style={{ '--depth': 0 }}
            onClick={() => setParentId(null)}
          >
            <span style={{ display: 'inline-block', width: 12 }} />
            <span className="fp-row__icon fp-row__icon--root">⊘</span>
            <span className="fp-row__label">{t('add.root')}</span>
          </div>
          {tree
            .filter((n) => n.type === 'folder')
            .map((node) => (
              <FolderTreeItem key={node.id} node={node} level={0} selectedId={parentId} onSelect={setParentId} />
            ))}
        </div>
      </div>

      <div className="modal-shell__footer">
        <button className="btn btn--primary" onClick={handleCreate} disabled={submitting || !name.trim()}>
          {submitting ? t('add.creating') : t('add.create')}
        </button>
        <button className="btn btn--ghost" onClick={handleClose} disabled={submitting}>
          {t('add.cancel')}
        </button>
      </div>
    </ModalShell>
  );
};

export default AddModal;
