import React from 'react';
import FileTree from './FileTree';
import FileContent from './FileContent';
import useFileTree from './useFileTree';
import './filesPanel.css';

/** GitHub-стиль просмотр репозитория: дерево слева, содержимое файла/каталога справа. */
const FilesPanel = ({ path, onPathChange }) => {
  const { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode } = useFileTree({
    path,
    onPathChange,
  });

  return (
    <div className="files-panel-container">
      <div className="files-panel-main">
        <div className="files-panel-tree">
          <FileTree
            treeCache={treeCache}
            loadingDirs={loadingDirs}
            expanded={expanded}
            selectedPath={path}
            onToggle={toggleExpand}
            onSelect={selectNode}
          />
        </div>
        <div className="files-panel-preview">
          <FileContent content={content} loading={contentLoading} onNavigate={onPathChange} />
        </div>
      </div>
    </div>
  );
};

export default FilesPanel;
