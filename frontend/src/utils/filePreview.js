// Как файловый браузер умеет показать файл, помимо его текста.
//
// Список расширений здесь — половина пары: вторая живёт на бэкенде
// (PreviewMedia), который по тому же расширению решает, отдавать ли байты и с
// каким типом содержимого. Дублируются они не по лени: тот список — граница
// безопасности (что вообще отдаётся сырым), этот — вопрос вида («рисовать или
// показать текст»), и расширение, добавленное только здесь, получит 415 вместо
// картинки, а добавленное только там просто никем не будет спрошено.

/** Растровые картинки: показываются только рисунком — текста у них нет. */
const RASTER = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'avif', 'bmp', 'ico'];

const extensionOf = (path) => {
  const name = (path || '').slice((path || '').lastIndexOf('/') + 1);
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : '';
};

/**
 * Чем файл может быть показан, кроме кода:
 * `'image'` — только рисунок (растр), `'vector'` — рисунок или его исходник
 * (SVG), `'markdown'` — код или разметка, `null` — обычный текст.
 */
export const previewKind = (path) => {
  const ext = extensionOf(path);
  if (RASTER.includes(ext)) return 'image';
  if (ext === 'svg') return 'vector';
  if (ext === 'md' || ext === 'mdx') return 'markdown';
  return null;
};

/** Вид, с которого файл открывается: рисунок показываем сразу, разметку — нет. */
export const defaultPreviewView = (kind) => {
  if (kind === 'image' || kind === 'vector') return 'preview';
  return 'source';
};
