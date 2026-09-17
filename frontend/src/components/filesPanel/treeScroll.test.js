import { describe, it, expect } from 'vitest';
import revealRow from './treeScroll';

/** Панель 300×100 на экране: левый край 0, правый 300, верх 0, низ 100. */
const view = { top: 0, bottom: 100, left: 0, right: 300 };
const rect = (left, right, top = 0, bottom = 20) => ({ left, right, top, bottom });
/** Строка растянута на всю ширину раскрытого дерева — заведомо шире панели. */
const wideRow = (top = 0) => rect(0, 900, top, top + 20);

describe('revealRow', () => {
  it('оставляет прокрутку как есть, когда строка целиком видна', () => {
    const at = { top: 40, left: 25 };
    expect(revealRow(view, wideRow(), rect(10, 26), rect(50, 200), at)).toEqual(at);
  });

  it('доводит по вертикали до ближнего края, а не до середины', () => {
    const below = revealRow(view, wideRow(130), rect(10, 26, 130, 150), rect(50, 200, 130, 150), { top: 0, left: 0 });
    expect(below.top).toBe(50);
    const above = revealRow(view, wideRow(-30), rect(10, 26, -30, -10), rect(50, 200, -30, -10), { top: 80, left: 0 });
    expect(above.top).toBe(50);
  });

  it('подтягивает хвост имени, когда строка помещается целиком', () => {
    // Шеврон на 40, конец имени на 320 — 280px содержимого помещаются в 300px.
    const { left } = revealRow(view, wideRow(), rect(40, 56), rect(80, 320), { top: 0, left: 0 });
    // Сдвиг ровно на вылет за правый край: начало строки при этом не ушло влево.
    expect(left).toBe(20);
  });

  it('показывает начало имени, а не хвост, когда имя длиннее панели', () => {
    // Шеврон на 40, конец имени на 500 — 460px содержимого в 300px панели.
    const { left } = revealRow(view, wideRow(), rect(40, 56), rect(80, 500), { top: 0, left: 0 });
    // Шеврон встаёт на левый край панели; будь приоритет у хвоста, вышло бы 200.
    expect(left).toBe(40);
  });

  it('подтягивает начало строки, ушедшее за левый край', () => {
    const { left } = revealRow(view, wideRow(), rect(-70, -54), rect(-30, 120), { top: 0, left: 200 });
    expect(left).toBe(130);
  });
});
