import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import FileTree from './FileTree';
import FileContent from './FileContent';
import FilesToolbar from './FilesToolbar';
import FileInfo from './FileInfo';
import ChangesList from './changes/ChangesList';
import useUncommittedChanges, { UNTRACKED_STATUS } from './changes/useUncommittedChanges';
import useChangeDiff from './changes/useChangeDiff';
import { readChangesFlat, saveChangesFlat } from './changes/changesLayout';
import ProjectPicker from './ProjectPicker';
import useFileTree from './useFileTree';
import useGitBranch from './git/useGitBranch';
import useGitActions from './git/useGitActions';
import GitPromptModal from './git/GitPromptModal';
import CommitDialog from '@/components/common/git/CommitDialog';
import PushDialog from '@/components/common/git/PushDialog';
import ConfirmModal from '@/components/common/modal/ConfirmModal';
import useNotice from '@/components/common/ui/useNotice';
import ErrorModal from '@/components/common/modal/ErrorModal';
import useProjectConfig from '@/components/common/config/useProjectConfig';
import { resolveProjectChoice } from '@/components/common/config/projectChoice';
import WorkspaceLayout from '@/components/common/layout/WorkspaceLayout';
import { IconInfo } from '@/icons/index';
import { RIGHT_TAB } from '@/constants/rightTabs';
import './filesPanel.css';

/**
 * GitHub-стиль просмотр репозитория: дерево слева, содержимое файла/каталога
 * в центре. Раскладка — общая (WorkspaceLayout); справа вкладка «Инфо»
 * (метаданные пути и последний коммит), как в чате и базе знаний.
 *
 * `project` — репозиторий, который показывает панель; приходит из адреса
 * (пусто — дефолтный). Смена проекта — это перемонтирование всего содержимого
 * (см. FilesPanelForProject ниже), а не набор сбросов состояния.
 */
const FilesPanelForProject = ({
  project,
  projectOptions,
  path,
  changes,
  onChangesToggle,
  onPathChange,
  onProjectChange,
  refreshToken,
  gitRefsToken,
  onRepoChanged,
  onGitRefsChanged,
  panels,
}) => {
  const { t } = useTranslation('files');
  const { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode } = useFileTree({
    project,
    path,
    onPathChange,
    refreshToken,
  });

  const diff = useChangeDiff({ project, path, refreshToken, enabled: changes });
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
    enabled: changes || actions.dialog === 'commit',
  });
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
  const choiceKey = `${changes ? 1 : 0} ${path}`;
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
  const diffPending = changes && !!path && diff.loading;
  const showDiff = changes && (diffChoice ?? (!!diff.entry && diff.entry.status !== UNTRACKED_STATUS));

  const rightTabs = useMemo(
    () => [
      {
        key: RIGHT_TAB.INFO,
        label: t('tabs.info'),
        icon: <IconInfo size={15} />,
        content: <FileInfo content={content} loading={contentLoading} path={path} project={project} />,
      },
    ],
    [t, content, contentLoading, path, project],
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
          ariaLabel: t(changes ? 'panel.changes' : 'panel.tree'),
          toolbar: (
            <FilesToolbar
              project={project}
              changes={changes}
              onChangesToggle={onChangesToggle}
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
              {changes ? (
                <ChangesList
                  tracked={changeList.tracked}
                  untracked={changeList.untracked}
                  flat={flat}
                  loading={changeList.loading}
                  error={changeList.error}
                  selectedPath={path}
                  onSelect={selectNode}
                  // Откат правки предлагается только там, где проекту разрешены
                  // команды: без разрешения кнопка отвечала бы отказом сервера.
                  onDiscard={git.capabilities?.commands && !git.running ? actions.askDiscard : null}
                />
              ) : (
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
          <FileContent
            content={content}
            path={path}
            loading={contentLoading || diffPending}
            onNavigate={onPathChange}
            // Тумблер «оригинал ↔ diff» показываем только там, где есть что
            // переключать: панель в режиме изменений и открыт какой-то путь.
            diff={changes && path ? diff : null}
            showDiff={showDiff}
            onToggleDiff={setDiffChoice}
          />
        }
        right={rightTabs}
      />
      <GitPromptModal
        open={actions.naming}
        title={t('git.newBranch')}
        label={t('git.branchName')}
        hint={git.status ? t('git.branchFrom', { branch: git.status.current }) : undefined}
        placeholder="feature/…"
        confirmLabel={t('git.create')}
        onConfirm={actions.confirmNewBranch}
        onCancel={actions.cancelNewBranch}
      />
      {/* Те же окна, что открывает вкладка «Репозиторий» в чате: коммит там и
          здесь означает одно и то же (см. common/git). */}
      {actions.dialog === 'commit' && <CommitDialog git={dialogGit} onClose={actions.closeDialog} />}
      {actions.dialog === 'push' && <PushDialog git={dialogGit} onClose={actions.closeDialog} />}
      <ConfirmModal
        open={!!actions.discarding}
        title={t('git.discardTitle')}
        message={t('git.discardMessage', { path: actions.discarding })}
        confirmLabel={t('git.discardConfirm')}
        onConfirm={actions.confirmDiscard}
        onCancel={actions.cancelDiscard}
      />
      <ErrorModal
        open={!!notice}
        title={notice ? t(notice.titleKey) : ''}
        message={notice ? t(notice.messageKey, notice.params) : ''}
        onClose={dismissNotice}
      />
    </>
  );
};

/**
 * Смена проекта перемонтирует панель по `key`: дерево, раскрытые узлы,
 * содержимое, запросы в полёте и ключ ответа — пять состояний, и любое забытое
 * при сбросе показало бы файлы прежнего репозитория. Кэши при этом не теряются:
 * они живут в модуле и разложены по проектам (fileTreeStore).
 */
const FilesPanel = ({
  project,
  path,
  changes,
  onChangesToggle,
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
      key={current}
      project={current}
      projectOptions={projectOptions}
      path={path}
      changes={changes}
      onChangesToggle={onChangesToggle}
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
