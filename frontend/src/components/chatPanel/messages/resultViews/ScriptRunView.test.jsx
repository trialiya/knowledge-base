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
});
