import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DiffResultView from './DiffResultView';
import { detectDiffResult } from './diffResult';

// Тело сообщения коммита — единственная часть вида, которую сворачивают
// шапкой, а не строкой файла: без тела шапка не кнопка вовсе.

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

const BODY = 'Первый абзац.\n\nВторой абзац.';

const groups = (over = {}) =>
  detectDiffResult({
    isJson: true,
    parsed: [
      {
        hash: '38e5ba2c6941bf43815588d2dbbdb1d5be9590ce',
        shortHash: '38e5ba2',
        author: 'Ivan Petrov',
        date: '2026-06-17T23:58:42+03:00',
        message: 'Черновик переживает переключение чата',
        files: [
          { status: 'M', path: 'a.jsx', oldPath: null, additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-a\n+b' },
        ],
        ...over,
      },
    ],
  });

const body = () => screen.queryByText(BODY, { normalizer: (text) => text });

describe('DiffResultView — тело сообщения коммита', () => {
  test('показано сразу и прячется кликом по шапке', async () => {
    render(<DiffResultView data={groups({ body: BODY })} />);

    expect(body()).toBeInTheDocument();

    // По хешу, а не по aria-expanded: строка файла тоже раскрыта и тоже кнопка.
    const head = screen.getByRole('button', { name: /38e5ba2/ });
    await userEvent.click(head);

    expect(body()).toBeNull();
    expect(head).toHaveAttribute('aria-expanded', 'false');
  });

  test('у коммита без тела шапка не кнопка', () => {
    render(<DiffResultView data={groups()} />);

    expect(body()).toBeNull();
    // Кнопки вида — только строки файлов; шапка среди них не появляется.
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });
});
