import React, { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import FileTree from './FileTree';
import FileContent from './FileContent';
import FileSearch from './FileSearch';
import FileInfo from './FileInfo';
import useFileTree from './useFileTree';
import WorkspaceLayout from '../common/WorkspaceLayout';
import { IconInfo } from '../../icons';
import { RIGHT_TAB } from '../../constants/rightTabs';
import './filesPanel.css';

/**
 * GitHub-стиль просмотр репозитория: дерево слева, содержимое файла/каталога
 * в центре. Раскладка — общая (WorkspaceLayout); справа вкладка «Инфо»
 * (метаданные пути и последний коммит), как в чате и базе знаний.
 */
const FilesPanel = ({ path, onPathChange, panels }) => {
  const { t } = useTranslation('files');
  const { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode } = useFileTree({
    path,
    onPathChange,
  });

  const rightTabs = useMemo(
    () => [
      {
        key: RIGHT_TAB.INFO,
        label: t('tabs.info'),
        icon: <IconInfo size={15} />,
        content: <FileInfo content={content} loading={contentLoading} />,
      },
    ],
    [t, content, contentLoading],
  );

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
      right={rightTabs}
    />
  );
};

export default FilesPanel;
