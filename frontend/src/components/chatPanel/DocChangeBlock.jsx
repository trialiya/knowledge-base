import React, { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import HistoryModal from '../knowledgeBasePanel/HistoryModal';
import { getDocChangeRef } from './toolMeta';
import { TOOL_STATUS } from '../../constants/toolStatus';
import { IconChevronDown } from '../../icons';
import './styles/doc-changes.css';

/**
 * Блок в конце ответа ИИ (рендерит MessageList по вызовам всех сегментов ответа):
 * документные мутации (createDocument/updateDocument) из toolCalls. Клик открывает
 * HistoryModal прямо в чате — модалка сама рендерится в портал и грузит историю
 * через api. id/version берём из resultMeta.
 *
 * Работает и в live-стриме, и после перезагрузки чата (в обоих случаях resultMeta
 * прокинут в toolCalls — live-события TOOL_CALL несут мету с бэка).
 */
const DocChangeBlock = ({ toolCalls, onNavigateToDoc }) => {
  const { t } = useTranslation('chat');
  const [target, setTarget] = useState(null); // { id, version, title, action } | null
  const [open, setOpen] = useState(false);

  // Одна строка на документ: максимальная версия + первый непустой title.
  // Вызовы со статусом ERROR пропускаются целиком: упавшая мутация не создала
  // новой версии, и её пропуск НЕ трогает уже учтённые успешные правки того же
  // документа — они остаются в byId со своей версией.
  const changes = useMemo(() => {
    const byId = new Map();
    for (const tc of toolCalls || []) {
      const ref = getDocChangeRef(tc);
      if (!ref || ref.status === TOOL_STATUS.ERROR) continue;
      const title = ref.title || tc.arguments?.title || tc.arguments?.name || null;
      const cur = byId.get(ref.id);
      if (!cur) {
        byId.set(ref.id, { ...ref, title });
      } else {
        // cur — свежий объект, созданный спредом в ЭТОМ же проходе memo;
        // мутация локальна и не задевает toolCalls/props.
        if ((ref.descriptionVersion ?? 0) > (cur.descriptionVersion ?? 0)) {
          cur.descriptionVersion = ref.descriptionVersion;
          cur.action = ref.action;
        }
        if (!cur.title && title) cur.title = title;
      }
    }
    return [...byId.values()];
  }, [toolCalls]);

  if (changes.length === 0) return null;

  return (
    <div className="doc-change-block">
      <button
        type="button"
        className="change-block-summary"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        <span className="change-block-summary-icon" aria-hidden="true">
          📄
        </span>
        <span className="change-block-summary-text">
          {t('docChange.summary', { count: changes.length, defaultValue: `Documents changed (${changes.length})` })}
        </span>
        <span className={`change-block-chevron ${open ? 'change-block-chevron--open' : ''}`}>
          <IconChevronDown />
        </span>
      </button>

      {open &&
        changes.map((c) => (
          <button
            key={c.id}
            type="button"
            className="doc-change-item"
            onClick={() => setTarget(c)}
            title={t('docChange.viewChanges')}
          >
            <span className="doc-change-icon" aria-hidden="true">
              📄
            </span>
            <span className="doc-change-text">
              <span className="doc-change-title">{c.title || t('docChange.untitled', { id: c.id })}</span>
              <span className="doc-change-sub">
                {c.action === 'createDocument' ? t('docChange.created') : t('docChange.updated')}
                {c.descriptionVersion != null ? ` · v${c.descriptionVersion}` : ''}
              </span>
            </span>
            <span className="doc-change-cta">{t('docChange.viewChanges')} ›</span>
          </button>
        ))}

      {target && (
        <HistoryModal
          documentId={target.id}
          documentTitle={target.title || `#${target.id}`}
          initialVersion={target.descriptionVersion}
          tree={[]}
          onNavigate={onNavigateToDoc ? (id) => onNavigateToDoc(String(id)) : undefined}
          onClose={() => setTarget(null)}
        />
      )}
    </div>
  );
};

export default DocChangeBlock;
