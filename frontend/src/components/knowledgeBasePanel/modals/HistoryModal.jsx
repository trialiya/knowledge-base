import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import MarkdownEditor from '../editor/MarkdownEditor';
import HistoryDiffView from './HistoryDiffView';
import { initialSelection } from './historySelection';
import api from '@/api/documentsApi';
import ModalShell from '@/components/common/modal/ModalShell';
import { IconX } from '@/icons/index';
import SegmentSwitch from '@/components/common/ui/SegmentSwitch';
import '@/components/common/ui/buttons.css';

/**
 * Полноэкранная панель истории изменений описания документа.
 *
 * props:
 *   documentId    — id документа
 *   documentTitle — заголовок (в шапку)
 *   initialVersion     — descriptionVersion, на которую навестись при открытии
 *   initialBaseVersion — optional: с какой версией её сравнить (по умолчанию —
 *                        с предыдущей). Версии нет в истории (0 — документа ещё не
 *                        было) ⇒ initialVersion показывается целиком
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

const HistoryModal = ({
  documentId,
  documentTitle,
  initialVersion,
  initialBaseVersion,
  tree = [],
  onNavigate,
  onRestore,
  onClose,
}) => {
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
  const inFlight = useRef(new Map()); // version → Promise<string> (дедуп + переиспользование)
  const aliveRef = useRef(true); // компонент ещё смонтирован?
  const docRef = useRef(documentId); // актуальный documentId (версии нумеруются по документу)

  useEffect(() => {
    docRef.current = documentId;
  }, [documentId]);

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  // Подгрузить описание версии. Возвращает Promise<string> с текстом версии,
  // чтобы и предпросмотр/diff (fire-and-forget), и «Восстановить» (нужен текст)
  // использовали один и тот же путь. Запросы дедуплицируются по версии.
  const ensureLoaded = useCallback(
    (version) => {
      if (version == null) return Promise.resolve('');
      if (cacheRef.current[version] !== undefined) return Promise.resolve(cacheRef.current[version]);
      if (inFlight.current.has(version)) return inFlight.current.get(version);

      const reqDoc = documentId;
      const promise = api
        .fetchHistoryVersion(reqDoc, version)
        .then((data) => {
          const text = data?.description ?? '';
          cacheRef.current[version] = text;
          if (aliveRef.current && docRef.current === reqDoc) {
            setDescCache((p) => ({ ...p, [version]: text }));
            setDescErr((p) => {
              if (!p[version]) return p;
              const n = { ...p };
              delete n[version];
              return n;
            });
          }
          return text;
        })
        .catch((err) => {
          if (aliveRef.current && docRef.current === reqDoc) setDescErr((p) => ({ ...p, [version]: true }));
          throw err;
        })
        .finally(() => inFlight.current.delete(version));

      inFlight.current.set(version, promise);
      return promise;
    },
    [documentId],
  );

  // Смена документа обнуляет всё, что относилось к предыдущему: версии
  // нумеруются внутри документа, поэтому и номера, и описания чужие. В рендере,
  // а не в эффекте — иначе один кадр показывал бы историю прошлого документа.
  const [prevReq, setPrevReq] = useState({ documentId, initialVersion, initialBaseVersion });
  if (
    prevReq.documentId !== documentId ||
    prevReq.initialVersion !== initialVersion ||
    prevReq.initialBaseVersion !== initialBaseVersion
  ) {
    setPrevReq({ documentId, initialVersion, initialBaseVersion });
    setEntries(null);
    setError(false);
    setDescCache({});
    setDescErr({});
  }

  // Загрузка списка версий (только метаданные)
  useEffect(() => {
    let alive = true;
    // зеркала того же кэша описаний — сбрасываем вместе с ним
    cacheRef.current = {};
    inFlight.current = new Map();
    api
      .fetchHistory(documentId)
      .then((data) => {
        if (!alive) return;
        const list = Array.isArray(data) ? data : [];
        setEntries(list);

        const sel = initialSelection(list, initialVersion, initialBaseVersion);
        setBaseIdx(sel.baseIdx);
        setCompareIdx(sel.compareIdx);
        setMode(sel.mode);
      })
      .catch(() => alive && setError(true));
    return () => {
      alive = false;
    };
  }, [documentId, initialVersion, initialBaseVersion]);

  const single = entries && entries.length < 2;
  const lastIdx = entries ? entries.length - 1 : 0;
  const base = entries && entries[baseIdx];
  const compare = entries && entries[compareIdx];

  // Подтягиваем описания только для выбранных версий (база + изменённая).
  useEffect(() => {
    if (!entries || entries.length === 0) return;
    if (base?.version != null) ensureLoaded(base.version).catch(() => {});
    if (compare?.version != null) ensureLoaded(compare.version).catch(() => {});
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

  // Восстановление переиспользует ensureLoaded (кэш или догрузка на лету).
  const handleRestore = async (entry) => {
    if (!entry || !onRestore) return;
    try {
      const text = await ensureLoaded(entry.version);
      onRestore(text || '');
      onClose();
    } catch {
      /* descErr уже выставлен ensureLoaded — модалка остаётся открытой */
    }
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
    return <HistoryDiffView base={baseDesc} compare={compareDesc} />;
  };

  return (
    <ModalShell onClose={onClose} variant="fullscreen">
      <div className="fs-editor__head">
        <span className="fs-editor__title">{t('history.title', { title: documentTitle })}</span>
        <button type="button" className="icon-btn" title={t('history.close')} onClick={onClose}>
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
              <SegmentSwitch
                options={[
                  { value: 'diff', label: t('history.modeDiff'), disabled: single },
                  { value: 'base', label: t('history.modeBase'), disabled: single },
                  { value: 'compare', label: t('history.modeCompare') },
                ]}
                value={mode}
                onChange={setMode}
                label={t('history.modeLabel')}
              />

              {onRestore && (
                <div className="history-restore-group">
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    disabled={!canRestoreBase}
                    title={t('history.restoreBaseTitle')}
                    onClick={() => handleRestore(base)}
                  >
                    {t('history.restoreBase')}
                  </button>
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
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
    </ModalShell>
  );
};

export default HistoryModal;
