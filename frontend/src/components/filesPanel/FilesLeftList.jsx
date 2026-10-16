import { useTranslation } from 'react-i18next';
import FileTree from './FileTree';
import ScopeChip from './ScopeChip';
import ChangesList from './changes/ChangesList';
import CommitHistory from './history/CommitHistory';
import { FILE_MODE } from '@/constants/fileModes';

/**
 * Тело левого блока «Файлов» — то, что показывает режим: ленту коммитов,
 * список изменений (с чипом сужения, пока идёт сравнение) или дерево. Откуда
 * берётся список изменений и что разрешено с его строками, решает FilesPanel:
 * здесь только раскладка.
 *
 * Чип сужения есть, только пока идёт сравнение (`comparing`); `changes` —
 * `null` вне режима изменений.
 */
const FilesLeftList = ({
  mode,
  project,
  rev,
  path,
  commit,
  refreshToken,
  gitRefsToken,
  onOpenCommitFile,
  onOpenSnapshot,
  scope,
  onScopeChange,
  changes,
  flat,
  snapshot,
  comparing,
  onDiscard,
  tree,
  onSelect,
}) => {
  const { t } = useTranslation('files');

  return (
    <div className="files-panel-tree">
      {mode === FILE_MODE.HISTORY && (
        <CommitHistory
          project={project}
          rev={rev}
          path={path}
          commit={commit}
          refreshToken={refreshToken}
          refsToken={gitRefsToken}
          onOpenFile={onOpenCommitFile}
          onOpenSnapshot={onOpenSnapshot}
        />
      )}
      {comparing && (
        <ScopeChip path={path} scope={scope} onScopeChange={onScopeChange} clearLabel={t('compare.scopeClear')} />
      )}
      {changes && (
        <ChangesList
          tracked={changes.tracked}
          untracked={changes.untracked}
          flat={flat}
          loading={changes.loading}
          error={changes.error}
          selectedPath={path}
          onSelect={onSelect}
          snapshot={snapshot}
          compare={comparing}
          onDiscard={onDiscard}
        />
      )}
      {mode === FILE_MODE.TREE && (
        <FileTree
          treeCache={tree.treeCache}
          loadingDirs={tree.loadingDirs}
          expanded={tree.expanded}
          selectedPath={path}
          onToggle={tree.onToggle}
          onSelect={onSelect}
        />
      )}
    </div>
  );
};

export default FilesLeftList;
