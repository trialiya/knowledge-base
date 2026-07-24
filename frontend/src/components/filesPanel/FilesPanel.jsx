import React from 'react';
import { useTranslation } from 'react-i18next';
import FileTree from './FileTree';
import FileContent from './FileContent';
import FileSearch from './FileSearch';
import useFileTree from './useFileTree';
import WorkspaceLayout from '../common/WorkspaceLayout';
import './filesPanel.css';

/**
 * GitHub-стиль просмотр репозитория: дерево слева, содержимое файла/каталога
 * в центре. Раскладка — общая (WorkspaceLayout), правой панели у раздела пока
 * нет: работа со структурой файлов и историей коммитов появится в ней позже.
 */
const FilesPanel = ({ path, onPathChange, panels }) => {
  const { t } = useTranslation('files');
  const { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode } = useFileTree({
    path,
    onPathChange,
  });

  return (
    <WorkspaceLayout
      className="workspace--files"
      {...panels}
      left={{
        title: t('panel.tree'),
        toolbar: <FileSearch onSelect={onPathChange} />,
        // Дерево прокручивает себя само (строки шире панели — нужен и
        // горизонтальный скролл), поэтому тело панели скролл не берёт.
        bodyScroll: false,
        children: (
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
        ),
      }}
      center={<FileContent content={content} loading={contentLoading} onNavigate={onPathChange} />}
    />
  );
};

export default FilesPanel;
