import { initialSelection } from './historySelection';

// История newest-first, как её отдаёт бэкенд: v3, v2, v1.
const list = [{ descriptionVersion: 3 }, { descriptionVersion: 2 }, { descriptionVersion: 1 }];

describe('initialSelection', () => {
  it('без наведения сравнивает новейшую с предыдущей', () => {
    expect(initialSelection(list)).toEqual({ baseIdx: 1, compareIdx: 0, mode: 'diff' });
  });

  it('без базы сравнивает выбранную версию с предыдущей', () => {
    expect(initialSelection(list, 2)).toEqual({ baseIdx: 2, compareIdx: 1, mode: 'diff' });
  });

  it('старейшую версию показывает саму — сравнивать не с чем', () => {
    expect(initialSelection(list, 1)).toEqual({ baseIdx: 2, compareIdx: 2, mode: 'compare' });
  });

  it('с заданной базой сравнивает через несколько версий', () => {
    expect(initialSelection(list, 3, 1)).toEqual({ baseIdx: 2, compareIdx: 0, mode: 'diff' });
  });

  it('базы, которой нет в истории, не было — версия показывается целиком', () => {
    expect(initialSelection(list, 3, 0)).toEqual({ baseIdx: 2, compareIdx: 0, mode: 'compare' });
    expect(initialSelection([{ descriptionVersion: 1 }], 1, 0)).toEqual({ baseIdx: 0, compareIdx: 0, mode: 'compare' });
  });

  it('база не старее выбранной версии игнорируется', () => {
    expect(initialSelection(list, 2, 3)).toEqual({ baseIdx: 2, compareIdx: 1, mode: 'diff' });
  });

  it('единственная версия без наведения показывается сама', () => {
    expect(initialSelection([{ descriptionVersion: 1 }])).toEqual({ baseIdx: 0, compareIdx: 0, mode: 'compare' });
  });
});
