import React, { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { diffLines } from 'diff';
import MarkdownEditor from './MarkdownEditor';
import api from './api';
import { IconX } from './icons';

/**
 * Полноэкранная панель истории изменений описания документа.
 *
 * props:
 *   documentId    — id документа
 *   documentTitle — заголовок (в шапку)
 *   tree, onNavigate — пробрасываются в MarkdownEditor (previewOnly) для DocLinkTooltip
 *   onRestore     — optional (markdown) => void; «Восстановить изменённую версию»
 *   onClose       — () => void
 *
 * Бэкенд: GET /api/documents/{id}/history → DocumentHistory[], newest-first,
 * по одной записи на каждую версию описания (descriptionVersion), с полным
 * текстом description. Diff и предпросмотр считаются на фронте из этого списка.
 *
 * Список идёт newest-first: index 0 = новейшая версия, больший индекс = старее.
 * Инвариант выбора: база СТАРЕЕ изменённой ⇒ baseIdx > compareIdx.
 */

const fmtDate = (iso) => {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleString('ru-RU');
  } catch {
    return String(iso);
  }
};

// ─── Построчный diff по исходному markdown (вариант A) ──────────────────────
const DiffView = ({ base, compare }) => {
  const parts = useMemo(() => diffLines(base || '', compare || ''), [base, compare]);

  if (!parts.length) return <p className="history-empty">Нет данных для сравнения.</p>;

  const unchanged = parts.every((p) => !p.added && !p.removed);

  const rows = [];
  parts.forEach((p, pi) => {
    const kind = p.added ? 'add' : p.removed ? 'del' : 'ctx';
    const sign = p.added ? '+' : p.removed ? '−' : '\u00A0';
    // value часто заканчивается \n — не плодим лишнюю пустую строку в конце чанка
    const lines = p.value.replace(/\n$/, '').split('\n');
    lines.forEach((line, li) => {
      rows.push(
        <div key={`${pi}-${li}`} className={`history-diff__line history-diff__line--${kind}`}>
          <span className="history-diff__sign">{sign}</span>
          <span className="history-diff__text">{line || '\u00A0'}</span>
        </div>,
      );
    });
  });

  return (
    <>
      {unchanged && <p className="history-note">Описание идентично в обеих версиях.</p>}
      <div className="history-diff">{rows}</div>
    </>
  );
};

const HistoryModal = ({ documentId, documentTitle, tree = [], onNavigate, onRestore, onClose }) => {
  const [entries, setEntries] = useState(null); // null = loading | [] = empty | [...]
  const [error, setError] = useState(false);
  const [baseIdx, setBaseIdx] = useState(1); // «база» (старее) — по умолчанию предпоследняя
  const [compareIdx, setCompareIdx] = useState(0); // «изменённая» (новее) — по умолчанию последняя
  const [mode, setMode] = useState('diff'); // 'diff' | 'base' | 'compare'

  // Esc закрывает
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Загрузка истории
  useEffect(() => {
    let alive = true;
    setEntries(null);
    setError(false);
    api
      .fetchHistory(documentId)
      .then((data) => {
        if (!alive) return;
        const list = Array.isArray(data) ? data : [];
        setEntries(list);
        if (list.length >= 2) {
          setBaseIdx(1);
          setCompareIdx(0);
          setMode('diff');
        } else if (list.length === 1) {
          setBaseIdx(0);
          setCompareIdx(0);
          setMode('compare'); // сравнивать не с чем — показываем единственную версию
        }
      })
      .catch(() => alive && setError(true));
    return () => {
      alive = false;
    };
  }, [documentId]);

  const single = entries && entries.length < 2;
  const lastIdx = entries ? entries.length - 1 : 0;
  const base = entries && entries[baseIdx];
  const compare = entries && entries[compareIdx];

  // ── Выбор версий с сохранением инварианта baseIdx > compareIdx ────────────
  const selectBase = (i) => {
    setBaseIdx(i);
    setCompareIdx((c) => (c >= i ? i - 1 : c)); // изменённая обязана быть новее базы
    setMode('diff');
  };
  const selectCompare = (j) => {
    setCompareIdx(j);
    setBaseIdx((b) => (b <= j ? j + 1 : b)); // база обязана быть старее изменённой
    setMode('diff');
  };

  // Восстановить можно любую выбранную версию, КРОМЕ текущей (idx 0) — откат к
  // ней бессмыслен. База всегда старее изменённой (baseIdx > compareIdx), т.е.
  // никогда не равна текущей, поэтому «Восстановить базу» доступна при ≥2 версиях.
  const canRestoreBase = !single && !!base;
  const canRestoreCompare = compareIdx !== 0 && !!compare;

  const renderBody = () => {
    if (error) return <p className="history-empty">Не удалось загрузить историю. Попробуйте позже.</p>;
    if (entries === null) return <p className="history-empty">Загрузка…</p>;
    if (entries.length === 0) return <p className="history-empty">История пуста — документ ещё не сохранялся.</p>;

    if (mode === 'base') {
      return <MarkdownEditor value={base?.description || ''} previewOnly tree={tree} onNavigate={onNavigate} />;
    }
    if (mode === 'compare') {
      return <MarkdownEditor value={compare?.description || ''} previewOnly tree={tree} onNavigate={onNavigate} />;
    }
    return <DiffView base={base?.description} compare={compare?.description} />;
  };

  return createPortal(
    <div className="fs-editor-overlay" onMouseDown={onClose}>
      <div className="fs-editor" onMouseDown={(e) => e.stopPropagation()}>
        <div className="fs-editor__head">
          <span className="fs-editor__title">{documentTitle} — История изменений</span>
          <button className="fs-editor__close" title="Закрыть (Esc)" onClick={onClose}>
            <IconX />
          </button>
        </div>

        <div className="fs-editor__body">
          <div className="history-layout">
            {/* ── Список версий ── */}
            <div className="history-list">
              <div className="history-list__head">
                <span>Версия</span>
                <span title="База — что сравниваем (старее)">База</span>
                <span title="Изменённая — с чем сравниваем (новее)">Изм.</span>
              </div>

              {entries === null && <p className="history-empty">Загрузка…</p>}
              {entries &&
                entries.map((e, i) => (
                  <div key={`${e.version}-${i}`} className="history-item">
                    <div className="history-item__meta">
                      <div className="history-item__date">{fmtDate(e.updatedAt)}</div>
                      <div className="history-item__sub">
                        ред. {e.descriptionVersion}
                        {i === 0 ? ' · текущая' : ''}
                      </div>
                    </div>
                    <input
                      type="radio"
                      name="history-base"
                      checked={baseIdx === i}
                      disabled={single || i === 0 /* новейшая не может быть базой */}
                      onChange={() => selectBase(i)}
                    />
                    <input
                      type="radio"
                      name="history-compare"
                      checked={compareIdx === i}
                      disabled={single || i === lastIdx /* старейшая не может быть изменённой */}
                      onChange={() => selectCompare(i)}
                    />
                  </div>
                ))}
            </div>

            {/* ── Основная область ── */}
            <div className="history-main">
              <div className="history-toolbar">
                <div className="history-seg">
                  <button
                    className={mode === 'diff' ? 'is-active' : ''}
                    disabled={single}
                    onClick={() => setMode('diff')}
                  >
                    Сравнение
                  </button>
                  <button
                    className={mode === 'base' ? 'is-active' : ''}
                    disabled={single}
                    onClick={() => setMode('base')}
                  >
                    База
                  </button>
                  <button className={mode === 'compare' ? 'is-active' : ''} onClick={() => setMode('compare')}>
                    Изменённая
                  </button>
                </div>

                {onRestore && (
                  <div className="history-restore-group">
                    <button
                      className="history-restore"
                      disabled={!canRestoreBase}
                      title="Сохранить версию «База» как новую правку (откат к более ранней версии)"
                      onClick={() => {
                        if (!canRestoreBase) return;
                        onRestore(base.description || '');
                        onClose();
                      }}
                    >
                      Восстановить базу
                    </button>
                    <button
                      className="history-restore"
                      disabled={!canRestoreCompare}
                      title={
                        canRestoreCompare
                          ? 'Сохранить версию «Изменённая» как новую правку'
                          : 'Это текущая версия — восстанавливать нечего'
                      }
                      onClick={() => {
                        if (!canRestoreCompare) return;
                        onRestore(compare.description || '');
                        onClose();
                      }}
                    >
                      Восстановить изменённую
                    </button>
                  </div>
                )}
              </div>

              <div className="history-body">{renderBody()}</div>
            </div>
          </div>
        </div>
      </div>
    </div>,
    document.body,
  );
};

export default HistoryModal;
