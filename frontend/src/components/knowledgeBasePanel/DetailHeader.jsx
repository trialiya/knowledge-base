import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc, IconEdit, IconTrash, IconCheck, IconX, IconLock } from '../../icons';
import HeadCrumbs from '../common/HeadCrumbs';

// ─── Inline rename ────────────────────────────────────────────────────────────

const InlineRename = ({ value, onSave, onCancel }) => {
  const [val, setVal] = useState(value);
  const ref = useRef(null);
  useEffect(() => {
    ref.current?.focus();
    ref.current?.select();
  }, []);

  return (
    <span className="inline-rename workspace__head-edit">
      <input
        ref={ref}
        className="inline-rename__input"
        value={val}
        onChange={(e) => setVal(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') onSave(val);
          if (e.key === 'Escape') onCancel();
        }}
      />
      <button className="icon-btn" onClick={() => onSave(val)}>
        <IconCheck />
      </button>
      <button className="icon-btn" onClick={onCancel}>
        <IconX />
      </button>
    </span>
  );
};

// ─── DetailHeader ─────────────────────────────────────────────────────────────

/**
 * Шапка открытого узла базы знаний: путь к нему, иконка типа, имя с
 * переименованием на месте и удаление.
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
 */
const DetailHeader = ({ node, path, onNavigate, onRename, onDelete }) => {
  const { t } = useTranslation('knowledgeBase');
  const [renaming, setRenaming] = useState(false);
  const isFolder = node.type === 'folder';
  // Системный узел не переименовать и не удалить — вместо кнопок замок,
  // объясняющий, почему их нет. Во время правки имени кнопки тоже не нужны:
  // подтверждение и отмена стоят в самом поле (InlineRename).
  const actions = node.system ? (
    <span className="detail-header__system-badge" title={t('detail.systemBadge')}>
      <IconLock size={13} />
    </span>
  ) : (
    !renaming && (
      <>
        <button
          className="icon-btn detail-header__rename-btn"
          title={t('detail.rename')}
          onClick={() => setRenaming(true)}
        >
          <IconEdit />
        </button>
        <button className="icon-btn" title={t('detail.delete')} onClick={() => onDelete(node.id)}>
          <IconTrash />
        </button>
      </>
    )
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

      {renaming ? (
        <InlineRename
          value={node.title}
          onSave={(name) => {
            onRename(node.id, name);
            setRenaming(false);
          }}
          onCancel={() => setRenaming(false)}
        />
      ) : (
        <h2 className="workspace__head-title">{node.title}</h2>
      )}

      <div className="workspace__head-actions">{actions}</div>
    </div>
  );
};

export default DetailHeader;
