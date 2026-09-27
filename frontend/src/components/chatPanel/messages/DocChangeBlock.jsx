import { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import HistoryModal from '@/components/knowledgeBasePanel/modals/HistoryModal';
import { collectDocChanges } from './docChanges';
import { IconChevronDown } from '@/icons/index';
import '../styles/doc-changes.css';

/**
 * Блок в конце ответа ИИ (рендерит MessageList по вызовам всех сегментов ответа):
 * документные мутации (createDocument/updateDocument/секционные правки) из toolCalls.
 * Клик открывает HistoryModal прямо в чате — модалка сама рендерится в портал и
 * грузит историю через api. Модалка сравнивает версию до ответа с последней
 * версией ответа; созданный ответом документ показывается целиком.
 *
 * Работает и в live-стриме, и после перезагрузки чата (в обоих случаях resultMeta
 * прокинут в toolCalls — live-события TOOL_CALL несут мету с бэка).
 */
const DocChangeBlock = ({ toolCalls, onNavigateToDoc }) => {
  const { t } = useTranslation('chat');
  const [target, setTarget] = useState(null); // строка из collectDocChanges | null
  const [open, setOpen] = useState(false);

  // Одна строка на документ за весь ответ — как сворачиваются правки, см. docChanges.js.
  const changes = useMemo(() => collectDocChanges(toolCalls), [toolCalls]);

  if (changes.length === 0) return null;

  return (
    <div className="doc-change-block">
      <button type="button" className="change-block-summary" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
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
                {c.created ? t('docChange.created') : t('docChange.updated')}
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
          initialBaseVersion={target.baseVersion}
          tree={[]}
          onNavigate={onNavigateToDoc ? (id) => onNavigateToDoc(String(id)) : undefined}
          onClose={() => setTarget(null)}
        />
      )}
    </div>
  );
};

export default DocChangeBlock;
