import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconCheck, IconChevronDown, IconChevronRight, IconCopy, IconX } from '@/icons/index';
import useCopyFeedback from '@/components/common/ui/useCopyFeedback';
import './gitOutputCard.css';

/**
 * Вывод одной git-команды — тёмная карточка с самим текстом, как его написал git.
 *
 * Один компонент на оба места, где вывод показывают: ленту чата, где команда
 * оставила ряд, и модалку, где её запустили. Место разное, читают одно и то же,
 * и разойтись в том, как выглядит отказ, им нельзя.
 *
 * Свёрнута по умолчанию, когда команда прошла: у половины git-команд успех
 * молчалив, и разворачивать «Fast-forward» на всю ленту не за чем. Отказ
 * развёрнут — он единственное, ради чего сюда возвращаются.
 */
const GitOutputCard = ({ event, compact = false }) => {
  const { t } = useTranslation('chat');
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
            title={t('repo.copyOutput')}
            aria-label={t('repo.copyOutput')}
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
            title={open ? t('repo.collapseOutput') : t('repo.expandOutput')}
            aria-label={open ? t('repo.collapseOutput') : t('repo.expandOutput')}
          >
            {open ? <IconChevronDown size={12} /> : <IconChevronRight size={12} />}
          </button>
        )}
      </div>
      {/* Молчаливая удачная команда — не пустая карточка, а строка о том, что
          git ничего не сказал: пустой чёрный прямоугольник читался бы как
          потерянный вывод. */}
      {open && expandable && <pre className="git-output__body">{output}</pre>}
      {open && !expandable && <p className="git-output__silent">{t('repo.noOutput')}</p>}
    </div>
  );
};

export default GitOutputCard;
