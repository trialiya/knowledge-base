import { useTranslation } from 'react-i18next';
import FileTreeNode from './FileTreeNode';
import useListNavigation from '@/components/common/search/useListNavigation';

const FileTree = ({ treeCache, loadingDirs, expanded, selectedPath, onToggle, onSelect }) => {
  const { t } = useTranslation('files');
  const rootNodes = treeCache[''];
  const rootLoading = loadingDirs.has('');
  const handleKeyDown = useListNavigation();

  return (
    <div className="file-tree ws-list" role="tree" aria-label={t('panel.tree')} tabIndex={0} onKeyDown={handleKeyDown}>
      {!rootNodes && rootLoading && (
        <div className="ws-hint" role="none">
          {t('tree.loading')}
        </div>
      )}
      {rootNodes && rootNodes.length === 0 && (
        <div className="ws-hint" role="none">
          {t('tree.empty')}
        </div>
      )}
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
