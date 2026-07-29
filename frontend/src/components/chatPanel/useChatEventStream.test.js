import { renderHook } from '@testing-library/react';
import useChatEventStream from './useChatEventStream';
import { openChatEventStream } from '../../api/chatEvents';

vi.mock('../../api/chatEvents');
vi.mock('../../api/chatApi', () => ({ default: { getActiveRun: vi.fn() } }));

/**
 * onDocChanged/onFileChanged are the hook's contribution to KB/Files cache
 * invalidation (see App.jsx): fired for every successful doc/file mutation
 * carried by the run-final TOOL_CALLS event, so cache invalidation reuses the
 * exact same extraction (toolMeta.js) already driving the in-chat "changes"
 * blocks — no separate detection logic to keep in sync.
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
        chatsRef: { current: [chat] },
        localClientIdsRef: { current: new Set() },
        tRef: { current: (k) => k },
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

  test('fires onDocChanged for a successful document mutation in the final TOOL_CALLS event', () => {
    const { fireEvent, onDocChanged, onFileChanged } = setup();

    fireEvent({
      type: 'TOOL_CALLS',
      runId: 'r1',
      payload: {
        toolCalls: [{ name: 'createDocument', status: 'OK', resultMeta: { id: 55, parent: 7, descriptionVersion: 1 } }],
      },
    });

    expect(onDocChanged).toHaveBeenCalledWith(
      expect.objectContaining({ id: '55', parentId: 7, action: 'createDocument' }),
    );
    expect(onFileChanged).not.toHaveBeenCalled();
  });

  test('fires onFileChanged for a successful file mutation', () => {
    const { fireEvent, onDocChanged, onFileChanged } = setup();

    fireEvent({
      type: 'TOOL_CALLS',
      runId: 'r1',
      payload: {
        toolCalls: [{ name: 'editFile', status: 'OK', resultMeta: { path: 'src/App.java', operation: 'edit' } }],
      },
    });

    expect(onFileChanged).toHaveBeenCalledWith(expect.objectContaining({ path: 'src/App.java' }));
    expect(onDocChanged).not.toHaveBeenCalled();
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
