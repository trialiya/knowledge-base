import { useSyncExternalStore } from 'react';

/* ── «Сейчас» — одно на всё приложение ────────────────────────────────────
   Относительная подпись («5 минут назад») считается от текущего момента, а
   компонент перерисовывается только от новых пропсов: без общего тика подпись
   под сообщением в молчащем чате застынет на минуте последнего события. Таймер
   один на модуль и идёт, только пока есть подписчики. */

const TICK_MS = 1000;

let now = 0;
// Пока подписчиков нет, `now` никто не двигает: первый же снимок после паузы
// берётся заново, иначе новый подписчик отрисовался бы от момента, когда
// таймер остановили. Флаг, а не Date.now() на каждый вызов: React требует,
// чтобы два чтения подряд без изменений давали одно и то же значение.
let stale = true;
let timer = null;
const listeners = new Set();

function tick() {
  now = Date.now();
  listeners.forEach((notify) => notify());
}

/* Фоновой вкладке браузер душит setInterval до раза в минуту и реже, поэтому
   по возвращении во вкладку или в окно момент пересчитывается сразу, не
   дожидаясь следующего тика. */
function onVisible() {
  if (document.visibilityState === 'visible') tick();
}

function subscribe(notify) {
  listeners.add(notify);
  if (listeners.size === 1) {
    // Снимок мог быть прочитан рендером, который так и не закоммитился, и тогда
    // `stale` уже снят без подписчика. React сверяет снимок после подписки и
    // перерисует подписчика, если момент здесь сдвинулся.
    if (Date.now() - now >= TICK_MS) now = Date.now();
    timer = setInterval(tick, TICK_MS);
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', onVisible);
  }
  return () => {
    listeners.delete(notify);
    if (listeners.size === 0) {
      clearInterval(timer);
      timer = null;
      stale = true;
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', onVisible);
    }
  };
}

function getSnapshot() {
  if (stale) {
    now = Date.now();
    stale = false;
  }
  return now;
}

const subscribeNever = () => () => {};
const getNothing = () => null;

/**
 * Текущий момент в миллисекундах, обновляемый раз в секунду и сразу при
 * возвращении во вкладку. Перерисовывается только вызвавший компонент, так что
 * звать хук стоит из листа (подписи времени), а не из всего сообщения.
 *
 * @param live false — не подписываться и вернуть null: подпись, которую тик уже
 *   не меняет (дата вместо «N минут назад»), не должна перерисовываться каждую
 *   секунду.
 */
export default function useNow(live = true) {
  return useSyncExternalStore(live ? subscribe : subscribeNever, live ? getSnapshot : getNothing);
}
