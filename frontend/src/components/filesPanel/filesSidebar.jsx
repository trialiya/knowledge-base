import FileInfo from './FileInfo';
import CommitInfo from './commit/CommitInfo';
import FileSections from './sections/FileSections';
import { IconHistory, IconInfo, IconList } from '@/icons/index';
import { RIGHT_TAB } from '@/constants/rightTabs';
import { FILE_TAB } from '@/constants/fileTabs';

/**
 * Вкладки правой панели файлового браузера: «Инфо» всегда; «Разделы» — у
 * markdown-файла (язык определяет бэкенд, тот же, что строит разделы: у файла,
 * для которого структуры нет, вкладки нет вовсе); «Коммит» — только в снимке
 * ревизии.
 */
export default function buildFileTabs({
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
  onChangesToggle,
  jump,
  onJump,
}) {
  const tabs = [
    {
      key: RIGHT_TAB.INFO,
      label: t('tabs.info'),
      icon: <IconInfo size={15} />,
      content: <FileInfo content={content} loading={contentLoading} path={path} project={project} rev={rev} />,
    },
  ];
  const file = content?.type === 'file' ? content.file : null;
  // Пока грузится следующий путь, content ещё держит прошлый файл: вкладка
  // остаётся до ответа, а не мигает — раскрытая панель схлопнулась бы на кадр.
  if (file?.language === 'markdown' && !file.binary) {
    tabs.push({
      key: FILE_TAB.SECTIONS,
      label: t('tabs.sections'),
      icon: <IconList size={15} />,
      content: (
        <FileSections
          path={content.path}
          project={project}
          rev={rev}
          refreshToken={contentToken}
          activeLine={jump?.line ?? null}
          onJump={onJump}
        />
      ),
    });
  }
  if (snapshot) {
    tabs.push({
      key: FILE_TAB.COMMIT,
      label: t('tabs.commit'),
      icon: <IconHistory size={15} />,
      content: (
        <CommitInfo
          rev={rev}
          commit={snapshotCommit.commit}
          loading={snapshotCommit.loading}
          error={snapshotCommit.error}
          changesShown={showChanges}
          onShowChanges={onChangesToggle}
        />
      ),
    });
  }
  return tabs;
}
