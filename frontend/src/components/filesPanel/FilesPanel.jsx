import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import FileTree from './FileTree';
import FileContent from './FileContent';
import FileSearch from './FileSearch';
import FileInfo from './FileInfo';
import ProjectPicker from './ProjectPicker';
import useFileTree from './useFileTree';
import useProjectConfig from '../common/useProjectConfig';
import { resolveProjectChoice } from '../common/projectChoice';
import WorkspaceLayout from '../common/WorkspaceLayout';
import { IconInfo } from '../../icons';
import { RIGHT_TAB } from '../../constants/rightTabs';
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
  onPathChange,
  onProjectChange,
  refreshToken,
  panels,
}) => {
  const { t } = useTranslation('files');
  const { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode } = useFileTree({
    project,
    path,
    onPathChange,
    refreshToken,
  });

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
        ariaLabel: t('panel.tree'),
        toolbar: <FileSearch project={project} onSelect={onPathChange} />,
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
      center={<FileContent content={content} path={path} loading={contentLoading} onNavigate={onPathChange} />}
      right={rightTabs}
    />
  );
};

/**
 * Смена проекта перемонтирует панель по `key`: дерево, раскрытые узлы,
 * содержимое, запросы в полёте и ключ ответа — пять состояний, и любое забытое
 * при сбросе показало бы файлы прежнего репозитория. Кэши при этом не теряются:
 * они живут в модуле и разложены по проектам (fileTreeStore).
 */
const FilesPanel = ({ project, path, onPathChange, refreshToken, panels }) => {
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
      onPathChange={onPathChange}
      // Путь из одного репозитория в другом ничего не значит — уходим в корень.
      // Дефолтный проект в адрес не пишем: пустое значение и означает его.
      onProjectChange={(id) => onPathChange('', id === defaultProjectId ? '' : id)}
      refreshToken={refreshToken}
      panels={panels}
    />
  );
};

export default FilesPanel;
