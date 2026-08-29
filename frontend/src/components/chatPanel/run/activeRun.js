// Занятость чата, спрошенная у бэка (GET /runs/active), — разобранная в те же поля,
// которыми её держит состояние чата. Одно место на все три вопроса к этому эндпоинту:
// восстановление чата после перезагрузки, сверку зависшего прогона и проверку «а не
// стартовал ли прогон, чей ответ до нас не доехал».
//
// Ошибку запроса не глотает: «не смогли спросить» и «чат свободен» — разные ответы, и
// что с ними делать, решает вызывающий.

import chatApi from '@/api/chatApi';
import { RUN_KIND } from '@/constants/runKind';

/**
 * Свободный чат — тот же набор полей, чтобы патч состояния был один и тот же.
 *
 * runStateUnknown — «занятость спросить не удалось». Свободный чат от неотвеченного вопроса
 * отличается именно им: показанная занятость обязана сама себя чинить, а «свободен» по ошибке
 * сети — это чат без текста ответа, без «Стоп» и с композером, чей вопрос получит 409.
 */
export const IDLE_RUN_STATE = { runId: null, runKind: null, runStartedAt: null, runStateUnknown: false };

/**
 * Занятость чата с бэка: state — поля для состояния чата, replayTruncated — успел ли хаб
 * потерять часть событий идущего прогона (реплей начала ответа уже не принесёт).
 */
export const fetchRunState = (chatId) =>
  chatApi.getActiveRun(chatId).then((active) => ({
    state: toRunState(active),
    replayTruncated: !!active?.replayTruncated,
  }));

const toRunState = (active) => {
  const runId = active?.runId || null;
  if (!runId) return IDLE_RUN_STATE;
  return {
    ...IDLE_RUN_STATE,
    runId,
    // Занятость без названного вида — генерация: ею она бывает почти всегда, а цена ошибки
    // здесь мала — лишняя кнопка «остановить», которая просто ничего не сделает.
    runKind: active.kind === RUN_KIND.OPERATION ? RUN_KIND.OPERATION : RUN_KIND.GENERATION,
    // elapsedMs — сколько прогон уже идёт по часам сервера: длительность, а не момент
    // старта, поэтому перекос часов клиента и сервера якорь таймера не портит. У операции
    // его нет — замерять там нечего, и таймер она не показывает.
    runStartedAt: active.elapsedMs != null ? Date.now() - active.elapsedMs : null,
  };
};
