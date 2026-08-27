import { transformPage, attachLeadingMetas, trimActiveRunTail } from './useChatMessages';

// Тесты маппинга «сырых» сообщений бэка в пузыри: раздельное сохранение сегментов
// (assistant с toolInvocationMetas, протокольные TOOL-строки) + legacy-крошки.

const meta = (name, callIndex = 0) => ({ name, arguments: {}, status: 'OK', callIndex });

describe('transformPage', () => {
  test('attaches segment metas to the segment own bubble with toolCallsRunId', () => {
    const { bubbles } = transformPage([
      { id: 1, content: 'вопрос', type: 'USER' },
      { id: 2, content: 'смотрю документ', type: 'ASSISTANT', runId: 'r1', toolInvocationMetas: [meta('getDocument')] },
      { id: 3, content: '', type: 'TOOL' },
      { id: 4, content: 'ответ', type: 'ASSISTANT' },
    ]);
    expect(bubbles).toHaveLength(3); // TOOL-строка не рендерится
    expect(bubbles[1]).toMatchObject({ sender: 'ai', text: 'смотрю документ', toolCallsRunId: 'r1' });
    expect(bubbles[1].toolCalls.map((t) => t.name)).toEqual(['getDocument']);
    expect(bubbles[2].toolCalls).toBeUndefined();
  });

  test('carries attached context items onto the user bubble', () => {
    const items = [{ kind: 'ATTACHMENT', ref: '12', label: 'report.md' }];
    const { bubbles } = transformPage([
      { id: 1, content: 'посмотри', type: 'USER', contextItems: items },
      { id: 2, content: 'смотрю', type: 'ASSISTANT' },
    ]);
    expect(bubbles[0].contextItems).toEqual(items);
    // Пузырь без приложенного поля не заводит вовсе — иначе чипы рисовались бы пустым рядом.
    expect(bubbles[1]).not.toHaveProperty('contextItems');
  });

  test('carries the answering model onto AI bubbles only', () => {
    const { bubbles } = transformPage([
      { id: 1, content: 'вопрос', type: 'USER', model: 'gpt-5' },
      { id: 2, content: 'ответ', type: 'ASSISTANT', model: 'gpt-5' },
      { id: 3, content: 'ответ постарше поля', type: 'ASSISTANT' },
    ]);
    // У вопроса модели быть не может: поле приезжает с ответа, а не с хода.
    expect(bubbles[0]).not.toHaveProperty('model');
    expect(bubbles[1].model).toBe('gpt-5');
    // Ответы старее поля подписи не получают — пустого места под ними тоже быть не должно.
    expect(bubbles[2]).not.toHaveProperty('model');
  });

  test('renders a tool-calls-only segment (empty text, has metas) as a plates-only bubble', () => {
    const { bubbles } = transformPage([
      { id: 1, content: '', type: 'ASSISTANT', runId: 'r1', toolInvocationMetas: [meta('searchDocs')] },
    ]);
    expect(bubbles).toHaveLength(1);
    expect(bubbles[0].text).toBe('');
    expect(bubbles[0].toolCalls).toHaveLength(1);
  });

  test('merges a tool-calls-only segment into the previous AI bubble of the same run', () => {
    const { bubbles } = transformPage([
      { id: 1, content: 'смотрю', type: 'ASSISTANT', runId: 'r1', toolInvocationMetas: [meta('getDocument', 0)] },
      { id: 2, content: '', type: 'ASSISTANT', runId: 'r1', toolInvocationMetas: [meta('searchDocs', 1)] },
      { id: 3, content: 'ответ', type: 'ASSISTANT', runId: 'r1' },
    ]);
    expect(bubbles).toHaveLength(2); // пустой сегмент не стал отдельным пузырём
    expect(bubbles[0].toolCalls.map((t) => t.name)).toEqual(['getDocument', 'searchDocs']);
    expect(bubbles[0].toolCallsRunId).toBe('r1');
  });

  test('does not merge a tool-calls-only segment into a bubble of a different run', () => {
    const { bubbles } = transformPage([
      { id: 1, content: 'старый ответ', type: 'ASSISTANT', runId: 'r0', toolInvocationMetas: [meta('getDocument', 0)] },
      { id: 2, content: '', type: 'ASSISTANT', runId: 'r1', toolInvocationMetas: [meta('searchDocs', 0)] },
    ]);
    expect(bubbles).toHaveLength(2);
    expect(bubbles[1].toolCalls.map((t) => t.name)).toEqual(['searchDocs']);
    expect(bubbles[1].toolCallsRunId).toBe('r1');
  });

  test('skips empty assistant rows without metas and protocol TOOL rows', () => {
    const { bubbles } = transformPage([
      { id: 1, content: '', type: 'ASSISTANT' },
      { id: 2, content: '', type: 'TOOL' },
    ]);
    expect(bubbles).toHaveLength(0);
  });

  test('legacy breadcrumb rows still attach their metas to the previous AI bubble', () => {
    const { bubbles } = transformPage([
      { id: 1, content: 'ответ', type: 'ASSISTANT' },
      {
        id: 2,
        content: 'Инструменты...\n{}',
        type: 'ASSISTANT',
        toolCalls: true,
        runId: 'r0',
        toolInvocationMetas: [meta('getDocument')],
      },
    ]);
    expect(bubbles).toHaveLength(1);
    expect(bubbles[0].toolCalls.map((t) => t.name)).toEqual(['getDocument']);
    expect(bubbles[0].toolCallsRunId).toBe('r0');
  });

  test('legacy breadcrumb at page start goes to leadingMetas and attaches upward', () => {
    const { bubbles, leadingMetas } = transformPage([
      { id: 2, content: 'x', type: 'ASSISTANT', toolCalls: true, toolInvocationMetas: [meta('searchDocs')] },
      { id: 3, content: 'дальше', type: 'USER' },
    ]);
    expect(leadingMetas).toHaveLength(1);

    const older = [{ mid: 1, sender: 'ai', text: 'старый ответ' }];
    const rest = attachLeadingMetas(older, leadingMetas);
    expect(rest).toHaveLength(0);
    expect(older[0].toolCalls.map((t) => t.name)).toEqual(['searchDocs']);
    expect(bubbles).toHaveLength(1);
  });
});

describe('transformPage: compaction notice', () => {
  test('renders the /compact notice row as its own bubble, not as an assistant reply', () => {
    const { bubbles } = transformPage([
      { id: 1, content: '/compact', type: 'USER' },
      {
        id: 2,
        content: '',
        type: 'ASSISTANT',
        timestamp: '2026-08-25T12:00:00',
        compact: { messages: 21, summaryChars: 4096, summaryId: 3 },
      },
    ]);
    expect(bubbles).toHaveLength(2);
    // dbId — адрес деталей: по нему модалка запрашивает текст сводки.
    expect(bubbles[1]).toMatchObject({
      sender: 'ai',
      dbId: 2,
      compact: { messages: 21, summaryChars: 4096 },
      timestamp: '2026-08-25T12:00:00',
    });
  });

  test('does not glue a following tool-only segment onto the notice', () => {
    const { bubbles } = transformPage([
      { id: 1, content: '', type: 'ASSISTANT', compact: { messages: 4, summaryChars: 100, summaryId: 0 } },
      { id: 2, content: '', type: 'ASSISTANT', runId: 'r1', toolInvocationMetas: [meta('searchDocs')] },
    ]);
    expect(bubbles[0].toolCalls).toBeUndefined();
    expect(bubbles[1].toolCalls.map((t) => t.name)).toEqual(['searchDocs']);
  });
});

describe('transformPage — ряд git-команды', () => {
  const gitEvent = { command: 'pull', project: 'kb', ok: true, output: 'Fast-forward', branch: 'main' };

  /**
   * Из истории карточка обязана получиться той же, что приезжает живым событием
   * (см. chatEventReducer, GIT_COMMAND). Разойдись эти два пути — карточка
   * меняла бы вид на перезагрузке, а ради того, чтобы этого не было, событие и
   * везёт `GitEventMeta` целиком.
   */
  test('turns the empty USER row into a card bubble rather than an empty message', () => {
    const { bubbles } = transformPage([
      { id: 1, content: 'вопрос', type: 'USER' },
      { id: 2, content: '', type: 'USER', gitEvent, timestamp: '2026-08-26T12:00:00' },
      { id: 3, content: 'ответ', type: 'ASSISTANT' },
    ]);

    expect(bubbles).toHaveLength(3);
    expect(bubbles[1]).toMatchObject({
      dbId: 2,
      sender: 'user',
      gitEvent,
      timestamp: '2026-08-26T12:00:00',
    });
    // Пузырём он не притворяется: текста у него нет, весь смысл — в событии.
    expect(bubbles[1].text).toBeUndefined();
  });

  /**
   * Крошка вызовов к ряду команды не липнет: он не сегмент ответа, и плашки
   * чужого хода на нём выглядели бы как его собственные.
   */
  test('a command row never collects the tool-call crumbs of a neighbouring answer', () => {
    const { bubbles } = transformPage([
      { id: 1, content: 'вопрос', type: 'USER' },
      { id: 2, content: '', type: 'USER', gitEvent },
      { id: 3, content: '', type: 'ASSISTANT', runId: 'r1', toolInvocationMetas: [meta('getDocument')] },
    ]);

    expect(bubbles.find((b) => b.gitEvent).toolCalls).toBeUndefined();
    expect(bubbles.at(-1)).toMatchObject({ sender: 'ai' });
    expect(bubbles.at(-1).toolCalls.map((t) => t.name)).toEqual(['getDocument']);
  });
});

describe('trimActiveRunTail', () => {
  const u = (text) => ({ mid: 1, sender: 'user', text });
  const a = (text) => ({ mid: 2, sender: 'ai', text });

  test('drops the assistant tail after the last user message (the in-flight run partials)', () => {
    // Перезагрузка посреди генерации: у последнего хода уже сохранены сегменты в БД.
    const bubbles = [u('q1'), a('a1'), u('q2'), a('преамбула'), a('ещё сегмент')];
    expect(trimActiveRunTail(bubbles)).toEqual([u('q1'), a('a1'), u('q2')]);
  });

  test('keeps history intact when the last user message has no assistant tail yet', () => {
    const bubbles = [u('q1'), a('a1'), u('q2')];
    expect(trimActiveRunTail(bubbles)).toEqual(bubbles);
  });

  /**
   * Ряд команды — тоже USER, но вопросом не является: обрезав хвост по нему,
   * оставили бы на экране ответ, который стрим сейчас перепишет заново.
   */
  test('does not mistake a command row for the question the run is answering', () => {
    const git = { mid: 3, sender: 'user', gitEvent: { command: 'pull', ok: true, output: '' } };
    const bubbles = [u('q1'), a('a1'), u('q2'), git, a('преамбула')];
    expect(trimActiveRunTail(bubbles)).toEqual([u('q1'), a('a1'), u('q2'), git]);
  });

  /**
   * Вопрос, отправленный во время прогона, ход не открывает — иначе сегменты, написанные
   * до него, остались бы на экране и продублировались бы реплеем. Сам он срезается наравне
   * с ними: событие USER_MESSAGE опубликовано внутри активного прогона и вернёт его.
   */
  test('does not mistake a mid-run question for the question the run is answering', () => {
    const mid = { mid: 4, sender: 'user', text: 'и добавь тесты', interjection: true };
    const bubbles = [u('q1'), a('a1'), u('q2'), a('преамбула'), mid, a('продолжение')];
    expect(trimActiveRunTail(bubbles)).toEqual([u('q1'), a('a1'), u('q2')]);
  });

  test('leaves the page untouched when it contains no user message (older-page run)', () => {
    const bubbles = [a('хвост старого ответа'), a('ещё')];
    expect(trimActiveRunTail(bubbles)).toEqual(bubbles);
  });
});
