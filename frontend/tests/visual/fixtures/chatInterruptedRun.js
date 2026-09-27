/**
 * Фикстура оборванных прогонов, как их отдаёт история после перезагрузки (GET /chats/{id}/messages)
 * — сырые ряды, а не пузыри: стенд прогоняет их через transformPage, и кадр проверяет заодно, что
 * служебная метка бэкенда ([stopped] / [error]) показана той же подписью, что и вживую.
 *
 * Оба прогона успели поработать инструментами и не написали текста: метку бэкенд пишет отдельным
 * рядом, и итог прогона стоит на нём (ChatRunService.persistPartial). Без этого ряда последним был
 * бы ответ из одних вызовов — без подписи и без плашки.
 */
const call = (callId, callIndex, name, args, gist) => ({
  name,
  arguments: args,
  status: 'OK',
  hasDetails: true,
  callIndex,
  callId,
  resultGist: gist,
});

const usage = (context, output, prompt, calls) => ({
  contextTokens: context,
  basePromptTokens: 9800,
  toolTokens: context - 9800 - output,
  outputTokens: output,
  promptTokens: prompt,
  cacheReadTokens: 0,
  cacheWriteTokens: 0,
  totalTokens: prompt + output,
  modelCalls: calls,
});

export const stoppedAndFailedAfterTools = [
  { id: 601, type: 'USER', content: 'Найди, где выставляется владелец продукта' },
  {
    id: 602,
    type: 'ASSISTANT',
    content: '',
    runId: 'run-1',
    model: 'gpt-5',
    contextTokens: 10400,
    toolInvocationMetas: [
      call('c1', 0, 'searchCodebase', { pattern: 'OwnershipTypeClass' }, '3 совпадения в 2 файлах'),
      call('c2', 1, 'getFileContent', { path: 'metadata-ingestion/src/datahub/emitter/mce_builder.py' }, 'python · 486 строк'),
    ],
  },
  { id: 605, type: 'ASSISTANT', content: '[stopped]', runId: 'run-1', model: 'gpt-5', usage: usage(10400, 120, 10280, 1) },
  { id: 606, type: 'USER', content: 'Продолжи с OwnerUtils.java' },
  {
    id: 607,
    type: 'ASSISTANT',
    content: '',
    runId: 'run-2',
    model: 'gpt-5',
    contextTokens: 12900,
    toolInvocationMetas: [
      call('c3', 0, 'getFileContent', { path: 'datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/resolvers/mutate/util/OwnerUtils.java' }, 'java · 265 строк'),
    ],
  },
  { id: 609, type: 'ASSISTANT', content: '[error]', runId: 'run-2', model: 'gpt-5', usage: usage(12900, 80, 25300, 2) },
];
