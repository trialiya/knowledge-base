import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ResultList from './ResultList';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  // Ключ вместо перевода: тест проверяет, ЧТО показано, а не как это звучит.
  // Счётчик подставляем сам — по нему видно, какое число ушло в подпись.
  useTranslation: () => ({
    t: (key, opts) => (opts && 'count' in opts ? `${key}:${opts.count}` : key),
    i18n: { language: 'ru' },
  }),
}));

const filesEntry = {
  data: {
    total: 8,
    truncated: false,
    files: [
      {
        path: 'backend/src/Main.java',
        lines: [1, 2, 3, 4, 5, 6, 7].map((n) => ({ line: n, text: `needle ${n}` })),
      },
    ],
  },
  error: null,
};

const renderFiles = (over = {}) =>
  render(
    <ResultList
      scope="files"
      query="needle"
      loading={false}
      entry={filesEntry}
      regex={false}
      rev=""
      project=""
      onOpenFile={vi.fn()}
      onOpenDoc={vi.fn()}
      onOpenChat={vi.fn()}
      {...over}
    />,
  );

test('совпадения одного файла остаются одной карточкой, хвост прячется под «ещё N»', async () => {
  renderFiles();

  // Пять строк видно сразу, остальные две — за кнопкой.
  expect(document.querySelectorAll('.search-group')).toHaveLength(1);
  expect(document.querySelectorAll('.search-group__row')).toHaveLength(5);
  await userEvent.click(screen.getByRole('button', { name: 'group.more:2' }));
  expect(document.querySelectorAll('.search-group__row')).toHaveLength(7);
});

test('ссылка карточки ведёт на файл в той же ревизии и с тем же запросом, с которыми его нашли', () => {
  renderFiles({ rev: 'v1' });

  // find в адресе — то, что подсветит открытый файл: иначе переход к найденному
  // высаживал бы на первой строке, и совпадение пришлось бы искать заново.
  expect(screen.getByRole('link', { name: 'Main.java' })).toHaveAttribute(
    'href',
    '/files/backend/src/Main.java?rev=v1&find=needle',
  );
});

test('регулярный запрос уезжает в адрес файла помеченным как выражение', () => {
  renderFiles({ regex: true });

  expect(screen.getByRole('link', { name: 'Main.java' })).toHaveAttribute(
    'href',
    '/files/backend/src/Main.java?find=needle&re=1',
  );
});

test('запрос подсвечивается в найденной строке, но не когда он — регулярка', () => {
  const { unmount } = renderFiles();
  expect(document.querySelectorAll('mark.search-match').length).toBeGreaterThan(0);
  unmount();

  renderFiles({ regex: true });
  expect(document.querySelectorAll('mark.search-match')).toHaveLength(0);
});

test('отказ категории объясняется по коду ответа, а не одним «ошибка»', () => {
  const { unmount } = renderFiles({ entry: { data: null, error: { status: 400 } } });
  expect(screen.getByText('error.badFilter')).toBeInTheDocument();
  unmount();

  renderFiles({ entry: { data: null, error: { status: 503 } } });
  expect(screen.getByText('error.timeout')).toBeInTheDocument();
});

test('обрезанная выдача честно об этом говорит', () => {
  renderFiles({ entry: { data: { ...filesEntry.data, truncated: true }, error: null } });

  expect(screen.getByText('truncated.files')).toBeInTheDocument();
});

test('пустой запрос ничего не ищет и говорит, что делать', () => {
  renderFiles({ query: '', entry: null });

  expect(screen.getByText('empty.noQuery')).toBeInTheDocument();
});

test('чаты показывают автора и время каждого совпавшего сообщения', () => {
  render(
    <ResultList
      scope="chats"
      query="needle"
      loading={false}
      entry={{
        data: {
          total: 1,
          truncated: false,
          chats: [
            {
              conversationId: 'c1',
              topic: 'Тема',
              updatedAt: '2026-01-02T10:00:00',
              titleMatched: false,
              messages: [{ id: 5, role: 'ASSISTANT', createdAt: '2026-01-02T10:00:00', snippet: 'needle here' }],
            },
          ],
        },
        error: null,
      }}
      regex={false}
      rev=""
      project=""
      onOpenFile={vi.fn()}
      onOpenDoc={vi.fn()}
      onOpenChat={vi.fn()}
    />,
  );

  expect(screen.getByRole('link', { name: 'Тема' })).toHaveAttribute('href', '/chat/c1');
  expect(screen.getByText('chats.roleAssistant')).toBeInTheDocument();
  expect(screen.getByText(/needle/)).toBeInTheDocument();
});
