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

// Отдаёт готовые кадры и после них «зависает»: соединение остаётся открытым, как настоящее.
function sseResponse(events) {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    ok: true,
    body: {
      getReader() {
        return {
          read: () =>
            i < events.length
              ? Promise.resolve({ done: false, value: encoder.encode(`data: ${JSON.stringify(events[i++])}\n\n`) })
              : new Promise(() => {}),
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
    global.fetch = vi.fn((url) => {
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
    global.fetch = vi.fn((url) => {
      requested.push(url);
      return Promise.resolve(hangingSseResponse());
    });

    const close = openChatEventStream('chat-a', { fromSeq: 7, onEvent: () => {} });
    await Promise.resolve();

    expect(requested[0]).toContain('fromSeq=7');
    close();
  });

  test('REPLAY_GAP ставит курсор на свою нумерацию, а не поднимает максимум', async () => {
    // Курсор из прошлой жизни хаба: номера сквозные на процесс, поэтому такой курсор лежит
    // ниже номеров текущего хаба, и REPLAY_GAP двигает его вниз — на начало лога. Оставленный
    // максимумом (он выше seq самого REPLAY_GAP), он заставлял бы хаб на каждом обрыве реплеить
    // лог целиком — поверх уже собранного пузыря, задваивая ответ.
    const seen = [];
    originalFetch = global.fetch;
    global.fetch = vi.fn(() =>
      Promise.resolve(
        sseResponse([
          { seq: 0, type: 'REPLAY_GAP' },
          { seq: 1756654321000042, type: 'STREAM' },
        ]),
      ),
    );

    const close = openChatEventStream('chat-a', { fromSeq: 340, onEvent: () => {}, onSeq: (s) => seen.push(s) });
    await vi.waitFor(() => expect(seen).toEqual([0, 1756654321000042]));
    close();
  });
});
