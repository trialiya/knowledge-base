import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { detectScriptRun } from './scriptRun';
import { parseResult } from './registry';
import ScriptRunView from './ScriptRunView';

// Здесь проверяется не разбор (он в scriptRun.test.js), а то, что показ лога
// переживает сворачивание секции над ним.

const LOG_CAP = 200;

const run = (logLines) =>
  detectScriptRun(
    parseResult(
      JSON.stringify({
        stats: { filesRead: 2, calls: 5 },
        log: Array.from({ length: logLines }, (_, i) => `строка ${i + 1}`),
        error: null,
        value: null,
        filesRead: [],
        edits: [],
      }),
    ),
  );

const logLines = () => document.querySelector('.tool-script__log').textContent.split('\n').length;

describe('ScriptRunView', () => {
  it('разворот лога переживает сворачивание секции', async () => {
    render(<ScriptRunView data={run(320)} />);

    // Длинный лог свёрнут: раскрываем секцию, потом сам лог.
    const head = document.querySelector('.tool-script__panel-head');
    await userEvent.click(head);
    expect(logLines()).toBe(LOG_CAP);

    await userEvent.click(screen.getByRole('button', { name: /showAll|показать|show/i }));
    expect(logLines()).toBe(320);

    // Свернули секцию и открыли обратно — «показать ещё» просили один раз.
    await userEvent.click(head);
    await userEvent.click(head);
    expect(logLines()).toBe(320);
  });

  it('шапка называет запущенный скрипт: тела скрипта в аргументах вызова нет', () => {
    const data = detectScriptRun(
      parseResult(
        JSON.stringify({
          stats: { filesRead: 2, calls: 5 },
          log: [],
          error: null,
          value: null,
          filesRead: [],
          edits: [],
          source: {
            kind: 'PROJECT',
            name: 'locale-diff',
            path: 'frontend/scripts/locale-diff.js',
            sha: '0f1c2d3e4a5b',
            args: { area: 'components' },
          },
        }),
      ),
    );

    render(<ScriptRunView data={data} />);

    expect(document.querySelector('.tool-script__source-name').textContent).toBe('locale-diff');
    expect(document.querySelector('.tool-script__source-path').textContent).toBe('frontend/scripts/locale-diff.js');
    expect(document.querySelector('.tool-script__source-args').textContent).toBe('{"area":"components"}');
  });

  it('плитка называет id, под которым значение сохранено для следующего скрипта', () => {
    const data = detectScriptRun(
      parseResult(
        JSON.stringify({
          resultId: 'r3',
          stats: { filesRead: 2, calls: 5 },
          log: [],
          error: null,
          value: 1,
          filesRead: [],
          edits: [],
        }),
      ),
    );

    render(<ScriptRunView data={data} />);

    const tile = screen.getByText('r3').closest('.tool-script__stat');
    expect(tile.getAttribute('title')).toContain("kb.result('r3')");
  });
});
