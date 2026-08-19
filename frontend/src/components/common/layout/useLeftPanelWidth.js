import { useCallback, useRef, useState, useSyncExternalStore } from 'react';
import { STORAGE_KEY_LEFT_WIDTH } from '../../../constants/storage';

const DEFAULT_LEFT_WIDTH = 280;
export const MIN_LEFT_WIDTH = 200;
export const MAX_LEFT_WIDTH = 520;
/** Шаг изменения ширины стрелками, когда фокус на разделителе. */
const KEY_STEP = 16;
const CSS_VAR = '--ws-left-width';
/** Не отдавать центру раздела больше этой доли ширины окна, см. viewportMax(). */
const MAX_WIDTH_VIEWPORT_SHARE = 0.6;

/**
 * MAX_LEFT_WIDTH — паспортный максимум для широких экранов; на узких он сам по
 * себе не спасает: на ~900px (ещё до оверлейного брейкпоинта в 820px, см.
 * workspaceLayout.css) панель в 520px — это 58% рабочей области. Верхнюю
 * границу берём как минимум из двух: паспортной и доли текущего окна.
 */
function viewportMax() {
  if (typeof window === 'undefined') return MAX_LEFT_WIDTH;
  return Math.min(MAX_LEFT_WIDTH, Math.round(window.innerWidth * MAX_WIDTH_VIEWPORT_SHARE));
}

const clamp = (px) => Math.min(viewportMax(), Math.max(MIN_LEFT_WIDTH, Math.round(px)));

function readStored() {
  try {
    const saved = Number(localStorage.getItem(STORAGE_KEY_LEFT_WIDTH));
    return saved ? clamp(saved) : DEFAULT_LEFT_WIDTH;
  } catch {
    return DEFAULT_LEFT_WIDTH;
  }
}

/* ── Ширина как один общий источник правды на всё приложение ──────────────────
   Не состояние компонента: WorkspaceLayout существует в нескольких экземплярах
   одновременно (чат и база знаний смонтированы всегда, просто скрыты). Держи
   каждый свою ширину — потянул в чате, вернулся в базу знаний, а там прежняя, и
   граница панели снова прыгает между разделами. Значение живёт в модуле, его
   раздают через useSyncExternalStore. */

let current = readStored();
const listeners = new Set();

/** Ширину рисует CSS-переменная на :root — её видят все панели сразу. */
function applyVar(px) {
  document.documentElement.style.setProperty(CSS_VAR, `${px}px`);
}

function commit(px) {
  const next = clamp(px);
  // Переменную пишем всегда: перетаскивание могло закончиться на прежней
  // ширине, но промежуточные значения в узле уже побывали.
  applyVar(next);
  if (next === current) return;
  current = next;
  try {
    localStorage.setItem(STORAGE_KEY_LEFT_WIDTH, String(next));
  } catch {
    /* ignore quota / private-mode errors */
  }
  listeners.forEach((notify) => notify());
}

function subscribe(notify) {
  listeners.add(notify);
  return () => listeners.delete(notify);
}

applyVar(current); // сохранённая ширина должна встать до первого кадра

/**
 * ТОЛЬКО ДЛЯ ТЕСТОВ. `current` живёт на уровне модуля (см. ниже) — общий на все
 * экземпляры хука, в точности как в приложении. Но это же делает тесты в одном
 * файле order-dependent: тест, оставивший ширину не-дефолтной (перетащил и не
 * вызвал reset/Home), портит следующий, который ожидает чистое состояние.
 * Вызывать из beforeEach/afterEach конкретного test-файла, не из кода приложения.
 */
export function resetLeftPanelWidthForTests() {
  current = DEFAULT_LEFT_WIDTH;
  applyVar(current);
  try {
    localStorage.removeItem(STORAGE_KEY_LEFT_WIDTH);
  } catch {
    /* ignore quota / private-mode errors */
  }
}

/**
 * Ширина левой панели, которую можно тянуть мышью, — ОДНА НА ВСЕ РАЗДЕЛЫ.
 *
 * Именно одна, а не своя у каждого раздела: разъезжающиеся ширины — это то, от
 * чего мы уходили (граница панели прыгала на 22px при переходе в «Файлы»).
 * Пользователь настраивает панель под себя один раз, и переключение раздела
 * по-прежнему ничего не двигает.
 *
 * Хранится в localStorage, а не в URL: в адресе живёт то, чем осмысленно
 * поделиться ссылкой (какая панель раскрыта, какая вкладка), а ширина — личная
 * настройка рабочего места.
 *
 * ВО ВРЕМЯ ПЕРЕТАСКИВАНИЯ НИЧЕГО НЕ ПЕРЕРИСОВЫВАЕТСЯ: каждый pointermove лишь
 * переписывает CSS-переменную, а в стор ширина попадает один раз — когда кнопку
 * отпустили. Иначе десятки перерисовок в секунду доставались бы всему разделу
 * целиком (WorkspaceLayout — корень чата, базы знаний и файлов).
 *
 * Указатель захватываем (setPointerCapture): курсор уезжает и за пределы
 * разделителя, и за окно — события всё равно приходят нам, глобальные слушатели
 * на document не нужны.
 */
export default function useLeftPanelWidth() {
  const width = useSyncExternalStore(subscribe, () => current);
  const [dragging, setDragging] = useState(false);
  const originRef = useRef(null); // { startX, startWidth }
  const liveRef = useRef(null); // ширина «в полёте», пока тянем

  const onPointerDown = useCallback((e) => {
    if (e.button !== 0) return;
    e.preventDefault(); // не выделять текст панели во время перетаскивания
    originRef.current = { startX: e.clientX, startWidth: current };
    liveRef.current = current;
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragging(true);
  }, []);

  const onPointerMove = useCallback((e) => {
    const origin = originRef.current;
    if (!origin) return;
    const next = clamp(origin.startWidth + (e.clientX - origin.startX));
    if (next === liveRef.current) return;
    liveRef.current = next;
    applyVar(next);
  }, []);

  const endDrag = useCallback((e) => {
    if (!originRef.current) return;
    originRef.current = null;
    if (e.currentTarget.hasPointerCapture?.(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    setDragging(false);
    if (liveRef.current != null) commit(liveRef.current);
    liveRef.current = null;
  }, []);

  /** Стрелки двигают границу с клавиатуры, Home/двойной клик — сброс к дефолту. */
  const onKeyDown = useCallback((e) => {
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      commit(current - KEY_STEP);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      commit(current + KEY_STEP);
    } else if (e.key === 'Home') {
      e.preventDefault();
      commit(DEFAULT_LEFT_WIDTH);
    }
  }, []);

  const reset = useCallback(() => commit(DEFAULT_LEFT_WIDTH), []);

  return {
    width,
    dragging,
    reset,
    handleProps: { onPointerDown, onPointerMove, onPointerUp: endDrag, onPointerCancel: endDrag, onKeyDown },
  };
}
