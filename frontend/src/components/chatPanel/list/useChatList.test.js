import { act, renderHook } from '@testing-library/react';
import useChatList from './useChatList';
import chatApi from '@/api/chatApi';
import { DRAFT_CHAT_ID } from '@/constants/storage';

vi.mock('@/api/chatApi', () => ({ default: { listChats: vi.fn() } }));

const makeDraft = () => ({ id: DRAFT_CHAT_ID, title: 'Новый чат', messages: [], draft: true });
const fromServer = (id, topic) => ({ conversationId: id, topic });

/** Список, который отвечает, когда тест скажет. */
function deferredList() {
  let answer;
  chatApi.listChats.mockReturnValue(new Promise((resolve) => (answer = resolve)));
  return (chats) => act(async () => answer(chats));
}

function mount(initialActiveChatId, initialPropChatId = initialActiveChatId) {
  return renderHook(() => useChatList({ initialActiveChatId, initialPropChatId, makeDraft, selectChat: vi.fn() }));
}

describe('useChatList — the first load', () => {
  afterEach(() => {
    vi.resetAllMocks();
  });

  /** Композер открытого /chat/new принимает текст раньше, чем приходит список. */
  test('a direct /chat/new holds its draft before the list arrives', () => {
    deferredList();
    const { result } = mount(DRAFT_CHAT_ID);

    expect(result.current.chats.map((c) => c.id)).toEqual([DRAFT_CHAT_ID]);
  });

  /**
   * Первое сообщение ушло, пока список был в пути: черновик стал чатом, которого в ответе
   * сервера нет. Список встаёт под него, а не вместо, — иначе открытый чат пропал бы.
   */
  test('a chat started while the list was loading stays on top, with its feed', async () => {
    const answer = deferredList();
    const { result } = mount(DRAFT_CHAT_ID);
    const question = { mid: 1, text: 'вопрос' };
    act(() => {
      result.current.setChats((prev) =>
        prev.map((c) => (c.id === DRAFT_CHAT_ID ? { ...c, id: 'fresh', draft: false, messages: [question] } : c)),
      );
    });

    await answer([fromServer('old', 'Старый')]);

    expect(result.current.chats.map((c) => c.id)).toEqual(['fresh', 'old']);
    expect(result.current.chats[0].messages).toEqual([question]);
  });

  /** Сервер успел записать новый чат — но локальная запись свежее: в ней лента. */
  test('a chat the server already knows keeps its local record', async () => {
    const answer = deferredList();
    const { result } = mount(DRAFT_CHAT_ID);
    act(() => {
      result.current.setChats((prev) =>
        prev.map((c) => (c.id === DRAFT_CHAT_ID ? { ...c, id: 'fresh', messages: [{ mid: 1 }] } : c)),
      );
    });

    await answer([fromServer('fresh', 'Новый чат'), fromServer('old', 'Старый')]);

    expect(result.current.chats.map((c) => c.id)).toEqual(['fresh', 'old']);
    expect(result.current.chats[0].messages).toEqual([{ mid: 1 }]);
  });

  test('a link to an existing chat gets the list as the server sent it', async () => {
    const answer = deferredList();
    const { result } = mount('old');

    await answer([fromServer('old', 'Старый'), fromServer('other', 'Другой')]);

    expect(result.current.chats.map((c) => [c.id, c.title, c.messages])).toEqual([
      ['old', 'Старый', null],
      ['other', 'Другой', null],
    ]);
  });
});
