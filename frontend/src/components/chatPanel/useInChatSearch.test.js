import { resolveActiveMatchMid } from './useInChatSearch';

// Пузыри как в стейте чата: загруженные из БД имеют dbId, свежие (отправка/стриминг
// текущей сессии) — нет.
const loaded = (mid, dbId, text) => ({ mid, dbId, text, sender: 'user' });
const fresh = (mid, text) => ({ mid, dbId: null, text, sender: 'user' });

describe('resolveActiveMatchMid', () => {
  it('загруженное сообщение: находит пузырь по dbId', () => {
    const messages = [loaded('m1', 10, 'про жирафов'), loaded('m2', 11, 'про слонов')];
    const matches = [{ id: 10 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 10 }, query: 'жираф' })).toBe('m1');
  });

  it('свежие сообщения: k-й свежий хит соответствует k-му свежему пузырю с запросом', () => {
    const messages = [
      loaded('m1', 10, 'старое про жирафов'),
      fresh('m2', 'спросил про жирафов'), // персистнут на бэке как id=20
      fresh('m3', 'ответ без совпадения'),
      fresh('m4', 'снова жирафы'), // персистнут как id=21
    ];
    const matches = [{ id: 10 }, { id: 20 }, { id: 21 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 20 }, query: 'жираф' })).toBe('m2');
    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 21 }, query: 'жираф' })).toBe('m4');
  });

  it('чат без загруженных страниц (создан в этой сессии): маппинг только по порядку', () => {
    const messages = [fresh('m1', 'жирафы раз'), fresh('m2', 'мимо'), fresh('m3', 'жирафы два')];
    const matches = [{ id: 5 }, { id: 7 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 5 }, query: 'жираф' })).toBe('m1');
    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 7 }, query: 'жираф' })).toBe('m3');
  });

  it('совпадение в незагруженной старой странице — null (догрузку ведёт эффект)', () => {
    const messages = [loaded('m1', 10, 'хвост истории')];
    const matches = [{ id: 3 }, { id: 10 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 3 }, query: 'хвост' })).toBeNull();
  });

  it('свежий хит без подходящего пузыря — null, а не чужой пузырь', () => {
    // Бэкенд уже видит сообщение, а стейт ещё нет (событие не дошло).
    const messages = [loaded('m1', 10, 'старое')];
    const matches = [{ id: 20 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 20 }, query: 'жираф' })).toBeNull();
  });

  it('нет активного совпадения или сообщений — null', () => {
    expect(resolveActiveMatchMid({ messages: [], matches: [], activeMatch: null, query: 'q' })).toBeNull();
    expect(resolveActiveMatchMid({ messages: undefined, matches: [], activeMatch: { id: 1 }, query: 'q' })).toBeNull();
  });
});
