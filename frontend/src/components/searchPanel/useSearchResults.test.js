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

/** Все три категории ответили на текущие фильтры. */
const settled = (r) => !r.current.files.loading && !r.current.docs.loading && !r.current.chats.loading;

beforeEach(() => {
  gitApi.grep.mockResolvedValue(FILES);
  documentsApi.searchGrouped.mockResolvedValue(DOCS);
  chatApi.searchChatsGrouped.mockResolvedValue(CHATS);
});

afterEach(() => vi.resetAllMocks());

test('спрашивает все три категории разом — счётчики нужны и у невыбранных', async () => {
  const { result } = renderHook(() => useSearchResults(args));

  await waitFor(() => expect(settled(result)).toBe(true));
  expect(gitApi.grep).toHaveBeenCalledTimes(1);
  expect(documentsApi.searchGrouped).toHaveBeenCalledTimes(1);
  expect(chatApi.searchChatsGrouped).toHaveBeenCalledTimes(1);
  expect(result.current.files.entry.data).toBe(FILES);
  expect(result.current.docs.entry.data).toBe(DOCS);
  expect(result.current.chats.entry.data).toBe(CHATS);
});

test('отказ одной категории не прячет остальные', async () => {
  const refusal = Object.assign(new Error('HTTP 400'), { status: 400 });
  gitApi.grep.mockRejectedValue(refusal);

  const { result } = renderHook(() => useSearchResults(args));

  await waitFor(() => expect(settled(result)).toBe(true));
  expect(result.current.files.entry.error).toBe(refusal);
  expect(result.current.files.entry.data).toBeNull();
  expect(result.current.docs.entry.data).toBe(DOCS);
  expect(result.current.chats.entry.data).toBe(CHATS);
});

test('пустой запрос не ищет ничего и не показывает загрузку', () => {
  const { result } = renderHook(() => useSearchResults({ ...args, query: '' }));

  expect(gitApi.grep).not.toHaveBeenCalled();
  expect(result.current.files.loading).toBe(false);
});

test('фильтр файлов перезапрашивает только файлы', async () => {
  const { result, rerender } = renderHook((props) => useSearchResults(props), { initialProps: args });
  await waitFor(() => expect(settled(result)).toBe(true));

  rerender({ ...args, untracked: true });

  await waitFor(() => expect(settled(result)).toBe(true));
  expect(gitApi.grep).toHaveBeenCalledTimes(2);
  expect(gitApi.grep).toHaveBeenLastCalledWith('needle', expect.objectContaining({ untracked: true }));
  // Документы и чаты про маску пути, ревизию и неотслеживаемые ничего не знают,
  // а поиск по документам в hybrid — это ещё и эмбеддинг запроса.
  expect(documentsApi.searchGrouped).toHaveBeenCalledTimes(1);
  expect(chatApi.searchChatsGrouped).toHaveBeenCalledTimes(1);
});

test('смена режима перезапрашивает только документы', async () => {
  const { result, rerender } = renderHook((props) => useSearchResults(props), { initialProps: args });
  await waitFor(() => expect(settled(result)).toBe(true));

  rerender({ ...args, mode: 'keyword' });

  await waitFor(() => expect(settled(result)).toBe(true));
  expect(documentsApi.searchGrouped).toHaveBeenLastCalledWith('needle', 'keyword', expect.anything());
  expect(documentsApi.searchGrouped).toHaveBeenCalledTimes(2);
  expect(gitApi.grep).toHaveBeenCalledTimes(1);
  expect(chatApi.searchChatsGrouped).toHaveBeenCalledTimes(1);
});

test('до ответа на новый запрос на экране остаётся прежняя выдача', async () => {
  const { result, rerender } = renderHook((props) => useSearchResults(props), { initialProps: args });
  await waitFor(() => expect(settled(result)).toBe(true));

  let release;
  gitApi.grep.mockReturnValue(new Promise((resolve) => (release = resolve)));
  rerender({ ...args, untracked: true });

  expect(result.current.files.loading).toBe(true);
  expect(result.current.files.entry.data).toBe(FILES);

  release(FILES);
  await waitFor(() => expect(settled(result)).toBe(true));
});

test('прерванный запрос не перетирает выдачу следующего', async () => {
  const { result, rerender } = renderHook((props) => useSearchResults(props), { initialProps: args });
  await waitFor(() => expect(settled(result)).toBe(true));

  // Первый ответ приходит уже после того, как запрос отменили сменой запроса.
  let release;
  gitApi.grep.mockReturnValueOnce(new Promise((resolve) => (release = resolve)));
  rerender({ ...args, query: 'first' });
  rerender({ ...args, query: 'second' });
  release({ ...FILES, total: 999 });

  await waitFor(() => expect(settled(result)).toBe(true));
  expect(gitApi.grep).toHaveBeenLastCalledWith('second', expect.anything());
  expect(result.current.files.entry.data.total).toBe(FILES.total);
});

test('стёртый запрос убирает выдачу — счётчику держаться не за что', async () => {
  const { result, rerender } = renderHook((props) => useSearchResults(props), { initialProps: args });
  await waitFor(() => expect(settled(result)).toBe(true));
  expect(result.current.files.entry.data).toBe(FILES);

  rerender({ ...args, query: '' });

  expect(result.current.files.entry).toBeNull();
  expect(result.current.docs.entry).toBeNull();
  expect(result.current.chats.entry).toBeNull();
  expect(result.current.files.loading).toBe(false);
});

test('с ревизией неотслеживаемые не запрашиваются: в снимке коммита их нет', async () => {
  const withRev = { ...args, rev: 'v1.4.0', untracked: true };
  const { result, rerender } = renderHook((props) => useSearchResults(props), { initialProps: withRev });
  await waitFor(() => expect(settled(result)).toBe(true));

  expect(gitApi.grep).toHaveBeenCalledWith('needle', expect.objectContaining({ rev: 'v1.4.0', untracked: false }));

  // Снятая галочка при заданной ревизии ничего не меняет — и запрос не повторяется.
  rerender({ ...withRev, untracked: false });

  await waitFor(() => expect(settled(result)).toBe(true));
  expect(gitApi.grep).toHaveBeenCalledTimes(1);
});
