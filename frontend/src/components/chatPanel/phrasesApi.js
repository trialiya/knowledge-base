// src/components/chatPanel/phrasesApi.js
//
// Тонкая обёртка над REST-эндпоинтами библиотеки фраз.
// Публичные ручки (для чата) — /api/phrases; админские — /api/admin/phrases.

/**
 * Единая точка запроса: ловит и сетевые сбои (fetch reject — нет сети/CORS),
 * и HTTP-ошибки (!ok), приводя их к одному Error с понятным message. Вызовы
 * наверху (PhrasesSettings/Phrases) ловят его и показывают баннер/делают откат.
 */
const request = async (url, options) => {
    let res;
    try {
        res = await fetch(url, options);
    } catch (e) {
        // обрыв сети, DNS, CORS и т.п. — fetch реджектится без ответа
        throw new Error(`Сеть недоступна: ${e.message}`);
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    // Пустое тело (204 или 200 от void-метода вроде DELETE) не парсим как JSON —
    // res.json() на пустой строке бросает «Unexpected end of JSON input».
    const text = await res.text();
    return text ? JSON.parse(text) : null;
};

const jsonBody = (body) => ({
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
});

// ── Публичные (чат) ──────────────────────────────────────────────────────────

/** Включённые фразы, отсортированные по категории и позиции. */
export const fetchPhrases = () => request('/api/phrases');

/** Переключить «избранное» прямо из блока фраз в чате. */
export const toggleFavorite = (id, value) =>
    request(`/api/phrases/${id}/favorite?value=${value}`, { method: 'PATCH' });

// ── Админские (настройки) ────────────────────────────────────────────────────

/** Все фразы, включая выключенные. q — быстрый поиск по label. */
export const fetchAllPhrases = (q = '') =>
    request(`/api/admin/phrases${q ? `?q=${encodeURIComponent(q)}` : ''}`);

export const createPhrase = (body) =>
    request('/api/admin/phrases', { method: 'POST', ...jsonBody(body) });

export const updatePhrase = (id, body) =>
    request(`/api/admin/phrases/${id}`, { method: 'PUT', ...jsonBody(body) });

export const deletePhrase = (id) =>
    request(`/api/admin/phrases/${id}`, { method: 'DELETE' });

export const adminToggleFavorite = (id, value) =>
    request(`/api/admin/phrases/${id}/favorite?value=${value}`, { method: 'PATCH' });

/** Переключить только флаг enabled — без полного PUT (текст не перетирается). */
export const adminToggleEnabled = (id, value) =>
    request(`/api/admin/phrases/${id}/enabled?value=${value}`, { method: 'PATCH' });

/** Переместить фразу внутри её категории на позицию position (значение слота соседа). */
export const movePhrase = (id, position) =>
    request(`/api/admin/phrases/${id}/move`, { method: 'PATCH', ...jsonBody({ position }) });