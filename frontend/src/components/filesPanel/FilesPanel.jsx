import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import FileTree from './FileTree';
import FileContent from './FileContent';
import FilesToolbar from './FilesToolbar';
import ChangesList from './changes/ChangesList';
import useSnapshotCommit from './commit/useSnapshotCommit';
import useUncommittedChanges, { UNTRACKED_STATUS } from './changes/useUncommittedChanges';
import useChangeDiff from './changes/useChangeDiff';
import { readChangesFlat, saveChangesFlat } from './changes/changesLayout';
import ProjectPicker from './ProjectPicker';
import useFileTree from './useFileTree';
import useGitBranch from './git/useGitBranch';
import useGitActions from './git/useGitActions';
import FilesGitDialogs from './git/FilesGitDialogs';
import CommitHistory from './history/CommitHistory';
import CommitFileDiff from './history/CommitFileDiff';
import useNotice from '@/components/common/ui/useNotice';
import useProjectConfig from '@/components/common/config/useProjectConfig';
import { resolveProjectChoice } from '@/components/common/config/projectChoice';
import WorkspaceLayout from '@/components/common/layout/WorkspaceLayout';
import { FILE_TAB } from '@/constants/fileTabs';
import { FILE_MODE } from '@/constants/fileModes';
import buildFileTabs from './filesSidebar';
import useOutlineJump from './outline/useOutlineJump';
import { previewKind } from '@/utils/filePreview';
import './filesPanel.css';

/**
 * GitHub-стиль просмотр репозитория: дерево слева, содержимое файла/каталога
 * в центре. Раскладка — общая (WorkspaceLayout); справа вкладка «Инфо»
 * (метаданные пути и последний коммит), как в чате и базе знаний, а в снимке
 * ревизии ещё и «Коммит» — о самом снимке.
 *
 * `project` — репозиторий, который показывает панель; приходит из адреса
 * (пусто — дефолтный). Смена проекта — это перемонтирование всего содержимого
 * (см. FilesPanelForProject ниже), а не набор сбросов состояния.
 */
const FilesPanelForProject = ({
  project,
  projectOptions,
  path,
  mode,
  commit,
  rev,
  blame,
  find,
  findRegex,
  lines,
  onModeChange,
  onRevChange,
  onBlameToggle,
  onFindChange,
  onPathChange,
  onProjectChange,
  refreshToken,
  gitRefsToken,
  onRepoChanged,
  onGitRefsChanged,
  panels,
}) => {
  const { t } = useTranslation('files');
  // Снимок ревизии — режим только для чтения: незакоммиченного в нём нет, и
  // команды, которые двигают рабочее дерево, к тому, что показано, отношения
  // не имеют. Режим «Изменения» в нём показывает то, что поменял сам коммит, —
  // источник списка и патча другой, строки и diff те же.
  const snapshot = !!rev;
  const showChanges = mode === FILE_MODE.CHANGES;
  // Файл, открытый из ленты коммитов, центр показывает его изменением в том
  // коммите (CommitFileDiff), а не самим файлом.
  const commitDiff = mode === FILE_MODE.HISTORY && !!commit && !!path;
  // Сигнал «показанное могло устареть». У снимка их два: ревизия бывает веткой,
  // и после fetch (`gitRefsToken`) она называет уже другой коммит — дерево и
  // файл обязаны перейти на него вместе со списком и diff (useSnapshotCommit),
  // иначе слева и в центре оказались бы разные коммиты.
  const contentToken = snapshot ? `${refreshToken ?? 0}.${gitRefsToken ?? 0}` : refreshToken;

  const { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode } = useFileTree({
    project,
    rev,
    path,
    onPathChange,
    refreshToken: contentToken,
  });

  const diff = useChangeDiff({ project, path, rev, refreshToken, refsToken: gitRefsToken, enabled: showChanges });
  // Коммит снимка нужен и вкладке «Коммит», и списку слева — один запрос на обоих,
  // и только пока хоть один из них на экране: без пути ответ несёт строку каждого
  // файла коммита, а у коммита с vendor-обновлением их тысячи.
  const snapshotCommit = useSnapshotCommit({
    project,
    rev,
    refreshToken,
    refsToken: gitRefsToken,
    enabled: showChanges || panels?.rightTab === FILE_TAB.COMMIT,
  });
  const git = useGitBranch({ project, refreshToken, refsToken: gitRefsToken, onRefsChanged: onGitRefsChanged });

  // Одно уведомление на панель: git-команда отказывает словами самого git
  // («Permission denied (publickey)»), и это ровно то, что нужно показать —
  // своя формулировка сказала бы меньше. Кроме коммита и push: их отказ остаётся
  // в окне, из которого их запустили (см. useGitActions).
  const { notice, notify, dismissNotice } = useNotice();
  const actions = useGitActions({ git, project, refreshToken, onRepoChanged, notify, t });

  // Список незакоммиченного нужен и режиму «Изменения», и окну коммита — окно
  // открывается и из режима дерева, где списка на экране нет.
  const changeList = useUncommittedChanges({
    project,
    refreshToken,
    enabled: (showChanges && !snapshot) || actions.dialog === 'commit',
  });
  const listed = snapshot ? snapshotCommit : changeList;
  // Панель дополняет контракт окон тем, чего сам `useGitActions` собрать не мог:
  // список спрашивается лениво, по открытому окну.
  const dialogGit = useMemo(
    () => ({
      ...actions.dialogGit,
      changes: changeList.entries,
      changesLoading: changeList.loading,
      changesError: changeList.error,
    }),
    [actions.dialogGit, changeList.entries, changeList.loading, changeList.error],
  );

  // Раскладка списка изменений — предпочтение, переживающее и проект, и
  // перезагрузку (см. changesLayout).
  const [flat, setFlat] = useState(readChangesFlat);
  const changeFlat = (next) => {
    setFlat(next);
    saveChangesFlat(next);
  };

  // Что показывать в центре — оригинал или diff. Дефолт зависит от открытого
  // файла: у изменённого смотрят изменение, у неотслеживаемого его нет вовсе —
  // весь файл и есть новое. Поэтому выбор следует пути и режиму и сбрасывается
  // в рендере под своим prev-стражем (см. правила хуков), а не эффектом,
  // который дорисовал бы кадр с выбором, сделанным для прошлого файла.
  const [diffChoice, setDiffChoice] = useState(null);
  const choiceKey = `${showChanges ? 1 : 0} ${path}`;
  const [prevChoiceKey, setPrevChoiceKey] = useState(choiceKey);
  if (prevChoiceKey !== choiceKey) {
    setPrevChoiceKey(choiceKey);
    setDiffChoice(null);
  }
  // Дефолт считаем по ответу про САМ файл, а не по списку слева: список — это
  // отдельный запрос, он приходит позже и может не прийти вовсе, и тогда центр
  // сначала показал бы исходник, а потом сам себя перерисовал в diff. По той же
  // причине центр ждёт этот ответ наравне с содержимым — иначе кадр между ними
  // показывает не то, на что кликнули (у удалённого файла — «не найдено»).
  const diffPending = showChanges && !!path && diff.loading;
  // Картинку смотрят рисунком: текстового патча у неё нет, и diff по умолчанию
  // показал бы заглушку вместо самого изменения.
  const diffByDefault = !!diff.entry && diff.entry.status !== UNTRACKED_STATUS && previewKind(path) !== 'image';
  const showDiff = showChanges && (diffChoice ?? diffByDefault);

  const { jump, onJump } = useOutlineJump(path);

  // Снимок коммита из ленты — тот же вид, что у ссылки на коммит: изменения
  // слева, вкладка «Коммит» справа (см. navigateToCommit).
  const openSnapshot = (filePath, hash) =>
    onPathChange(filePath, undefined, { rev: hash, changes: true, right: FILE_TAB.COMMIT });

  const rightTabs = useMemo(
    () =>
      buildFileTabs({
        t,
        content,
        contentLoading,
        path,
        project,
        rev,
        contentToken,
        snapshot,
        snapshotCommit,
        showChanges,
        onModeChange,
        jump,
        onJump,
      }),
    [
      t,
      content,
      contentLoading,
      path,
      project,
      rev,
      contentToken,
      snapshot,
      snapshotCommit,
      showChanges,
      onModeChange,
      jump,
      onJump,
    ],
  );

  return (
    <>
      <WorkspaceLayout
        className="workspace--files"
        {...panels}
        left={{
          // Заголовок панели — сам селектор репозитория: панель показывает один
          // репозиторий, и его имя и есть её заголовок, отдельной строки под выбор
          // не нужно. Единственный проект выбирать не из чего — остаётся надпись.
          title:
            projectOptions.length > 1 ? (
              <ProjectPicker value={project} options={projectOptions} onChange={onProjectChange} />
            ) : (
              t('panel.tree')
            ),
          ariaLabel: t(leftLabelKey(mode, snapshot)),
          toolbar: (
            <FilesToolbar
              project={project}
              mode={mode}
              rev={rev}
              onRevChange={onRevChange}
              gitRefsToken={gitRefsToken}
              onModeChange={onModeChange}
              flat={flat}
              onFlatToggle={changeFlat}
              onSelect={onPathChange}
              git={git}
              actions={actions}
            />
          ),
          // Дерево прокручивает себя само (строки шире панели — нужен и
          // горизонтальный скролл), поэтому тело панели скролл не берёт.
          bodyScroll: false,
          children: (
            <div className="files-panel-tree">
              {mode === FILE_MODE.HISTORY && (
                <CommitHistory
                  project={project}
                  rev={rev}
                  path={path}
                  commit={commit}
                  refreshToken={refreshToken}
                  refsToken={gitRefsToken}
                  onOpenFile={(filePath, hash) => onPathChange(filePath, undefined, { commit: hash })}
                  onOpenSnapshot={(hash) => openSnapshot('', hash)}
                />
              )}
              {showChanges && (
                <ChangesList
                  tracked={listed.tracked}
                  untracked={listed.untracked}
                  flat={flat}
                  loading={listed.loading}
                  error={listed.error}
                  selectedPath={path}
                  onSelect={selectNode}
                  snapshot={snapshot}
                  // Откат правки предлагается только там, где проекту разрешены
                  // команды: без разрешения кнопка отвечала бы отказом сервера.
                  // Коммит откатывать нечем — снимок только для чтения.
                  onDiscard={!snapshot && git.capabilities?.commands && !git.running ? actions.askDiscard : null}
                />
              )}
              {mode === FILE_MODE.TREE && (
                <FileTree
                  treeCache={treeCache}
                  loadingDirs={loadingDirs}
                  expanded={expanded}
                  selectedPath={path}
                  onToggle={toggleExpand}
                  onSelect={selectNode}
                />
              )}
            </div>
          ),
        }}
        center={
          commitDiff ? (
            <CommitFileDiff
              key={`${commit}\n${path}`}
              project={project}
              path={path}
              commit={commit}
              onNavigate={onPathChange}
              onOpenFile={(filePath) => onPathChange(filePath)}
              onOpenSnapshot={(hash) => openSnapshot(path, hash)}
            />
          ) : (
            <FileContent
              content={content}
              path={path}
              // Картинку центр грузит сам, по адресу сырых байт, — а адрес этот,
              // как и всякая ссылка на файл, есть пара (проект, путь), да ещё и
              // снимок ревизии, если панель стоит на нём.
              project={project}
              rev={rev}
              // Дерево и содержимое перезапрашивает useFileTree, а байты картинки
              // грузит браузер по неизменному адресу — без этого токена он остался
              // бы с прошлой картинкой там, где файл уже другой.
              reloadToken={contentToken}
              loading={contentLoading || diffPending}
              onNavigate={onPathChange}
              // Тумблер «оригинал ↔ diff» показываем только там, где есть что
              // переключать: панель в режиме изменений и открыт какой-то путь.
              diff={showChanges && path ? diff : null}
              showDiff={showDiff}
              onToggleDiff={setDiffChoice}
              // Колонка blame — состояние экрана из адреса (`?blame=1`), как
              // режим изменений: переживает F5 и переезжает на соседний файл.
              blame={blame}
              onToggleBlame={onBlameToggle}
              find={find}
              findRegex={findRegex}
              onFindChange={onFindChange}
              lines={lines}
              jump={jump}
            />
          )
        }
        right={rightTabs}
      />
      <FilesGitDialogs
        git={git}
        actions={actions}
        dialogGit={dialogGit}
        notice={notice}
        onDismissNotice={dismissNotice}
      />
    </>
  );
};

/** Имя левого блока для скринридера — по тому, что в нём сейчас. */
function leftLabelKey(mode, snapshot) {
  if (mode === FILE_MODE.HISTORY) return 'panel.history';
  if (mode === FILE_MODE.CHANGES) return snapshot ? 'panel.commitChanges' : 'panel.changes';
  return 'panel.tree';
}

/**
 * Смена проекта перемонтирует панель по `key`: дерево, раскрытые узлы,
 * содержимое, запросы в полёте и ключ ответа — пять состояний, и любое забытое
 * при сбросе показало бы файлы прежнего репозитория. Кэши при этом не теряются:
 * они живут в модуле и разложены по паре (проект, ревизия) — fileTreeStore.
 *
 * Ревизия входит в тот же ключ и по той же причине: снимок коммита — это другой
 * набор тех же путей, и любое состояние, пережившее переключение, показало бы
 * файлы не того снимка.
 */
const FilesPanel = ({
  project,
  path,
  mode,
  commit,
  rev,
  blame,
  find,
  findRegex,
  lines,
  onModeChange,
  onRevChange,
  onBlameToggle,
  onFindChange,
  onPathChange,
  refreshToken,
  gitRefsToken,
  onRepoChanged,
  onGitRefsChanged,
  panels,
}) => {
  const { projectOptions, defaultProjectId, ready } = useProjectConfig();
  // Адрес без проекта означает дефолтный. Ждём ответа со списком: смонтироваться
  // раньше — значит смонтироваться на пустом ключе и тут же перемонтироваться,
  // то есть два запроса дерева, мигание и осиротевшая ветка кэша. Отказ запроса
  // тоже считается ответом: тогда едем на «проект не назван», который бэкенд
  // разрешает в дефолтный, — панель без списка проектов работать обязана.
  if (!ready) return null;
  // Проект из адреса сверяем со списком: сохранённая или присланная ссылка могла
  // пережить и выключение проекта, и переименование id, а бэкенд на неизвестный
  // отвечает 400 — панель показала бы одну ошибку вместо дерева, и починить адрес
  // было бы негде, при одном проекте селектор скрыт. Уезжаем на дефолтный, как чат;
  // сказать об этом, в отличие от чата, некому — у панели нет своей строки состояния.
  const { selected: current } = resolveProjectChoice(project, projectOptions, defaultProjectId);

  return (
    <FilesPanelForProject
      key={`${current}\n${rev || ''}`}
      project={current}
      projectOptions={projectOptions}
      path={path}
      mode={mode || FILE_MODE.TREE}
      commit={commit || ''}
      rev={rev || ''}
      blame={!!blame}
      find={find || ''}
      findRegex={!!findRegex}
      lines={lines || ''}
      onModeChange={onModeChange}
      onRevChange={onRevChange}
      onBlameToggle={onBlameToggle}
      onFindChange={onFindChange}
      onPathChange={onPathChange}
      // Путь из одного репозитория в другом ничего не значит — уходим в корень.
      // Дефолтный проект в адрес не пишем: пустое значение и означает его.
      onProjectChange={(id) => onPathChange('', id === defaultProjectId ? '' : id)}
      refreshToken={refreshToken}
      gitRefsToken={gitRefsToken}
      onRepoChanged={onRepoChanged}
      onGitRefsChanged={onGitRefsChanged}
      panels={panels}
    />
  );
};

export default FilesPanel;
