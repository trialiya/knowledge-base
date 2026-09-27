import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { diffLines } from 'diff';

// ─── Построчный diff + overview ruler ───────────────────────────────────────
const HistoryDiffView = ({ base, compare }) => {
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

export default HistoryDiffView;
