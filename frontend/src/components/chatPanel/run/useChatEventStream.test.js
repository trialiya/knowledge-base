import { act, renderHook } from '@testing-library/react';
import useChatEventStream from './useChatEventStream';
import { openChatEventStream } from '../../../api/chatEvents';
import chatApi from '../../../api/chatApi';

vi.mock('../../../api/chatEvents');
vi.mock('../../../api/chatApi', () => ({ default: { getActiveRun: vi.fn() } }));

/**
 * onDocChanged/onFileChanged are the hook's contribution to KB/Files cache
 * invalidation (see App.jsx): fired once per run-final TOOL_CALLS event, with
 * the full array of that event's successful doc/file mutations, so cache
 * invalidation reuses the exact same extraction (toolMeta.js) already driving
 * the in-chat "changes" blocks — no separate detection logic to keep in sync.
 * One call per event (not one per tool call) matters: React 18 batches
 * several setState calls issued synchronously in the same tick down to the
 * last one, so firing separately would silently drop every mutation but the
 * last when a single run touches more than one document/file.
 */
describe('useChatEventStream doc/file mutation detection', () => {
  const chat = { id: 'c1', messages: [], notFound: false, loadError: false };

  function setup(overrides = {}) {
    const onDocChanged = vi.fn();
    const onFileChanged = vi.fn();
    let onEvent;
    openChatEventStream.mockImplementation((chatId, cb) => {
      onEvent = cb.onEvent;
      return () => {};
    });

    renderHook(() =>
      useChatEventStream({
        activeChatId: 'c1',
        activeMessagesReady: true,
        getChats: () => [chat],
        isLocalClientId: () => false,
        setChats: vi.fn(),
        onChatDeleted: vi.fn(),
        onRunSettled: vi.fn(),
        reloadMessages: vi.fn(),
        onDocChanged,
        onFileChanged,
        ...overrides,
      }),
    );

    return { fireEvent: (ev) => onEvent(ev), onDocChanged, onFileChanged };
  }

  afterEach(() => {
    vi.resetAllMocks();
  });

  test('fires onDocChanged once with an array for a successful document mutation in the final TOOL_CALLS event', () => {
    const { fireEvent, onDocChanged, onFileChanged } = setup();

    fireEvent({
      type: 'TOOL_CALLS',
      runId: 'r1',
      payload: {
        toolCalls: [{ name: 'createDocument', status: 'OK', resultMeta: { id: 55, parent: 7, descriptionVersion: 1 } }],
      },
    });

    expect(onDocChanged).toHaveBeenCalledTimes(1);
    expect(onDocChanged).toHaveBeenCalledWith([
      expect.objectContaining({ id: '55', parentId: 7, action: 'createDocument' }),
    ]);
    expect(onFileChanged).not.toHaveBeenCalled();
  });

  test('fires onFileChanged once with an array for a successful file mutation', () => {
    const { fireEvent, onDocChanged, onFileChanged } = setup();

    fireEvent({
      type: 'TOOL_CALLS',
      runId: 'r1',
      payload: {
        toolCalls: [{ name: 'editFile', status: 'OK', resultMeta: { path: 'src/App.java', operation: 'edit' } }],
      },
    });

    expect(onFileChanged).toHaveBeenCalledTimes(1);
    expect(onFileChanged).toHaveBeenCalledWith([expect.objectContaining({ path: 'src/App.java' })]);
    expect(onDocChanged).not.toHaveBeenCalled();
  });

  test('batches every mutation of a multi-tool-call run into a single onDocChanged call', () => {
    // Regression test: a run that creates several documents (e.g. "create
    // these 3 docs") reports them all in one TOOL_CALLS event. Firing
    // onDocChanged separately per tool call would let React 18 collapse the
    // resulting setState calls down to the last one in App.jsx, silently
    // losing every earlier document's cache invalidation / tree refresh.
    const { fireEvent, onDocChanged } = setup();

    fireEvent({
      type: 'TOOL_CALLS',
      runId: 'r1',
      payload: {
        toolCalls: [
          { name: 'createDocument', status: 'OK', resultMeta: { id: 1, parent: 10 } },
          { name: 'createDocument', status: 'OK', resultMeta: { id: 2, parent: 20 } },
        ],
      },
    });

    expect(onDocChanged).toHaveBeenCalledTimes(1);
    expect(onDocChanged).toHaveBeenCalledWith([
      expect.objectContaining({ id: '1', parentId: 10 }),
      expect.objectContaining({ id: '2', parentId: 20 }),
    ]);
  });

  test('skips a failed mutation — a rolled-back tool call must not invalidate anything', () => {
    const { fireEvent, onDocChanged, onFileChanged } = setup();

    fireEvent({
      type: 'TOOL_CALLS',
      runId: 'r1',
      payload: {
        toolCalls: [
          { name: 'updateDocument', status: 'ERROR', resultMeta: { id: 9 } },
          { name: 'createFile', status: 'ERROR', resultMeta: { path: 'oops.txt' } },
        ],
      },
    });

    expect(onDocChanged).not.toHaveBeenCalled();
    expect(onFileChanged).not.toHaveBeenCalled();
  });

  test('ignores non-mutation tool calls', () => {
    const { fireEvent, onDocChanged, onFileChanged } = setup();

    fireEvent({
      type: 'TOOL_CALLS',
      runId: 'r1',
      payload: { toolCalls: [{ name: 'getDocument', status: 'OK', resultMeta: { id: 1 } }] },
    });

    expect(onDocChanged).not.toHaveBeenCalled();
    expect(onFileChanged).not.toHaveBeenCalled();
  });
});

/**
 * Coming back to a chat whose answer finished while another chat was open. The
 * event stream is open for the active chat only, so RUN_DONE was missed, and a
 * replay will not bring it back: the hub clears its event log when the run ends.
 * Without reconciling against the backend the chat would keep a half-written
 * bubble and a blocked composer until the page is reloaded.
 */
describe('useChatEventStream stale run reconciliation', () => {
  let chats;

  function setup({ runId = 'r1', activeRun = {}, reloaded } = {}) {
    chats = [{ id: 'c1', messages: [{ mid: 1 }], runId, notFound: false, loadError: false }];
    chatApi.getActiveRun.mockResolvedValue(activeRun);
    openChatEventStream.mockImplementation(() => () => {});

    const setChats = vi.fn((fn) => {
      chats = typeof fn === 'function' ? fn(chats) : fn;
    });
    const reloadMessages = vi.fn().mockResolvedValue(reloaded);
    const onRunSettled = vi.fn();
    const onDocChanged = vi.fn();
    const onFileChanged = vi.fn();

    const view = renderHook(() =>
      useChatEventStream({
        activeChatId: 'c1',
        activeMessagesReady: true,
        getChats: () => chats,
        isLocalClientId: () => false,
        setChats,
        onChatDeleted: vi.fn(),
        onRunSettled,
        reloadMessages,
        onDocChanged,
        onFileChanged,
      }),
    );

    return { view, setChats, reloadMessages, onRunSettled, onDocChanged, onFileChanged };
  }

  afterEach(() => {
    vi.resetAllMocks();
  });

  test('run finished while the chat was in the background: unblocks input and reloads history', async () => {
    const { reloadMessages, onRunSettled } = setup({ runId: 'r1', activeRun: {} });

    await act(async () => {});

    expect(chatApi.getActiveRun).toHaveBeenCalledWith('c1');
    expect(chats[0].runId).toBeNull();
    expect(reloadMessages).toHaveBeenCalledWith('c1');
    // The backend names the chat once the run is over — that RUN_DONE was missed too.
    expect(onRunSettled).toHaveBeenCalledWith('c1');
  });

  test('the same run is still alive: history is left alone, the stream catches up itself', async () => {
    const { reloadMessages, onRunSettled } = setup({ runId: 'r1', activeRun: { runId: 'r1' } });

    await act(async () => {});

    expect(chats[0].runId).toBe('r1');
    expect(reloadMessages).not.toHaveBeenCalled();
    expect(onRunSettled).not.toHaveBeenCalled();
  });

  test('another run started meanwhile: resubscribes from scratch so its replay is not skipped', async () => {
    const { reloadMessages } = setup({ runId: 'r1', activeRun: { runId: 'r2' } });

    await act(async () => {});

    expect(reloadMessages).toHaveBeenCalledWith('c1');
    // The previous run's seq cursor is dropped: a fresh hub numbers its events from scratch.
    expect(openChatEventStream).toHaveBeenLastCalledWith('c1', expect.objectContaining({ fromSeq: 0 }));
  });

  test('no run in the UI: nothing to reconcile, the backend is not asked', async () => {
    setup({ runId: null });

    await act(async () => {});

    expect(chatApi.getActiveRun).not.toHaveBeenCalled();
  });
});

/**
 * The TOOL_CALLS events of a run missed in the background carried the knowledge
 * base / files cache invalidation too (see App.jsx). They are gone with the hub's
 * event log, so the same tool call metas are read back from the reloaded history:
 * otherwise a document the assistant wrote while another chat was open would keep
 * showing its stale preview and tree row.
 */
describe('useChatEventStream stale run cache invalidation', () => {
  let chats;

  function setup({ activeRun = {}, reloaded = [] } = {}) {
    chats = [{ id: 'c1', messages: [{ mid: 1 }], runId: 'r1', notFound: false, loadError: false }];
    chatApi.getActiveRun.mockResolvedValue(activeRun);
    openChatEventStream.mockImplementation(() => () => {});

    const reloadMessages = vi.fn().mockResolvedValue(reloaded);
    const onDocChanged = vi.fn();
    const onFileChanged = vi.fn();

    renderHook(() =>
      useChatEventStream({
        activeChatId: 'c1',
        activeMessagesReady: true,
        getChats: () => chats,
        isLocalClientId: () => false,
        setChats: vi.fn((fn) => {
          chats = typeof fn === 'function' ? fn(chats) : fn;
        }),
        onChatDeleted: vi.fn(),
        onRunSettled: vi.fn(),
        reloadMessages,
        onDocChanged,
        onFileChanged,
      }),
    );

    return { onDocChanged, onFileChanged };
  }

  afterEach(() => {
    vi.resetAllMocks();
  });

  test('invalidates the caches for the mutations of the run that finished unseen', async () => {
    const { onDocChanged, onFileChanged } = setup({
      reloaded: [
        {
          sender: 'ai',
          toolCallsRunId: 'r1',
          toolCalls: [
            { name: 'updateDocument', status: 'OK', resultMeta: { id: 55, parent: 7 } },
            { name: 'editFile', status: 'OK', resultMeta: { path: 'src/App.java', operation: 'edit' } },
          ],
        },
      ],
    });

    await act(async () => {});

    expect(onDocChanged).toHaveBeenCalledWith([expect.objectContaining({ id: '55', action: 'updateDocument' })]);
    expect(onFileChanged).toHaveBeenCalledWith([expect.objectContaining({ path: 'src/App.java' })]);
  });

  test('takes only the missed run: earlier runs of the same chat were invalidated when they happened', async () => {
    const { onDocChanged } = setup({
      reloaded: [
        {
          sender: 'ai',
          toolCallsRunId: 'r0',
          toolCalls: [{ name: 'createDocument', status: 'OK', resultMeta: { id: 1 } }],
        },
        {
          sender: 'ai',
          toolCallsRunId: 'r1',
          toolCalls: [{ name: 'createDocument', status: 'OK', resultMeta: { id: 2 } }],
        },
      ],
    });

    await act(async () => {});

    expect(onDocChanged).toHaveBeenCalledWith([expect.objectContaining({ id: '2' })]);
  });

  test('a run that turned out to be alive touches no caches', async () => {
    const { onDocChanged, onFileChanged } = setup({ activeRun: { runId: 'r1' } });

    await act(async () => {});

    expect(onDocChanged).not.toHaveBeenCalled();
    expect(onFileChanged).not.toHaveBeenCalled();
  });
});

/**
 * The order inside settleStaleRun. Both hazards below end the same way — the run the
 * user came back for is on screen, but half of it is missing — and neither shows up
 * as an error anywhere.
 */
describe('useChatEventStream stale run resubscribe order', () => {
  let chats;
  let subscriptions;

  function setup({ reload }) {
    chats = [{ id: 'c1', messages: [{ mid: 1 }], runId: 'r1', notFound: false, loadError: false }];
    chatApi.getActiveRun.mockResolvedValue({});
    subscriptions = [];
    openChatEventStream.mockImplementation((chatId, cb) => {
      // prevClosed — состояние прошлой подписки на момент открытия этой: закрыть старый
      // поток надо ДО переоткрытия, иначе он ещё успевает двигать курсор и писать в чат.
      const sub = { ...cb, closed: false, prevClosed: subscriptions.at(-1)?.closed ?? null };
      subscriptions.push(sub);
      return () => {
        sub.closed = true;
      };
    });

    renderHook(() =>
      useChatEventStream({
        activeChatId: 'c1',
        activeMessagesReady: true,
        getChats: () => chats,
        isLocalClientId: () => false,
        setChats: vi.fn((fn) => {
          chats = typeof fn === 'function' ? fn(chats) : fn;
        }),
        onChatDeleted: vi.fn(),
        onRunSettled: vi.fn(),
        reloadMessages: reload,
      }),
    );
  }

  afterEach(() => {
    vi.resetAllMocks();
  });

  test('resubscribes only once the reloaded history is in place', async () => {
    // Поток подключается ТОЛЬКО поверх загруженной истории: реплей, легший в старые
    // messages, затёрла бы пришедшая следом страница из БД.
    let finishReload;
    setup({ reload: vi.fn(() => new Promise((resolve) => (finishReload = () => resolve([])))) });

    await act(async () => {});
    expect(subscriptions).toHaveLength(1);

    await act(async () => finishReload());
    expect(subscriptions).toHaveLength(2);
    expect(subscriptions[1].fromSeq).toBe(0);
  });

  test('closes the old stream as soon as the run turns out to be stale', async () => {
    // Пока поток открыт, он продолжает двигать курсор seq и писать события в чат: дожить
    // до уборки эффекта (целый рендер спустя) он не должен — за это время он и вернул бы
    // выброшенный курсор, и попал бы под затирающую перезагрузку истории.
    let finishReload;
    setup({ reload: vi.fn(() => new Promise((resolve) => (finishReload = () => resolve([])))) });
    const first = subscriptions[0];
    first.onSeq(42); // прогон, за которым мы следили, успел дойти до seq=42

    await act(async () => {});
    expect(first.closed).toBe(true); // ещё до перезагрузки истории и переподписки

    await act(async () => finishReload());
    expect(subscriptions[1].prevClosed).toBe(true);
    // Курсор выброшен и никем не восстановлен — новый прогон реплеится целиком.
    expect(subscriptions[1].fromSeq).toBe(0);
  });
});
