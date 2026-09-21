import { parseScriptCommand } from './scriptCommand';

// Хвост команды набирает человек, поэтому проверяется то, что человек набирает:
// имя, пары ключ=значение, значение с пробелом в кавычках, JSON для аргумента-массива
// и опечатка, которая не должна исчезать по дороге к серверу.

describe('parseScriptCommand', () => {
  it('имя без аргументов', () => {
    expect(parseScriptCommand('locale-diff')).toEqual({ name: 'locale-diff', args: {} });
  });

  it('пары ключ=значение', () => {
    expect(parseScriptCommand('locale-diff area=components limit=50')).toEqual({
      name: 'locale-diff',
      args: { area: 'components', limit: '50' },
    });
  });

  // Числа и булевы приводит бэкенд — там же, где знает объявленный тип.
  it('значение остаётся строкой: тип знает манифест, а не поле ввода', () => {
    expect(parseScriptCommand('report dry=true').args).toEqual({ dry: 'true' });
  });

  it('значение с пробелом — в кавычках', () => {
    expect(parseScriptCommand('report note="про запас" area=docs').args).toEqual({
      note: 'про запас',
      area: 'docs',
    });
  });

  // Массив и объект бэкенд строкой не принимает: их разбирает поле, иначе такой
  // аргумент нельзя было бы передать ничем.
  it('аргумент-массив разбирается как JSON, даже с пробелом внутри', () => {
    expect(parseScriptCommand('report files=["a.js", "b.js"]').args).toEqual({
      files: ['a.js', 'b.js'],
    });
  });

  it('незакрытый JSON уезжает строкой — отказ напишет сервер, знающий тип', () => {
    expect(parseScriptCommand('report files=["a.js"').args).toEqual({ files: '["a.js"' });
  });

  // Опечатка обязана доехать: сервер назовёт её по имени («unknown parameter»),
  // а молча потерянный токен выглядел бы как сработавшая команда.
  it('токен без = уезжает как есть', () => {
    expect(parseScriptCommand('report area').args).toEqual({ area: '' });
  });

  it('вложение запускается тем же именем', () => {
    expect(parseScriptCommand('attachment:12').name).toBe('attachment:12');
  });

  it('пустой хвост — команды нет', () => {
    expect(parseScriptCommand('')).toBeNull();
    expect(parseScriptCommand('   ')).toBeNull();
  });
});
