import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc, IconChevronRight, IconEdit, IconTrash, IconCheck, IconX, IconLock } from '../../icons';

// ─── Inline rename ────────────────────────────────────────────────────────────

const InlineRename = ({ value, onSave, onCancel }) => {
  const [val, setVal] = useState(value);
  const ref = useRef(null);
  useEffect(() => {
    ref.current?.focus();
    ref.current?.select();
  }, []);

  return (
    <span className="inline-rename">
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

// ─── Breadcrumb ───────────────────────────────────────────────────────────────

/**
 * Путь к предкам узла (сам узел в `path` не входит — его имя стоит следом в
 * шапке). Разделитель есть и после последнего предка: крошки и заголовок
 * читаются одной цепочкой, как в файловом браузере.
 */
const Breadcrumb = ({ path, onNavigate }) => {
  if (!path || path.length === 0) return null;
  return (
    <div className="detail-breadcrumb">
      {path.map((node) => (
        <React.Fragment key={node.id}>
          <button className="detail-breadcrumb__item" onClick={() => onNavigate(node)}>
            {node.title}
          </button>
          <span className="detail-breadcrumb__sep" aria-hidden="true">
            <IconChevronRight size={11} />
          </span>
        </React.Fragment>
      ))}
    </div>
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
 */
const DetailHeader = ({ node, path, onNavigate, onRename, onDelete }) => {
  const { t } = useTranslation('knowledgeBase');
  const [renaming, setRenaming] = useState(false);
  const isFolder = node.type === 'folder';
  const isSystem = !!node.system;

  return (
    <div className="workspace__head detail-header">
      <Breadcrumb path={path} onNavigate={onNavigate} />

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

      <div className="workspace__head-actions">
        {isSystem ? (
          <span className="detail-header__system-badge" title={t('detail.systemBadge')}>
            <IconLock size={13} />
          </span>
        ) : (
          <>
            {!renaming && (
              <button
                className="icon-btn detail-header__rename-btn"
                title={t('detail.rename')}
                onClick={() => setRenaming(true)}
              >
                <IconEdit />
              </button>
            )}
            <button className="icon-btn" title={t('detail.delete')} onClick={() => onDelete(node.id)}>
              <IconTrash />
            </button>
          </>
        )}
      </div>
    </div>
  );
};

export default DetailHeader;
