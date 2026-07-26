import React from 'react';
import { useTranslation } from 'react-i18next';
import InfoList from '../common/InfoList';
import useLastCommit from './useLastCommit';
import { formatFileSize, formatDateTime } from '../../utils/formatting';

/**
 * Вкладка «Инфо» правой панели файлового браузера.
 *
 * Две группы полей: сам объект (имя, путь, размер, язык, число строк — всё уже
 * загружено центром, лишнего запроса нет) и последний коммит, который его
 * тронул — дата, автор и заголовок коммита. Историю запрашиваем отдельно
 * (см. useLastCommit): `git log` по пути дороже листинга дерева.
 *
 * `content` — это объект из useFileTree: { type: 'file'|'directory'|'not-found'
 * |'error', path, file?, nodes? }.
 */
const FileInfo = ({ content, loading }) => {
  const { t, i18n } = useTranslation('files');

  const type = content?.type;
  const known = type === 'file' || type === 'directory';
  const path = content?.path ?? '';
  // Историю тянем только для существующих путей: у not-found/error спрашивать нечего.
  const { commit, loading: commitLoading } = useLastCommit(path, known);

  if (loading) {
    return <p className="info-list__hint">{t('loading')}</p>;
  }
  if (!known) {
    return <p className="info-list__hint">{type === 'error' ? t('file.loadError') : t('file.notFound')}</p>;
  }

  const file = content.file;
  const isDir = type === 'directory';
  // Корень репозитория — путь пустой, показывать «/» понятнее, чем пустую строку.
  const name = path ? path.slice(path.lastIndexOf('/') + 1) : t('breadcrumb.root');

  const rows = [
    { label: t('info.name'), value: name },
    { label: t('info.path'), value: path || '/', mono: true },
    { label: t('info.type'), value: isDir ? t('info.typeDirectory') : t('info.typeFile') },
    { label: t('info.items'), value: isDir ? String(content.nodes?.length ?? 0) : null },
    { label: t('info.size'), value: !isDir && file?.sizeBytes != null ? formatFileSize(file.sizeBytes) : null },
    { label: t('info.language'), value: !isDir ? file?.language : null },
    { label: t('info.lines'), value: !isDir && file?.lineCount != null ? String(file.lineCount) : null },
    // ── Последний коммит. Пока история грузится, показываем это в строке даты,
    // а не отдельным блоком: иначе список дёргается, дорисовывая четыре строки.
    { label: t('info.modified'), value: commitLoading ? t('loading') : formatDateTime(commit?.date, i18n.language) },
    { label: t('info.author'), value: commit?.author },
    { label: t('info.commit'), value: commit?.shortHash, mono: true },
    { label: t('info.commitMessage'), value: commit?.message, block: true },
  ];

  return <InfoList rows={rows} />;
};

export default FileInfo;
