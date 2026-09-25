import { detectScriptRun } from './scriptRun';
import { parseResult } from './registry';

// Данные — форма настоящего ответа `runScript` (см. DTO бэкенда: ScriptResult,
// ScriptStats, ScriptError, GitEditResult).

const detect = (resultText) => {
  const input = parseResult(resultText);
  return input ? detectScriptRun(input) : null;
};

const PATCH = '@@ -1,2 +1,3 @@\n context\n-old\n+new\n+added\n';

const result = (over = {}) => ({
  value: null,
  log: ['начало обхода', 'найдено 3 совпадения'],
  stats: { filesRead: 42, bytesRead: 1258291, calls: 87, filesEdited: 0, elapsedMs: 340 },
  error: null,
  filesRead: ['frontend/src/App.jsx', 'frontend/src/main.jsx'],
  edits: [],
  ...over,
});

const edit = (path) => ({ operation: 'edit', path, additions: 14, deletions: 2, lineCount: 210, diff: PATCH });

describe('detectScriptRun — что попадает в «Обзор»', () => {
  it('прогон только на чтение: плитки в известном порядке, правок нет', () => {
    const data = detect(JSON.stringify(result()));
    expect(data.stats.map((s) => s.key)).toEqual(['filesRead', 'bytesRead', 'calls', 'filesEdited', 'elapsedMs']);
    expect(data.log).toHaveLength(2);
    expect(data.filesRead).toHaveLength(2);
    expect(data.edits).toBeNull();
    expect(data.error).toBeNull();
  });

  it('проект берётся из ответа: прогон мог читать соседний репозиторий', () => {
    expect(detect(JSON.stringify(result({ project: 'billing' }))).project).toBe('billing');
    // Старый ответ без проекта разбор не ломает.
    expect(detect(JSON.stringify(result())).project).toBeNull();
  });

  it('id сохранённого значения берётся из ответа; нет его — значит, не сохраняли', () => {
    expect(detect(JSON.stringify(result({ resultId: 'r3' }))).resultId).toBe('r3');
    expect(detect(JSON.stringify(result())).resultId).toBeNull();
  });

  it('правки разбирает вид diff’а, а не второй такой же разбор здесь', () => {
    const data = detect(JSON.stringify(result({ edits: [edit('a.jsx'), edit('b.css')], stats: { filesEdited: 2 } })));
    expect(data.edits).toHaveLength(1);
    expect(data.edits[0].files.map((f) => f.path)).toEqual(['a.jsx', 'b.css']);
    expect(data.edits[0].files[0].patch).toBe(PATCH);
  });

  it('упавший прогон — тоже результат: статистика и лог показывают, докуда дошёл', () => {
    const data = detect(
      JSON.stringify(result({ error: { kind: 'BUDGET', message: 'превышен лимит kb.script.limits.files', line: 7 } })),
    );
    expect(data.error).toEqual({ kind: 'BUDGET', message: 'превышен лимит kb.script.limits.files', line: 7 });
    expect(data.stats).not.toHaveLength(0);
  });

  it('возврат скрипта: строка как есть, объект — развёрнутым JSON', () => {
    expect(detect(JSON.stringify(result({ value: 'готово' }))).value).toBe('готово');
    expect(detect(JSON.stringify(result({ value: { found: 3 } }))).value).toBe('{\n  "found": 3\n}');
    expect(detect(JSON.stringify(result())).value).toBeNull();
  });

  it('счётчик, которого в порядке нет, идёт следом, а не пропадает', () => {
    // Набор ScriptStats закреплён в порядке вывода, но вид отбирается по форме:
    // у стороннего инструмента счётчики могут быть свои.
    const data = detect(JSON.stringify(result({ stats: { calls: 3, retries: 1 } })));
    expect(data.stats.map((s) => s.key)).toEqual(['calls', 'retries']);
  });
});

describe('detectScriptRun — источник скрипта', () => {
  const source = (over = {}) => ({
    kind: 'PROJECT',
    name: 'locale-diff',
    path: 'frontend/scripts/locale-diff.js',
    sha: '0f1c2d3e4a5b',
    args: { area: 'components' },
    ...over,
  });

  it('прогон по имени называет скрипт, файл и аргументы', () => {
    const data = detect(JSON.stringify(result({ source: source() })));
    expect(data.source).toEqual({
      kind: 'PROJECT',
      name: 'locale-diff',
      path: 'frontend/scripts/locale-diff.js',
      sha: '0f1c2d3e4a5b',
      args: '{"area":"components"}',
    });
  });

  it('скрипт, написанный моделью, источника не называет — и это не ломает разбор', () => {
    expect(detect(JSON.stringify(result())).source).toBeNull();
    expect(detect(JSON.stringify(result({ source: null }))).source).toBeNull();
  });

  it('половина источника — не источник', () => {
    expect(detect(JSON.stringify(result({ source: source({ path: undefined }) }))).source).toBeNull();
    expect(detect(JSON.stringify(result({ source: 'locale-diff' }))).source).toBeNull();
  });

  it('пустые аргументы не показываются', () => {
    expect(detect(JSON.stringify(result({ source: source({ args: {} }) }))).source.args).toBeNull();
  });
});

describe('detectScriptRun — что остаётся другим видам', () => {
  it('нечисловое значение среди счётчиков — это не статистика', () => {
    expect(detect(JSON.stringify(result({ stats: { calls: 3, mode: 'dry-run' } })))).toBeNull();
  });

  it('ошибка без вида — форма не та: придумывать вид за бэкенд нечем', () => {
    expect(detect(JSON.stringify(result({ error: { message: 'что-то пошло не так' } })))).toBeNull();
    expect(detect(JSON.stringify(result({ error: 'сломалось' })))).toBeNull();
  });

  it('правки, которые не разбираются как diff, уводят в JSON весь ответ', () => {
    // Показать статистику и умолчать про правки хуже, чем показать всё сырым.
    expect(detect(JSON.stringify(result({ edits: [{ path: 'a.jsx' }] })))).toBeNull();
  });

  it('без лога, путей или статистики форма не та', () => {
    expect(detect(JSON.stringify(result({ log: undefined })))).toBeNull();
    expect(detect(JSON.stringify(result({ stats: {} })))).toBeNull();
    expect(detect(JSON.stringify(result({ filesRead: [{ path: 'a.jsx' }] })))).toBeNull();
  });

  it('массив, скаляр и не JSON', () => {
    expect(detect(JSON.stringify([result()]))).toBeNull();
    expect(detect('"Done"')).toBeNull();
    expect(detect('не json вовсе')).toBeNull();
  });
});
