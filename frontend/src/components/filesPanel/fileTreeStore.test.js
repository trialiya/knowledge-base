import {
  readDir,
  readDirs,
  readExpanded,
  putDirs,
  putExpanded,
  invalidatePath,
  resetFileTreeCache,
} from './fileTreeStore';

/** Кэш разложен по проектам — тесты работают в одном. */
const PROJECT = 'kb';

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
    putDirs(PROJECT, { a: [], 'a/b': [] });
    putExpanded(PROJECT, new Set(['', 'a', 'a/b']));

    vi.advanceTimersByTime(61_000); // > TTL_MS, dirs не запрашивали

    expect(readDir(PROJECT, 'a')).toBeUndefined(); // протух, кэш каталогов сброшен
    putDirs(PROJECT, { a: [], 'a/b': [] }); // тут же перезапрошен и наполнен заново

    expect(Object.keys(readDirs(PROJECT))).toEqual(expect.arrayContaining(['a', 'a/b']));
    expect([...readExpanded(PROJECT)]).toEqual(expect.arrayContaining(['', 'a', 'a/b']));
  });

  it('протухание, обнаруженное через readDirs/readExpanded на монтировании, всё ещё сбрасывает dirs', () => {
    putDirs(PROJECT, { a: [] });
    putExpanded(PROJECT, new Set(['', 'a']));

    vi.advanceTimersByTime(61_000);

    expect(readDirs(PROJECT)).toEqual({});
    expect([...readExpanded(PROJECT)]).toEqual(expect.arrayContaining(['', 'a']));
  });
});

/**
 * Кэш каталогов — на проект. Общий на всех он показал бы дерево одного
 * репозитория в другом: пути совпадают, и подмена выглядит как обычная выдача.
 */
describe('fileTreeStore: проекты не делят кэш', () => {
  beforeEach(() => resetFileTreeCache());

  it('листинги и раскрытые узлы разных проектов не пересекаются', () => {
    putDirs('kb', { '': [{ path: 'kb.txt' }] });
    putExpanded('kb', new Set(['', 'src']));

    expect(readDir('billing', '')).toBeUndefined();
    expect(readDirs('billing')).toEqual({});
    expect([...readExpanded('billing')]).toEqual(['']);

    putDirs('billing', { '': [{ path: 'billing.txt' }] });
    expect(readDir('kb', '')).toEqual([{ path: 'kb.txt' }]);
    expect(readDir('billing', '')).toEqual([{ path: 'billing.txt' }]);
  });

  it('сброс по правке файла бьёт только по своему проекту', () => {
    putDirs('kb', { '': [], a: [] });
    putDirs('billing', { '': [], a: [] });

    invalidatePath('kb', 'a/new.txt');

    expect(readDir('kb', 'a')).toBeUndefined();
    expect(readDir('billing', 'a')).toEqual([]);
  });
});
