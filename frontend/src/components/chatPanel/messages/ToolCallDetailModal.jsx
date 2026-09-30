import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import chatApi from '@/api/chatApi';
import { getToolIcon, humanizeTool, statusLabelKey, toolLabelKey } from '@/components/common/ui/toolNames';
import CopyButton from '@/components/common/ui/CopyButton';
import ModalShell from '@/components/common/modal/ModalShell';
import { formatTokens, usageTooltip } from './tokenUsage';
import { detectResultView } from './resultViews/registry';
import { detectArgumentList } from './resultViews/argumentList';
import ArgumentListView from './resultViews/ArgumentListView';
import { formatJson, tryFormatJson, highlightJson } from './resultViews/jsonText';
import { TOOL_STATUS } from '@/constants/toolStatus';
import '@/components/common/ui/buttons.css';
import '../styles/tool-call-detail.css';

// Два режима на секцию результата, не три: «Обзор» — типизированный вид,
// «JSON» — сам ответ, отформатированный и подсвеченный.
// Отдельный «сырой» режим не нужен: JSON-режим на неразбираемом ответе печатает
// исходную строку как есть, так что ответ виден всегда.
const MODE = { OVERVIEW: 'overview', JSON: 'json' };
const MODES = [MODE.OVERVIEW, MODE.JSON];

// Какой ответ показывать, когда модели ушёл урезанный (`fullResultText` есть только
// тогда): целиком — то, что на самом деле вернул инструмент, или ровно то, что
// получила модель. Режимы выше работают поверх любой из двух версий.
const VERSION = { FULL: 'full', MODEL: 'model' };
const VERSIONS = [VERSION.FULL, VERSION.MODEL];

// Опрос деталей работающего вызова: первая пауза короткая (инструмент часто отвечает через
// секунду-другую), дальше вдвое до потолка — модалку могли открыть и забыть.
const POLL_MIN_MS = 1000;
const POLL_MAX_MS = 15000;
/** Сколько раз повторяем сорвавшийся запрос, прежде чем оставить ошибку на экране. */
const ERROR_RETRIES = 3;

/**
 * Переключатель состояний одной секции: «Обзор | JSON», «Полный | Для модели».
 * `keyPrefix` — ключи i18n подписи (`<prefix>.<value>`) и подсказки
 * (`<prefix>.<value>Hint`, если она есть).
 *
 * Группа кнопок с `aria-pressed`, а не `tablist`/`tab`: настоящие вкладки
 * требуют `tabpanel` с `aria-controls` и стрелок вместо Tab, а здесь два
 * состояния одной секции.
 */
const ModeSwitch = ({ values, mode, onChange, label, keyPrefix }) => {
  const { t } = useTranslation('chat');
  return (
    <div className="tool-call-detail__modes" role="group" aria-label={label}>
      {values.map((value) => (
        <button
          key={value}
          type="button"
          aria-pressed={mode === value}
          className={`tool-call-detail__mode${mode === value ? ' tool-call-detail__mode--active' : ''}`}
          title={t(`${keyPrefix}.${value}Hint`, { defaultValue: '' }) || undefined}
          onClick={() => onChange(value)}
        >
          {t(`${keyPrefix}.${value}`)}
        </button>
      ))}
    </div>
  );
};

/**
 * Чем обошёлся вызов: своя модель инструмента и её токены. Есть только у инструментов, которые
 * сами ходят в модель (сегодня это searchCodebase), и приезжает в resultMeta, а не в результате —
 * модели эти числа намеренно не показывают (см. SearchAgentResult).
 *
 * Наверху здесь total input и output, а не занятый контекст: контекст суб-агента живёт секунды и
 * умирает вместе с вызовом, а вопрос к нему один — во сколько обошёлся поиск.
 */
const ToolCost = ({ meta }) => {
  const { t } = useTranslation('chat');
  if (!meta?.model) return null;
  const usage = meta.usage;

  return (
    <div className="tool-call-detail__cost">
      <span>{t('toolCall.detail.costModel', { model: meta.model })}</span>
      {usage?.contextTokens > 0 && (
        <span title={usageTooltip(usage, t, 'toolCall.detail.costContext')}>
          {t('toolCall.detail.costTokens', {
            input: formatTokens(usage.promptTokens),
            output: formatTokens(usage.outputTokens),
            calls: usage.modelCalls,
          })}
        </span>
      )}
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
  const [version, setVersion] = useState(VERSION.FULL);

  const [req, setReq] = useState({ conversationId, callId });
  if (req.conversationId !== conversationId || req.callId !== callId) {
    setReq({ conversationId, callId });
    setAnswer(null);
    setMode(MODE.OVERVIEW);
    setArgsMode(MODE.OVERVIEW);
    setVersion(VERSION.FULL);
  }

  // Модалку открывают и на работающем вызове — ради аргументов, — поэтому пока ответ говорит
  // STARTED или UNKNOWN (исход ещё не записан), детали перезапрашиваются сами. Одной
  // перезагрузки по смене статуса плашки мало сразу по трём причинам: SSE-событие ответа
  // публикуется ещё внутри транзакции записи, так что запрос может обогнать коммит и получить
  // тот же STARTED; у вызова, брошенного оборванным прогоном, меты не будет никогда — в итоговое
  // TOOL_CALLS он не попадает, плашку ему закрывает finalize статусом UNKNOWN, и отличить это от
  // «мета ещё не доехала» модалке нечем; а неудачная единственная попытка не повторилась бы.
  // Пауза растёт от POLL_MIN_MS к POLL_MAX_MS — забытая открытой модалка не должна долбить
  // сервер, а живой вызов виден почти сразу.
  //
  // Статус плашки при этом остаётся в зависимостях: он приходит раньше нашего опроса, и по
  // нему результат подхватывается без ожидания следующего тика.
  useEffect(() => {
    if (!callId) return undefined;
    let cancelled = false;
    let timer = null;
    let delay = POLL_MIN_MS;
    let errors = 0;

    const again = () => {
      // Показанные аргументы не стираем: сорвавшийся перезапрос — повод повторить, а не
      // заменить экран ошибкой уже над прочитанным.
      timer = setTimeout(load, delay);
      delay = Math.min(delay * 2, POLL_MAX_MS);
    };

    const load = () => {
      chatApi
        .getToolCallDetails(conversationId, callId)
        .then((data) => {
          if (cancelled) return;
          setAnswer({ details: data || null, failed: false });
          // UNKNOWN здесь не окончательный: так отвечает вызов, ответ которого уже записан, а
          // мета прогона — ещё нет (её пишет конец прогона), и статус плашки при этом меняться
          // не обязан — переспросить некому, кроме нас.
          if (data?.status === TOOL_STATUS.STARTED || data?.status === TOOL_STATUS.UNKNOWN) again();
        })
        .catch(() => {
          if (cancelled) return;
          setAnswer((prev) => (prev?.details ? prev : { details: null, failed: true }));
          // Ошибку повторяем считанное число раз: 404 бывает и осмысленным (детали не
          // сохранены), а вечный опрос ради него — трафик впустую. Смена статуса плашки
          // перезапускает эффект, и попытки начинаются заново.
          if (++errors <= ERROR_RETRIES) again();
        });
    };

    load();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [conversationId, callId, tc.status]);

  // Без callId запроса нет вовсе — сразу ошибка.
  const loading = !!callId && answer === null;
  const details = answer?.details ?? null;
  const error = !callId || answer?.failed ? t('toolCall.detail.loadError') : null;

  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const statusClass = details ? ` tool-call-detail__status--${details.status.toLowerCase()}` : '';

  // Вторая версия есть, только когда модели ушёл урезанный ответ.
  const trimmed = !!details?.fullResultText;
  const resultText = trimmed && version === VERSION.FULL ? details.fullResultText : details?.resultText;

  // Разбор ответа — за useMemo: `resultText` бывает в десятки килобайт, а
  // переключение режима перерисовывает модалку целиком.
  const argsPretty = useMemo(() => (details ? formatJson(details.argumentsRaw) : null), [details]);
  const resultPretty = useMemo(() => (details ? tryFormatJson(resultText) : null), [details, resultText]);
  const view = useMemo(
    () => (details ? detectResultView(resultText, details.argumentsRaw) : null),
    [details, resultText],
  );
  const args = useMemo(() => (details ? detectArgumentList(details.argumentsRaw) : null), [details]);
  const showOverview = view !== null && mode === MODE.OVERVIEW;
  const OverviewView = view?.View;
  // Вызов ещё идёт: аргументы уже есть, результата нет — вместо пустого JSON-блока
  // говорим об этом словами. Как только придёт ответ, эффект выше перезапросит детали.
  const running = details?.status === TOOL_STATUS.STARTED && !details.resultText;

  return (
    <ModalShell onClose={onClose} className="tool-call-detail">
      <div className="tool-call-detail__header">
        <span className="tool-call-detail__title">
          <span className="tool-call-detail__icon" aria-hidden="true">
            {icon}
          </span>
          {label}
        </span>
        <button type="button" className="icon-btn" onClick={onClose} title={t('close')}>
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
          <div className={`tool-call-detail__status${statusClass}`}>{t(statusLabelKey(details.status))}</div>

          <ToolCost meta={tc.resultMeta} />

          <section className="tool-call-detail__section">
            <div className="tool-call-detail__section-head">
              <div className="tool-call-detail__label">{t('toolCall.detail.arguments')}</div>
              {args && (
                <ModeSwitch
                  values={MODES}
                  mode={argsMode}
                  onChange={setArgsMode}
                  label={t('toolCall.detail.arguments')}
                  keyPrefix="toolCall.detail.mode"
                />
              )}
              <CopyButton value={argsPretty} label={t('toolCall.detail.arguments')} />
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
              {trimmed && (
                <ModeSwitch
                  values={VERSIONS}
                  mode={version}
                  onChange={setVersion}
                  label={t('toolCall.detail.versionLabel')}
                  keyPrefix="toolCall.detail.version"
                />
              )}
              {view && (
                <ModeSwitch
                  values={MODES}
                  mode={mode}
                  onChange={setMode}
                  label={t('toolCall.detail.result')}
                  keyPrefix="toolCall.detail.mode"
                />
              )}
              <CopyButton value={resultText} label={t('toolCall.detail.result')} />
            </div>
            {running ? (
              <div className="tool-call-detail__notice" role="status" aria-live="polite">
                {t('toolCall.detail.running')}
              </div>
            ) : showOverview ? (
              // key по вызову: у видов есть своё состояние (какие файлы
              // раскрыты, markdown или исходник), а ключи блоков внутри —
              // порядковые и у разных вызовов совпадают. Без key состояние
              // предыдущего вызова переехало бы на следующий.
              <OverviewView key={callId} data={view.data} />
            ) : (
              <JsonBlock text={resultPretty} fallback={resultText} />
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
