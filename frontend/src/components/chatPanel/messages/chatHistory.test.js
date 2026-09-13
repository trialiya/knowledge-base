import { isChatEmpty } from './chatHistory';

describe('isChatEmpty', () => {
  it('чат с вопросом не пуст', () => {
    expect(isChatEmpty({ messages: [{ sender: 'user', text: 'привет' }] })).toBe(false);
  });

  // Так помечен чат, которому id выдало вложение, а не первый вопрос: id есть,
  // сжимать нечего.
  it('загруженная пустая история — пустой чат', () => {
    expect(isChatEmpty({ messages: [] })).toBe(true);
  });

  // Пока история не доехала, пустым чат считать нельзя: иначе `/compact` отклонялся
  // бы в чате, где сжимать есть что, а блок git-фраз мелькал бы на открытии.
  it('незагруженная история пустым чатом не считается', () => {
    expect(isChatEmpty({ messages: null })).toBe(false);
    expect(isChatEmpty({})).toBe(false);
    expect(isChatEmpty(null)).toBe(false);
  });

  // Плашки сжатия и прочие служебные записи sender не несут — разговором они не являются.
  it('записи без отправителя разговором не считаются', () => {
    expect(isChatEmpty({ messages: [{ compact: true }] })).toBe(true);
  });
});
