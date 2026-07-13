// ─── Base API client ────────────────────────────────────────────────────────
// Единая точка выполнения fetch-запросов. Все API-модули (chatApi, phrasesApi,
// attachmentApi, knowledgeBasePanel/api) используют эти примитивы.
//
// Соглашение об ошибках:
//   • err.type = 'network'  — нет сети / CORS / DNS (fetch rejects)
//   • err.type = 'http'     — сервер ответил, но !response.ok
//   • err.status            — HTTP-статус (только при type='http')

/**
 * Базовый запрос. Бросает типизированный Error на сетевую или HTTP-ошибку.
 * Тело ответа парсится как JSON; пустой ответ (204) возвращает null.
 */
export async function request(url, options) {
  let res;
  try {
    res = await fetch(url, options);
  } catch (e) {
    // Отмена через AbortSignal — не сетевая ошибка: пробрасываем как есть,
    // чтобы вызывающий код мог отличить её по err.name === 'AbortError'.
    if (e.name === 'AbortError') throw e;
    const err = new Error(`Network error: ${e.message}`);
    err.type = 'network';
    throw err;
  }
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`);
    err.type = 'http';
    err.status = res.status;
    throw err;
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

/**
 * Как request(), но возвращает сырой Response вместо парсинга.
 * Используется для write-эндпоинтов, где вызывающий код проверяет res.ok
 * и читает тело самостоятельно (например, текст ошибки).
 */
export async function requestRaw(url, options) {
  try {
    return await fetch(url, options);
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    const err = new Error(`Network error: ${e.message}`);
    err.type = 'network';
    throw err;
  }
}

/** Хелпер: заголовки + сериализованное тело для JSON-запросов. */
export const json = (body) => ({
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});
