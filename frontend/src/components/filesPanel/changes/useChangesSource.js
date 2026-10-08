import useSnapshotCommit from '../commit/useSnapshotCommit';
import useCompareView from '../compare/useCompareView';
import useChangeDiff from './useChangeDiff';
import useUncommittedChanges from './useUncommittedChanges';

/**
 * Источник режима «Изменения»: чей список слева и чей патч в центре. Их три —
 * незакоммиченное рабочего дерева, изменения коммита снимка (`rev`) и разница
 * показанной ревизии с базой сравнения (`base`), — и форма у всех одна
 * (`tracked`/`untracked`), поэтому список об источнике не знает.
 *
 * Каждый запрос идёт, только пока его ответ кому-то нужен: коммит снимка —
 * списку или открытой вкладке «Коммит» (без пути ответ несёт строку каждого
 * файла коммита, а у коммита с vendor-обновлением их тысячи); незакоммиченное —
 * списку или окну коммита, которое открывается и из режима дерева.
 *
 * База без режима изменений в адрес не попадает (navUrl), так что `comparing`
 * отдельной проверки режима не требует — но и лишней она не будет.
 */
export default function useChangesSource({
  project,
  path,
  rev,
  base,
  direct,
  showChanges,
  branch,
  refreshToken,
  refsToken,
  commitTabOpen,
  commitDialogOpen,
  onCompareChange,
  onPathChange,
}) {
  const snapshot = !!rev;
  const comparing = showChanges && !!base;

  const diff = useChangeDiff({
    project,
    path,
    rev,
    base: comparing ? base : '',
    direct,
    refreshToken,
    refsToken,
    enabled: showChanges,
  });
  const snapshotCommit = useSnapshotCommit({
    project,
    rev,
    refreshToken,
    refsToken,
    enabled: (showChanges && !comparing) || commitTabOpen,
  });
  const { comparison, compareTab } = useCompareView({
    enabled: comparing,
    project,
    path,
    rev,
    base,
    direct,
    branch,
    refreshToken,
    refsToken,
    onCompareChange,
    onPathChange,
  });
  const changeList = useUncommittedChanges({
    project,
    refreshToken,
    enabled: (showChanges && !snapshot && !comparing) || commitDialogOpen,
  });

  let listed = changeList;
  if (comparing) listed = comparison;
  else if (snapshot) listed = snapshotCommit;

  return { comparing, diff, snapshotCommit, compareTab, changeList, listed };
}
