import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { detectTreeResult } from './treeResult';
import { parseResult } from './registry';
import TreeResultView from './TreeResultView';

// Здесь проверяется не разбор (он в treeResult.test.js), а поведение строки под
// курсором: лист нажимается наравне с ветвью, ветвь при этом ещё и сворачивается.

const files = () =>
  detectTreeResult(
    parseResult(
      JSON.stringify([
        { path: 'src/a.js', type: 'file' },
        { path: 'src/b.js', type: 'file' },
      ]),
    ),
  );

const rows = () => [...document.querySelectorAll('.tool-tree__row')];
const byLabel = (text) => rows().find((row) => row.textContent.includes(text));
const current = () => document.querySelector('.tool-tree__row--current');

describe('TreeResultView', () => {
  it('лист нажимается и отмечается, повторный клик отметку снимает', async () => {
    render(<TreeResultView data={files()} />);
    const leaf = byLabel('a.js');

    expect(leaf.disabled).toBe(false);
    expect(current()).toBeNull();

    await userEvent.click(leaf);
    expect(current()).toBe(leaf);
    expect(leaf).toHaveAttribute('aria-current', 'true');

    await userEvent.click(leaf);
    expect(current()).toBeNull();
  });

  it('отмечен всегда один: клик по соседней строке уводит отметку с прежней', async () => {
    render(<TreeResultView data={files()} />);

    await userEvent.click(byLabel('a.js'));
    await userEvent.click(byLabel('b.js'));
    expect(current()).toBe(byLabel('b.js'));
  });

  it('ветвь и отмечается, и сворачивается', async () => {
    render(<TreeResultView data={files()} />);
    const branch = byLabel('src');

    expect(rows()).toHaveLength(3);
    await userEvent.click(branch);

    expect(current()).toBe(branch);
    expect(branch).toHaveAttribute('aria-expanded', 'false');
    expect(rows()).toHaveLength(1);
  });
});
