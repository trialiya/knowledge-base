import { readDir, readDirs, readExpanded, putDirs, putExpanded, resetFileTreeCache } from './fileTreeStore';

/**
 * Регрессия: readDir зовётся не только при монтировании (как readDirs/
 * readExpanded), а и посреди сессии — из useFileTree.ensureDir и из эффекта
 * открытия пути. Если бы протухание TTL там же стирало expanded, набор молча
 * расходился бы с dirs: вызвавший readDir код тут же перезаписывает dirs через
 * putDirs, а expanded остаётся пустым, потому что ничего не просит его
 * восстановить. Пользователь при следующем визите в раздел видел бы все
 * раскрытые вручную каталоги свёрнутыми, хотя их листинги всё ещё в кэше.
 */
describe('fileTreeStore: TTL и expanded', () => {
  beforeEach(() => {
    resetFileTreeCache();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('протухание dirs, обнаруженное через readDir, не стирает expanded', () => {
    putDirs({ a: [], 'a/b': [] });
    putExpanded(new Set(['', 'a', 'a/b']));

    vi.advanceTimersByTime(61_000); // > TTL_MS, dirs не запрашивали

    expect(readDir('a')).toBeUndefined(); // протух, кэш каталогов сброшен
    putDirs({ a: [], 'a/b': [] }); // тут же перезапрошен и наполнен заново

    expect(Object.keys(readDirs())).toEqual(expect.arrayContaining(['a', 'a/b']));
    expect([...readExpanded()]).toEqual(expect.arrayContaining(['', 'a', 'a/b']));
  });

  it('протухание, обнаруженное через readDirs/readExpanded на монтировании, всё ещё сбрасывает dirs', () => {
    putDirs({ a: [] });
    putExpanded(new Set(['', 'a']));

    vi.advanceTimersByTime(61_000);

    expect(readDirs()).toEqual({});
    expect([...readExpanded()]).toEqual(expect.arrayContaining(['', 'a']));
  });
});
