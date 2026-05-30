import React, { useState, useRef, useEffect } from 'react';
import { IconFolder, IconChevron } from './icons';
import { findNodeById } from '../common/utils';

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
  const [name, setName] = useState('');
  const [type, setType] = useState('document');
  const [parentId, setParentId] = useState(defaultParentId ?? null);
  const inputRef = useRef(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const handleCreate = () => {
    if (!name.trim()) return;
    onCreate({
      title: name.trim(),
      type,
      parentId,
      description: type === 'document' ? `# ${name.trim()}\n\n` : null,
    });
  };

  // Label for the selected folder
  const selectedFolder = parentId ? findNodeById(tree, parentId) : null;
  const locationLabel = selectedFolder ? selectedFolder.title : 'Корневой уровень';

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal modal--wide" onClick={(e) => e.stopPropagation()}>
        <h3>Новый элемент</h3>

        {/* Name */}
        <input
          ref={inputRef}
          type="text"
          placeholder="Название"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
        />

        {/* Type toggle */}
        <div className="modal-type-row">
          {['document', 'folder'].map((t) => (
            <button
              key={t}
              className={`modal-type-btn ${type === t ? 'modal-type-btn--active' : ''}`}
              onClick={() => setType(t)}
            >
              {t === 'document' ? '📄 Документ' : '📁 Папка'}
            </button>
          ))}
        </div>

        {/* Folder tree picker */}
        <div className="fp-wrap">
          <div className="fp-header">
            <span className="fp-header__label">Расположение</span>
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
              <span className="fp-row__label">Корневой уровень</span>
            </div>
            {tree
              .filter((n) => n.type === 'folder')
              .map((node) => (
                <FolderTreeItem key={node.id} node={node} level={0} selectedId={parentId} onSelect={setParentId} />
              ))}
          </div>
        </div>

        <div className="modal-buttons">
          <button onClick={handleCreate}>Создать</button>
          <button onClick={onClose}>Отмена</button>
        </div>
      </div>
    </div>
  );
};

export default AddModal;
