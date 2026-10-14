import { render, screen } from '@testing-library/react';
import { detectOriginResult } from './originResult';
import { parseResult } from './registry';
import OriginResultView from './OriginResultView';
import { commitUrl } from '@/navigation/urlScheme';

// Здесь проверяется не разбор (он в originResult.test.js), а что вид называет
// коммит-ответ, строки «стало»/«было» и не приписывает автора границе клона.

const NEW = 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0';
const OLD = 'e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3';

const view = (result) => detectOriginResult(parseResult(JSON.stringify({ project: 'billing', result })));

const step = (hash, text, extra = {}) => ({
  hash,
  date: '2024-03-01T10:00:00Z',
  path: 'a.txt',
  line: 3,
  text,
  ...extra,
});

describe('OriginResultView', () => {
  it('коммит-ответ с автором и темой, «стало» и «было», путь строки', () => {
    render(
      <OriginResultView
        data={view({
          path: 'a.txt',
          line: 3,
          query: 'total',
          status: 'FOUND',
          steps: [step(NEW, 'final x = total()'), step(OLD, 'x = total()', { author: 'Alice', summary: 'Add total' })],
          before: step('c'.repeat(40), 'x = 1'),
        })}
      />,
    );

    expect(screen.getAllByText('e4f5a6b')[0].closest('a')).toHaveAttribute('href', commitUrl(OLD, 'billing'));
    expect(screen.getAllByText('Add total').length).toBeGreaterThan(0);
    expect(screen.getByText('x = 1')).toBeInTheDocument();
    expect(document.querySelectorAll('.tool-origin__step')).toHaveLength(2);
  });

  it('граница клона — подпись и подсказка вместо автора', () => {
    render(
      <OriginResultView
        data={view({
          path: 'a.txt',
          line: 3,
          query: 'total',
          status: 'BOUNDARY',
          steps: [step(OLD, 'x = total()', { boundary: true })],
        })}
      />,
    );

    expect(document.querySelector('.origin-commit__boundary')).toHaveTextContent(/start of the clone's history/);
    expect(document.querySelector('.tool-origin__hint')).toHaveTextContent(/shallow/);
    expect(document.querySelector('.tool-origin__versions')).toBeNull();
  });

  it('подстроки в строке нет — только статус', () => {
    render(<OriginResultView data={view({ path: 'a.txt', line: 3, query: 'x', status: 'NOT_IN_LINE', steps: [] })} />);

    expect(screen.getByText(/does not contain/)).toBeInTheDocument();
    expect(document.querySelector('.tool-origin__answer')).toBeNull();
  });
});
