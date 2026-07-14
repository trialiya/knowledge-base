import { applyChatEvent } from './chatEventReducer';

const ctx = {
  isLocal: () => false,
  stoppedLabel: '[stopped]',
  errorLabel: 'Ошибка',
  interruptedNote: '\n_прервано_',
};

const userChat = (id = 'c') => ({ id, messages: [{ text: 'вопрос', sender: 'user' }], runId: null });
const last = (chat) => chat.messages[chat.messages.length - 1];

describe('applyChatEvent', () => {
  test('RUN_STARTED appends an empty AI bubble tagged with runId', () => {
    const chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    expect(chat.runId).toBe('r1');
    expect(last(chat)).toMatchObject({ sender: 'ai', runId: 'r1', text: '' });
  });

  test('STREAM appends text to the run bubble and trims leading newlines', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: '\n\nответ' } }, ctx);
    expect(last(chat).text).toBe('ответ');
  });

  test('RUN_ERROR keeps partial text, appends the interrupted note and flags error', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'частичный ответ' } }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_ERROR', runId: 'r1', payload: {} }, ctx);

    const ai = last(chat);
    expect(ai.error).toBe(true);
    expect(ai.text).toContain('частичный ответ');
    expect(ai.text).toContain('прервано');
    expect(chat.runId).toBeNull();
    expect(ai.runId).toBeUndefined(); // finalize drops the transient runId
    expect(ai.toolCallsRunId).toBe('r1');
  });

  test('RUN_ERROR before any chunk creates an error bubble with the error label', () => {
    const chat = applyChatEvent({ ...userChat(), runId: 'r2' }, { type: 'RUN_ERROR', runId: 'r2', payload: {} }, ctx);
    const ai = last(chat);
    expect(ai).toMatchObject({ sender: 'ai', error: true, text: 'Ошибка' });
  });

  test('RUN_DONE finalizes without an error flag', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r3' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r3', payload: { message: 'ответ' } }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_DONE', runId: 'r3' }, ctx);
    expect(last(chat).error).toBeUndefined();
    expect(chat.runId).toBeNull();
  });

  test('AI bubble keeps a stable mid across streaming updates', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    const mid = last(chat).mid;
    expect(mid).toBeTruthy();
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'hi' } }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'TOOL_CALL', runId: 'r1', payload: { toolCall: { name: 'x', status: 'OK' } } },
      ctx,
    );
    expect(last(chat).mid).toBe(mid);
  });

  test('local USER_MESSAGE echo is ignored (already shown optimistically)', () => {
    const localCtx = { ...ctx, isLocal: (id) => id === 'mine' };
    const before = userChat();
    const after = applyChatEvent(
      before,
      { type: 'USER_MESSAGE', clientMsgId: 'mine', payload: { text: 'вопрос' } },
      localCtx,
    );
    expect(after).toBe(before); // no change
  });

  test('TOOL_CALLS metas with distinct callIndex stay separate even with identical args', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    const meta = { name: 'getDocument', arguments: { id: 5 }, status: 'OK' };
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALLS',
        runId: 'r1',
        payload: {
          toolCalls: [
            { ...meta, callIndex: 0 },
            { ...meta, callIndex: 1 },
          ],
        },
      },
      ctx,
    );
    expect(last(chat).toolCalls).toHaveLength(2);
  });

  test('final TOOL_CALLS meta merges into the live TOOL_CALL entry (no callIndex on the live one)', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'getDocument', arguments: { id: 5 }, status: 'STARTED' } },
      },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALLS',
        runId: 'r1',
        payload: { toolCalls: [{ name: 'getDocument', arguments: { id: 5 }, status: 'OK', callIndex: 0 }] },
      },
      ctx,
    );
    const calls = last(chat).toolCalls;
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({ status: 'OK', callIndex: 0 });
  });
});
