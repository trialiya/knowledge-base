import React from 'react';
import { useTranslation } from 'react-i18next';
import FileTreeNode from './FileTreeNode';

const FileTree = ({ treeCache, loadingDirs, expanded, selectedPath, onToggle, onSelect }) => {
  const { t } = useTranslation('files');
  const rootNodes = treeCache[''];
  const rootLoading = loadingDirs.has('');

  return (
    <div className="file-tree">
      {!rootNodes && rootLoading && <div className="file-tree-hint">{t('tree.loading')}</div>}
      {rootNodes && rootNodes.length === 0 && <div className="file-tree-hint">{t('tree.empty')}</div>}
      {rootNodes &&
        rootNodes.map((node) => (
          <FileTreeNode
            key={node.path}
            node={node}
            level={0}
            selectedPath={selectedPath}
            expanded={expanded}
            treeCache={treeCache}
            loadingDirs={loadingDirs}
            onToggle={onToggle}
            onSelect={onSelect}
          />
        ))}
    </div>
  );
};

export default FileTree;
