import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FileSections from './FileSections';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi');
vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key }),
}));

const outline = (symbols) => ({ path: 'docs/guide.md', language: 'markdown', parser: 'markdown', symbols });

const SYMBOLS = [
  { kind: 'preamble', name: '_preamble', signature: '_preamble', startLine: 1, endLine: 2 },
  { kind: 'h2', name: 'Установка', signature: 'Установка', startLine: 3, endLine: 8 },
  { kind: 'h3', name: 'Docker', signature: 'Установка > Docker', startLine: 5, endLine: 8 },
  { kind: 'h2', name: 'FAQ', signature: 'FAQ', startLine: 9, endLine: 12 },
];

const show = (props = {}) => render(<FileSections path="docs/guide.md" project="kb" onJump={() => {}} {...props} />);

describe('FileSections', () => {
  afterEach(() => vi.resetAllMocks());

  test('спрашивает структуру у той же ревизии, что показана в центре', async () => {
    gitApi.getFileOutline.mockResolvedValue(outline(SYMBOLS));
    show({ rev: 'v1' });

    await screen.findByText('Docker');
    expect(gitApi.getFileOutline).toHaveBeenCalledWith(
      'docs/guide.md',
      expect.objectContaining({ project: 'kb', rev: 'v1' }),
    );
  });

  // Файл начинается с «##» — самый мелкий уровень и есть верхний, ступеньки
  // вправо у всего списка быть не должно.
  test('вкладывает по уровню, считая от самого мелкого заголовка файла', async () => {
    gitApi.getFileOutline.mockResolvedValue(outline(SYMBOLS));
    show();

    const rows = await screen.findAllByRole('treeitem');
    expect(rows.map((r) => [r.textContent, r.getAttribute('aria-level')])).toEqual([
      ['sections.preamble1', '1'],
      ['Установка3', '1'],
      ['Docker5', '2'],
      ['FAQ9', '1'],
    ]);
    // Полный путь раздела — подсказкой: глубокий заголовок обрезан.
    expect(rows[2]).toHaveAttribute('title', 'Установка > Docker');
  });

  test('клик по разделу просит прокрутить к его строке и отмечает его', async () => {
    const user = userEvent.setup();
    const onJump = vi.fn();
    gitApi.getFileOutline.mockResolvedValue(outline(SYMBOLS));
    const { rerender } = show({ onJump });

    await user.click(await screen.findByText('Docker'));
    expect(onJump).toHaveBeenCalledWith(5);

    rerender(<FileSections path="docs/guide.md" project="kb" onJump={onJump} activeLine={5} />);
    expect(screen.getByText('Docker').closest('[role="treeitem"]')).toHaveAttribute('aria-selected', 'true');
  });

  test('файл без заголовков — подсказка вместо одной строки «начало файла»', async () => {
    gitApi.getFileOutline.mockResolvedValue(outline([SYMBOLS[0]]));
    show();

    expect(await screen.findByText('sections.empty')).toBeInTheDocument();
    expect(screen.queryByRole('treeitem')).not.toBeInTheDocument();
  });

  test('отказ сервера — сообщение, а не пустой список', async () => {
    gitApi.getFileOutline.mockRejectedValue(new Error('400'));
    show();

    expect(await screen.findByText('sections.loadError')).toBeInTheDocument();
  });

  /** Правка, pull или откат меняют и заголовки: токен обновления — повод спросить снова. */
  test('спрашивает снова, когда сдвинулся токен обновления', async () => {
    gitApi.getFileOutline.mockResolvedValue(outline(SYMBOLS));
    const { rerender } = show({ refreshToken: 0 });
    await screen.findByText('Docker');

    rerender(<FileSections path="docs/guide.md" project="kb" onJump={() => {}} refreshToken={1} />);

    await waitFor(() => expect(gitApi.getFileOutline).toHaveBeenCalledTimes(2));
  });
});
