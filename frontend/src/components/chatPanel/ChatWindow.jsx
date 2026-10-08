import { useState, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
// Перевод вне рендера берём у самого i18n, а не у t() из хука: колбэки стриминга
// не должны пересоздаваться на смену языка, а зеркалить t в рефе — не за чем.
import i18n from '@/i18n/index';
import { STORAGE_KEY_ACTIVE_CHAT, DRAFT_CHAT_ID } from '@/constants/storage';
import { getLastModel, getLastMode, getLastProject, getLastReasoning } from './run/lastChoiceStore';
import useModelConfig from './run/useModelConfig';
import useProjectConfig from '@/components/common/config/useProjectConfig';
import useModeConfig from './run/useModeConfig';
import useChatList from './list/useChatList';
import useChatMessages from './run/useChatMessages';
import useChatEventStream from './run/useChatEventStream';
import useChatRun from './run/useChatRun';
import useChatUsage from './run/useChatUsage';
import useChatAttachments from './run/useChatAttachments';
import useInChatSearch from './center/useInChatSearch';
import useChatFindShortcut from './center/useChatFindShortcut';
import useChatDrafts from './composer/useChatDrafts';
import useChatDeletion from './list/useChatDeletion';
import useNotice from '@/components/common/ui/useNotice';
import { chatLoadErrorNotice, CHAT_DELETED_NOTICE } from './run/chatNotices';
import useComposerChoices from './composer/useComposerChoices';

import ChatCenter from './center/ChatCenter';
import { buildChatTabs, buildRepoTab } from './center/chatSidebar';
import useChatGit from './git/useChatGit';
import { RIGHT_TAB } from '@/constants/rightTabs';
import { RUN_KIND } from '@/constants/runKind';
import CommitDialog from '@/components/common/git/CommitDialog';
import PushDialog from '@/components/common/git/PushDialog';
import ChatList from './list/ChatList';
import ChatSearch from './list/ChatSearch';
import WorkspaceLayout from '@/components/common/layout/WorkspaceLayout';
import { isChatEmpty as chatIsEmpty } from './messages/chatHistory';
import { IconPlus } from '@/icons/index';
import './chatWindow.css';
import ErrorModal from '@/components/common/modal/ErrorModal';
import ConfirmModal from '@/components/common/modal/ConfirmModal';

const ChatWindow = ({
  onNavigateToDoc,
  isActive = true,
  activeChatId = null,
  onSelectChat: selectChat,
  find = '',
  msg = '',
  onFindChange,
  onDocChanged,
  onFileChanged,
  filesRefreshToken,
  gitRefsToken,
  onRepoChanged,
  onGitRefsChanged,
  panels,
}) => {
  // Второй namespace — ради общего словаря git: названия команд и состояний
  // репозитория живут в `files` и дублировать их здесь незачем.
  const { t } = useTranslation(['chat', 'files']);
  // Активный чат — только проп из навигации: выбор поднимается через
  // selectChat (openChat стора) и в том же вызове возвращается сюда новым
  // пропом, своего состояния у панели нет. `navigate: false` — выбор сделан не
  // пользователем, а автоматикой: навигация запомнит чат, но не утащит с
  // открытого раздела (панель смонтирована всегда, в том числе поверх /files).
  // Чат, запомненный в localStorage, нужен один раз — первичной загрузке
  // списка, когда адрес чата не называет; пишет его useChatMessages, убедившись,
  // что чат существует.
  const [rememberedChatId] = useState(() => localStorage.getItem(STORAGE_KEY_ACTIVE_CHAT) || null);

  // Создаёт объект черновика. model берём из последней использованной (localStorage),
  // иначе сработает фолбэк на дефолтную модель в селекторе/отправке.
  const makeDraft = useCallback(
    () => ({
      id: DRAFT_CHAT_ID,
      title: i18n.t('chat:window.defaultTitle'),
      messages: [],
      model: getLastModel(),
      mode: getLastMode() || null,
      reasoning: getLastReasoning() || null,
      project: getLastProject(),
      draft: true,
    }),
    [],
  );

  // Одно уведомление на все поводы (см. chatNotices) — модалка внизу тоже одна.
  const { notice, notify, dismissNotice } = useNotice();
  // Конфиг моделей и режимов грузится один раз — вынесено в отдельные хуки.
  const { modelConfig, modelOptions } = useModelConfig();
  const { modeOptions } = useModeConfig();
  const { projectOptions, defaultProjectId } = useProjectConfig();
  // Bump → MessageInput перечитает черновик: текст поля живёт в нём, и правка,
  // сделанная здесь («удаление» черновика, простановка проекта в чипах), иначе до
  // него не дойдёт.
  const [composerDraftSignal, setComposerDraftSignal] = useState(0);
  const bumpDraftSignal = useCallback(() => setComposerDraftSignal((n) => n + 1), []);
  // Неотправленные черновики по чатам ({ chatId: text }, localStorage) — вынесено
  // в useChatDrafts (отложенная запись + flush на beforeunload/размонтирование).
  const {
    getDraftFor,
    handleTextChange: handleComposerTextChange,
    clearDraft,
    clearDraftText,
    flushDrafts,
    getStagedFor,
    stageContextItem,
    unstageContextItem,
    moveDraft,
  } = useChatDrafts();

  // Список чатов и точечные правки в нём.
  const {
    chats,
    getChats,
    setChats,
    patchChat,
    patchMessages,
    renameChat,
    changeModel,
    changeMode,
    changeReasoning,
    changeProject,
    refreshChatMeta,
  } = useChatList({
    initialActiveChatId: activeChatId || rememberedChatId,
    initialPropChatId: activeChatId,
    makeDraft,
    selectChat,
  });

  const handleLoadError = useCallback((info) => notify(chatLoadErrorNotice(info)), [notify]);

  // Загрузка/пагинация сообщений активного чата (+ защита от повторных загрузок и
  // запоминание активного чата) вынесены в useChatMessages.
  const { loadingMessages, loadMessages, loadOlderMessages } = useChatMessages({
    chats,
    getChats,
    setChats,
    activeChatId,
    onLoadError: handleLoadError,
  });

  const handleLoadOlder = useCallback(() => loadOlderMessages(activeChatId), [activeChatId, loadOlderMessages]);

  // (Запись URL вынесена в useAppNavigation — ChatWindow историю не трогает.)

  const activeChat = useMemo(() => chats.find((c) => c.id === activeChatId) || null, [chats, activeChatId]);
  const activeMessages = useMemo(() => activeChat?.messages || [], [activeChat]);

  // Отправка, повтор и остановка прогона.
  const { pendingRunChatId, isLocalClientId, sendMessage, retryMessage, stopGeneration } = useChatRun({
    activeChatId,
    getChats,
    setChats,
    patchChat,
    patchMessages,
    selectChat,
    clearDraft,
    clearDraftText,
    // Вернуть в поле ввода то, что там было. Текст поле стирает на отправке, а сама отправка
    // может и не состояться (команда чату во время ответа) — черновик при этом не тронут, и
    // сигнала достаточно, чтобы поле перечитало его из initialText.
    restoreDraft: bumpDraftSignal,
    getStagedFor,
    modelConfig,
    modelOptions,
    modeOptions,
    projectOptions,
    defaultProjectId,
    notify,
  });

  // Идёт генерация в активном чате? Источник правды — runId чата (его ставит старт
  // прогона и снимает терминальное событие) ПЛЮС pendingRunChatId, закрывающий
  // окно до ответа сервера на POST /runs. Решает, показывать ли «остановить» и
  // блокировать ли селекторы — но НЕ поле ввода, см. isComposerBusy ниже.
  const isStreaming = !!activeChat?.runId || pendingRunChatId === activeChatId;

  // Чат занят операцией, а не генерацией: сжатием контекста (/compact), git-командой,
  // восстановлением очереди на старте бэка. Занятость та же — ввод заблокирован, — но
  // останавливать нечего: своего прогона у такой операции нет (см. RUN_KIND), и кнопка
  // «остановить» на ней обещала бы несуществующее.
  const isOperation = activeChat?.runKind === RUN_KIND.OPERATION;

  // Занят ли САМ КОМПОЗЕР. Уже не всякой генерацией: пока идёт прогон, сообщение встаёт
  // в его очередь (см. useChatRun.sendMessage), и блокировать ввод значило бы отнять
  // ровно то, ради чего очередь и заведена. Остаются два случая, где писать некуда:
  // операция (очереди у неё нет — она опустошается терминальной обработкой прогона, а её
  // здесь не будет) и окно до ответа на POST /runs, пока runId ещё неизвестен и очередь
  // некуда адресовать.
  const isComposerBusy = isOperation || pendingRunChatId === activeChatId;

  // Поиск сообщений внутри активного чата (find-бар, Ctrl+F / кнопка-лупа в шапке).
  // messages передаём из рендера (getChats обновляется эффектом и на рендер отстаёт).
  const inChatSearch = useInChatSearch({
    activeChatId,
    getChats,
    loadOlderMessages,
    messages: activeChat?.messages,
    find,
    msg,
    onFindChange,
  });
  const canSearchChat =
    !!activeChatId && activeChatId !== DRAFT_CHAT_ID && !activeChat?.notFound && !activeChat?.loadError;
  // Ctrl/Cmd+F и Escape для find-бара; поле бара — его ref, хук фокусирует его сам.
  const inChatSearchInputRef = useChatFindShortcut({ isActive, canSearch: canSearchChat, search: inChatSearch });

  // Список для сайдбара: черновик «new» не показываем, пока в нём нет сообщений.
  // Он промоутится в реальный чат (с UUID и draft:false) при отправке первого
  // сообщения — тогда и появляется пунктом в списке. В главном окне черновик при
  // этом остаётся активным (берётся из полного chats), печатать в него можно.
  const visibleChats = useMemo(() => chats.filter((c) => c.id !== DRAFT_CHAT_ID), [chats]);

  // Что выбрано в композере (модель, режим, рассуждения, проект) и подписи для «Инфо».
  const choices = useComposerChoices({
    t,
    activeChat,
    activeChatId,
    modelConfig,
    modelOptions,
    modeOptions,
    projectOptions,
    defaultProjectId,
    changeModel,
    changeMode,
    changeReasoning,
    changeProject,
    drafts: { getDraftFor, handleTextChange: handleComposerTextChange, flushDrafts, bumpDraftSignal },
  });
  const { selectedProjectId } = choices;

  // Правку сделал инструмент прогона — значит, в проекте этого чата: сбрасывать
  // кэши файлов нужно именно там, иначе удар придётся по чужому репозиторию, в
  // котором просто есть файл с тем же путём.
  const handleFileChanged = useCallback(
    (refs) => onFileChanged?.(refs, selectedProjectId),
    [onFileChanged, selectedProjectId],
  );

  // ── Репозиторий проекта этого чата ─────────────────────────────────────────
  // Занят чат — заняты и команды: и генерация, и сжатие читают те же файлы, и
  // разница между ними для git никакая. Настоящий запрет всё равно на сервере
  // (см. ChatGitLog): между нажатием и запросом чат успевает стать занятым.
  // Какое из двух окон репозитория открыто: 'commit' | 'push' | null. Одним
  // состоянием, а не двумя флагами: открытых одновременно не бывает.
  const [gitDialog, setGitDialog] = useState(null);
  const git = useChatGit({
    chatId: activeChatId === DRAFT_CHAT_ID ? null : activeChatId,
    project: selectedProjectId,
    refreshToken: filesRefreshToken,
    refsToken: gitRefsToken,
    // Список несохранённого спрашивается только под открытой вкладкой; ветка и
    // права — всегда, они решают, быть ли вкладке вообще.
    visible: panels.rightTab === RIGHT_TAB.REPO,
    busy: isStreaming,
    onRepoChanged,
    onRefsChanged: onGitRefsChanged,
  });

  const closeGitDialog = useCallback(() => {
    setGitDialog(null);
    // Отказ живёт до следующей команды и показывается в окне: закрыли окно —
    // читать его больше негде, и в следующем открытии он был бы чужим.
    git.dismissFailure();
  }, [git]);

  // Тот же признак спрашивает отправка, отклоняя `/compact` в чате, который нечем
  // сжимать, — поэтому он живёт в одном месте, а не двумя копиями условия.
  const isChatEmpty = useMemo(() => chatIsEmpty(activeChat), [activeChat]);

  const handleNewChat = useCallback(() => {
    // Создаём черновик: реального id ещё нет (в URL будет 'new'), на бэк ничего
    // не пишем. UUID и запись в БД появятся при отправке первого сообщения.
    // Держим максимум один черновик в списке.
    setChats((prev) => [makeDraft(), ...prev.filter((c) => c.id !== DRAFT_CHAT_ID)]);
    selectChat(DRAFT_CHAT_ID);
  }, [setChats, selectChat, makeDraft]);

  const { chatDeleteConfirm, requestDeleteChat, confirmDeleteChat, cancelDeleteChat, consumeLocalDeletion } =
    useChatDeletion({
      getChats,
      activeChatId,
      selectChat,
      setChats,
      clearDraft,
      handleNewChat,
      notify,
      dismissNotice,
    });

  // Чат удалён извне (из другой вкладки/сессии). Поток событий открыт только для
  // активного чата, поэтому событие приходит лишь когда удалили именно открытый чат.
  const handleRemoteChatDeleted = useCallback(
    (id) => {
      if (consumeLocalDeletion(id)) {
        // Это эхо нашего же удаления — UI уже обновлён в confirmDeleteChat, молчим.
        return;
      }
      setChats((prev) => prev.filter((c) => c.id !== id));
      notify(CHAT_DELETED_NOTICE);
      const remaining = getChats().filter((c) => c.id !== id);
      // Событие пришло извне (другая вкладка/сессия), а не от пользователя:
      // если он сейчас в файлах или базе знаний — не утаскиваем его в чат.
      selectChat(remaining[0]?.id || null, { navigate: false });
    },
    [consumeLocalDeletion, setChats, getChats, notify, selectChat],
  );

  // Поток событий активного чата: стриминг ответа + синхронизация между вкладками.
  // Подключаемся ТОЛЬКО когда история уже загружена (messages — массив), чтобы
  // события легли поверх неё, а не были затёрты последующей загрузкой из БД. При
  // обрыве/перезагрузке поток сам переподключается и дозагружает пропущенное, так
  // что ответ продолжает «течь» после reload и догоняется поздно открытой вкладкой.
  const activeMessagesReady = Array.isArray(activeChat?.messages);
  useChatEventStream({
    activeChatId,
    activeMessagesReady,
    getChats,
    isLocalClientId,
    setChats,
    onChatDeleted: handleRemoteChatDeleted,
    onRunSettled: refreshChatMeta,
    reloadMessages: loadMessages,
    onDocChanged,
    // Правку сделал инструмент этого прогона — значит, в проекте этого чата.
    // Без проекта сброс кэша ударил бы по чужому репозиторию с тем же путём.
    onFileChanged: handleFileChanged,
    onRepoChanged,
  });

  // Вложения активного чата: бейдж, скрепка в композере, чипы отложенных файлов.
  const { attachCount, setAttachCount, refreshSignal, attachFile, unstageContext, handleAttachmentDeleted } =
    useChatAttachments({
      activeChatId,
      setChats,
      selectChat,
      stageContextItem,
      unstageContextItem,
      moveDraft,
      notify,
    });

  const handleDeleteChat = useCallback(
    (id) => {
      if (id === DRAFT_CHAT_ID) {
        // У черновика нет сущности на бэке — «удаление» лишь очищает поле ввода.
        // Сам черновик и выбранная модель остаются.
        clearDraft(DRAFT_CHAT_ID);
        bumpDraftSignal();
        return;
      }
      requestDeleteChat(id);
    },
    [bumpDraftSignal, clearDraft, requestDeleteChat],
  );

  const handleSelectChat = useCallback(
    (id, opts) => {
      // Тот же чат и без запроса — делать нечего. С запросом это всё-таки
      // переход: пришли из поиска, и подсветить в уже открытом чате надо.
      if (id === activeChatId && !opts?.find) return;
      flushDrafts(); // зафиксировать текущий черновик до ухода
      selectChat(id, opts);
      // Счётчик вложений сбрасывать вручную не нужно: useAttachmentCount сам
      // обнуляет его при смене владельца и запрашивает новое число.
    },
    [activeChatId, selectChat, flushDrafts],
  );

  // Выбор результата поиска по чатам (сайдбар): открываем чат и, если совпадение
  // было по сообщениям, уносим запрос в адрес — оттуда его подхватит find-бар и
  // сядет на самое свежее совпадение, то же, что дало сниппет.
  const handleChatSearchSelect = useCallback(
    (result, query) => {
      handleSelectChat(result.conversationId, { find: result.messageMatchCount > 0 ? query : '' });
    },
    [handleSelectChat],
  );

  // Мемо ниже держится на этом срезе, а не на самом activeChat: объект чата
  // пересоздаётся на каждый чанк стриминга (в нём лежат messages), и вкладка
  // «Инфо» тянула бы за собой пересборку всей правой панели. Поля здесь —
  // примитивы, меняются только когда меняются реально.
  const chatTitle = activeChat?.title ?? null;
  const chatAiTopic = activeChat?.aiTopic ?? null;
  const chatCreatedAt = activeChat?.createdAt ?? null;
  const chatUpdatedAt = activeChat?.updatedAt ?? null;
  const infoChat = useMemo(
    () =>
      activeChatId
        ? {
            id: activeChatId,
            title: chatTitle,
            aiTopic: chatAiTopic,
            createdAt: chatCreatedAt,
            updatedAt: chatUpdatedAt,
          }
        : null,
    [activeChatId, chatTitle, chatAiTopic, chatCreatedAt, chatUpdatedAt],
  );

  // ── Правая панель: инфо о чате + вложения ──────────────────────────────────
  // Мемоизируем: ChatWindow перерисовывается на каждый чанк стриминга, а без
  // этого на каждый чанк пересоздавалось бы и содержимое открытой панели
  // вложений (таблица со списком файлов).
  // Токены чата отдельным хуком: считаются по ленте (она меняется на каждый чанк), а наружу
  // отдаются прежним объектом, пока не изменились сами числа — иначе мемо ниже пересобиралось бы
  // по буквам ответа, ровно вопреки своей цели.
  const chatUsage = useChatUsage(activeChatId, activeMessages, isStreaming);

  const baseTabs = useMemo(
    () =>
      buildChatTabs({
        t,
        chatId: activeChatId,
        infoChat,
        usage: chatUsage,
        labels: choices.labels,
        attachmentCount: attachCount,
        onAttachmentCountChange: setAttachCount,
        attachmentsRefreshSignal: refreshSignal,
        onAttachmentDeleted: handleAttachmentDeleted,
      }),
    [
      t,
      attachCount,
      activeChatId,
      setAttachCount,
      refreshSignal,
      handleAttachmentDeleted,
      infoChat,
      chatUsage,
      choices.labels,
    ],
  );

  // Вкладка репозитория пересобирается отдельно — и заметно чаще: её состояние
  // перечитывается после каждой правки файла инструментом прогона. В одном мемо
  // с остальными она тащила бы за собой пересоздание панели вложений, ради чего
  // тот мемо и заведён.
  const rightTabs = useMemo(
    () => [
      ...baseTabs,
      ...buildRepoTab({ t, git, onCommit: () => setGitDialog('commit'), onPush: () => setGitDialog('push') }),
    ],
    [baseTabs, t, git],
  );

  return (
    <>
      <WorkspaceLayout
        {...panels}
        left={{
          title: t('list.title'),
          action: (
            <button type="button" onClick={handleNewChat} className="btn btn--primary">
              <IconPlus />
              {t('list.newChat')}
            </button>
          ),
          toolbar: <ChatSearch onSelect={handleChatSearchSelect} />,
          children: (
            <ChatList
              chats={visibleChats}
              activeChatId={activeChatId}
              onSelectChat={handleSelectChat}
              onDeleteChat={handleDeleteChat}
            />
          ),
        }}
        center={
          <ChatCenter
            chat={activeChat}
            chatId={activeChatId}
            messages={activeMessages}
            loadingMessages={loadingMessages}
            isStreaming={isStreaming}
            isComposerBusy={isComposerBusy}
            isOperation={isOperation}
            isChatEmpty={isChatEmpty}
            isActive={isActive}
            usage={chatUsage}
            search={{ ...inChatSearch, inputRef: inChatSearchInputRef, canSearch: canSearchChat }}
            staged={getStagedFor(activeChatId)}
            initialText={getDraftFor(activeChatId)}
            composerDraftSignal={composerDraftSignal}
            model={choices.model}
            mode={choices.mode}
            reasoning={choices.reasoning}
            project={choices.project}
            onRename={renameChat}
            onDelete={handleDeleteChat}
            onNavigateToDoc={onNavigateToDoc}
            onLoadOlder={handleLoadOlder}
            onRetry={retryMessage}
            onSend={sendMessage}
            onStop={stopGeneration}
            onAttachFile={attachFile}
            onUnstage={unstageContext}
            onTextChange={(v) => handleComposerTextChange(activeChatId, v)}
          />
        }
        right={rightTabs}
      />
      <ConfirmModal
        open={!!chatDeleteConfirm}
        icon="🗑️"
        title={t('deleteModal.title')}
        message={
          chatDeleteConfirm?.title
            ? t('deleteModal.messageNamed', { title: chatDeleteConfirm.title })
            : t('deleteModal.message')
        }
        confirmLabel={t('deleteModal.confirm')}
        cancelLabel={t('deleteModal.cancel')}
        onConfirm={confirmDeleteChat}
        onCancel={cancelDeleteChat}
      />
      <ErrorModal
        open={!!notice}
        icon={notice?.icon}
        title={notice ? t(notice.titleKey) : ''}
        message={notice ? t(notice.messageKey, notice.params) : ''}
        onClose={dismissNotice}
      />
      {gitDialog === 'commit' && <CommitDialog git={git} onClose={closeGitDialog} />}
      {gitDialog === 'push' && <PushDialog git={git} onClose={closeGitDialog} />}
    </>
  );
};

export default ChatWindow;
