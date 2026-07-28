import React, { useCallback, useRef, useState } from 'react';

/**
 * Строка-операция в «Настройках»/«Администрировании»: иконка, название с
 * пояснением, кнопка запуска и результат последнего запуска под текстом.
 *
 * Одна и та же вёрстка (`.set-op` + бейдж результата) и один и тот же цикл
 * состояний были переписаны в каждой группе с кнопкой — переиндексация и
 * экспорт отличались только текстами и иконкой. Следующей операции (импорт,
 * очистка — под них и заводился `.set-op`) достаточно позвать этот компонент.
 *
 * props:
 *   icon        — узел иконки слева
 *   title/desc  — переведённые строки; desc уже с подставленными параметрами
 *   labels      — { run, running, doneBadge, done, errorBadge, error }
 *   state       — из useOperation: idle | running | done | error
 *   onRun       — из useOperation
 *   runVariant  — модификатор кнопки: primary (по умолчанию) | ghost
 *   runDisabled — заблокировать запуск и вне running (импорту нечего применять)
 *   children    — доп. контрол операции (чекбокс «с метаданными»), между
 *                 пояснением и результатом
 *   progress    — что показывать во время работы вместо статичного «идёт…»:
 *                 операции на SSE знают, какой узел обрабатывают прямо сейчас
 *   done/error  — переопределяют текст результата, когда его надо собрать из
 *                 сводки (сколько создано/обновлено), а не взять из labels
 */
const OperationRow = ({
  icon,
  title,
  desc,
  labels,
  state,
  onRun,
  runVariant = 'primary',
  runDisabled = false,
  children,
  progress,
  done,
  error,
}) => (
  <div className="set-op">
    {icon && <span className="set-op__icon">{icon}</span>}
    <div className="set-op__text">
      <div className="set-op__title">{title}</div>
      <div className="set-op__desc">{desc}</div>
      {children}
      {state === 'running' && progress && <div className="set-op__progress">{progress}</div>}
      {state === 'done' && <OperationStatus kind="ok" badge={labels.doneBadge} text={done ?? labels.done} />}
      {state === 'error' && <OperationStatus kind="error" badge={labels.errorBadge} text={error ?? labels.error} />}
    </div>
    <button className={`btn btn--${runVariant}`} onClick={onRun} disabled={state === 'running' || runDisabled}>
      {state === 'running' ? labels.running : labels.run}
    </button>
  </div>
);

const OperationStatus = ({ kind, badge, text }) => (
  <div className="set-op__status">
    <span className={`set-op__badge set-op__badge--${kind}`}>{badge}</span>
    <span>{text}</span>
  </div>
);

/**
 * Цикл состояний операции: idle → running → done | error.
 *
 * Повторный запуск отсекается по ref, а не по state: кнопка на время работы и
 * так заблокирована, а ref не тянет `state` в зависимости колбэка.
 *
 * `run` может сообщить о неуспехе двумя способами — бросить (сеть, HTTP) или
 * вернуть `false` (сервер ответил 200 с `ok: false`, как экспорт при незаданном
 * DOCUMENTS_EXPORT_PATH). Любой другой результат считается успехом.
 *
 * @param run async-функция запуска
 * @returns [state, start]
 */
export const useOperation = (run) => {
  const [state, setState] = useState('idle');
  const runningRef = useRef(false);

  const start = useCallback(async () => {
    if (runningRef.current) return;
    runningRef.current = true;
    setState('running');
    try {
      setState((await run()) === false ? 'error' : 'done');
    } catch {
      setState('error');
    } finally {
      runningRef.current = false;
    }
  }, [run]);

  return [state, start];
};

export default OperationRow;
