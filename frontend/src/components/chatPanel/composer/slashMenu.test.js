import { slashMenuItems, SLASH_KIND } from './slashMenu';

const triggers = (text) => (slashMenuItems(text) || []).map((i) => i.trigger);

describe('slashMenuItems', () => {
  it('на голом слэше показывает оба реестра — команды и триггеры чипов', () => {
    const items = slashMenuItems('/');

    expect(items.map((i) => i.kind)).toEqual([
      SLASH_KIND.COMMAND,
      SLASH_KIND.COMMAND,
      SLASH_KIND.COMMAND,
      SLASH_KIND.INSERT,
      SLASH_KIND.INSERT,
    ]);
    expect(triggers('/')).toEqual(['/compact', '/compact-1', '/script', '/file', '/doc']);
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
  // сам он закрывал бы собой строку про уже набранную команду. Верно и для
  // `/compact`, которую продолжает `/compact-1`: законченная команда набрана, и
  // выбирать за пользователя более длинную список не вправе.
  it('на набранном целиком триггере список закрыт', () => {
    expect(slashMenuItems('/compact')).toEqual([]);
    expect(slashMenuItems('/сжать')).toEqual([]);
    expect(slashMenuItems('/doc')).toEqual([]);
    expect(triggers('/compac')).toEqual(['/compact', '/compact-1']);
    expect(triggers('/compact-')).toEqual(['/compact-1']);
  });

  it('ищет по всем синонимам, а показывает тот, что совпал с набранным', () => {
    const [compact] = slashMenuItems('/сж');

    expect(compact.trigger).toBe('/сжать');
    expect(compact.alt).toEqual(['/compact']);
    expect(triggers('/сж')).toEqual(['/сжать', '/сжать-1']);
  });

  it('набранное не подменяет каноническим триггером', () => {
    expect(triggers('/фай')).toEqual(['/файл']);
    expect(triggers('/co')).toEqual(['/compact', '/compact-1']);
  });

  it('регистр набранного значения не имеет', () => {
    expect(triggers('/COM')).toEqual(['/compact', '/compact-1']);
  });

  it('на чужом слэше список пуст, а не отсутствует: Enter уйдёт в отправку', () => {
    expect(slashMenuItems('/nope')).toEqual([]);
  });
});
