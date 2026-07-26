/**
 * Фикстуры для пути к файлу — он же шапка центра файлового раздела
 * (components/filesPanel/Breadcrumb.jsx).
 *
 * Компонент принимает путь строкой и сам режет его на звенья (segmentsOf), так
 * что фикстура — это просто путь, относительный от корня репозитория.
 */

/**
 * Глубокий путь (12 звеньев): заведомо шире шапки на ноутбучной ширине, поэтому
 * на нём и видно, прижат ли скролл к концу. Файл существует в репозитории —
 * тот же путь годится и для Playwright-прогона поверх живого бэкенда.
 */
export const deepJavaPath = 'backend/src/main/java/io/github/trialiya/kb/model/phrase/dto/MovePhraseRequest.java';

/**
 * Путь в никуда: крошки строятся из URL, даже когда файла нет. Пакет
 * com/example в репозитории отсутствует — центр показывает «File or directory
 * not found», а шапка остаётся полноценной.
 */
export const missingPath = 'backend/src/main/java/com/example/knowledgebase';
