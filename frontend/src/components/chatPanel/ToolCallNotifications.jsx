import React, { useState, useRef, useEffect, useLayoutEffect } from 'react';
import ReactDOM from 'react-dom';
import { useTranslation } from 'react-i18next';
import ToolCallDetailModal from './ToolCallDetailModal';
import { getToolIcon, toolLabelKey, humanizeTool } from './toolMeta';
import { IconCopySmall, IconCopied, IconStatusStarted, IconStatusOk, IconStatusError } from '../../icons';
import { COPY_DONE_MS, GIST_PREVIEW_LEN } from '../../constants/ui';
import { TOOL_STATUS } from '../../constants/toolStatus';
import './styles/tool-calls.css';

// Inline-блок плашек вызовов инструментов под пузырём ответа ассистента.
// Вынесено из Message.jsx: сам пузырь сообщения не должен знать о деталях
// tool-call UI (тултипы, группировка, модалка деталей).

const StatusIcon = ({ status }) => {
  switch (status) {
    case TOOL_STATUS.STARTED:
      return <IconStatusStarted />;
    case TOOL_STATUS.OK:
      return <IconStatusOk />;
    case TOOL_STATUS.ERROR:
      return <IconStatusError />;
    default:
      return <IconStatusStarted />;
  }
};

const formatArgs = (args) => {
  if (!args || Object.keys(args).length === 0) return null;
  return Object.entries(args)
    .map(([key, val]) => `${key}: ${typeof val === 'string' ? val : JSON.stringify(val)}`)
    .join(', ');
};

/** Однострочное усечённое превью resultGist для плашки. */
const gistPreview = (gist) => {
  if (!gist) return null;
  const oneLine = gist.replace(/\s+/g, ' ').trim();
  return oneLine.length > GIST_PREVIEW_LEN ? oneLine.slice(0, GIST_PREVIEW_LEN) + '…' : oneLine;
};

/**
 * Build a copyable text summary of a tool call.
 * `t` передаётся параметром, т.к. функция вне области React-хука.
 */
const buildCopyText = (tc, t) => {
  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const parts = [label];
  const argsStr = formatArgs(tc.arguments);
  if (argsStr) parts.push(argsStr);
  if (tc.resultGist) parts.push(`${t('toolCall.result')}: ${tc.resultGist}`);
  parts.push(`${t('toolCall.status')}: ${tc.status || '—'}`);
  if (tc.status === TOOL_STATUS.ERROR && tc.error) parts.push(`${t('toolCall.error')}: ${tc.error}`);
  return parts.join('\n');
};

/** Одиночная плашка вызова — hover-тултип + кнопка копирования + кнопка деталей */
const ToolCallItem = ({ tc, conversationId }) => {
  const { t } = useTranslation('chat');
  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const argsStr = formatArgs(tc.arguments);
  const gist = gistPreview(tc.resultGist);
  const itemRef = useRef(null);
  const tooltipRef = useRef(null);
  const copyTimerRef = useRef(null);
  const [hover, setHover] = useState(false);
  // pos: null пока не измерили реальный размер тултипа (рендерим скрытым).
  const [pos, setPos] = useState(null);
  const [copied, setCopied] = useState(false);
  const [showDetail, setShowDetail] = useState(false);

  useEffect(() => () => clearTimeout(copyTimerRef.current), []);
  // messageId/callId приходят вместе с плашкой (SSE TOOL_CALL/TOOL_CALLS или GET /messages) —
  // без них (старые записи до этого поля) модалке деталей нечего запросить.
  const canShowDetail = !!(
    conversationId &&
    tc.messageId &&
    tc.callId &&
    tc.status !== TOOL_STATUS.STARTED &&
    tc.hasDetails !== false
  );

  const GAP = 8;

  // Скролл (список сообщений, колонка тулзов, автоскролл при стриминге) двигает
  // плашку из-под неподвижного курсора, а mouseleave при этом НЕ стреляет —
  // браузер пересчитывает hover только на движение мыши. Без этого тултип
  // «залипал» на экране до первого движения курсора. Слушаем в capture-фазе,
  // чтобы поймать скролл любого вложенного контейнера.
  useEffect(() => {
    if (!hover) return undefined;
    const hide = () => {
      setHover(false);
      setPos(null);
    };
    window.addEventListener('scroll', hide, true);
    return () => window.removeEventListener('scroll', hide, true);
  }, [hover]);

  // Позиционируем ПОСЛЕ рендера, по фактическим размерам тултипа — иначе при
  // неверной оценке высоты/ширины он «улетал». Якорим рядом с плашкой и зажимаем
  // в видимую область, чтобы тултип всегда оставался у своего блока.
  useLayoutEffect(() => {
    if (!hover || !itemRef.current || !tooltipRef.current) return;
    const item = itemRef.current.getBoundingClientRect();
    const tip = tooltipRef.current.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const w = tip.width;
    const h = tip.height;

    // Горизонтально: плашки под пузырём, справа обычно свободно — пробуем туда,
    // затем влево, затем — ближайший зажатый край.
    let left;
    if (item.right + GAP + w <= vw - GAP) {
      left = item.right + GAP;
    } else if (item.left - GAP - w >= GAP) {
      left = item.left - GAP - w;
    } else {
      left = item.right + GAP; // прижмём зажимом ниже
    }
    left = Math.min(Math.max(left, GAP), Math.max(GAP, vw - w - GAP));

    // Вертикально: по верху плашки, зажатый в экран.
    let top = Math.min(Math.max(item.top, GAP), Math.max(GAP, vh - h - GAP));

    // Микро-движения избегаем — обновляем только при заметном сдвиге.
    setPos((prev) =>
      prev && Math.abs(prev.left - left) < 0.5 && Math.abs(prev.top - top) < 0.5 ? prev : { top, left },
    );
  }, [hover, argsStr, gist, tc.status, tc.error]);

  const handleCopy = async (e) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(buildCopyText(tc, t));
      setCopied(true);
      clearTimeout(copyTimerRef.current);
      copyTimerRef.current = setTimeout(() => setCopied(false), COPY_DONE_MS);
    } catch {
      /* clipboard API may fail in insecure contexts */
    }
  };

  return (
    <div
      ref={itemRef}
      className={`tool-call-item tool-call-item--${(tc.status || 'STARTED').toLowerCase()}${
        canShowDetail ? ' tool-call-item--clickable' : ''
      }`}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => {
        setHover(false);
        setPos(null);
      }}
      onClick={() => {
        if (!canShowDetail) return;
        setShowDetail(true);
        setHover(false);
        setPos(null);
      }}
    >
      <span className="tool-call-status-icon">
        <StatusIcon status={tc.status} />
      </span>
      <div className="tool-call-body">
        <span className="tool-call-name">
          <span className="tool-call-icon" aria-hidden="true">
            {icon}
          </span>
          {label}
        </span>
        {argsStr && <span className="tool-call-args">{argsStr}</span>}
        {gist && <span className="tool-call-gist">{gist}</span>}
        {tc.status === TOOL_STATUS.ERROR && tc.error && <span className="tool-call-error">{tc.error}</span>}
      </div>
      <button className="tool-call-copy-btn" onClick={handleCopy} title={t('toolCall.copy')}>
        {copied ? <IconCopied /> : <IconCopySmall />}
      </button>
      {showDetail && canShowDetail && (
        <ToolCallDetailModal
          conversationId={conversationId}
          messageId={tc.messageId}
          callId={tc.callId}
          responseMessageId={tc.responseMessageId}
          tc={tc}
          onClose={() => setShowDetail(false)}
        />
      )}
      {hover &&
        ReactDOM.createPortal(
          <div
            ref={tooltipRef}
            className="tool-call-tooltip"
            style={{
              top: pos ? pos.top : 0,
              left: pos ? pos.left : 0,
              visibility: pos ? 'visible' : 'hidden',
            }}
          >
            <div className="tool-call-tooltip-name">{label}</div>
            {argsStr && <div className="tool-call-tooltip-args">{argsStr}</div>}
            <div className="tool-call-tooltip-status">
              {t('toolCall.status')}: {tc.status || '—'}
            </div>
            {tc.resultGist && <div className="tool-call-tooltip-gist">{tc.resultGist}</div>}
            {tc.status === TOOL_STATUS.ERROR && tc.error && <div className="tool-call-tooltip-error">{tc.error}</div>}
          </div>,
          document.body,
        )}
    </div>
  );
};

/** Группа одноимённых последовательных вызовов — сворачиваемая */
const ToolCallGroup = ({ name, items, conversationId }) => {
  const { t } = useTranslation('chat');
  const [open, setOpen] = useState(false);

  // Одиночный вызов — рендерим как обычную плашку, без шеврона/бейджа
  if (items.length === 1) {
    return <ToolCallItem tc={items[0]} conversationId={conversationId} />;
  }

  // Группа ≥2: заголовок показывает аргументы первого вызова (чтобы высота
  // не прыгала при переходе 1→2), плюс бейдж ×N и шеврон.
  const first = items[0];
  const label = t(toolLabelKey(name), { defaultValue: humanizeTool(name) });
  const icon = getToolIcon(name);
  const firstArgsStr = formatArgs(first.arguments);
  const groupStatus = items.some((t2) => t2.status === TOOL_STATUS.ERROR)
    ? TOOL_STATUS.ERROR
    : items.some((t2) => t2.status === TOOL_STATUS.STARTED)
    ? TOOL_STATUS.STARTED
    : TOOL_STATUS.OK;

  return (
    <div className="tool-call-group">
      <div
        className={`tool-call-item tool-call-item--${groupStatus.toLowerCase()} tool-call-item--group-header`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="tool-call-status-icon">
          <StatusIcon status={groupStatus} />
        </span>
        <div className="tool-call-body">
          <span className="tool-call-name">
            <span className="tool-call-icon" aria-hidden="true">
              {icon}
            </span>
            {label}
            <span className="tool-call-count">×{items.length}</span>
          </span>
          {firstArgsStr && <span className="tool-call-args">{firstArgsStr}</span>}
        </div>
        <span className={`tool-call-chevron ${open ? 'tool-call-chevron--open' : ''}`}>›</span>
      </div>
      {open && (
        <div className="tool-call-group-children">
          {items.map((tc, i) => (
            <ToolCallItem key={i} tc={tc} conversationId={conversationId} />
          ))}
        </div>
      )}
    </div>
  );
};

const ToolCallNotifications = ({ toolCalls, conversationId }) => {
  if (!toolCalls || toolCalls.length === 0) return null;

  // Группируем последовательные вызовы с одним именем
  const groups = [];
  for (const tc of toolCalls) {
    const last = groups[groups.length - 1];
    if (last && last.name === tc.name) {
      last.items.push(tc);
    } else {
      groups.push({ name: tc.name, items: [tc] });
    }
  }

  return (
    <div className="tool-call-notifications">
      <div className="tool-call-scroll">
        {groups.map((g, i) => (
          <ToolCallGroup key={`${g.name}-${i}`} name={g.name} items={g.items} conversationId={conversationId} />
        ))}
      </div>
    </div>
  );
};

export default ToolCallNotifications;
