import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ToolCallDetailModal from './ToolCallDetailModal';
import { getToolIcon, toolLabelKey, humanizeTool } from '../../common/ui/toolNames';
import { IconCopySmall, IconCopied, IconStatusStarted, IconStatusOk, IconStatusError } from '../../../icons';
import { GIST_PREVIEW_LEN } from '../../../constants/ui';
import useCopyFeedback from '../../common/ui/useCopyFeedback';
import { TOOL_STATUS } from '../../../constants/toolStatus';
import '../styles/tool-calls.css';

// Inline-блок плашек вызовов инструментов под пузырём ответа ассистента.
// Вынесено из Message.jsx: сам пузырь сообщения не должен знать о деталях
// tool-call UI (группировка, модалка деталей).

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

/** Одиночная плашка вызова — кнопка копирования + кнопка деталей */
const ToolCallItem = ({ tc, conversationId }) => {
  const { t } = useTranslation('chat');
  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const argsStr = formatArgs(tc.arguments);
  const gist = gistPreview(tc.resultGist);
  const [copied, copy] = useCopyFeedback();
  const [showDetail, setShowDetail] = useState(false);
  // callId приходит вместе с плашкой (SSE TOOL_CALL/TOOL_CALLS или GET /messages) — без него
  // (старые записи до этого поля) модалке деталей нечего запросить.
  const canShowDetail = !!(conversationId && tc.callId && tc.status !== TOOL_STATUS.STARTED && tc.hasDetails !== false);

  const handleCopy = (e) => {
    // Плашка сама по себе кликабельна (открывает детали) — копирование не должно
    // всплывать до неё.
    e.stopPropagation();
    copy(buildCopyText(tc, t));
  };

  return (
    <div
      className={`tool-call-item tool-call-item--${(tc.status || 'STARTED').toLowerCase()}${
        canShowDetail ? ' tool-call-item--clickable' : ''
      }`}
      onClick={() => {
        if (!canShowDetail) return;
        setShowDetail(true);
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
          callId={tc.callId}
          tc={tc}
          onClose={() => setShowDetail(false)}
        />
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
