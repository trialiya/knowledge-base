import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconCheck, IconChevronDown, IconChevronRight, IconCopy, IconX } from '@/icons/index';
import useCopyFeedback from '@/components/common/ui/useCopyFeedback';
import './gitOutput.css';

/**
 * Вывод одной git-команды — тёмная карточка с самим текстом, как его написал git.
 *
 * Один компонент на все места, где вывод показывают: ленту чата, где команда
 * оставила ряд, и окна коммита и push, откуда её запустили — в панели чата и в
 * панели «Файлы». Место разное, читают одно и то же, и разойтись в том, как
 * выглядит отказ, им нельзя.
 *
 * Свёрнута по умолчанию, когда команда прошла: у половины git-команд успех
 * молчалив, и разворачивать «Fast-forward» на всю ленту не за чем. Отказ
 * развёрнут — он единственное, ради чего сюда возвращаются.
 */
const GitOutputCard = ({ event, compact = false }) => {
  const { t } = useTranslation('common');
  const [copied, copy] = useCopyFeedback();
  const [open, setOpen] = useState(!event.ok);

  const output = event.output?.trim() ?? '';
  const expandable = output.length > 0;

  return (
    <div className={`git-output${event.ok ? '' : ' git-output--failed'}${compact ? ' git-output--compact' : ''}`}>
      <div className="git-output__head">
        <span className="git-output__icon" aria-hidden="true">
          {event.ok ? <IconCheck size={12} /> : <IconX size={12} />}
        </span>
        <span className="git-output__command">git {event.command}</span>
        {event.branch && <span className="git-output__branch">{event.branch}</span>}
        {output && (
          <button
            type="button"
            className="icon-btn"
            onClick={() => copy(output)}
            title={t('gitOutput.copy')}
            aria-label={t('gitOutput.copy')}
          >
            {copied ? <IconCheck size={12} /> : <IconCopy size={12} />}
          </button>
        )}
        {expandable && (
          <button
            type="button"
            className="icon-btn"
            onClick={() => setOpen((v) => !v)}
            aria-expanded={open}
            title={open ? t('gitOutput.collapse') : t('gitOutput.expand')}
            aria-label={open ? t('gitOutput.collapse') : t('gitOutput.expand')}
          >
            {open ? <IconChevronDown size={12} /> : <IconChevronRight size={12} />}
          </button>
        )}
      </div>
      {open && <pre className="git-output__body">{output}</pre>}
      {/* Молчаливой команде сворачивать нечего, и строку о том, что git ничего
          не сказал, она показывает всегда: пустой чёрный прямоугольник читался
          бы как потерянный вывод, а одна шапка — как обрезанная карточка. */}
      {!expandable && <p className="git-output__silent">{t('gitOutput.silent')}</p>}
    </div>
  );
};

export default GitOutputCard;
