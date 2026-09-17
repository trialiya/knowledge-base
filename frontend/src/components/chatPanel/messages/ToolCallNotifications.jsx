import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ToolCallDetailModal from './ToolCallDetailModal';
import { getToolIcon, toolLabelKey, humanizeTool, statusLabelKey } from '@/components/common/ui/toolNames';
import {
  IconCopySmall,
  IconCopied,
  IconStatusStarted,
  IconStatusOk,
  IconStatusError,
  IconStatusUnknown,
} from '@/icons/index';
import { GIST_PREVIEW_LEN } from '@/constants/ui';
import useCopyFeedback from '@/components/common/ui/useCopyFeedback';
import { TOOL_STATUS } from '@/constants/toolStatus';
import '@/components/common/ui/buttons.css';
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
    case TOOL_STATUS.UNKNOWN:
      return <IconStatusUnknown />;
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
  parts.push(`${t('toolCall.status')}: ${tc.status ? t(statusLabelKey(tc.status)) : '—'}`);
  if (tc.status === TOOL_STATUS.ERROR && tc.error) parts.push(`${t('toolCall.error')}: ${tc.error}`);
  return parts.join('\n');
};

/** Одиночная плашка вызова — кнопка копирования + кнопка деталей */
const ToolCallItem = ({ tc, conversationId, onOpenDetail }) => {
  const { t } = useTranslation('chat');
  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const argsStr = formatArgs(tc.arguments);
  const gist = gistPreview(tc.resultGist);
  const [copied, copy] = useCopyFeedback();
  // callId приходит вместе с плашкой (SSE TOOL_CALL/TOOL_CALLS или GET /messages) — без него
  // (старые записи до этого поля) модалке деталей нечего запросить. Статус не ограничивает:
  // аргументы вызова сохранены до его запуска, и посмотреть, с чем модель позвала инструмент,
  // можно, не дожидаясь ответа, — результат модалка дотянет сама, когда он появится.
  const canShowDetail = !!(conversationId && tc.callId && tc.hasDetails !== false);

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
        onOpenDetail(tc.callId);
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
      <button
        className={`icon-btn icon-btn--xs icon-btn--quiet tool-call-copy-btn${copied ? ' icon-btn--done' : ''}`}
        onClick={handleCopy}
        title={t('toolCall.copy')}
      >
        {copied ? <IconCopied /> : <IconCopySmall />}
      </button>
    </div>
  );
};

/** Группа одноимённых последовательных вызовов — сворачиваемая */
const ToolCallGroup = ({ name, items, conversationId, onOpenDetail }) => {
  const { t } = useTranslation('chat');
  // Развёрнута по умолчанию: заголовок «×N» говорит только, сколько раз инструмент звали, а
  // читают плашки ради того, С ЧЕМ его звали — и свёрнутая группа прячет это до клика. Сама
  // группа при этом собирается на ходу: второй вызов того же инструмента сворачивает уже
  // показанную плашку в заголовок, и по умолчанию свёрнутая группа убирала бы с экрана то,
  // что человек в этот момент читает. Шеврон остаётся — свернуть длинную серию можно руками,
  // и это решение переживает дописывание в неё новых вызовов.
  const [open, setOpen] = useState(true);

  // Одиночный вызов — рендерим как обычную плашку, без шеврона/бейджа
  if (items.length === 1) {
    return <ToolCallItem tc={items[0]} conversationId={conversationId} onOpenDetail={onOpenDetail} />;
  }

  // Группа ≥2: заголовок — имя, бейдж ×N и шеврон. Аргументы в нём только у
  // свёрнутой: там он стоит вместо спрятанного списка (и высота не прыгает при
  // переходе 1→2). У развёрнутой это была бы вторая копия первой же строки.
  const first = items[0];
  const label = t(toolLabelKey(name), { defaultValue: humanizeTool(name) });
  const icon = getToolIcon(name);
  const firstArgsStr = formatArgs(first.arguments);
  // Худшее из того, что внутри: провал группу красит целиком, неизвестный исход — тоже вперёд
  // успеха, иначе одна зелёная плашка отвечала бы за вызов, о котором ничего не известно.
  const groupStatus = items.some((t2) => t2.status === TOOL_STATUS.ERROR)
    ? TOOL_STATUS.ERROR
    : items.some((t2) => t2.status === TOOL_STATUS.STARTED)
    ? TOOL_STATUS.STARTED
    : items.some((t2) => t2.status === TOOL_STATUS.UNKNOWN)
    ? TOOL_STATUS.UNKNOWN
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
          {!open && firstArgsStr && <span className="tool-call-args">{firstArgsStr}</span>}
        </div>
        <span className={`tool-call-chevron ${open ? 'tool-call-chevron--open' : ''}`}>›</span>
      </div>
      {open && (
        <div className="tool-call-group-children">
          {items.map((tc, i) => (
            <ToolCallItem key={tc.callId ?? i} tc={tc} conversationId={conversationId} onOpenDetail={onOpenDetail} />
          ))}
        </div>
      )}
    </div>
  );
};

const ToolCallNotifications = ({ toolCalls, conversationId }) => {
  // Открытая модалка принадлежит ленте плашек, а не плашке: пока её читают, модель успевает
  // позвать тот же инструмент ещё раз, одиночная плашка становится группой (другой узел
  // дерева), и состояние, лежавшее в ней, исчезло бы вместе с ней — прямо из-под читающего.
  // Держим здесь callId: он переживает и перегруппировку, и слияние результата в плашку.
  const [detailCallId, setDetailCallId] = useState(null);
  // Последнее, что о вызове знала эта лента. Нужно ровно на случай, когда он из неё уходит:
  // ряды тоже перестраиваются, и склейка соседних рядов из одних вызовов (toolRuns.js)
  // распадается, как только второму прогону становится что показать помимо вызовов —
  // остановленному дописывают пометку. Вызовы уезжают в свой ряд, а модалка открыта здесь,
  // и без запомненного она закрылась бы прямо из-под читающего.
  const [held, setHeld] = useState(null);

  const calls = toolCalls || [];
  // Вызов, на котором открыты детали. Пока он в списке, берём его оттуда, а не из памяти:
  // mergeToolCall кладёт на его место новый, и по забытому модалка не увидела бы ни статуса,
  // ни resultMeta.
  const found = detailCallId ? calls.find((tc) => tc.callId === detailCallId) : null;
  if (found && found !== held) {
    setHeld(found);
  }
  const detail = detailCallId ? found ?? held : null;

  if (calls.length === 0) return null;

  // Группируем последовательные вызовы с одним именем
  const groups = [];
  for (const tc of calls) {
    const last = groups[groups.length - 1];
    if (last && last.name === tc.name) {
      last.items.push(tc);
    } else {
      groups.push({ name: tc.name, items: [tc] });
    }
  }

  return (
    <div className="tool-call-notifications">
      <div className="tool-call-list">
        {groups.map((g, i) => (
          <ToolCallGroup
            key={`${g.name}-${i}`}
            name={g.name}
            items={g.items}
            conversationId={conversationId}
            onOpenDetail={setDetailCallId}
          />
        ))}
      </div>
      {detail && (
        <ToolCallDetailModal
          conversationId={conversationId}
          callId={detail.callId}
          tc={detail}
          onClose={() => setDetailCallId(null)}
        />
      )}
    </div>
  );
};

export default ToolCallNotifications;
