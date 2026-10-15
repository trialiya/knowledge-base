/**
 * Фикстура деталей коммита из ленты «Истории»
 * (components/filesPanel/history/CommitDetailModal.jsx): сводка коммита, под
 * ней все изменённые файлы и патч выбранного.
 *
 * Файлы подобраны под узкий столбец списка: длинные Java-пути и переименования,
 * у которых старый путь длиннее всей строки. Строка `R` обязана уместиться —
 * имя и счётчики на виду, обрезаются каталог и старый путь, — а у списка не
 * должно появиться горизонтальной прокрутки. Данные синтетические, по форме —
 * ответ `GET /api/git/commit`. Стенд отдаёт его на любой запрос, и патч
 * выбранного файла окно берёт из первой записи, поэтому патч написан про неё.
 */

const SRC = 'metadata-service/services/src/main/java/com/linkedin/metadata/service';
const OLD = 'metadata-service/services/src/main/java/com/linkedin/metadata/service/csv';
const NEW = `${SRC}/excel`;

export const hash = 'c2fad2a7b41e9d03f5c86a1e2b7d904c3e15f6a8';

const patch =
  '@@ -251,6 +251,7 @@\n' +
  '       - ownership\n' +
  '       - subscription\n' +
  '       - entityRating\n' +
  '+      - hasRelatedEntities\n' +
  '       - structuredProperties\n' +
  '       - forms\n' +
  '   - name: container\n' +
  '@@ -282,6 +283,7 @@\n' +
  '       - browsePathsV2\n' +
  '       - structuredProperties\n' +
  '       - forms\n' +
  '+      - dvCatalog\n' +
  '   - name: tag\n' +
  '     category: core\n' +
  '     keyAspect: tagKey\n';

const file = (status, path, additions, deletions, oldPath = null) => ({
  status,
  path,
  oldPath,
  additions,
  deletions,
  patch: null,
});

/** Коммит с переименованиями, длинными путями и патчем первого файла. */
export const renamedFiles = {
  hash,
  parents: ['9e41b0c2d7f3a85e6c19b4d02f7a3e8c5b16d940'],
  author: 'Ivan Petrov',
  email: 'ivan.petrov@example.com',
  date: '2026-09-18T15:42:00Z',
  message: 'Выгрузка в Excel вместо CSV для контейнеров и схем датасетов',
  body: '',
  files: [
    {
      ...file('M', 'metadata-models/src/main/resources/entity-registry.yml', 4, 0),
      patchHeader:
        'diff --git a/metadata-models/src/main/resources/entity-registry.yml b/metadata-models/src/main/resources/entity-registry.yml\nindex 3f2a1b0..8c7d6e5 100644',
      patch,
    },
    file('A', `${SRC}/health/HealthChecker.java`, 7, 0),
    file('D', `${OLD}/AsyncContainerCsvService.java`, 0, 13),
    file('D', `${OLD}/ContainerCsvService.java`, 0, 5),
    file('D', `${OLD}/CsvPrinterFacade.java`, 0, 26),
    file('R', `${NEW}/AsyncContainerExcelService.java`, 13, 0, `${OLD}/AsyncContainerCsvExportService.java`),
    file('R', `${NEW}/ContainerExcelService.java`, 5, 0, `${OLD}/ContainerCsvExportService.java`),
    file('R', `${NEW}/DatasetSchemaExcelService.java`, 5, 0, `${OLD}/DatasetSchemaCsvExportService.java`),
    file('A', `${NEW}/ExcelService.java`, 22, 0),
    file('R', `${NEW}/PrintWriterFacade.java`, 22, 0, `${OLD}/CsvPrintWriterFacade.java`),
  ],
};
