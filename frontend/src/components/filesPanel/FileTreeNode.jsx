import React, { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc, IconChevron } from '../../icons';

const FileTreeNode = ({ node, level, selectedPath, expanded, treeCache, loadingDirs, onToggle, onSelect }) => {
  const { t } = useTranslation('files');
  const isDir = node.type === 'directory';
  const isOpen = expanded.has(node.path);
  const isSelected = node.path === selectedPath;
  const children = treeCache[node.path];
  const isLoading = loadingDirs.has(node.path);

  // Доскроллить дерево до выбранного узла: на глубокой вложенности (deep link)
  // он оказывается за нижней/правой границей панели. 'nearest' — no-op, когда
  // узел уже виден (обычный клик).
  const labelRef = useRef(null);
  useEffect(() => {
    if (isSelected) labelRef.current?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [isSelected]);

  return (
    <div className="file-tree-node-wrap">
      <div
        className={`file-tree-row ${isSelected ? 'file-tree-row--selected' : ''}`}
        style={{ '--depth': level }}
        onClick={() => {
          onSelect(node);
          if (isDir) onToggle(node.path);
        }}
      >
        <span
          className="file-tree-row__chevron"
          onClick={(e) => {
            e.stopPropagation();
            if (isDir) onToggle(node.path);
          }}
        >
          {isDir ? <IconChevron open={isOpen} /> : <span className="file-tree-row__chevron-spacer" />}
        </span>
        <span className={`file-tree-row__icon ${isDir ? 'file-tree-row__icon--folder' : 'file-tree-row__icon--file'}`}>
          {isDir ? <IconFolder /> : <IconDoc />}
        </span>
        <span ref={labelRef} className="file-tree-row__label">
          {node.name}
        </span>
      </div>

      {isDir && isOpen && (
        <div className="file-tree-children">
          {!children && isLoading && (
            <div className="file-tree-hint" style={{ '--depth': level + 1 }}>
              {t('tree.loading')}
            </div>
          )}
          {children && children.length === 0 && (
            <div className="file-tree-hint" style={{ '--depth': level + 1 }}>
              {t('tree.empty')}
            </div>
          )}
          {children &&
            children.map((child) => (
              <FileTreeNode
                key={child.path}
                node={child}
                level={level + 1}
                selectedPath={selectedPath}
                expanded={expanded}
                treeCache={treeCache}
                loadingDirs={loadingDirs}
                onToggle={onToggle}
                onSelect={onSelect}
              />
            ))}
        </div>
      )}
    </div>
  );
};

export default FileTreeNode;
