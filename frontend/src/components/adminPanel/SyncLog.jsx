import React, { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { logTotal } from './syncLog';

// ─── Что импорт сделал, узел за узлом ────────────────────────────────────────
// Список различий отвечает на «что изменится», сводка — на «сколько изменилось».
// Между ними была дыра: после прогона не видно, какие именно узлы записались и
// почему пропущены остальные. Журнал заполняется по ходу импорта, поэтому он же
// служит и подробным прогрессом — строки идут в том порядке, в котором бэк
// трогал узлы, включая второй проход по ссылкам.

const SyncLog = ({ log, running }) => {
  const { t } = useTranslation('settings');
  const listRef = useRef(null);

  // Во время прогона интересна последняя строка, но не ценой прокрутки под
  // руками у того, кто отлистал наверх читать отказ: дотягиваем только если
  // человек и так стоит у нижнего края.
  useEffect(() => {
    const list = listRef.current;
    if (!running || !list) return;
    if (list.scrollHeight - list.scrollTop - list.clientHeight < 40) {
      list.scrollTop = list.scrollHeight;
    }
  }, [log.lines.length, running]);

  if (!log.lines.length) return null;

  return (
    <div className="sync-log">
      <div className="sync-log__head">{t('admin.bulk.log.title', { count: logTotal(log) })}</div>

      <ul className="sync-log__list" role="log" ref={listRef}>
        {log.lines.map((line, i) => (
          <li key={`${line.path}#${i}`} className={`sync-log__row sync-log__row--${line.action}`}>
            <span className={`sync-log__badge sync-log__badge--${line.action}`}>
              {t(`admin.bulk.log.action.${line.action}`)}
            </span>
            <span className="sync-log__path">{line.path}</span>
            {line.message && <span className="sync-log__message">{line.message}</span>}
          </li>
        ))}
      </ul>

      {log.dropped > 0 && <div className="sync-log__more">{t('admin.bulk.log.dropped', { count: log.dropped })}</div>}
    </div>
  );
};

export default SyncLog;
