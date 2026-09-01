import { useEffect, useState } from 'react';
import chatApi from '@/api/chatApi';
import { DRAFT_CHAT_ID } from '@/constants/storage';
import { contextUsageOf } from '../messages/tokenUsage';

/**
 * Токены чата: занятый контекст сейчас — из ленты, итоги за всё время — с бэкенда.
 *
 * Разделение не произвольное. Занятое отвечает последним замером, и хвоста ленты для него хватает
 * всегда. Итог же складывается по ВСЕМ прогонам чата, а лента — это страница (по умолчанию два
 * десятка сообщений), поэтому счёт по ней был бы счётом по хвосту разговора; его и считает
 * `GET /api/chats/{id}/usage` (см. ChatUsageService), одним проходом по метам без содержимого
 * сообщений.
 *
 * Оттуда же приезжает системная часть контекста: её знает только первый прогон чата, а он в
 * загруженную страницу обычно не входит вовсе.
 *
 * @param chatId активный чат; черновик своих рядов ещё не имеет и не спрашивается
 * @param messages лента чата (пузыри), может быть пустой
 * @param running идёт ли прогон — по его завершении итоги перечитываются
 * @returns {{current: object|null, totals: object|null}}
 */
const useChatUsage = (chatId, messages, running) => {
  // Пара «чат + его итоги», а не одни итоги: при переходе в другой чат прежние числа обязаны
  // исчезнуть сразу, а сбросить их синхронно в эффекте нельзя — это лишний каскадный рендер, и
  // eslint такое не пропускает. Достаточно читать их только для того чата, для которого загружены.
  const [loaded, setLoaded] = useState(null);

  // Сжатия в ленте: их плашки несут собственный замер, а появляются они и вне прогона — фоновая
  // суммаризация идёт после RUN_DONE, и без этой зависимости оплаченный ею раунд попал бы в итоги
  // только со следующим ответом, разойдясь с тем, что показывает перезагрузка страницы.
  const compactions = (messages || []).reduce((count, m) => (m.compact ? count + 1 : count), 0);

  useEffect(() => {
    if (!chatId || chatId === DRAFT_CHAT_ID) {
      return undefined;
    }
    // Зависимость от `running` перечитывает итоги по завершении прогона — по нему-то они и
    // меняются. Лишний запрос на старте прогона того стоит: правило «перечитываем, когда прогон
    // менялся» видно целиком, а ref с прошлым значением пришлось бы читать вместе с ним.
    let alive = true;
    chatApi
      .getUsage(chatId)
      .then((fetched) => alive && setLoaded({ chatId, totals: fetched }))
      // Счёт токенов — не то, ради чего стоит показывать ошибку поверх чата: без него вкладка
      // просто пуста (а сразу после сжатия пуст и счётчик в шапке — оценивать его не из чего),
      // зато разговор продолжается.
      .catch(() => alive && setLoaded({ chatId, totals: null }));
    return () => {
      alive = false;
    };
  }, [chatId, running, compactions]);

  const totals = loaded?.chatId === chatId ? loaded.totals : null;

  const fresh = { current: contextUsageOf(messages, totals?.baseContextTokens ?? null), totals };
  const key = JSON.stringify(fresh);

  // Замена во время рендера, а не эффектом (см. .claude/rules/frontend-ui.md): эффект отдал бы
  // один кадр со старыми числами уже после того, как приехали новые. Ссылка нужна стабильная —
  // ChatWindow перерисовывается на КАЖДЫЙ чанк стрима, а вкладки правой панели собираются мемо,
  // которое намеренно не зависит от ленты. Сравнение по строке, а не по полям: полей у итога
  // десяток, они разного смысла, и ручной shallow-equal разошёлся бы с ними на первом добавленном.
  const [held, setHeld] = useState({ key, value: fresh });
  if (held.key !== key) {
    setHeld({ key, value: fresh });
    return fresh;
  }
  return held.value;
};

export default useChatUsage;
