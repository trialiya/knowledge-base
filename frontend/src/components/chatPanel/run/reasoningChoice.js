// ─── Уровень рассуждений чата ───────────────────────────────────────────────
// Уровни — свойство модели (kb.chat.*.reasoning, GET /api/chats/models), а выбор —
// свойство чата (chat.reasoning). Выбор переживает смену модели: у новой модели
// такого уровня может не быть, и тогда чат идёт на её умолчании — ровно так же
// решает бэкенд (ChatModelProperties#reasoningLevel). Эти функции — одно место,
// где фронт отвечает на тот же вопрос, чтобы селектор показывал то, на чём
// прогон действительно пойдёт.

/** Пункт «по умолчанию» у модели без reasoning.default: ничего не отправлять. */
export const DEFAULT_REASONING = '';

/**
 * Уровни модели: { default, levels } или null, если у модели их нет (селектора нет).
 *
 * @param {object|null} modelConfig ответ GET /api/chats/models
 * @param {?string} modelId id модели; null/пусто — модель по умолчанию
 */
export function reasoningOf(modelConfig, modelId) {
  const def = modelConfig?.defaultModel;
  if (!def) return null;
  const option =
    !modelId || modelId === def.id ? def : (modelConfig.models || []).find((m) => m.id === modelId) || null;
  const reasoning = option?.reasoning;
  return reasoning?.levels?.length ? reasoning : null;
}

/**
 * Есть ли выбранный уровень у этой модели — тогда он и уходит в запрос. Иначе в запрос
 * не уходит ничего, и бэкенд берёт сохранённый у чата выбор, а за ним — умолчание модели.
 */
export function isLevelOf(reasoning, levelId) {
  return !!levelId && !!reasoning?.levels.some((l) => l.id === levelId);
}

/**
 * Уровень, на котором пойдёт прогон: выбранный, если он у модели есть, иначе её
 * умолчание, иначе DEFAULT_REASONING.
 */
export function effectiveReasoning(reasoning, chosen) {
  if (!reasoning) return DEFAULT_REASONING;
  if (isLevelOf(reasoning, chosen)) return chosen;
  return reasoning.default || DEFAULT_REASONING;
}

/**
 * Что отправить в поле reasoning:
 *   • выбор чата, если он у модели есть;
 *   • '' — у чата выбора нет (никогда не было или сброшен к «по умолчанию»): явный сброс.
 *     Сброс едет с самим сообщением, а не держится на отдельном PUT, который мог не дойти
 *     или прийти позже отправки — иначе бэк ответил бы на прежнем уровне;
 *   • null («не названо») — выбор есть, но у этой модели такого уровня нет. Бэк возьмёт
 *     умолчание модели, а выбор чата сохранит. Умолчание модели явно не шлём: бэк записал
 *     бы его чату как выбор, и вернувшись на модель с прежним уровнем, чат бы его потерял.
 */
export function reasoningForSend(modelConfig, modelId, chosen) {
  if (!chosen) return DEFAULT_REASONING;
  return isLevelOf(reasoningOf(modelConfig, modelId), chosen) ? chosen : null;
}

/**
 * Название уровня без приставки — для вкладки «Инфо», где строка и так подписана. Подпись
 * из конфигурации важнее словаря: её дали этому уровню нарочно.
 *
 * @param {function} t i18n-функция namespace `chat`
 * @param {?object} reasoning уровни модели (reasoningOf)
 * @param {string} id уровень (effectiveReasoning)
 */
export function reasoningLevelName(t, reasoning, id) {
  if (id === DEFAULT_REASONING) return t('reasoning.default');
  const level = reasoning?.levels.find((l) => l.id === id);
  return level?.label || t(`reasoning.levels.${id}`, { defaultValue: id });
}
