import { fireEvent, render, screen } from '@testing-library/react';
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

test('чаты показывают автора, время и все совпадения каждого сообщения', () => {
  const onOpenChat = vi.fn();
  render(
    <ResultList
      scope="chats"
      query="needle"
      loading={false}
      entry={{
        data: {
          total: 2,
          truncated: false,
          chats: [
            {
              conversationId: 'c1',
              topic: 'Тема',
              updatedAt: '2026-01-02T10:00:00',
              titleMatched: false,
              messages: [
                {
                  id: 5,
                  role: 'ASSISTANT',
                  createdAt: '2026-01-02T10:00:00',
                  fragments: ['needle here', 'needle again'],
                },
              ],
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
      onOpenChat={onOpenChat}
    />,
  );

  // Запрос уходит в адрес чата: там его подхватит find-бар и сядет на совпадение.
  // Заголовок карточки ведёт в чат целиком, подпись сообщения — в само сообщение.
  expect(screen.getByRole('link', { name: 'Тема' })).toHaveAttribute('href', '/chat/c1?find=needle');
  const heading = screen.getByText('chats.roleAssistant').closest('a');
  expect(heading).toHaveAttribute('href', '/chat/c1?find=needle&msg=5');

  // Строка на каждое вхождение в сообщении, а не одна на сообщение; сами строки
  // никуда не ведут — переход живёт на подписи над ними.
  const hits = screen.getAllByText('needle');
  expect(hits).toHaveLength(2);
  hits.forEach((hit) => expect(hit.closest('a')).toBeNull());

  fireEvent.click(heading);
  expect(onOpenChat).toHaveBeenCalledWith('c1', { find: 'needle', msg: 5 });
});

/** Совпало только название — искать в сообщениях нечего, и бар открывать незачем. */
test('чат, найденный только по теме, уводит без запроса', () => {
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
              conversationId: 'c2',
              topic: 'needle в теме',
              updatedAt: '2026-01-02T10:00:00',
              titleMatched: true,
              messages: [],
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

  expect(screen.getByRole('link', { name: /needle в теме/ })).toHaveAttribute('href', '/chat/c2');
});

/**
 * Совпадения документа сгруппированы по разделам, и раздел — ссылка на документ
 * с запросом и путём раздела: find-бар там встанет на первое совпадение в нём.
 */
test('документ: совпадения по разделам, раздел ведёт к себе', () => {
  const onOpenDoc = vi.fn();
  render(
    <ResultList
      scope="docs"
      query="needle"
      loading={false}
      entry={{
        data: {
          total: 3,
          documents: [
            {
              id: 5,
              title: 'Док',
              updatedAt: '2026-01-02T10:00:00',
              parentList: [],
              fragments: [
                { line: 2, sectionPath: '_preamble', text: 'needle до заголовка' },
                { line: 10, sectionPath: 'FAQ > Вопрос', text: 'needle раз' },
                { line: 12, sectionPath: 'FAQ > Вопрос', text: 'needle два' },
              ],
            },
          ],
        },
        error: null,
      }}
      regex={false}
      rev=""
      project=""
      onOpenFile={vi.fn()}
      onOpenDoc={onOpenDoc}
      onOpenChat={vi.fn()}
    />,
  );

  expect(screen.getByRole('link', { name: 'Док' })).toHaveAttribute('href', '/knowledge/doc/5?find=needle');
  // Преамбула — не раздел: подписана, но ведёт в начало документа.
  expect(screen.getByRole('link', { name: 'docs.preamble' })).toHaveAttribute('href', '/knowledge/doc/5?find=needle');
  const section = screen.getByRole('link', { name: 'FAQ > Вопрос' });
  expect(section).toHaveAttribute(
    'href',
    '/knowledge/doc/5?find=needle&section=FAQ+%3E+%D0%92%D0%BE%D0%BF%D1%80%D0%BE%D1%81',
  );
  expect(screen.getAllByRole('link', { name: /needle/ })).toHaveLength(3);

  fireEvent.click(section);
  expect(onOpenDoc).toHaveBeenCalledWith(5, { find: 'needle', section: 'FAQ > Вопрос' });
});

/** Найден по смыслу: подстроки в тексте может не быть, и бар открылся бы с «0/0». */
test('документ со сниппетом ранжирования уводит без запроса', () => {
  render(
    <ResultList
      scope="docs"
      query="needle"
      loading={false}
      entry={{
        data: {
          total: 1,
          documents: [
            {
              id: 6,
              title: 'Смысл',
              updatedAt: '2026-01-02T10:00:00',
              parentList: [],
              fragments: [{ line: null, sectionPath: null, text: 'о том же, другими словами' }],
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

  expect(screen.getByRole('link', { name: 'Смысл' })).toHaveAttribute('href', '/knowledge/doc/6');
  expect(screen.getAllByRole('link')).toHaveLength(1);
});
