import { applyChatEvent } from './chatEventReducer';

const ctx = {
  isLocal: () => false,
  stoppedLabel: '[stopped]',
  errorLabel: 'Ошибка',
  interruptedNote: '\n_прервано_',
  compactingLabel: 'сжимаю…',
  compactErrorLabel: 'сжать не вышло',
};

const userChat = (id = 'c') => ({ id, messages: [{ text: 'вопрос', sender: 'user' }], runId: null });
const last = (chat) => chat.messages[chat.messages.length - 1];

describe('applyChatEvent', () => {
  test('RUN_STARTED appends an empty AI bubble tagged with runId', () => {
    const chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    expect(chat.runId).toBe('r1');
    expect(last(chat)).toMatchObject({ sender: 'ai', runId: 'r1', text: '' });
  });

  test('RUN_STARTED marks the bubble with the run model, and new segments inherit it', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1', payload: { model: 'gpt-5' } }, ctx);
    expect(last(chat).model).toBe('gpt-5');
    // Граница сегмента tool-цикла открывает новый пузырь — того же прогона, значит той же модели.
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'до вызова' } }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { finishReason: 'TOOL_CALLS' } }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'после вызова' } }, ctx);
    expect(last(chat).text).toBe('после вызова');
    expect(last(chat).model).toBe('gpt-5');
  });

  test('RUN_STARTED fills the model into a bubble that already exists (optimistic/replay)', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    expect(last(chat).model).toBeUndefined();
    chat = applyChatEvent(chat, { type: 'RUN_STARTED', runId: 'r1', payload: { model: 'gpt-5' } }, ctx);
    expect(chat.messages.filter((m) => m.sender === 'ai')).toHaveLength(1);
    expect(last(chat).model).toBe('gpt-5');
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
    // Ответ уже начался — повторять ход нельзя, кнопки под пузырём не будет.
    expect(ai.retryMode).toBeUndefined();
  });

  test('RUN_ERROR before any chunk creates an error bubble with the error label', () => {
    const chat = applyChatEvent({ ...userChat(), runId: 'r2' }, { type: 'RUN_ERROR', runId: 'r2', payload: {} }, ctx);
    const ai = last(chat);
    // Модель не выдала ничего — вопрос в истории остался неотвеченным, повтор
    // просто запустит прогон заново, без второго USER-сообщения.
    expect(ai).toMatchObject({ sender: 'ai', error: true, text: 'Ошибка', retryMode: 'continue' });
  });

  test('RUN_ERROR after a tool call offers no retry even without any text', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'TOOL_CALL', runId: 'r1', payload: { toolCall: { name: 'listFiles', status: 'OK' } } },
      ctx,
    );
    chat = applyChatEvent(chat, { type: 'RUN_ERROR', runId: 'r1', payload: {} }, ctx);

    // Инструмент отработал — это уже начатый ответ (и, возможно, побочные эффекты).
    expect(last(chat).error).toBe(true);
    expect(last(chat).retryMode).toBeUndefined();
  });

  test('RUN_ERROR in a later segment offers no retry, even if that segment is empty', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'думаю' } }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'TOOL_CALL', runId: 'r1', payload: { toolCall: { name: 'listFiles', status: 'OK' } } },
      ctx,
    );
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'вторая часть' } }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_ERROR', runId: 'r1', payload: {} }, ctx);

    // Ошибка садится на последний пузырь прогона, но решение смотрит на весь прогон.
    expect(chat.messages.filter((m) => m.retryMode)).toHaveLength(0);
  });

  test('USER_MESSAGE from another tab carries the attached context items', () => {
    const items = [{ kind: 'ATTACHMENT', ref: '12', label: 'report.md' }];
    const chat = applyChatEvent(
      { id: 'c', messages: [], runId: null },
      { type: 'USER_MESSAGE', payload: { id: 5, text: 'посмотри', contextItems: items } },
      ctx,
    );
    expect(last(chat)).toMatchObject({ sender: 'user', dbId: 5, contextItems: items });
  });

  test('USER_MESSAGE backfills context items onto a bubble that arrived without them', () => {
    const items = [{ kind: 'ATTACHMENT', ref: '12', label: 'report.md' }];
    // Пузырь из истории: догрузился без чипов, эхо прогона их приносит.
    const chat = applyChatEvent(
      { id: 'c', messages: [{ text: 'посмотри', sender: 'user', dbId: 5 }], runId: null },
      { type: 'USER_MESSAGE', payload: { id: 5, text: 'посмотри', contextItems: items } },
      ctx,
    );
    expect(chat.messages).toHaveLength(1);
    expect(last(chat).contextItems).toEqual(items);
  });

  test('USER_MESSAGE from another tab carries the project switch', () => {
    const chat = applyChatEvent(
      { id: 'c', messages: [], runId: null },
      { type: 'USER_MESSAGE', payload: { id: 5, text: 'посмотри', project: 'billing', projectSwitchFrom: 'kb' } },
      ctx,
    );
    expect(last(chat)).toMatchObject({ sender: 'user', projectSwitch: { from: 'kb', to: 'billing' } });
  });

  test('USER_MESSAGE own echo backfills the project switch onto the optimistic bubble', () => {
    // Смену проекта решает бэкенд — оптимистичный пузырь её не знал; эхо дописывает.
    const local = { ...ctx, isLocal: () => true };
    const chat = applyChatEvent(
      { id: 'c', messages: [{ text: 'посмотри', sender: 'user', dbId: 5 }], runId: null },
      {
        type: 'USER_MESSAGE',
        clientMsgId: 'mine',
        payload: { id: 5, text: 'посмотри', project: 'billing', projectSwitchFrom: 'kb' },
      },
      local,
    );
    expect(chat.messages).toHaveLength(1);
    expect(last(chat).projectSwitch).toEqual({ from: 'kb', to: 'billing' });
  });

  test('USER_MESSAGE own echo without a switch changes nothing', () => {
    const local = { ...ctx, isLocal: () => true };
    const before = { id: 'c', messages: [{ text: 'посмотри', sender: 'user', dbId: 5 }], runId: null };
    const chat = applyChatEvent(
      before,
      { type: 'USER_MESSAGE', clientMsgId: 'mine', payload: { id: 5, text: 'посмотри' } },
      local,
    );
    expect(chat).toBe(before);
  });

  // Повтор прогона считает смену заново — эхо для плашки авторитетно, а не «дополняет пустое».
  test('USER_MESSAGE re-aims the project switch of a retried question', () => {
    const chat = applyChatEvent(
      {
        id: 'c',
        messages: [{ text: 'посмотри', sender: 'user', dbId: 5, projectSwitch: { from: 'kb', to: 'billing' } }],
        runId: null,
      },
      { type: 'USER_MESSAGE', payload: { id: 5, text: 'посмотри', project: 'docs', projectSwitchFrom: 'kb' } },
      ctx,
    );
    expect(last(chat).projectSwitch).toEqual({ from: 'kb', to: 'docs' });
  });

  test('USER_MESSAGE clears the project switch when the retry went back to the original project', () => {
    const chat = applyChatEvent(
      {
        id: 'c',
        messages: [{ text: 'посмотри', sender: 'user', dbId: 5, projectSwitch: { from: 'kb', to: 'billing' } }],
        runId: null,
      },
      { type: 'USER_MESSAGE', payload: { id: 5, text: 'посмотри', project: 'kb' } },
      ctx,
    );
    expect(last(chat).projectSwitch).toBeNull();
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

  test('USER_MESSAGE replay after a mid-run reload drops the persisted run tail (no dup)', () => {
    // Перезагрузка посреди генерации: из БД уже пришли вопрос + сохранённый сегмент
    // прогона (плашки инструментов). Реплей начинается с USER_MESSAGE того же вопроса.
    const chat = {
      id: 'c',
      runId: null,
      messages: [
        { mid: 1, text: 'как работает tool_calls?', sender: 'user' },
        { mid: 2, text: '', sender: 'ai', toolCalls: [{ name: 'searchDocuments', status: 'OK' }] },
      ],
    };
    // 1) USER_MESSAGE (не local — после reload localClientIds пуст) срезает хвост прогона.
    let next = applyChatEvent(
      chat,
      { type: 'USER_MESSAGE', clientMsgId: 'x', payload: { text: 'как работает tool_calls?' } },
      ctx,
    );
    expect(next.messages).toHaveLength(1);
    expect(next.messages[0]).toMatchObject({ sender: 'user', text: 'как работает tool_calls?' });
    // 2) реплей пересобирает прогон один раз.
    next = applyChatEvent(next, { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    next = applyChatEvent(
      next,
      { type: 'TOOL_CALL', runId: 'r1', payload: { toolCall: { name: 'searchDocuments', status: 'OK' } } },
      ctx,
    );
    const ai = next.messages.filter((m) => m.sender === 'ai');
    expect(next.messages.filter((m) => m.sender === 'user')).toHaveLength(1); // вопрос не задвоился
    expect(ai).toHaveLength(1); // и сегмент прогона не задвоился
    expect(ai[0].toolCalls.map((t) => t.name)).toEqual(['searchDocuments']);
  });

  test('USER_MESSAGE with a genuinely new question is appended (last question differs)', () => {
    const chat = {
      id: 'c',
      runId: null,
      messages: [
        { mid: 1, text: 'первый вопрос', sender: 'user' },
        { mid: 2, text: 'ответ', sender: 'ai' },
      ],
    };
    const next = applyChatEvent(
      chat,
      { type: 'USER_MESSAGE', clientMsgId: 'other-tab', payload: { text: 'второй вопрос' } },
      ctx,
    );
    expect(next.messages.filter((m) => m.sender === 'user').map((m) => m.text)).toEqual([
      'первый вопрос',
      'второй вопрос',
    ]);
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

  test('USER_MESSAGE carries the persisted message id onto the bubble', () => {
    // Бэк сохраняет вопрос до обращения к модели, поэтому id есть уже в событии —
    // пузырь получает якорь для поиска по чату сразу, а не после перезагрузки.
    const chat = { id: 'c', runId: null, messages: [] };
    const next = applyChatEvent(
      chat,
      { type: 'USER_MESSAGE', clientMsgId: 'other-tab', payload: { id: 42, text: 'вопрос' } },
      ctx,
    );
    expect(next.messages).toHaveLength(1);
    expect(next.messages[0]).toMatchObject({ sender: 'user', text: 'вопрос', dbId: 42 });
  });

  test('USER_MESSAGE backfills dbId on a matching bubble loaded without it', () => {
    const chat = {
      id: 'c',
      runId: null,
      messages: [{ mid: 1, text: 'вопрос', sender: 'user' }],
    };
    const next = applyChatEvent(
      chat,
      { type: 'USER_MESSAGE', clientMsgId: 'other-tab', payload: { id: 7, text: 'вопрос' } },
      ctx,
    );
    expect(next.messages).toHaveLength(1);
    expect(next.messages[0].dbId).toBe(7);
  });

  test('USER_MESSAGE with a different id is a new question even when the text repeats', () => {
    // «Повторить» шлёт тот же текст: текстовая сверка приняла бы это за уже показанный
    // вопрос и молча проглотила бы новый ход.
    const chat = {
      id: 'c',
      runId: null,
      messages: [{ mid: 1, dbId: 10, text: 'повтори', sender: 'user' }],
    };
    const next = applyChatEvent(
      chat,
      { type: 'USER_MESSAGE', clientMsgId: 'other-tab', payload: { id: 11, text: 'повтори' } },
      ctx,
    );
    expect(next.messages.filter((m) => m.sender === 'user').map((m) => m.dbId)).toEqual([10, 11]);
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

  // Сжатие контекста: занятость чата та же, что у прогона, но пузырь один на всю
  // операцию — плашка «сжимаю…» превращается в строку результата.
  test('COMPACT_STARTED blocks the chat and shows one notice bubble', () => {
    const chat = applyChatEvent(userChat(), { type: 'COMPACT_STARTED', runId: 'r1' }, ctx);
    expect(chat.runId).toBe('r1');
    expect(chat.compacting).toBe(true);
    expect(last(chat)).toMatchObject({ sender: 'ai', runId: 'r1', text: 'сжимаю…' });
  });

  test('COMPACT_DONE turns that bubble into the notice and unblocks the chat', () => {
    let chat = applyChatEvent(userChat(), { type: 'COMPACT_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(
      chat,
      {
        type: 'COMPACT_DONE',
        runId: 'r1',
        payload: { messageId: 77, messages: 42, summaryChars: 1024, createdAt: '2026-08-25T12:00:00' },
      },
      ctx,
    );
    expect(chat.messages.filter((m) => m.sender === 'ai')).toHaveLength(1);
    // Плашка живёт полем compact, а не текстом: рисует её CompactNotice, тот же компонент,
    // что и после перезагрузки страницы.
    expect(last(chat)).toMatchObject({
      dbId: 77,
      text: '',
      compact: { messages: 42, summaryChars: 1024 },
      timestamp: '2026-08-25T12:00:00',
    });
    expect(last(chat).runId).toBeUndefined();
    expect(chat.runId).toBeNull();
    expect(chat.compacting).toBe(false);
  });

  test('COMPACT_DONE survives finalize even though the notice bubble has no text', () => {
    let chat = applyChatEvent(userChat(), { type: 'COMPACT_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'COMPACT_DONE', runId: 'r1', payload: { messageId: 5, messages: 3 } }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_DONE', runId: 'r1' }, ctx);
    expect(chat.messages.filter((m) => m.compact)).toHaveLength(1);
  });

  // ─── Git-команда пользователя ──────────────────────────────────────────────
  // Прогона нет: команду выполнил человек, а не модель. Ряд просто дописывается
  // в конец — тем же, чем приехал бы из истории после перезагрузки.
  test('GIT_COMMAND appends the command row without touching the run', () => {
    const chat = applyChatEvent(
      userChat(),
      {
        type: 'GIT_COMMAND',
        payload: {
          id: 91,
          createdAt: '2026-08-26T12:00:00',
          event: { command: 'pull', ok: true, output: 'Fast-forward', branch: 'main' },
        },
      },
      ctx,
    );

    expect(last(chat)).toMatchObject({
      dbId: 91,
      sender: 'user',
      gitEvent: { command: 'pull', ok: true },
      timestamp: '2026-08-26T12:00:00',
    });
    // Прогон командой не заводится и не закрывается: он к ней отношения не имеет.
    expect(chat.runId).toBe(userChat().runId);
  });

  /**
   * Вкладка, запустившая команду, получает своё же событие обратно, а после
   * переподключения ещё и переиграет пропущенные — ряд обязан остаться один.
   */
  test('GIT_COMMAND replayed for the same row does not double it', () => {
    const ev = {
      type: 'GIT_COMMAND',
      payload: { id: 91, event: { command: 'pull', ok: true, output: '' } },
    };
    let chat = applyChatEvent(userChat(), ev, ctx);
    chat = applyChatEvent(chat, ev, ctx);

    expect(chat.messages.filter((m) => m.gitEvent)).toHaveLength(1);
  });

  /**
   * «Вопрос → неудачный прогон → git-команда → Повторить». Эхо повтора приходит без clientMsgId,
   * и сверять его надо с последним ВОПРОСОМ, а не с последним USER-рядом: последний USER здесь —
   * карточка git.
   */
  test('USER_MESSAGE echo looks past a git row to find the question it repeats', () => {
    const chat = applyChatEvent(
      {
        id: 'c',
        messages: [
          { text: 'вопрос', sender: 'user', dbId: 7 },
          { text: 'Ошибка', sender: 'ai', error: true },
          { sender: 'user', dbId: 91, gitEvent: { command: 'pull', ok: true, output: '' } },
        ],
        runId: null,
      },
      { type: 'USER_MESSAGE', runId: 'r2', payload: { id: 7, text: 'вопрос' } },
      ctx,
    );

    // Вопрос остался один, пузырь с прошлой ошибкой срезан…
    expect(chat.messages.filter((m) => m.sender === 'user' && !m.gitEvent)).toHaveLength(1);
    expect(chat.messages.some((m) => m.error)).toBe(false);
    // …а карточка git пережила срез: реплей событий прогона её не вернёт.
    expect(chat.messages.filter((m) => m.gitEvent)).toHaveLength(1);
  });

  /** То же самое для своего оптимистичного эха: плашка обязана лечь на вопрос, а не на карточку. */
  test('local USER_MESSAGE echo patches the question, not the git row above it', () => {
    const chat = applyChatEvent(
      {
        id: 'c',
        messages: [
          { text: 'вопрос', sender: 'user' },
          { sender: 'user', gitEvent: { command: 'pull', ok: true, output: '' } },
        ],
        runId: null,
      },
      {
        type: 'USER_MESSAGE',
        clientMsgId: 'local-1',
        payload: { text: 'вопрос', projectSwitchFrom: 'kb', project: 'docs' },
      },
      { ...ctx, isLocal: () => true },
    );

    expect(chat.messages[0].projectSwitch).toEqual({ from: 'kb', to: 'docs' });
    expect(chat.messages[1].projectSwitch).toBeUndefined();
  });

  test('COMPACT_ERROR flags the bubble and unblocks the chat without offering a retry', () => {
    let chat = applyChatEvent(userChat(), { type: 'COMPACT_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'COMPACT_ERROR', runId: 'r1', payload: { message: 'boom' } }, ctx);
    expect(last(chat)).toMatchObject({ error: true, text: 'сжать не вышло' });
    expect(last(chat).retryMode).toBeUndefined();
    expect(chat.runId).toBeNull();
    expect(chat.compacting).toBe(false);
  });

  test('RUN_DONE drops a trailing empty bubble without tool calls', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'RUN_DONE', runId: 'r1' }, ctx);
    expect(chat.messages.filter((m) => m.sender === 'ai')).toHaveLength(0);
  });
  // ─── Сообщение, отправленное во время ответа ───────────────────────────────

  test('MESSAGE_QUEUED shows a waiting bubble in the tabs that did not send it', () => {
    const chat = applyChatEvent(
      userChat(),
      { type: 'MESSAGE_QUEUED', runId: 'r1', clientMsgId: 'm1', payload: { id: 7, text: 'и добавь тесты' } },
      ctx,
    );
    expect(last(chat)).toMatchObject({ sender: 'user', text: 'и добавь тесты', queued: true, clientMsgId: 'm1' });
  });

  test("MESSAGE_QUEUED does not duplicate the sending tab's own optimistic bubble", () => {
    const local = { ...ctx, isLocal: (id) => id === 'm1' };
    const chat = applyChatEvent(
      userChat(),
      { type: 'MESSAGE_QUEUED', runId: 'r1', clientMsgId: 'm1', payload: { text: 'и добавь тесты' } },
      local,
    );
    expect(chat.messages).toHaveLength(1);
  });

  /**
   * Доставка внутрь прогона: пузырь становится настоящим, а продолжение ответа обязано уйти
   * ПОД него. Иначе текст следующей итерации дописался бы в сегмент над вопросом — и туда же
   * прилипли бы плашки инструментов, которые этот вопрос уже застали.
   */
  test('USER_MESSAGE with interjection delivers the waiting bubble and opens a segment below it', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1', payload: { model: 'gpt-5' } }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'начал искать' } }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'MESSAGE_QUEUED', runId: 'r1', clientMsgId: 'm1', payload: { text: 'и добавь тесты' } },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      {
        type: 'USER_MESSAGE',
        runId: 'r1',
        clientMsgId: 'm1',
        payload: { id: 42, text: 'и добавь тесты', interjection: true },
      },
      ctx,
    );

    const delivered = chat.messages.find((m) => m.dbId === 42);
    expect(delivered).toMatchObject({ sender: 'user', interjection: true });
    expect(delivered.queued).toBeUndefined();
    // Сегмент, написанный ДО вопроса, остался на месте и не потерял текста.
    expect(chat.messages.find((m) => m.text === 'начал искать')).toBeTruthy();

    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'продолжаю' } }, ctx);
    expect(last(chat)).toMatchObject({ sender: 'ai', text: 'продолжаю', model: 'gpt-5' });
  });

  /**
   * Главная ловушка общего пути USER_MESSAGE: он опознаёт эхо по тексту и срезает всё, что
   * стоит после совпавшего вопроса. Перебивка, повторяющая текст вопроса, снесла бы этим
   * идущий ответ.
   */
  test('an interjection repeating the question text does not cut the answer written so far', () => {
    let chat = applyChatEvent(userChat(), { type: 'RUN_STARTED', runId: 'r1' }, ctx);
    chat = applyChatEvent(chat, { type: 'STREAM', runId: 'r1', payload: { message: 'уже написанное' } }, ctx);
    chat = applyChatEvent(
      chat,
      { type: 'USER_MESSAGE', runId: 'r1', payload: { id: 42, text: 'вопрос', interjection: true } },
      ctx,
    );
    expect(chat.messages.some((m) => m.text === 'уже написанное')).toBe(true);
    expect(chat.messages.filter((m) => m.sender === 'user')).toHaveLength(2);
  });

  /** Доставка после конца прогона — обычный вопрос: флага нет, но «ожидание» снять надо. */
  test('a plain delivery clears the waiting bubble without splitting anything', () => {
    let chat = applyChatEvent(
      userChat(),
      { type: 'MESSAGE_QUEUED', runId: 'r1', clientMsgId: 'm1', payload: { text: 'и добавь тесты' } },
      ctx,
    );
    chat = applyChatEvent(
      chat,
      { type: 'USER_MESSAGE', clientMsgId: 'm1', payload: { id: 42, text: 'и добавь тесты' } },
      ctx,
    );
    expect(last(chat)).toMatchObject({ sender: 'user', dbId: 42 });
    expect(last(chat).queued).toBeUndefined();
    expect(last(chat).interjection).toBeUndefined();
  });

  test('a replayed interjection is applied once', () => {
    const ev = { type: 'USER_MESSAGE', runId: 'r1', payload: { id: 42, text: 'и добавь тесты', interjection: true } };
    let chat = applyChatEvent(userChat(), ev, ctx);
    chat = applyChatEvent(chat, ev, ctx);
    expect(chat.messages.filter((m) => m.dbId === 42)).toHaveLength(1);
  });
});
