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

  // ── Сегментация по границе tool-цикла ─────────────────────────────────────

  const aiOfRun = (chat, runId) =>
    chat.messages.filter((m) => m.sender === 'ai' && (m.runId === runId || m.toolCallsRunId === runId));

  test('TOOL_CALL after a TOOL_CALLS boundary attaches to the sealed segment, not a new bubble', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'смотрю документ' } }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'STREAM', runId: 'r1', payload: { message: '', finishReason: 'TOOL_CALLS' } },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'getDocument', arguments: { id: 1 }, status: 'STARTED' } },
      },
      ctx,
    );

    const segments = aiOfRun(chat, 'r1');
    expect(segments).toHaveLength(1); // новый пустой пузырь не открыт
    expect(segments[0].text).toBe('смотрю документ');
    expect(segments[0].toolCalls).toHaveLength(1);
  });

  test('text after the boundary opens a new bubble; each segment keeps its own tool calls', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'сегмент 1' } }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'STREAM', runId: 'r1', payload: { message: '', finishReason: 'TOOL_CALLS' } },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'getDocument', arguments: { id: 1 }, status: 'OK' } },
      },
      ctx,
    );
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'сегмент 2' } }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'STREAM', runId: 'r1', payload: { message: '', finishReason: 'TOOL_CALLS' } },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'searchDocs', arguments: { q: 'x' }, status: 'OK' } },
      },
      ctx,
    );
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'финал' } }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_DONE', runId: 'r1' }, ctx);

    const segments = aiOfRun(chat, 'r1');
    expect(segments.map((s) => s.text)).toEqual(['сегмент 1', 'сегмент 2', 'финал']);
    expect(segments[0].toolCalls.map((t) => t.name)).toEqual(['getDocument']);
    expect(segments[1].toolCalls.map((t) => t.name)).toEqual(['searchDocs']);
    expect(segments[2].toolCalls || []).toHaveLength(0);
    expect(segments.every((s) => s.sealed === undefined)).toBe(true); // finalize снял флаг
  });

  test('final TOOL_CALLS metas are distributed to the segments holding the matching live calls', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'сегмент 1' } }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'STREAM', runId: 'r1', payload: { message: '', finishReason: 'TOOL_CALLS' } },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'getDocument', arguments: { id: 1 }, status: 'OK' } },
      },
      ctx,
    );
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'финал' } }, ctx);
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALLS',
        runId: 'r1',
        payload: {
          toolCalls: [
            { name: 'getDocument', arguments: { id: 1 }, status: 'OK', callIndex: 0, resultMeta: { doc: 1 } },
          ],
        },
      },
      ctx,
    );

    const segments = aiOfRun(chat, 'r1');
    expect(segments).toHaveLength(2);
    // Мета ушла в первый сегмент (где живой вызов), а не в последний пузырь.
    expect(segments[0].toolCalls[0]).toMatchObject({ callIndex: 0, resultMeta: { doc: 1 } });
    expect(segments[1].toolCalls || []).toHaveLength(0);
  });

  test('a tool-calls-only segment (no text before the call) keeps its plates on the empty bubble', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'STREAM', runId: 'r1', payload: { message: '', finishReason: 'TOOL_CALLS' } },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'getDocument', arguments: { id: 1 }, status: 'OK' } },
      },
      ctx,
    );
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'ответ' } }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_DONE', runId: 'r1' }, ctx);

    const segments = aiOfRun(chat, 'r1');
    expect(segments).toHaveLength(2);
    expect(segments[0].text).toBe('');
    expect(segments[0].toolCalls).toHaveLength(1);
    expect(segments[1].text).toBe('ответ');
  });

  test('segments split on TOOL_CALL alone — finishReason=TOOL_CALLS never reaches the client', () => {
    // ToolCallingAdvisor отфильтровывает агрегированный tool-чанк (носитель finishReason),
    // поэтому границу сегмента даёт само событие TOOL_CALL.
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'смотрю коммит' } }, ctx);
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'getCommitDiff', arguments: { h: '1' }, status: 'STARTED' } },
      },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'getCommitDiff', arguments: { h: '1' }, status: 'OK' } },
      },
      ctx,
    );
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'итоговый анализ' } }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_DONE', runId: 'r1' }, ctx);

    const segments = aiOfRun(chat, 'r1');
    expect(segments.map((s) => s.text)).toEqual(['смотрю коммит', 'итоговый анализ']);
    expect(segments[0].toolCalls).toHaveLength(1);
    expect(segments[0].toolCalls[0].status).toBe('OK');
    expect(segments[1].toolCalls || []).toHaveLength(0);
  });

  test('whitespace-only chunk after a sealed segment does not open an empty bubble', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'сегмент 1' } }, ctx);
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'getDocument', arguments: { id: 1 }, status: 'OK' } },
      },
      ctx,
    );
    // Модель прислала «пустое сообщение» (одни переносы) между tool-циклами.
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: '\n\n' } }, ctx);
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: { toolCall: { name: 'searchDocs', arguments: { q: 'x' }, status: 'OK' } },
      },
      ctx,
    );

    const segments = aiOfRun(chat, 'r1');
    expect(segments).toHaveLength(1); // плашки не разорваны пустым пузырём
    expect(segments[0].toolCalls.map((t) => t.name)).toEqual(['getDocument', 'searchDocs']);
  });

  test('live TOOL_CALL carries resultMeta so doc-change refs are available mid-run', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(
      chat,
      {
        type: 'TOOL_CALL',
        runId: 'r1',
        payload: {
          toolCall: {
            name: 'updateDocument',
            arguments: { id: 5 },
            status: 'OK',
            callIndex: 0,
            resultMeta: { id: 5, descriptionVersion: 3 },
          },
        },
      },
      ctx,
    );
    expect(last(chat).toolCalls[0].resultMeta).toEqual({ id: 5, descriptionVersion: 3 });
  });

  test('RUN_DONE drops a trailing empty bubble without tool calls', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_DONE', runId: 'r1' }, ctx);
    expect(chat.messages.filter((m) => m.sender === 'ai')).toHaveLength(0);
  });
});
