import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
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
 * Бэкенд:
 *   GET /api/documents/{id}/history           → DocumentHistoryShort[], newest-first,
 *                                               по одной записи на каждую версию описания,
 *                                               БЕЗ тела description (только метаданные).
 *   GET /api/documents/{id}/history/{version} → DocumentHistory с полным description.
 *
 * Текст версии (description) подтягивается лениво — только когда версия выбрана
 * (база/изменённая) — и кэшируется по номеру версии. Diff и предпросмотр считаются
 * на фронте из подгруженных описаний.
 *
 * Список идёт newest-first: index 0 = новейшая версия, больший индекс = старее.
 * Инвариант выбора: база СТАРЕЕ изменённой ⇒ baseIdx > compareIdx.
 */

const fmtDate = (iso, locale) => {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleString(locale);
  } catch {
    return String(iso);
  }
};

// ─── Построчный diff + overview ruler ───────────────────────────────────────
const DiffView = ({ base, compare }) => {
  const { t } = useTranslation('knowledgeBase');
  const parts = useMemo(() => diffLines(base || '', compare || ''), [base, compare]);
  const scrollRef = useRef(null);
  const rulerRef = useRef(null);
  const dragRef = useRef(null);
  const [metrics, setMetrics] = useState({ top: 0, height: 0, total: 1 });

  const { rows, markers, totalLines, unchanged } = useMemo(() => {
    const rows = [];
    const markers = [];
    let lineNo = 0;
    let unchanged = true;

    parts.forEach((p, pi) => {
      const kind = p.added ? 'add' : p.removed ? 'del' : 'ctx';
      if (kind !== 'ctx') unchanged = false;
      const sign = p.added ? '+' : p.removed ? '−' : '\u00A0';
      const lines = p.value.replace(/\n$/, '').split('\n');
      const start = lineNo;

      lines.forEach((line, li) => {
        rows.push(
          <div key={`${pi}-${li}`} className={`history-diff__line history-diff__line--${kind}`}>
            <span className="history-diff__sign">{sign}</span>
            <span className="history-diff__text">{line || '\u00A0'}</span>
          </div>,
        );
        lineNo += 1;
      });

      if (kind !== 'ctx') markers.push({ kind, start, len: lines.length });
    });

    return { rows, markers, totalLines: Math.max(lineNo, 1), unchanged };
  }, [parts]);

  // Синхронизируем положение/размер ползунка со скроллом и ресайзом.
  const syncMetrics = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    setMetrics({ top: el.scrollTop, height: el.clientHeight, total: el.scrollHeight || 1 });
  }, []);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return undefined;
    syncMetrics();
    const ro = new ResizeObserver(syncMetrics);
    ro.observe(el);
    return () => ro.disconnect();
  }, [syncMetrics, rows]);

  if (!parts.length) return <p className="history-empty">{t('history.noDiff')}</p>;

  const scrollToFrac = (frac) => {
    const el = scrollRef.current;
    if (!el) return;
    const clamped = Math.min(Math.max(frac, 0), 1);
    el.scrollTo({ top: clamped * (el.scrollHeight - el.clientHeight), behavior: 'smooth' });
  };

  // Клик по дорожке (не по ползунку и не по метке) — прыжок к позиции.
  const onTrackClick = (e) => {
    if (e.target !== rulerRef.current) return;
    const rect = rulerRef.current.getBoundingClientRect();
    scrollToFrac((e.clientY - rect.top) / rect.height);
  };

  // Перетаскивание ползунка.
  const onThumbDown = (e) => {
    e.preventDefault();
    e.stopPropagation();
    const el = scrollRef.current;
    const rect = rulerRef.current.getBoundingClientRect();
    dragRef.current = { startY: e.clientY, startTop: el.scrollTop, rulerH: rect.height };
    const onMove = (ev) => {
      const d = dragRef.current;
      if (!d) return;
      const deltaFrac = (ev.clientY - d.startY) / d.rulerH;
      el.scrollTop = d.startTop + deltaFrac * el.scrollHeight;
    };
    const onUp = () => {
      dragRef.current = null;
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  const thumbTop = (metrics.top / metrics.total) * 100;
  const thumbHeight = (metrics.height / metrics.total) * 100;
  const showThumb = metrics.height < metrics.total - 1; // есть что скроллить

  return (
    <div className="history-diff-area">
      <div
        className={`history-diff-scroll${unchanged ? '' : ' history-diff-scroll--ruled'}`}
        ref={scrollRef}
        onScroll={syncMetrics}
      >
        {unchanged && <p className="history-note">{t('history.identical')}</p>}
        <div className="history-diff">{rows}</div>
      </div>

      {!unchanged && (
        <div className="history-ruler" ref={rulerRef} onClick={onTrackClick} title={t('history.rulerHint')}>
          {markers.map((m, i) => (
            <button
              key={i}
              type="button"
              className={`history-ruler__mark history-ruler__mark--${m.kind}`}
              style={{
                top: `${(m.start / totalLines) * 100}%`,
                height: `${Math.max((m.len / totalLines) * 100, 0.8)}%`,
              }}
              onClick={(e) => {
                e.stopPropagation();
                scrollToFrac(m.start / totalLines);
              }}
            />
          ))}
          {showThumb && (
            <div
              className="history-ruler__thumb"
              style={{ top: `${thumbTop}%`, height: `${thumbHeight}%` }}
              onPointerDown={onThumbDown}
            />
          )}
        </div>
      )}
    </div>
  );
};

const HistoryModal = ({ documentId, documentTitle, tree = [], onNavigate, onRestore, onClose }) => {
  const { t, i18n } = useTranslation('knowledgeBase');
  const [entries, setEntries] = useState(null); // null = loading | [] = empty | [...]
  const [error, setError] = useState(false);
  const [baseIdx, setBaseIdx] = useState(1); // «база» (старее) — по умолчанию предпоследняя
  const [compareIdx, setCompareIdx] = useState(0); // «изменённая» (новее) — по умолчанию последняя
  const [mode, setMode] = useState('diff'); // 'diff' | 'base' | 'compare'

  // ── Ленивая подгрузка описаний по номеру версии ───────────────────────────
  // descCache: { [version]: string }  — текст версии (undefined = ещё не загружен)
  // descErr:   { [version]: true }    — ошибка загрузки конкретной версии
  const [descCache, setDescCache] = useState({});
  const [descErr, setDescErr] = useState({});
  const cacheRef = useRef({}); // синхронное зеркало descCache для проверок без гонок
  const inFlight = useRef(new Set()); // версии, для которых запрос уже летит
  const aliveRef = useRef(true); // компонент ещё смонтирован?
  const docRef = useRef(documentId); // актуальный documentId (версии нумеруются по документу)
  docRef.current = documentId;

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  // Подгрузить описание версии (если ещё не загружено и не грузится сейчас).
  const ensureLoaded = useCallback(
    (version) => {
      if (version == null) return;
      if (cacheRef.current[version] !== undefined) return; // уже есть
      if (inFlight.current.has(version)) return; // уже грузится
      const reqDoc = documentId;
      inFlight.current.add(version);
      api
        .fetchHistoryVersion(reqDoc, version)
        .then((data) => {
          if (!aliveRef.current || docRef.current !== reqDoc) return; // документ сменился — игнор
          const text = data?.description ?? '';
          cacheRef.current[version] = text;
          setDescCache((p) => ({ ...p, [version]: text }));
          setDescErr((p) => {
            if (!p[version]) return p;
            const n = { ...p };
            delete n[version];
            return n;
          });
        })
        .catch(() => {
          if (!aliveRef.current || docRef.current !== reqDoc) return;
          setDescErr((p) => ({ ...p, [version]: true }));
        })
        .finally(() => inFlight.current.delete(version));
    },
    [documentId],
  );

  // Esc закрывает
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Загрузка списка версий (только метаданные)
  useEffect(() => {
    let alive = true;
    setEntries(null);
    setError(false);
    // сбрасываем кэш описаний — версии нумеруются внутри документа
    cacheRef.current = {};
    inFlight.current = new Set();
    setDescCache({});
    setDescErr({});
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

  // Подтягиваем описания только для выбранных версий (база + изменённая).
  useEffect(() => {
    if (!entries || entries.length === 0) return;
    if (base?.version != null) ensureLoaded(base.version);
    if (compare?.version != null) ensureLoaded(compare.version);
  }, [entries, baseIdx, compareIdx, base, compare, ensureLoaded]);

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

  // Описания выбранных версий из кэша (undefined = ещё грузится).
  const baseVersion = base?.version;
  const compareVersion = compare?.version;
  const baseDesc = baseVersion != null ? descCache[baseVersion] : undefined;
  const compareDesc = compareVersion != null ? descCache[compareVersion] : undefined;
  const baseDescErr = baseVersion != null && !!descErr[baseVersion];
  const compareDescErr = compareVersion != null && !!descErr[compareVersion];

  // Восстановление: гарантированно берём текст (из кэша или догружаем на лету).
  const handleRestore = async (entry) => {
    if (!entry || !onRestore) return;
    let text = cacheRef.current[entry.version];
    if (text === undefined) {
      try {
        const data = await api.fetchHistoryVersion(documentId, entry.version);
        text = data?.description ?? '';
        cacheRef.current[entry.version] = text;
        if (aliveRef.current) setDescCache((p) => ({ ...p, [entry.version]: text }));
      } catch {
        if (aliveRef.current) setDescErr((p) => ({ ...p, [entry.version]: true }));
        return;
      }
    }
    onRestore(text || '');
    onClose();
  };

  const renderBody = () => {
    if (error) return <p className="history-empty">{t('history.loadHistoryError')}</p>;
    if (entries === null) return <p className="history-empty">{t('history.loading')}</p>;
    if (entries.length === 0) return <p className="history-empty">{t('history.emptyHistory')}</p>;

    if (mode === 'base') {
      if (baseDescErr) return <p className="history-empty">{t('history.loadVersionError')}</p>;
      if (baseDesc === undefined) return <p className="history-empty">{t('history.loadingVersion')}</p>;
      return <MarkdownEditor value={baseDesc || ''} previewOnly tree={tree} onNavigate={onNavigate} />;
    }
    if (mode === 'compare') {
      if (compareDescErr) return <p className="history-empty">{t('history.loadVersionError')}</p>;
      if (compareDesc === undefined) return <p className="history-empty">{t('history.loadingVersion')}</p>;
      return <MarkdownEditor value={compareDesc || ''} previewOnly tree={tree} onNavigate={onNavigate} />;
    }
    // mode === 'diff' — нужны оба описания
    if (baseDescErr || compareDescErr) return <p className="history-empty">{t('history.loadVersionError')}</p>;
    if (baseDesc === undefined || compareDesc === undefined)
      return <p className="history-empty">{t('history.loadingVersions')}</p>;
    return <DiffView base={baseDesc} compare={compareDesc} />;
  };

  return createPortal(
    <div className="fs-editor-overlay" onMouseDown={onClose}>
      <div className="fs-editor" onMouseDown={(e) => e.stopPropagation()}>
        <div className="fs-editor__head">
          <span className="fs-editor__title">{t('history.title', { title: documentTitle })}</span>
          <button className="fs-editor__close" title={t('history.close')} onClick={onClose}>
            <IconX />
          </button>
        </div>

        <div className="fs-editor__body">
          <div className="history-layout">
            {/* ── Список версий ── */}
            <div className="history-list">
              <div className="history-list__head">
                <span>{t('history.colVersion')}</span>
                <span title={t('history.colBaseHint')}>{t('history.colBase')}</span>
                <span title={t('history.colCompareHint')}>{t('history.colCompare')}</span>
              </div>

              {entries === null && <p className="history-empty">{t('history.loading')}</p>}
              {entries &&
                entries.map((e, i) => (
                  <div key={`${e.version}-${i}`} className="history-item">
                    <div className="history-item__meta">
                      <div className="history-item__date">{fmtDate(e.updatedAt, i18n.language)}</div>
                      <div className="history-item__sub">
                        {t('history.edit', { n: e.descriptionVersion })}
                        {i === 0 ? ` · ${t('history.current')}` : ''}
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
                    {t('history.modeDiff')}
                  </button>
                  <button
                    className={mode === 'base' ? 'is-active' : ''}
                    disabled={single}
                    onClick={() => setMode('base')}
                  >
                    {t('history.modeBase')}
                  </button>
                  <button className={mode === 'compare' ? 'is-active' : ''} onClick={() => setMode('compare')}>
                    {t('history.modeCompare')}
                  </button>
                </div>

                {onRestore && (
                  <div className="history-restore-group">
                    <button
                      className="history-restore"
                      disabled={!canRestoreBase}
                      title={t('history.restoreBaseTitle')}
                      onClick={() => handleRestore(base)}
                    >
                      {t('history.restoreBase')}
                    </button>
                    <button
                      className="history-restore"
                      disabled={!canRestoreCompare}
                      title={canRestoreCompare ? t('history.restoreCompareTitle') : t('history.restoreCurrentTitle')}
                      onClick={() => handleRestore(compare)}
                    >
                      {t('history.restoreCompare')}
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
