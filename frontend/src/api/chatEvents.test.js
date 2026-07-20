import { openChatEventStream } from './chatEvents';

// Держит соединение открытым (reader «зависает»), пока тест не закроет поток — так мы
// проверяем ровно то, что важно для фикса: с каким fromSeq клиент подключается к хабу.
function hangingSseResponse() {
  return {
    ok: true,
    body: {
      getReader() {
        return {
          read: () => new Promise(() => {}),
          cancel() {},
        };
      },
    },
  };
}

describe('openChatEventStream fromSeq', () => {
  let originalFetch;
  afterEach(() => {
    global.fetch = originalFetch;
  });

  test('первое подключение идёт с fromSeq=0 (полный реплей текущего прогона)', async () => {
    const requested = [];
    originalFetch = global.fetch;
    global.fetch = jest.fn((url) => {
      requested.push(url);
      return Promise.resolve(hangingSseResponse());
    });

    const close = openChatEventStream('chat-a', { onEvent: () => {} });
    await Promise.resolve();

    expect(requested).toHaveLength(1);
    expect(requested[0]).toContain('/api/chats/chat-a/events?fromSeq=0');
    close();
  });

  test('переподписка с курсором подключается с fromSeq=N, а не с нуля', async () => {
    // Регрессия бага «данные другого чата»: при переключении чатов повторная подписка
    // с fromSeq=0 заставляла хаб реплеить весь прогон заново, и редьюсер дописывал его
    // поверх уже собранного пузыря — ответ задваивался. Курсор чата это исключает.
    const requested = [];
    originalFetch = global.fetch;
    global.fetch = jest.fn((url) => {
      requested.push(url);
      return Promise.resolve(hangingSseResponse());
    });

    const close = openChatEventStream('chat-a', { fromSeq: 7, onEvent: () => {} });
    await Promise.resolve();

    expect(requested[0]).toContain('fromSeq=7');
    close();
  });
});
