import { detectScalarResult } from './scalarResult';
import { parseResult } from './registry';

const detect = (resultText) => {
  const input = parseResult(resultText);
  return input ? detectScalarResult(input) : null;
};

describe('detectScalarResult', () => {
  it('короткое значение: recordChatInsights, getChatId, createAttachment', () => {
    expect(detect('"Done"')).toEqual({ value: 'Done' });
    expect(detect(JSON.stringify('c5dfa618-0ad2-4845-a976-ada46c50f9a4'))).toEqual({
      value: 'c5dfa618-0ad2-4845-a976-ada46c50f9a4',
    });
    expect(detect('42')).toEqual({ value: '42' });
    expect(detect('true')).toEqual({ value: 'true' });
  });

  it('длинная и многострочная строка — это содержимое, а не значение', () => {
    // Дополнение к isContentText: граница между видами одна на оба.
    expect(detect(JSON.stringify('строка\nвторая'))).toBeNull();
    expect(detect(JSON.stringify('x'.repeat(200)))).toBeNull();
    expect(detect(JSON.stringify('x'.repeat(199)))).toEqual({ value: 'x'.repeat(199) });
  });

  it('пусто, не скаляр и не JSON', () => {
    expect(detect('""')).toBeNull();
    expect(detect('"   "')).toBeNull();
    expect(detect('null')).toBeNull();
    expect(detect('{"a":1}')).toBeNull();
    expect(detect('[1,2]')).toBeNull();
    expect(detect('')).toBeNull();
    expect(detect('не json вовсе')).toBeNull();
  });
});
