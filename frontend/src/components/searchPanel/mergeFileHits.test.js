import mergeFileHits from './mergeFileHits';

const grep = {
  total: 3,
  truncated: false,
  files: [
    { path: 'docs/notes.md', tracked: true, lines: [{ line: 1, text: 'registry' }] },
    {
      path: 'src/registry.jsx',
      tracked: true,
      lines: [
        { line: 4, text: 'registry' },
        { line: 9, text: 'registry' },
      ],
    },
  ],
};
const node = (path) => ({ path, name: path.split('/').pop(), type: 'FILE', tracked: true });

test('без находок по имени возвращается сам ответ git grep', () => {
  expect(mergeFileHits(grep, [], 'registry')).toBe(grep);
  expect(mergeFileHits(grep, undefined, 'registry')).toBe(grep);
});

test('файл, названный запросом целиком, идёт первым — даже после файлов с совпадениями', () => {
  const merged = mergeFileHits(grep, [node('src/registry.jsx')], 'registry.jsx');

  expect(merged.files.map((f) => f.path)).toEqual(['src/registry.jsx', 'docs/notes.md']);
  expect(merged.files[0].nameMatch).toBe(true);
  expect(merged.files[0].lines).toHaveLength(2);
});

test('найденный только по имени — в конце, без строк, и добавляет одно совпадение', () => {
  const merged = mergeFileHits(grep, [node('a/my-registry-helper.js')], 'registry');

  expect(merged.files.map((f) => f.path)).toEqual(['docs/notes.md', 'src/registry.jsx', 'a/my-registry-helper.js']);
  expect(merged.files[2]).toMatchObject({ lines: [], nameMatch: true });
  expect(merged.total).toBe(4);
});

test('нечёткие совпадения бэкенда отбрасываются: нужна подстрока', () => {
  expect(mergeFileHits(grep, [node('src/r_e_g_i_s_t_r_y.js')], 'registry')).toBe(grep);
});

test('точное имя без учёта регистра, и каталоги не берутся', () => {
  const merged = mergeFileHits(grep, [node('x/Registry'), { ...node('registry'), type: 'DIRECTORY' }], 'registry');

  expect(merged.files[0].path).toBe('x/Registry');
  expect(merged.files.map((f) => f.path)).not.toContain('registry');
});
