import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Реестр кейсов — данные, а не проза: его шапка обещает, что из кейса соберётся
 * стори без уточняющих вопросов. Пока его читают только глазами, сломанный
 * синтаксис никто не замечает, и к моменту, когда файл понадобится машине,
 * чинить придётся сотню мест разом.
 *
 * Разборщика YAML в зависимостях нет, поэтому здесь проверяются два способа
 * испортить файл, которыми он и был испорчен, — оба возникают от того, что
 * строку прозы пишут без кавычек:
 *
 *   • двоеточие с пробелом внутри строки: YAML читает начало строки как ключ
 *     отображения и на этом спотыкается — файл перестаёт разбираться целиком;
 *   • решётка с пробелом перед ней: остаток строки читается как комментарий и
 *     молча пропадает. Это хуже первого: файл разбирается, а `origin: - PR #155,
 *     сессия ...` превращается в `PR`, и потеря видна только при сверке.
 *
 * Лечится и то, и другое одинаково — одинарными кавычками вокруг всей строки.
 */
const FILE = join(dirname(fileURLToPath(import.meta.url)), 'cases.yaml');
const KEY = /^[A-Za-z_][\w-]*: /;

/** Строки-скаляры реестра: без ключей, без тел блочных скаляров (`|`) и комментариев. */
function plainScalars() {
  const out = [];
  let blockIndent = null;
  readFileSync(FILE, 'utf8')
    .split('\n')
    .forEach((line, i) => {
      const text = line.trim();
      const indent = line.length - line.trimStart().length;
      if (blockIndent !== null) {
        if (text === '' || indent > blockIndent) return;
        blockIndent = null;
      }
      if (!text || text.startsWith('#')) return;
      if (/^[\w-]+: *[|>][-+0-9]*$/.test(text) || text === '- |' || text === '- >') {
        blockIndent = indent;
        return;
      }
      // «- id: files-panel» — элемент-отображение, а не строка прозы.
      const value = text.startsWith('- ') ? text.slice(2) : text.replace(KEY, '');
      if (text.startsWith('- ') && KEY.test(value)) return;
      if (!text.startsWith('- ') && !KEY.test(text)) return;
      if (value.startsWith("'") || value.startsWith('"')) return;
      out.push({ where: `cases.yaml:${i + 1}`, value });
    });
  return out;
}

describe('реестр визуальных кейсов', () => {
  it('не содержит строк, на которых разбор споткнётся', () => {
    const broken = plainScalars().filter(({ value }) => value.includes(': '));
    expect(broken.map(({ where, value }) => `${where}: ${value}`)).toEqual([]);
  });

  it('не содержит строк, хвост которых будет прочитан как комментарий', () => {
    const cut = plainScalars().filter(({ value }) => value.includes(' #'));
    expect(cut.map(({ where, value }) => `${where}: ${value}`)).toEqual([]);
  });

  /*
   * Кейс, у которого забыли `- id:`, не пропадает и не падает: его ключи
   * читаются продолжением предыдущего кейса и молча его перекрывают.
   * `component:` есть у каждого кейса и стоит первым после id — по нему и
   * считаем: каждому `component:` на отступе кейса положен свой `- id:` строкой
   * выше.
   */
  it('у каждого кейса есть свой id', () => {
    const lines = readFileSync(FILE, 'utf8').split('\n');
    const orphans = lines
      .map((line, i) => ({ line, i }))
      .filter(({ line }) => /^  component: /.test(line))
      .filter(({ i }) => !/^- id: /.test(lines[i - 1]))
      .map(({ i }) => `cases.yaml:${i + 1}`);
    expect(orphans).toEqual([]);
  });
});
