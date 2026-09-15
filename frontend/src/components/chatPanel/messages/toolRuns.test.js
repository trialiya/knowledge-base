import { toolRunsIn } from './toolRuns';

// Два ряда из одних вызовов подряд — это повтор прогона, умершего до первого слова: вопроса
// между ними нет, и разрыва в ленте быть не должно. Длина ответа обязана совпадать с длиной
// ленты — на индексах висят и блоки правок в конце ответа, и поиск откатываемого ответа.

const tools = (...names) => ({ sender: 'ai', text: '', toolCalls: names.map((name) => ({ name })) });

describe('toolRunsIn', () => {
  it('склеивает подряд идущие ряды из одних вызовов в один', () => {
    const runs = toolRunsIn([{ sender: 'user', text: 'вопрос' }, tools('a', 'b'), tools('b', 'c')]);

    expect(runs).toHaveLength(3);
    expect(runs[0]).toBeNull();
    // Группировка одноимённых вызовов идёт по этому списку — на границе она больше не
    // обрывается: два `b` подряд станут одной группой.
    expect(runs[1].toolCalls.map((tc) => tc.name)).toEqual(['a', 'b', 'b', 'c']);
    expect(runs[2]).toEqual({ absorbed: true });
  });

  it('одиночный ряд оставляет как есть', () => {
    // Склеивать нечего, а новый массив на каждый рендер обесценил бы memo пузыря.
    expect(toolRunsIn([tools('a'), { sender: 'user', text: 'ещё вопрос' }])).toEqual([null, null]);
  });

  it('не склеивает через ряд, которому есть что показать помимо вызовов', () => {
    const withText = { sender: 'ai', text: 'нашёл вот что', toolCalls: [{ name: 'b' }] };
    const runs = toolRunsIn([tools('a'), withText, tools('c')]);

    expect(runs).toEqual([null, null, null]);
  });

  it('не склеивает через ошибку и через плашки событий', () => {
    const failed = { sender: 'ai', text: '', error: 'timeout', toolCalls: [{ name: 'b' }] };
    const revert = { sender: 'user', fileRevert: { paths: ['a.java'] } };
    const runs = toolRunsIn([tools('a'), failed, tools('c'), revert, tools('d')]);

    expect(runs.map((r) => r?.absorbed ?? (r ? 'run' : null))).toEqual([null, null, null, null, null]);
  });
});
