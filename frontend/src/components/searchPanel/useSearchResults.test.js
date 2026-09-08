import { renderHook, waitFor } from '@testing-library/react';
import gitApi from '@/api/gitApi';
import documentsApi from '@/api/documentsApi';
import chatApi from '@/api/chatApi';
import useSearchResults from './useSearchResults';

vi.mock('@/api/gitApi');
vi.mock('@/api/documentsApi');
vi.mock('@/api/chatApi');

const FILES = { total: 2, truncated: false, files: [{ path: 'a.java', lines: [{ line: 1, text: 'x' }] }] };
const DOCS = { total: 1, documents: [{ id: 7, title: 'Doc', fragments: [] }] };
const CHATS = { total: 1, truncated: false, chats: [{ conversationId: 'c1', messages: [] }] };

const args = { query: 'needle', mode: 'hybrid', path: '', project: '', rev: '', regex: false, untracked: false };

beforeEach(() => {
  gitApi.grep.mockResolvedValue(FILES);
  documentsApi.searchGrouped.mockResolvedValue(DOCS);
  chatApi.searchChatsGrouped.mockResolvedValue(CHATS);
});

afterEach(() => vi.resetAllMocks());

test('спрашивает все три категории разом — счётчики нужны и у невыбранных', async () => {
  const { result } = renderHook(() => useSearchResults(args));

  await waitFor(() => expect(result.current.loading).toBe(false));
  expect(gitApi.grep).toHaveBeenCalledTimes(1);
  expect(documentsApi.searchGrouped).toHaveBeenCalledTimes(1);
  expect(chatApi.searchChatsGrouped).toHaveBeenCalledTimes(1);
  expect(result.current.files.data).toBe(FILES);
  expect(result.current.docs.data).toBe(DOCS);
  expect(result.current.chats.data).toBe(CHATS);
});

test('отказ одной категории не прячет остальные', async () => {
  const refusal = Object.assign(new Error('HTTP 400'), { status: 400 });
  gitApi.grep.mockRejectedValue(refusal);

  const { result } = renderHook(() => useSearchResults(args));

  await waitFor(() => expect(result.current.loading).toBe(false));
  expect(result.current.files.error).toBe(refusal);
  expect(result.current.files.data).toBeNull();
  expect(result.current.docs.data).toBe(DOCS);
  expect(result.current.chats.data).toBe(CHATS);
});

test('пустой запрос не ищет ничего и не показывает загрузку', () => {
  const { result } = renderHook(() => useSearchResults({ ...args, query: '' }));

  expect(gitApi.grep).not.toHaveBeenCalled();
  expect(result.current.loading).toBe(false);
});

test('смена фильтра перезапрашивает и до ответа держит прежнюю выдачу', async () => {
  const { result, rerender } = renderHook((props) => useSearchResults(props), { initialProps: args });
  await waitFor(() => expect(result.current.loading).toBe(false));

  let release;
  gitApi.grep.mockReturnValue(new Promise((resolve) => (release = resolve)));
  rerender({ ...args, untracked: true });

  // Ответ ещё не пришёл: идёт поиск, но на экране остаётся то, что нашли раньше.
  expect(result.current.loading).toBe(true);
  expect(result.current.files.data).toBe(FILES);
  expect(gitApi.grep).toHaveBeenLastCalledWith('needle', expect.objectContaining({ untracked: true }));

  release(FILES);
  await waitFor(() => expect(result.current.loading).toBe(false));
});

test('прерванный запрос не перетирает выдачу следующего', async () => {
  const { result, rerender } = renderHook((props) => useSearchResults(props), { initialProps: args });
  await waitFor(() => expect(result.current.loading).toBe(false));

  // Первый ответ приходит уже после того, как запрос отменили сменой запроса.
  let release;
  gitApi.grep.mockReturnValueOnce(new Promise((resolve) => (release = resolve)));
  rerender({ ...args, query: 'first' });
  rerender({ ...args, query: 'second' });
  release(FILES);

  await waitFor(() => expect(result.current.loading).toBe(false));
  expect(gitApi.grep).toHaveBeenLastCalledWith('second', expect.anything());
});
