import { render, screen } from '@testing-library/react';
import { detectBlameResult } from './blameResult';
import { parseResult } from './registry';
import BlameResultView from './BlameResultView';
import { commitUrl } from '@/navigation/urlScheme';

// Здесь проверяется не разбор (он в blameResult.test.js), а что строка ханка
// ведёт к коммиту и что пустой диапазон не рисует пустую таблицу.

const HASH = 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0';

const view = (result) =>
  detectBlameResult(parseResult(JSON.stringify({ project: 'billing', result, truncated: false })));

describe('BlameResultView', () => {
  it('хеш ведёт к коммиту, незакоммиченные строки подписаны', () => {
    render(
      <BlameResultView
        data={view({
          path: 'src/Foo.java',
          lineCount: 3,
          hunks: [
            { fromLine: 1, lineCount: 2, hash: HASH, author: 'Alice', summary: 'Fix parser', sourceLine: 1 },
            { fromLine: 3, lineCount: 1 },
          ],
        })}
      />,
    );

    expect(screen.getByText('a1b2c3d').closest('a')).toHaveAttribute('href', commitUrl(HASH, 'billing'));
    expect(screen.getByText('Fix parser')).toBeInTheDocument();
    expect(document.querySelector('.tool-blame__uncommitted')).not.toBeNull();
  });

  it('диапазон за концом файла — подпись вместо строк', () => {
    render(<BlameResultView data={view({ path: 'a.txt', lineCount: 3, hunks: [], fromLine: 10, toLine: 9 })} />);

    expect(document.querySelector('.tool-blame__rows')).toBeNull();
    expect(document.querySelector('.tool-blame__empty')).not.toBeNull();
  });
});
