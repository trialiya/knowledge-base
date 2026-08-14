/**
 * UUID v4. `crypto.randomUUID` есть во всех целевых браузерах, но только в
 * защищённом контексте (https / localhost) — по HTTP в локальной сети его нет,
 * поэтому фолбэк остаётся рабочим путём, а не данью старым браузерам.
 */
export function generateUUID() {
  if (crypto?.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
