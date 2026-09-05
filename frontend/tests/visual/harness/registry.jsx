import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ChatHeader from '@/components/chatPanel/center/ChatHeader';
import ChatUsage from '@/components/chatPanel/center/ChatUsage';
import ComposerToolbar from '@/components/chatPanel/composer/ComposerToolbar';
import PhraseFillModal from '@/components/chatPanel/composer/PhraseFillModal';
import RunStatus from '@/components/chatPanel/composer/RunStatus';
import ChatRepoPanel from '@/components/chatPanel/git/ChatRepoPanel';
import CommitDialog from '@/components/common/git/CommitDialog';
import PushDialog from '@/components/common/git/PushDialog';
import GitOutputCard from '@/components/common/git/GitOutputCard';
import CompactNotice from '@/components/chatPanel/messages/CompactNotice';
import ToolCallDetailModal from '@/components/chatPanel/messages/ToolCallDetailModal';
import ToolCallNotifications from '@/components/chatPanel/messages/ToolCallNotifications';
import GitBranchBar from '@/components/filesPanel/git/GitBranchBar';
import Breadcrumb from '@/components/filesPanel/Breadcrumb';
import DetailHeader from '@/components/knowledgeBasePanel/detail/DetailHeader';
import DocumentDetail from '@/components/knowledgeBasePanel/detail/DocumentDetail';
import { buildDetailTabs } from '@/components/knowledgeBasePanel/detail/detailSidebar';
import AttachmentModal from '@/components/common/attachments/AttachmentModal';
import RightPanel from '@/components/common/layout/RightPanel';
import InfoList from '@/components/common/ui/InfoList';
import OperationRow from '@/components/common/ui/OperationRow';
import ModelsSettings from '@/components/settingsPanel/ModelsSettings';
import SearchSettings from '@/components/settingsPanel/SearchSettings';
import ToolsSettings from '@/components/settingsPanel/ToolsSettings';
import ScriptsSettings from '@/components/settingsPanel/ScriptsSettings';
import ToolCatalog from '@/components/settingsPanel/ToolCatalog';
import SystemInfo from '@/components/adminPanel/SystemInfo';
import IndexOperations from '@/components/adminPanel/IndexOperations';
import SyncDiffList from '@/components/adminPanel/SyncDiffList';
import SyncLog from '@/components/adminPanel/SyncLog';
import { DOC_TAB } from '@/constants/docTabs';
import { IconRefresh, IconUpload } from '@/icons/index';
import * as aiConfig from '../fixtures/aiConfig';
import * as chatHeader from '../fixtures/chatHeader';
import * as chatRepo from '../fixtures/chatRepo';
import * as chatUsage from '../fixtures/chatUsage';
import * as compactNotice from '../fixtures/compactNotice';
import * as composerToolbar from '../fixtures/composerToolbar';
import * as detailHeader from '../fixtures/detailHeader';
import * as detailPanel from '../fixtures/detailPanel';
import * as filesBreadcrumb from '../fixtures/filesBreadcrumb';
import * as gitMenu from '../fixtures/gitMenu';
import * as infoList from '../fixtures/infoList';
import * as modalFind from '../fixtures/modalFind';
import * as operationRow from '../fixtures/operationRow';
import * as phraseFill from '../fixtures/phraseFill';
import * as projects from '../fixtures/projects';
import * as runStatus from '../fixtures/runStatus';
import * as syncDiff from '../fixtures/syncDiff';
import * as systemInfo from '../fixtures/systemInfo';
import * as toolCallDetail from '../fixtures/toolCallDetail';
import * as toolCallNotifications from '../fixtures/toolCallNotifications';
import * as toolCatalog from '../fixtures/toolCatalog';

/**
 * Что стенд умеет показать: фикстура из `../fixtures/` плюс компонент, которым
 * её рисуют, плюс рамка, в которой этот компонент живёт в приложении.
 *
 * Список явный, а не выведенный из `cases.yaml`: не всякая фикстура рисуется
 * в одиночку — часть кейсов про целые экраны (раздел со стором и роутером,
 * `kb-empty-no-selection`), и им нужен поднятый бэкенд (`./run/test.sh smoke`).
 * Здесь только то, чему хватает пропсов и заявленных ответов сервера.
 *
 * `id` совпадает с ссылкой на фикстуру в `cases.yaml` — `<модуль>#<экспорт>`,
 * — чтобы кейс и снимок находились друг по другу без второго имени. Одну и ту
 * же фикстуру рисуют разные компоненты (снимок конфигурации читают три группы
 * «Настроек»), поэтому у записи есть необязательный вариант через `@`:
 * `aiConfig.js#defaultAiConfig@search`. Экспорт берётся до `@`.
 *
 * `frame` — во что обернуть:
 *   `panel`    — тело правой панели, 320px (иначе компонент растечётся на всё
 *                окно, и всё, что решает ширина, окажется непроверенным);
 *   `right`    — правая панель целиком, со своей шапкой и вкладками;
 *   `center`   — центр раздела: там живут шапки и содержимое под ними;
 *   `settings` — тело группы «Настроек»/«Администрирования» (отступы колонки);
 *   `feed`     — колонка ленты чата: она flex, и элемент без `flex: none` в ней
 *                схлопывается — ровно так однажды пропала карточка вывода;
 *   `bare`     — сам себе рамка (модалка: у неё свой оверлей на всё окно);
 *   `left`     — колонка левой панели и центр рядом с ней: её выпадающие списки
 *                уходят порталом поверх центра, и без него не видно ни куда они
 *                попадают, ни сколько места им осталось.
 *
 * `api` — ответы сервера кейсу: `{ '<начало url>': данные }` или функция от
 * пропсов. Экран, который сам идёт за данными (группы «Настроек» и
 * «Администрирования», детали вызова инструмента), без них показал бы
 * «Загрузка…». Незаявленный запрос не подменяется и попадает в консольные
 * ошибки кейса — см. main.jsx.
 *
 * `steps` — что сделать перед снимком: `{ click }`, `{ press }`, `{ type }`.
 * Состояние, которое компонент открывает сам (меню, find-бар, набранный
 * запрос), пропсами не задаётся вовсе.
 *
 * `viewport` — `[ширина, высота]` вместо стандартных 1440×900. Рамки высотой в
 * экран прокручиваются внутри себя, поэтому длинная колонка настроек попадает в
 * кадр ровно настолько, насколько заказано высоты — ни fullPage, ни прокрутка
 * её не добирают. Ширина задаётся, когда кейс именно про тесноту: цепочка
 * крошек схлопывается только там, где она не влезает.
 */
const noop = () => {};

/** Правая панель узла базы знаний целиком: набор вкладок решает buildDetailTabs. */
const DetailTabs = ({ node, activeKey, folderChildren = [] }) => {
  const { t } = useTranslation('knowledgeBase');
  const tabs = buildDetailTabs({
    node,
    t,
    onNavigate: noop,
    onSummarize: noop,
    attachmentCount: 0,
    onAttachmentCountChange: noop,
    folderChildren,
  });
  // Вкладки переключает рельс WorkspaceLayout, стенд же рисует одну панель —
  // поэтому кейс сразу выбирает вкладку, которую снимает.
  return <RightPanel tab={tabs.find((tab) => tab.key === activeKey) || tabs[0]} onClose={noop} />;
};

/**
 * Одна и та же строка-операция во всех четырёх состояниях: кейс про то, что
 * различаются они только бейджем результата и подписью кнопки.
 */
const OperationStates = ({ props, icon }) => (
  <>
    {operationRow.operationStates.map((state) => (
      <OperationRow key={state} {...props} icon={icon} state={state} onRun={noop} />
    ))}
  </>
);

/**
 * Детали вызова инструмента: модалка сама идёт за ними в чат, а `tc` (плашка,
 * из которой её открыли) — это имя и статус того же вызова.
 */
const toolCallCase = ([name, viewport]) => ({
  id: `toolCallDetail.js#${name}`,
  frame: 'bare',
  viewport,
  api: (p) => ({ '/api/chats/': p }),
  render: (p) => (
    <ToolCallDetailModal
      conversationId="1"
      callId="call-1"
      // resultMeta прокидывается: строку цены вызова (модель инструмента и её токены) рисуют
      // именно из неё, а не из ответа — модели эти числа не показывают.
      tc={{ name: p.name, status: p.status, resultMeta: p.resultMeta }}
      onClose={noop}
    />
  ),
});

/**
 * Лента плашек, перестраивающаяся под идущий ответ: клик по плашке открывает
 * детали, а следующим тиком приходит второй вызов того же инструмента —
 * одиночная плашка становится группой. Ждать этого от живого приложения
 * нечего: момент длится один шаг tool-цикла, а от того, что показывают в этот
 * момент, кейс и зависит.
 */
const LiveToolCalls = ({ calls, next }) => {
  const [items, setItems] = useState(calls);
  const deliver = () =>
    setTimeout(() => setItems((prev) => (prev.length > calls.length ? prev : [...prev, next])), 0);
  return (
    <div onClickCapture={deliver}>
      <ToolCallNotifications toolCalls={items} conversationId="1" />
    </div>
  );
};

const REGISTRY = [
  // ── Чат ──
  {
    id: 'chatHeader.js#activeChatProps',
    frame: 'center',
    render: (p) => <ChatHeader {...p} onToggleSearch={noop} onRename={noop} onDelete={noop} />,
  },
  {
    id: 'chatHeader.js#activeChatWithContextProps',
    frame: 'center',
    render: (p) => <ChatHeader {...p} onToggleSearch={noop} onRename={noop} onDelete={noop} />,
  },
  { id: 'chatUsage.js#measuredChat', frame: 'panel', render: (p) => <ChatUsage {...p} /> },
  { id: 'chatUsage.js#subagentSpending', frame: 'panel', render: (p) => <ChatUsage {...p} /> },
  { id: 'chatRepo.js#repoTabIdle', frame: 'panel', render: (p) => <ChatRepoPanel {...p} /> },
  { id: 'chatRepo.js#repoTabBusy', frame: 'panel', render: (p) => <ChatRepoPanel {...p} /> },
  { id: 'chatRepo.js#repoTabMerging', frame: 'panel', render: (p) => <ChatRepoPanel {...p} /> },
  { id: 'chatRepo.js#repoTabManyChanges', frame: 'panel', render: (p) => <ChatRepoPanel {...p} /> },
  // Патч выбранной строки окно спрашивает само (useChangeDiff → GET /api/git/status):
  // без ответа стенда правая половина показала бы «Загрузка…».
  {
    id: 'chatRepo.js#commitDialogDirty',
    frame: 'bare',
    api: { '/api/git/status': chatRepo.commitDialogPatch },
    render: (p) => <CommitDialog {...p} />,
  },
  {
    id: 'chatRepo.js#commitDialogBusy',
    frame: 'bare',
    api: { '/api/git/status': chatRepo.commitDialogPatch },
    render: (p) => <CommitDialog {...p} />,
  },
  {
    id: 'chatRepo.js#pushDialogAhead',
    frame: 'bare',
    api: { '/api/git/outgoing': chatRepo.pushDialogCommits },
    render: (p) => <PushDialog {...p} />,
  },
  {
    id: 'chatRepo.js#pushDialogNewBranch',
    frame: 'bare',
    api: { '/api/git/outgoing': chatRepo.pushDialogCommits },
    render: (p) => <PushDialog {...p} />,
  },
  { id: 'chatRepo.js#outputCardOk', frame: 'feed', render: (p) => <GitOutputCard {...p} /> },
  { id: 'chatRepo.js#outputCardRefused', frame: 'feed', render: (p) => <GitOutputCard {...p} /> },
  { id: 'chatRepo.js#outputCardSilent', frame: 'feed', render: (p) => <GitOutputCard {...p} /> },

  // Диалог заполнения плейсхолдеров. Без `phraseLabel` — фраза из библиотеки
  // может быть безымянной, и тогда у диалога общий заголовок.
  {
    id: 'phraseFill.js#allTypesPhrase',
    frame: 'bare',
    render: (p) => <PhraseFillModal phraseText={p} onSubmit={noop} onCancel={noop} />,
  },
  {
    id: 'phraseFill.js#legacyPhrase',
    frame: 'bare',
    render: (p) => <PhraseFillModal phraseText={p} onSubmit={noop} onCancel={noop} />,
  },
  {
    id: 'phraseFill.js#edgeCasesPhrase',
    frame: 'bare',
    render: (p) => <PhraseFillModal phraseText={p} onSubmit={noop} onCancel={noop} />,
  },

  // Строка идущего прогона над полем ввода: таймер (в фикстуре укрощён в 0:00) и прирост input.
  {
    id: 'runStatus.js#generatingRun',
    frame: 'feed',
    render: (p) => <RunStatus startedAt={p.startedAt} inputGrowth={p.inputGrowth} />,
  },

  // Плашка сжатия — по состоянию экономии на кейс. Рамка `feed`: плашка живёт разделителем
  // в ленте и меряется её шириной.
  ...['measured', 'estimated', 'unmeasured'].map((name) => ({
    id: `compactNotice.js#${name}`,
    frame: 'feed',
    render: (p) => <CompactNotice {...p} />,
  })),

  // Нижняя панель композера — по состоянию занятости чата на кейс. Рамка `feed`:
  // панель живёт в колонке ленты, и ряд селекторов меряется её шириной.
  ...['idleComposer', 'generatingComposer', 'compactingComposer'].map((name) => ({
    id: `composerToolbar.js#${name}`,
    frame: 'feed',
    render: (p) => <ComposerToolbar {...p} onAttach={noop} onSend={noop} onStop={noop} />,
  })),

  // Детали вызова инструмента — по виду результата на кейс (см. cases.yaml,
  // tool-call-detail-*).
  // Окно выше стандартного там, где результат длиннее модалки: тело модалки
  // 85vh, и на 900px хвост списка правок остаётся за нижней кромкой.
  ...[
    ['fileContentCall'],
    ['documentCall'],
    ['attachmentsCall'],
    ['mcpCall'],
    ['editFileCall'],
    ['uncommittedChangesCall', [1440, 1100]],
    ['commitDiffCall'],
    // Единственный инструмент со строкой цены: он сам ходит в модель (см. фикстуру).
    ['searchCodebaseCall'],
    ['searchDocumentsCall'],
    ['attachmentListCall'],
    ['insightsCall'],
    ['documentOutlineCall'],
    ['fileOutlineCall'],
    ['grepCall'],
    ['docMutationCall'],
    ['scriptRunCall', [1440, 1350]],
    ['scriptFailedCall'],
  ].map(toolCallCase),

  // Плашки вызовов под ответом. Рамка `feed`: ширина ленты решает, что в плашке
  // поместится, а модалка деталей уходит порталом поверх неё — как в чате.
  ...[
    // Ответ дошёл до первого вызова: одиночная плашка, деталей никто не открывал.
    ['', undefined],
    // По плашке кликнули, и ровно тогда модель позвала тот же инструмент снова.
    ['@detail', [{ click: '.tool-call-item' }]],
    // Детали закрыли: группа осталась развёрнутой, кликнутая плашка на виду.
    ['@expanded', [{ click: '.tool-call-item' }, { click: '.tool-call-detail__header .icon-btn' }]],
  ].map(([variant, steps]) => ({
    id: `toolCallNotifications.js#editFileTwice${variant}`,
    frame: 'feed',
    steps,
    api: (p) => ({ '/api/chats/': p.detail }),
    render: (p) => <LiveToolCalls calls={p.calls} next={p.next} />,
  })),

  // Исходы вызова рядом: успех, провал и несохранённый исход (см. cases.yaml,
  // tool-call-outcomes). Рамка `feed` — плашки живут в колонке ленты.
  {
    id: 'toolCallNotifications.js#callOutcomes',
    frame: 'feed',
    render: (p) => <ToolCallNotifications toolCalls={p} conversationId="1" />,
  },

  // ── База знаний ──
  {
    id: 'detailHeader.js#documentInFolder',
    frame: 'center',
    render: (p) => <DetailHeader {...p} onNavigate={noop} onRename={noop} onDelete={noop} />,
  },
  {
    id: 'detailHeader.js#documentInFolder@rename',
    frame: 'center',
    steps: [{ click: '.detail-header__title' }],
    render: (p) => <DetailHeader {...p} onNavigate={noop} onRename={noop} onDelete={noop} />,
  },
  // Проекты — не для экрана: за ними идёт подсказка ссылки на файл внутри
  // документа (DocLinkTooltip), и без ответа кейс писал бы 404 в консоль.
  {
    id: 'detailPanel.js#documentWithContent',
    frame: 'center',
    api: { '/api/chats/projects': projects.singleDefaultProject },
    render: (p) => (
      <DocumentDetail
        node={p.node}
        path={p.path}
        contentDraft={p.node.description}
        setContentDraft={noop}
        onUpdate={noop}
        onDelete={noop}
        onNavigate={noop}
        onRename={noop}
        onExpandContent={noop}
        onHistory={noop}
      />
    ),
  },
  {
    id: 'detailPanel.js#documentWithContent@sidebar',
    frame: 'right',
    render: (p) => <DetailTabs node={p.node} activeKey={DOC_TAB.SUMMARY} />,
  },
  {
    id: 'detailPanel.js#documentWithSummary@sidebar',
    frame: 'right',
    render: (p) => <DetailTabs node={p.node} activeKey={DOC_TAB.SUMMARY} />,
  },
  {
    id: 'detailPanel.js#folderWithChildren@sidebar',
    frame: 'right',
    render: (p) => <DetailTabs node={p.node} folderChildren={p.children} activeKey={DOC_TAB.CONTENTS} />,
  },

  // ── Файлы ──
  {
    id: 'filesBreadcrumb.js#deepJavaPath',
    frame: 'center',
    // Кейс ровно про то, что делает теснота: на 1440px этот путь влезает целиком
    // и ни «…», ни прокрутки к концу не показывает.
    viewport: [1150, 900],
    render: (p) => <Breadcrumb path={p} onNavigate={noop} />,
  },
  {
    id: 'filesBreadcrumb.js#missingPath',
    frame: 'center',
    render: (p) => <Breadcrumb path={p} onNavigate={noop} />,
  },
  {
    id: 'gitMenu.js#branchMenuLongNames',
    frame: 'left',
    steps: [{ click: '.git-menu .icon-btn' }],
    render: (p) => <GitBranchBar {...p} />,
  },

  // ── Общее: правая панель и модалки ──
  { id: 'infoList.js#chatRows', frame: 'panel', render: (p) => <InfoList rows={p} /> },
  { id: 'infoList.js#fileRows', frame: 'panel', render: (p) => <InfoList rows={p} /> },
  // Find-бар модалки: открывается только с клавиатуры, и счётчик появляется
  // лишь на набранном запросе — отсюда шаги. Текст под оверлеем в кадр не
  // попадает: доказательство «ищет по диалогу, а не по странице» остаётся за
  // прогоном по живому приложению.
  {
    id: 'modalFind.js#dialogContent',
    frame: 'bare',
    api: (p) => ({ '/api/attachments/': p.body }),
    steps: [{ press: 'Control+f' }, { type: modalFind.query }],
    render: (p) => <AttachmentModal attachment={{ id: 1, fileName: p.title }} mode="content" onClose={noop} />,
  },

  // ── Настройки ──
  {
    id: 'aiConfig.js#defaultAiConfig',
    frame: 'center',
    viewport: [1440, 1560],
    api: (p) => ({ '/api/settings/ai-config': p }),
    render: () => <ModelsSettings />,
  },
  {
    id: 'aiConfig.js#defaultAiConfig@search',
    frame: 'center',
    api: (p) => ({ '/api/settings/ai-config': p }),
    render: () => <SearchSettings />,
  },
  {
    id: 'aiConfig.js#defaultAiConfig@tools',
    frame: 'center',
    viewport: [1440, 1160],
    api: (p) => ({ '/api/settings/ai-config': p, '/api/settings/tools': toolCatalog.builtinTools }),
    render: () => <ToolsSettings />,
  },
  {
    id: 'aiConfig.js#defaultAiConfig@scripts',
    frame: 'center',
    viewport: [1440, 1560],
    api: (p) => ({ '/api/settings/ai-config': p }),
    render: () => <ScriptsSettings />,
  },
  {
    id: 'aiConfig.js#editEnabledButReadOnlyTree@tools',
    frame: 'center',
    viewport: [1440, 1210],
    api: (p) => ({ '/api/settings/ai-config': p, '/api/settings/tools': toolCatalog.builtinTools }),
    render: () => <ToolsSettings />,
  },
  {
    id: 'aiConfig.js#strongAndWeakModels',
    frame: 'center',
    viewport: [1440, 1760],
    api: (p) => ({ '/api/settings/ai-config': p }),
    render: () => <ModelsSettings />,
  },
  {
    id: 'aiConfig.js#scriptEnabled@scripts',
    frame: 'center',
    viewport: [1440, 1520],
    api: (p) => ({ '/api/settings/ai-config': p }),
    render: () => <ScriptsSettings />,
  },
  {
    id: 'toolCatalog.js#builtinTools',
    frame: 'settings',
    api: (p) => ({ '/api/settings/tools': p }),
    render: () => <ToolCatalog />,
  },
  // Инструмент внешнего MCP-сервера показывается только выбранным — до выбора в
  // карточке стоит первый пункт списка, а пилюля MCP и есть то, ради чего кейс.
  {
    id: 'toolCatalog.js#withMcpTool',
    frame: 'settings',
    api: (p) => ({ '/api/settings/tools': p }),
    steps: [{ click: '.lb-select__trigger' }, { click: '.lb-select__option-label:has-text("fetch_pages")' }],
    render: () => <ToolCatalog />,
  },

  // ── Администрирование ──
  {
    id: 'systemInfo.js#h2SystemInfo',
    frame: 'center',
    viewport: [1440, 1210],
    api: (p) => ({ '/api/admin/system': p }),
    render: () => <SystemInfo />,
  },
  {
    id: 'systemInfo.js#h2SystemInfo@index',
    frame: 'center',
    api: (p) => ({ '/api/admin/system': p }),
    render: () => <IndexOperations />,
  },
  {
    id: 'operationRow.js#reindexOperation',
    frame: 'settings',
    render: (p) => <OperationStates props={p} icon={<IconRefresh />} />,
  },
  {
    id: 'operationRow.js#exportOperation',
    frame: 'settings',
    render: (p) => <OperationStates props={p} icon={<IconUpload size={14} />} />,
  },
  // Список различий: `showUnchanged` выключен, как после сравнения, — совпавшие
  // записи прячутся, и в шапке остаётся счётчик скрытых.
  {
    id: 'syncDiff.js#mixedDiffEntries',
    frame: 'settings',
    render: (p) => (
      <SyncDiffList entries={p} selected={new Set()} onToggle={noop} showUnchanged={false} onShowUnchanged={noop} />
    ),
  },
  // Удалённый с диска узел — единственный статус, который может что-то стереть;
  // в живом прогоне он не наблюдался, поэтому и рисуется только здесь.
  {
    id: 'syncDiff.js#missingEntry',
    frame: 'settings',
    render: (p) => (
      <SyncDiffList
        entries={[...syncDiff.mixedDiffEntries, p]}
        selected={new Set()}
        onToggle={noop}
        showUnchanged={false}
        onShowUnchanged={noop}
      />
    ),
  },
  { id: 'syncDiff.js#importLog', frame: 'settings', render: (p) => <SyncLog log={p} running={false} /> },
];

const MODULES = {
  'aiConfig.js': aiConfig,
  'chatHeader.js': chatHeader,
  'chatRepo.js': chatRepo,
  'detailHeader.js': detailHeader,
  'detailPanel.js': detailPanel,
  'filesBreadcrumb.js': filesBreadcrumb,
  'gitMenu.js': gitMenu,
  'chatUsage.js': chatUsage,
  'infoList.js': infoList,
  'modalFind.js': modalFind,
  'operationRow.js': operationRow,
  'composerToolbar.js': composerToolbar,
  'phraseFill.js': phraseFill,
  'compactNotice.js': compactNotice,
  'runStatus.js': runStatus,
  'syncDiff.js': syncDiff,
  'systemInfo.js': systemInfo,
  'toolCallDetail.js': toolCallDetail,
  'toolCallNotifications.js': toolCallNotifications,
  'toolCatalog.js': toolCatalog,
};

/** Все кейсы стенда с уже разрешёнными пропсами. Незнакомый экспорт — ошибка сборки кейса. */
export const cases = REGISTRY.map((entry) => {
  const [module, ref] = entry.id.split('#');
  const props = MODULES[module]?.[ref.split('@')[0]];
  const api = props && typeof entry.api === 'function' ? entry.api(props) : entry.api;
  return { ...entry, props, api, missing: !props };
});

export const findCase = (id) => cases.find((c) => c.id === id);
