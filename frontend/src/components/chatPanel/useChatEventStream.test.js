import { renderHook } from '@testing-library/react';
import useChatEventStream from './useChatEventStream';
import { openChatEventStream } from '../../api/chatEvents';

vi.mock('../../api/chatEvents');
vi.mock('../../api/chatApi', () => ({ default: { getActiveRun: vi.fn() } }));

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
