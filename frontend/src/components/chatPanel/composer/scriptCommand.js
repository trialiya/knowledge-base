// ─── Хвост команды `/script` ─────────────────────────────────────────────────
// `/script locale-diff area=components limit=50` → имя и аргументы. Разбор живёт
// на фронте, а не на сервере: `key=value` — правило про то, КАК человек набирает
// команду в поле, и меняться оно будет вместе с полем. Серверу уезжает уже
// разобранное, тем же телом, каким его пришлёт любой другой клиент.

/**
 * Значение одного аргумента.
 *
 * Строки, числа и булевы уезжают как набраны: числа и булевы приводит бэкенд
 * (ScriptArgs — слабая модель кавычит всё, и поблажка написана там же). Массив
 * и объект он строкой не принимает вовсе, поэтому набранный JSON разбирается
 * здесь — иначе такой аргумент нельзя было бы передать ничем. Неразобравшийся
 * JSON уходит строкой: отказ с объяснением напишет сервер, знающий объявленный
 * тип, а не разбор, который о нём не знает.
 */
const valueOf = (raw) => {
  const text = raw.startsWith('"') && raw.endsWith('"') && raw.length > 1 ? raw.slice(1, -1) : raw;
  if (!/^[[{]/.test(text)) return text;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
};

/**
 * Разбивает хвост на токены, не разрывая значения в кавычках и в скобках:
 * `files=["a.js", "b.js"]` и `note="про запас"` — по одному токену каждый.
 */
const tokenize = (tail) => {
  const tokens = [];
  let current = '';
  let depth = 0;
  let quoted = false;
  for (const ch of tail) {
    if (ch === '"') quoted = !quoted;
    if (!quoted && (ch === '[' || ch === '{')) depth += 1;
    if (!quoted && (ch === ']' || ch === '}')) depth -= 1;
    if (/\s/.test(ch) && !quoted && depth <= 0) {
      if (current) tokens.push(current);
      current = '';
      continue;
    }
    current += ch;
  }
  if (current) tokens.push(current);
  return tokens;
};

/**
 * Хвост команды → `{ name, args }`, либо null, если имени в нём нет.
 *
 * Токен без `=` после имени — не аргумент, а опечатка, и молча терять его нельзя:
 * он уезжает в `args` под своим же текстом с пустым значением, и отказ сервера
 * назовёт его по имени («unknown parameter»). Так опечатка видна там же, где
 * видны остальные ошибки вызова, а не исчезает по дороге.
 */
export function parseScriptCommand(tail) {
  const tokens = tokenize((tail || '').trim());
  if (tokens.length === 0) return null;
  const [name, ...rest] = tokens;
  const args = {};
  for (const token of rest) {
    const eq = token.indexOf('=');
    if (eq <= 0) {
      args[token] = '';
      continue;
    }
    args[token.slice(0, eq)] = valueOf(token.slice(eq + 1));
  }
  return { name, args };
}
