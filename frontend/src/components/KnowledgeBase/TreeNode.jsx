import React, { useState, useEffect } from 'react';
import { IconFolder, IconDoc, IconChevron } from './icons';
import { findNodeById } from './utils';

const TreeNode = ({ node, level, selectedId, onSelect, onDelete }) => {
  const [open, setOpen] = useState(level === 0);
  const isFolder = node.type === 'folder';
  const hasChildren = isFolder && node.children && node.children.length > 0;
  const isSelected = node.id === selectedId;

  // Auto-open when a descendant is selected (e.g. restored from URL)
  useEffect(() => {
    if (isFolder && hasChildren && findNodeById(node.children, selectedId)) {
      setOpen(true);
    }
  }, [selectedId]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="tree-node-wrap">
      <div
        className={`tree-row ${isSelected ? 'tree-row--selected' : ''}`}
        style={{ '--depth': level }}
        onClick={() => {
          onSelect(node);
          if (isFolder) setOpen((o) => !o);
        }}
      >
        <span
          className="tree-row__chevron"
          onClick={(e) => {
            e.stopPropagation();
            setOpen((o) => !o);
          }}
        >
          {hasChildren ? <IconChevron open={open} /> : <span style={{ display: 'inline-block', width: 12 }} />}
        </span>

        <span className={`tree-row__icon ${isFolder ? 'tree-row__icon--folder' : 'tree-row__icon--doc'}`}>
          {isFolder ? <IconFolder /> : <IconDoc />}
        </span>

        <span className="tree-row__label">{node.title}</span>

        {isFolder && node.children && <span className="tree-row__count">{node.children.length}</span>}

        <button
          className="tree-row__del"
          title="Удалить"
          onClick={(e) => {
            e.stopPropagation();
            onDelete(node.id);
          }}
        >
          ✕
        </button>
      </div>

      {hasChildren && open && (
        <div className="tree-children">
          {node.children.map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              level={level + 1}
              selectedId={selectedId}
              onSelect={onSelect}
              onDelete={onDelete}
            />
          ))}
        </div>
      )}
    </div>
  );
};

export default TreeNode;
