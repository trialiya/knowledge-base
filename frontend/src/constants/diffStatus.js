/**
 * Буква статуса файла в изменениях (`GitDiffEntry.status`): git'овские
 * A/M/D/R/C плюс U — неотслеживаемый файл из allow-globs проекта, о котором
 * индекс не знает ничего. Те же буквы пишет бэкенд (`DiffStatus`).
 */
export const DIFF_STATUS = {
  ADDED: 'A',
  MODIFIED: 'M',
  DELETED: 'D',
  RENAMED: 'R',
  COPIED: 'C',
  UNTRACKED: 'U',
};
