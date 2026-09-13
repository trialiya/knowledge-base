import { slashMenuItems, SLASH_KIND } from './slashMenu';

const triggers = (text) => (slashMenuItems(text) || []).map((i) => i.trigger);

describe('slashMenuItems', () => {
  it('на голом слэше показывает оба реестра — команды и триггеры чипов', () => {
    const items = slashMenuItems('/');

    expect(items.map((i) => i.kind)).toEqual([SLASH_KIND.COMMAND, SLASH_KIND.INSERT, SLASH_KIND.INSERT]);
    expect(triggers('/')).toEqual(['/compact', '/file', '/doc']);
  });

  // Ради этого списки и сведены в один: слэш посреди сообщения командой не станет,
  // и обещать её там значило бы обещать несуществующее.
  it('списку нет места, пока поле не состоит из одного слэш-префикса', () => {
    expect(slashMenuItems('')).toBeNull();
    expect(slashMenuItems('что делает /compact?')).toBeNull();
    expect(slashMenuItems(' /compact')).toBeNull();
    expect(slashMenuItems('/compact ')).toBeNull();
  });

  // Открытый список здесь стоил бы дважды: Enter уходил бы ему, а не отправке, и
  // сам он закрывал бы собой строку про уже набранную команду.
  it('на набранном целиком триггере списку уже нечего дополнять', () => {
    expect(slashMenuItems('/compact')).toEqual([]);
    expect(slashMenuItems('/сжать')).toEqual([]);
    expect(slashMenuItems('/doc')).toEqual([]);
    expect(triggers('/compac')).toEqual(['/compact']);
  });

  it('ищет по всем синонимам, а показывает тот, что совпал с набранным', () => {
    const [compact] = slashMenuItems('/сж');

    expect(compact.trigger).toBe('/сжать');
    expect(compact.alt).toEqual(['/compact']);
    expect(slashMenuItems('/сж')).toHaveLength(1);
  });

  it('набранное не подменяет каноническим триггером', () => {
    expect(triggers('/фай')).toEqual(['/файл']);
    expect(triggers('/co')).toEqual(['/compact']);
  });

  it('регистр набранного значения не имеет', () => {
    expect(triggers('/COM')).toEqual(['/compact']);
  });

  it('на чужом слэше список пуст, а не отсутствует: Enter уйдёт в отправку', () => {
    expect(slashMenuItems('/nope')).toEqual([]);
  });
});
