import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import { getToolIcon, humanizeTool, toolLabelKey } from '../common/toolNames';
import { IconCopySmall, IconCopied } from '../../icons';
import useCopyFeedback from '../../hooks/useCopyFeedback';
import ModalShell from '../common/ModalShell';
import { detectResultView } from './resultViews/registry';
import { detectArgumentList } from './resultViews/argumentList';
import ArgumentListView from './resultViews/ArgumentListView';
import { formatJson, tryFormatJson, highlightJson } from './resultViews/jsonText';
import './styles/tool-call-detail.css';

// Два режима на секцию результата, не три: «Обзор» — типизированный вид,
// «JSON» — ровно то, что ушло модели, отформатированное и подсвеченное.
// Отдельный «сырой» режим не нужен: JSON-режим на неразбираемом ответе печатает
// исходную строку как есть, так что вход модели виден всегда.
const MODE = { OVERVIEW: 'overview', JSON: 'json' };

/** Маленькая кнопка копирования содержимого секции (аргументы/результат). */
const CopyButton = ({ value }) => {
  const { t } = useTranslation('chat');
  const [copied, copy] = useCopyFeedback();

  if (!value) return null;

  return (
    <button
      type="button"
      className={`tool-call-detail__copy${copied ? ' tool-call-detail__copy--done' : ''}`}
      onClick={() => copy(value)}
      title={copied ? t('common:copied') : t('toolCall.copy')}
    >
      {copied ? <IconCopied /> : <IconCopySmall />}
    </button>
  );
};

/**
 * Переключатель «Обзор | JSON». Не рендерится, когда обзора для формы нет.
 *
 * Группа кнопок с `aria-pressed`, а не `tablist`/`tab`: настоящие вкладки
 * требуют `tabpanel` с `aria-controls` и стрелок вместо Tab, а здесь два
 * состояния одной секции.
 */
const ModeSwitch = ({ mode, onChange, label }) => {
  const { t } = useTranslation('chat');
  return (
    <div className="tool-call-detail__modes" role="group" aria-label={label}>
      {[MODE.OVERVIEW, MODE.JSON].map((value) => (
        <button
          key={value}
          type="button"
          aria-pressed={mode === value}
          className={`tool-call-detail__mode${mode === value ? ' tool-call-detail__mode--active' : ''}`}
          onClick={() => onChange(value)}
        >
          {t(`toolCall.detail.mode.${value}`)}
        </button>
      ))}
    </div>
  );
};

const JsonBlock = ({ text, fallback }) =>
  text ? (
    <pre className="tool-call-detail__pre" dangerouslySetInnerHTML={{ __html: highlightJson(text) }} />
  ) : (
    <pre className="tool-call-detail__pre">{fallback || '—'}</pre>
  );

const ToolCallDetailModal = ({ conversationId, callId, tc, onClose }) => {
  const { t } = useTranslation('chat');
  // Ответ сервера; null — запрос ещё идёт. `loading`/`error` выводятся из него
  // при рендере, а сброс на смену вызова делается тут же — эффект показал бы
  // кадр с деталями предыдущего инструмента.
  const [answer, setAnswer] = useState(null); // { details, failed } | null
  const [mode, setMode] = useState(MODE.OVERVIEW);
  const [argsMode, setArgsMode] = useState(MODE.OVERVIEW);

  const [req, setReq] = useState({ conversationId, callId });
  if (req.conversationId !== conversationId || req.callId !== callId) {
    setReq({ conversationId, callId });
    setAnswer(null);
    setMode(MODE.OVERVIEW);
    setArgsMode(MODE.OVERVIEW);
  }

  useEffect(() => {
    if (!callId) return undefined;
    let cancelled = false;
    chatApi
      .getToolCallDetails(conversationId, callId)
      .then((data) => {
        if (!cancelled) setAnswer({ details: data || null, failed: false });
      })
      .catch(() => {
        if (!cancelled) setAnswer({ details: null, failed: true });
      });
    return () => {
      cancelled = true;
    };
  }, [conversationId, callId]);

  // Без callId запроса нет вовсе — сразу ошибка.
  const loading = !!callId && answer === null;
  const details = answer?.details ?? null;
  const error = !callId || answer?.failed ? t('toolCall.detail.loadError') : null;

  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const statusClass = details ? ` tool-call-detail__status--${details.status.toLowerCase()}` : '';

  // Разбор ответа — за useMemo: `resultText` бывает в десятки килобайт, а
  // переключение режима перерисовывает модалку целиком.
  const argsPretty = useMemo(() => (details ? formatJson(details.argumentsRaw) : null), [details]);
  const resultPretty = useMemo(() => (details ? tryFormatJson(details.resultText) : null), [details]);
  const view = useMemo(() => (details ? detectResultView(details.resultText, details.argumentsRaw) : null), [details]);
  const args = useMemo(() => (details ? detectArgumentList(details.argumentsRaw) : null), [details]);
  const showOverview = view !== null && mode === MODE.OVERVIEW;
  const OverviewView = view?.View;

  return (
    <ModalShell onClose={onClose} className="tool-call-detail">
      <div className="tool-call-detail__header">
        <span className="tool-call-detail__title">
          <span className="tool-call-detail__icon" aria-hidden="true">
            {icon}
          </span>
          {label}
        </span>
        <button className="tool-call-detail__close" onClick={onClose} title={t('close')}>
          ✕
        </button>
      </div>

      {loading && <div className="tool-call-detail__notice">{t('loading')}</div>}
      {error && <div className="tool-call-detail__notice tool-call-detail__notice--error">{error}</div>}
      {!loading && !error && !details && (
        <div className="tool-call-detail__notice tool-call-detail__notice--error">{t('toolCall.detail.notFound')}</div>
      )}

      {details && !loading && (
        <div className="tool-call-detail__body">
          <div className={`tool-call-detail__status${statusClass}`}>{details.status}</div>

          <section className="tool-call-detail__section">
            <div className="tool-call-detail__section-head">
              <div className="tool-call-detail__label">{t('toolCall.detail.arguments')}</div>
              {args && <ModeSwitch mode={argsMode} onChange={setArgsMode} label={t('toolCall.detail.arguments')} />}
              <CopyButton value={argsPretty} />
            </div>
            {args && argsMode === MODE.OVERVIEW ? (
              <ArgumentListView key={callId} data={args} />
            ) : (
              <JsonBlock text={argsPretty} />
            )}
          </section>

          <section className="tool-call-detail__section">
            <div className="tool-call-detail__section-head">
              <div className="tool-call-detail__label">{t('toolCall.detail.result')}</div>
              {view && <ModeSwitch mode={mode} onChange={setMode} label={t('toolCall.detail.result')} />}
              <CopyButton value={details.resultText} />
            </div>
            {showOverview ? (
              // key по вызову: у видов есть своё состояние (какие файлы
              // раскрыты, markdown или исходник), а ключи блоков внутри —
              // порядковые и у разных вызовов совпадают. Без key состояние
              // предыдущего вызова переехало бы на следующий.
              <OverviewView key={callId} data={view.data} />
            ) : (
              <JsonBlock text={resultPretty} fallback={details.resultText} />
            )}
          </section>

          {details.error && (
            <section className="tool-call-detail__section">
              <div className="tool-call-detail__label tool-call-detail__label--error">{t('toolCall.error')}</div>
              <pre className="tool-call-detail__pre tool-call-detail__pre--error">{details.error}</pre>
            </section>
          )}
        </div>
      )}
    </ModalShell>
  );
};

export default ToolCallDetailModal;
