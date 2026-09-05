import { useState, useCallback, useRef, useEffect, useEffectEvent, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
// Перевод вне рендера берём у самого i18n, а не у t() из хука: колбэки стриминга
// не должны пересоздаваться на смену языка, а зеркалить t в рефе — не за чем.
import i18n from '@/i18n/index';
import { STORAGE_KEY_ACTIVE_CHAT, DRAFT_CHAT_ID } from '@/constants/storage';
import { getLastModel, getLastMode, getLastProject } from './run/lastChoiceStore';
import useModelConfig, { modelLabelOf } from './run/useModelConfig';
import useProjectConfig from '@/components/common/config/useProjectConfig';
import { resolveProjectChoice } from '@/components/common/config/projectChoice';
import useModeConfig from './run/useModeConfig';
import useChatList from './list/useChatList';
import useChatMessages from './run/useChatMessages';
import useChatEventStream from './run/useChatEventStream';
import useChatRun from './run/useChatRun';
import useChatUsage from './run/useChatUsage';
import useChatAttachments from './run/useChatAttachments';
import useInChatSearch from './center/useInChatSearch';
import useChatDrafts from './composer/useChatDrafts';
import useChatDeletion from './list/useChatDeletion';
import useNotice from '@/components/common/ui/useNotice';
import { chatLoadErrorNotice, CHAT_DELETED_NOTICE } from './run/chatNotices';
import { stampChipProject } from './composer/fileChips';

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
import { IconPlus } from '@/icons/index';
import './chatWindow.css';
import ErrorModal from '@/components/common/modal/ErrorModal';
import ConfirmModal from '@/components/common/modal/ConfirmModal';

const ChatWindow = ({
  onNavigateToDoc,
  isActive = true,
  activeChatId: propActiveChatId = null,
  onSelectChat,
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
  // Внутреннее зеркало активного чата. Источник правды — проп propActiveChatId
  // (его держит useAppNavigation в App). Локальные выборы поднимаются наверх
  // через onSelectChat и возвращаются сюда уже как проп.
  const [activeChatId, setActiveChatId] = useState(
    propActiveChatId || localStorage.getItem(STORAGE_KEY_ACTIVE_CHAT) || null,
  );

  // Поднять выбор чата наверх (в навигацию). Локальный стейт обновится, когда
  // App вернёт новый propActiveChatId — но мы также обновляем его сразу, чтобы
  // не зависеть от round-trip и сохранить мгновенную реакцию UI.
  // `navigate: false` — выбор сделан не пользователем, а автоматикой загрузки:
  // тогда навигация лишь запомнит чат, но не утащит с открытого раздела (панель
  // чата смонтирована всегда, в том числе поверх /files и /knowledge).
  const selectChat = useCallback(
    (id, opts) => {
      setActiveChatId(id);
      if (id) localStorage.setItem(STORAGE_KEY_ACTIVE_CHAT, id);
      if (onSelectChat) onSelectChat(id, opts);
    },
    [onSelectChat],
  );

  // Создаёт объект черновика. model берём из последней использованной (localStorage),
  // иначе сработает фолбэк на дефолтную модель в selectedModelId/отправке.
  const makeDraft = useCallback(
    () => ({
      id: DRAFT_CHAT_ID,
      title: i18n.t('chat:window.defaultTitle'),
      messages: [],
      model: getLastModel(),
      mode: getLastMode() || null,
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
    changeProject,
    fetchAndUpdateTitle,
  } = useChatList({
    initialActiveChatId: activeChatId,
    initialPropChatId: propActiveChatId,
    makeDraft,
    selectChat,
  });

  // Источник правды — проп из навигации. Когда он меняется (клик по вкладке,
  // popstate, восстановление из URL), подхватываем активный чат — в рендере, а
  // не эффектом: иначе кадр между сменой адреса и подхватом рисует прошлый чат.
  const [prevPropChatId, setPrevPropChatId] = useState(propActiveChatId);
  if (prevPropChatId !== propActiveChatId) {
    setPrevPropChatId(propActiveChatId);
    if (propActiveChatId && propActiveChatId !== activeChatId) setActiveChatId(propActiveChatId);
  }

  // Запоминание активного чата — побочный эффект, в рендере ему не место.
  useEffect(() => {
    if (propActiveChatId) localStorage.setItem(STORAGE_KEY_ACTIVE_CHAT, propActiveChatId);
  }, [propActiveChatId]);

  // Вернуть в поле ввода то, что там было. Текст поле стирает на отправке, а сама отправка
  // может и не состояться (команда чату во время ответа) — черновик при этом не тронут, и
  // сигнала достаточно, чтобы поле перечитало его из initialText.
  const restoreDraft = useCallback(() => setComposerDraftSignal((n) => n + 1), []);

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
    restoreDraft,
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
  });
  const inChatSearchInputRef = useRef(null);
  const canSearchChat =
    !!activeChatId && activeChatId !== DRAFT_CHAT_ID && !activeChat?.notFound && !activeChat?.loadError;

  // Тело шортката — useEffectEvent: слушатель вешается один раз на вкладку, но
  // внутри читает всегда свежие canSearchChat/inChatSearch. Держать их в
  // зависимостях эффекта нельзя — объект useInChatSearch пересоздаётся каждый
  // рендер, то есть слушатель переподписывался бы на каждый чанк стриминга.
  const openChatSearch = useEffectEvent((e) => {
    if (!canSearchChat) return;
    // Модалка поверх чата (детали tool-call, подтверждения и т.п.) — не открываем
    // find-бар чата под ней: Ctrl+F относится к тому, на что пользователь смотрит,
    // и его берёт на себя find-бар самой модалки (ModalShell → useModalFind).
    if (document.querySelector('[aria-modal="true"]')) return;
    e.preventDefault();
    if (inChatSearch.open) {
      inChatSearchInputRef.current?.focus();
      inChatSearchInputRef.current?.select();
    } else {
      inChatSearch.openBar();
    }
  });

  // Ctrl/Cmd+F открывает (или фокусирует уже открытый) find-бар текущего чата —
  // только пока вкладка «Чат» активна, иначе перехватывали бы поиск в других вкладках.
  useEffect(() => {
    if (!isActive) return undefined;
    const onKeyDown = (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey) return;
      // e.code — физическая клавиша: на нелатинских раскладках (например, русской)
      // e.key даёт символ раскладки («а»), и проверка только по key ломает шорткат.
      if (e.key !== 'f' && e.key !== 'F' && e.code !== 'KeyF') return;
      openChatSearch(e);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [isActive]);

  // Список для сайдбара: черновик «new» не показываем, пока в нём нет сообщений.
  // Он промоутится в реальный чат (с UUID и draft:false) при отправке первого
  // сообщения — тогда и появляется пунктом в списке. В главном окне черновик при
  // этом остаётся активным (берётся из полного chats), печатать в него можно.
  const visibleChats = useMemo(() => chats.filter((c) => c.id !== DRAFT_CHAT_ID), [chats]);

  // Выбранная в селекторе модель. Если у чата модель не задана или её больше нет
  // в конфиге — показываем дефолтную (чтобы select оставался валидным).
  const selectedModelId = useMemo(() => {
    const def = modelConfig?.defaultModel?.id || '';
    const m = activeChat?.model;
    return m && modelOptions.some((o) => o.id === m) ? m : def;
  }, [activeChat, modelOptions, modelConfig]);

  // Выбранный режим чата. Нет режима / режим убран из конфига → «без режима» ('').
  const selectedModeId = useMemo(() => {
    const m = activeChat?.mode;
    return m && modeOptions.some((o) => o.id === m) ? m : '';
  }, [activeChat, modeOptions]);

  // Проект, выбранный в селекторе: у чата → дефолтный. Отдельно — id, который у чата
  // записан, но которого в конфиге больше нет: селектор показывает дефолт, а рядом
  // должно стоять предупреждение, иначе подмена репозитория пройдёт незамеченной.
  const { selected: selectedProjectId, missing: missingProjectId } = resolveProjectChoice(
    activeChat?.project ?? null,
    projectOptions,
    defaultProjectId,
  );
  // Для адресов — только не-дефолтный проект: дефолтный в схеме не пишется, а
  // пустое значение и означает его (см. urlScheme.filesUrl).
  const projectInLinks = selectedProjectId && selectedProjectId !== defaultProjectId ? selectedProjectId : null;

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

  // Чат считается пустым ТОЛЬКО когда сообщения уже загружены (messages !== null)
  // и среди них нет ни одного реального (с полем sender). Пока messages === null
  // (идёт загрузка старого чата), блок не показываем — иначе он мелькает.
  const isChatEmpty = useMemo(() => {
    const msgs = activeChat?.messages;
    if (!Array.isArray(msgs)) return false; // ещё не загружено
    return !msgs.some((m) => m && m.sender);
  }, [activeChat]);

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
    onRunSettled: fetchAndUpdateTitle,
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
        setComposerDraftSignal((n) => n + 1);
        return;
      }
      requestDeleteChat(id);
    },
    [clearDraft, requestDeleteChat],
  );

  const handleSelectChat = useCallback(
    (id) => {
      if (id === activeChatId) return;
      flushDrafts(); // зафиксировать текущий черновик до ухода
      selectChat(id);
      // Счётчик вложений сбрасывать вручную не нужно: useAttachmentCount сам
      // обнуляет его при смене владельца и запрашивает новое число.
    },
    [activeChatId, selectChat, flushDrafts],
  );

  // Выбор результата поиска по чатам (сайдбар): открываем чат и, если совпадение
  // было по сообщениям, сразу запускаем в нём find-бар с тем же запросом — он
  // по умолчанию садится на самое свежее совпадение, то же, что дало сниппет.
  const handleChatSearchSelect = useCallback(
    (result, query) => {
      handleSelectChat(result.conversationId);
      if (result.messageMatchCount > 0 && query) {
        inChatSearch.openWithQuery(query);
      }
    },
    [handleSelectChat, inChatSearch],
  );

  const handleModelChange = useCallback((newId) => changeModel(activeChatId, newId), [activeChatId, changeModel]);
  const handleModeChange = useCallback((newId) => changeMode(activeChatId, newId), [activeChatId, changeMode]);
  // Чип в черновике мог остаться без имени проекта: так писала прежняя версия
  // формата, и означает это «репозиторий чата». Пока чат ещё работает в прежнем,
  // вписываем его в такие чипы — после смены проекта тот же путь вёл бы уже в
  // другой файл, и подставился бы он молча, в отправленном сообщении.
  //
  // Сравниваем с РАЗРЕШЁННЫМ проектом, а не с записанным у чата: выбрать дефолт в
  // чате, чей проект исчез из конфигурации, — способ убрать предупреждение, и
  // репозиторий при этом не меняется.
  const handleProjectChange = useCallback(
    (newId) => {
      if (newId !== selectedProjectId) {
        const draft = getDraftFor(activeChatId);
        const stamped = stampChipProject(draft, selectedProjectId);
        if (stamped !== draft) {
          handleComposerTextChange(activeChatId, stamped);
          // Пишем на диск сразу, не дожидаясь отложенной записи: проект у чата
          // меняется немедленно, и вкладка, погибшая в эти полсекунды, оставила бы
          // в хранилище чипы без проекта — то есть уже про новый репозиторий.
          flushDrafts();
          setComposerDraftSignal((n) => n + 1);
        }
      }
      changeProject(activeChatId, newId);
    },
    [activeChatId, changeProject, flushDrafts, getDraftFor, handleComposerTextChange, selectedProjectId],
  );

  // Подписи модели и режима для вкладки «Инфо»: в чате хранятся id, а показывать
  // осмысленно человекочитаемый label из конфига.
  const selectedModelLabel = useMemo(
    () => modelLabelOf(modelOptions, selectedModelId),
    [modelOptions, selectedModelId],
  );
  const selectedModeLabel = useMemo(
    () => modeOptions.find((o) => o.id === selectedModeId)?.label || null,
    [modeOptions, selectedModeId],
  );
  // Исчезнувший проект показываем самим id и говорим, что его больше нет: подписи
  // для него уже нет, а «пусто» читалось бы как «проект не выбран».
  const selectedProjectLabel = useMemo(
    () =>
      missingProjectId
        ? t('project.goneValue', { id: missingProjectId })
        : projectOptions.find((o) => o.id === selectedProjectId)?.label || null,
    [missingProjectId, projectOptions, selectedProjectId, t],
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
        modelLabel: selectedModelLabel,
        modeLabel: selectedModeLabel,
        projectLabel: selectedProjectLabel,
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
      selectedModelLabel,
      selectedModeLabel,
      selectedProjectLabel,
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
            model={{
              config: modelConfig,
              options: modelOptions,
              selected: selectedModelId,
              onChange: handleModelChange,
            }}
            mode={{
              options: modeOptions,
              selected: selectedModeId,
              onChange: handleModeChange,
            }}
            project={{
              options: projectOptions,
              defaultId: defaultProjectId,
              selected: selectedProjectId,
              inLinks: projectInLinks,
              missing: missingProjectId,
              onChange: handleProjectChange,
            }}
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
