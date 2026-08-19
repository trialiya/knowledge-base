import { useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc, IconTrash, IconLock, IconDownload } from '../../../icons/index';
import HeadCrumbs from '../../common/layout/HeadCrumbs';
import documentsApi from '../../../api/documentsApi';

// ─── DetailHeader ─────────────────────────────────────────────────────────────

/**
 * Шапка открытого узла базы знаний: путь к нему, иконка типа, имя с
 * переименованием на месте (клик по имени, как в шапке чата) и удаление.
 *
 * Оболочка общая — .workspace__head (common/workspaceLayout.css), высота та же,
 * что у шапок боковых панелей, поэтому строка ровно одна. Отсюда убраны:
 *   • дата создания — она (вместе с датой правки, версиями и id) на вкладке
 *     «Инфо» в правой панели, второй раз показывать её незачем;
 *   • кнопка «на уровень выше» — она вела ровно туда же, куда последняя
 *     хлебная крошка, а крошки теперь стоят в той же строке.
 *
 * В `path` только предки узла — сам он стоит следом заголовком, поэтому у крошек
 * есть замыкающий разделитель: путь и имя читаются одной цепочкой.
 *
 * Цепочку предков отдаём целиком: не влезающую в шапку середину схлопывает сам
 * HeadCrumbs. Названия узлов здесь длинные (это фразы, а не сегменты пути),
 * поэтому переполнение начинается уже на двух-трёх предках — считать его по
 * глубине дерева было бы мимо.
 */
const DetailHeader = ({ node, path, onNavigate, onRename, onDelete }) => {
  const { t } = useTranslation('knowledgeBase');
  // Черновик переименования храним вместе с id узла, которому он принадлежит:
  // коммит идёт по blur и может прийти уже после смены выделения — переименовать
  // надо тот узел, чьё имя правили.
  const [editing, setEditing] = useState(null); // null | { id, draft }
  // Отмена по Escape: blur после него приходит с уже устаревшим замыканием,
  // поэтому флаг живёт в ref. Гасится в обработчике blur и ещё раз при начале
  // новой правки — Escape уносит поле из DOM, и blur может не прийти.
  const cancelRef = useRef(false);
  const isFolder = node.type === 'folder';

  const commitRename = () => {
    const cancelled = cancelRef.current;
    cancelRef.current = false;
    if (!cancelled && editing?.draft.trim()) onRename(editing.id, editing.draft.trim());
    setEditing(null);
  };

  // Системный узел не переименовать и не удалить — вместо кнопок замок,
  // объясняющий, почему их нет.
  // Скачивание — обычная ссылка, а не fetch: браузер сам стримит ответ в файл,
  // и содержимое папки не проходит через память вкладки.
  const download = (
    <a
      className="icon-btn"
      href={documentsApi.downloadUrl(node.id)}
      download
      title={isFolder ? t('detail.downloadFolder') : t('detail.downloadDocument')}
    >
      <IconDownload size={14} />
    </a>
  );

  const actions = node.system ? (
    <>
      {download}
      <span className="detail-header__system-badge" title={t('detail.systemBadge')}>
        <IconLock size={13} />
      </span>
    </>
  ) : (
    <>
      {download}
      <button className="icon-btn" title={t('detail.delete')} onClick={() => onDelete(node.id)}>
        <IconTrash />
      </button>
    </>
  );

  const crumbs = (path || []).map((ancestor) => ({
    key: ancestor.id,
    label: ancestor.title,
    onNavigate: () => onNavigate(ancestor),
  }));

  return (
    <div className="workspace__head detail-header">
      <HeadCrumbs items={crumbs} trailingSep label={t('detail.breadcrumb')} />

      <span className={`detail-header__icon ${isFolder ? 'detail-header__icon--folder' : 'detail-header__icon--doc'}`}>
        {isFolder ? <IconFolder size={15} /> : <IconDoc size={13} />}
      </span>

      {editing ? (
        <input
          className="workspace__head-edit detail-header__edit"
          value={editing.draft}
          autoFocus
          onChange={(e) => setEditing((ed) => (ed ? { ...ed, draft: e.target.value } : ed))}
          onBlur={commitRename}
          onKeyDown={(e) => {
            if (e.key === 'Enter') e.target.blur();
            if (e.key === 'Escape') {
              cancelRef.current = true;
              e.target.blur();
            }
          }}
        />
      ) : node.system ? (
        <h2 className="workspace__head-title">{node.title}</h2>
      ) : (
        <h2
          className="workspace__head-title detail-header__title"
          title={t('detail.renameHint')}
          onClick={() => {
            cancelRef.current = false;
            setEditing({ id: node.id, draft: node.title });
          }}
        >
          {node.title}
        </h2>
      )}

      <div className="workspace__head-actions">{actions}</div>
    </div>
  );
};

export default DetailHeader;
