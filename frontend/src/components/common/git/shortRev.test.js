import shortRev from './shortRev';

describe('shortRev', () => {
  it('режет хеш до семи знаков', () => {
    expect(shortRev('0123456789abcdef0123456789abcdef01234567')).toBe('0123456');
    expect(shortRev('ABCDEF0123')).toBe('ABCDEF0');
  });

  it('имя ветки, тега и короткий хеш оставляет целиком', () => {
    expect(shortRev('release-1')).toBe('release-1');
    expect(shortRev('HEAD~2')).toBe('HEAD~2');
    expect(shortRev('abc1234')).toBe('abc1234');
    expect(shortRev('')).toBe('');
    expect(shortRev(null)).toBe('');
  });
});
