import { transformPage, attachLeadingMetas } from './useChatMessages';

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

  test('renders a tool-calls-only segment (empty text, has metas) as a plates-only bubble', () => {
    const { bubbles } = transformPage([
      { id: 1, content: '', type: 'ASSISTANT', runId: 'r1', toolInvocationMetas: [meta('searchDocs')] },
    ]);
    expect(bubbles).toHaveLength(1);
    expect(bubbles[0].text).toBe('');
    expect(bubbles[0].toolCalls).toHaveLength(1);
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
