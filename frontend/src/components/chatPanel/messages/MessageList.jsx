// MessageList.jsx
import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Message from './Message';
import CompactNotice from './CompactNotice';
import GitOutputCard from '@/components/common/git/GitOutputCard';
import FileRevertNotice from './FileRevertNotice';
import DocChangeBlock from './DocChangeBlock';
import FileChangeBlock from './FileChangeBlock';
import { IconArrowDown } from '@/icons/index';
import { modelLabelOf } from '../run/useModelConfig';
import { compactSavingsIn } from './tokenUsage';
import { SENDER } from '@/constants/messageSender';
import { buildMatcher, collectMatchRanges } from '@/components/common/search/findMatches';
import useMatchRanges from '@/components/common/search/useMatchRanges';
import useMatchHighlight from '@/components/common/search/useMatchHighlight';
import {
  SCROLL_STICK_THRESHOLD as STICK_THRESHOLD,
  SCROLL_LOAD_THRESHOLD as LOAD_MORE_THRESHOLD,
} from '@/constants/ui';

// Сколько тишины в событиях скролла считать концом собственной прокрутки к
// совпадению: плавная прокрутка шлёт события кадр за кадром, пауза длиннее
// кадра означает, что она доехала.
const SEEK_IDLE_MS = 150;

const isAtBottom = (el) => el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD;

// onLoadMore: async () => boolean — true если что-то догрузилось (для UI-индикатора).
// hasMore: есть ли ещё более старые сообщения на бэке.
// canLoadMore: разрешена ли догрузка прямо сейчас (например, false во время стриминга).
// activeSearchMid: mid пузыря, на который сейчас указывает find-бар (useInChatSearch) —
// подсвечивается и к первому вхождению запроса в нём делается программный скролл
// (один раз на mid).
// searchQuery: текущий запрос find-бара ('' — бар закрыт) — по нему подсвечиваются
// вхождения в тексте сообщений.

// Совпадения ищутся по тексту пузырей: время отправки и карточки вызовов
// инструментов в поиск по сообщениям не входят.
const WITHIN_MESSAGES = '.message';

const MessageList = ({
  conversationId,
  // Проект чата в форме для адресов: null — дефолтный (см. ChatWindow.projectInLinks).
  // Годится и для запросов: «проект не назван» бэкенд разрешает в тот же дефолтный.
  project,
  // Конфигурация проектов — подписи для плашки смены проекта. Пустой список — не беда:
  // плашка покажет id, что честнее, чем прятать смену, пока конфигурация не доехала.
  projectOptions = [],
  // Конфигурация моделей — подписи под ответами. Как и у проектов, пустой список не беда:
  // подписью станет сам id, что честнее, чем спрятать, какая модель отвечала.
  modelOptions = [],
  messages,
  onNavigateToDoc,
  onLoadMore,
  onRetry,
  hasMore = false,
  canLoadMore = true,
  activeSearchMid = null,
  searchQuery = '',
  // Идёт ли сейчас прогон: пока модель работает, откатывать её же правки нельзя (сервер
  // откажет так же — см. ChatGitLog.claimIdleAndOwned).
  isStreaming = false,
}) => {
  const { t } = useTranslation('chat');
  const containerRef = useRef(null);
  // Источник правды для синхронной логики в эффектах: держимся ли у низа.
  const stickRef = useRef(true);
  const prevLenRef = useRef(messages.length);
  // Для рендера кнопки нужен re-render — держим зеркало в state.
  const [showScrollButton, setShowScrollButton] = useState(false);

  // Когда триггерим догрузку вверх — запоминаем метрики ДО вставки старых
  // сообщений, чтобы после вставки вернуть прокрутку на тот же контент.
  // null — обычный апдейт (новое сообщение / стриминг), не восстанавливаем.
  const prependRef = useRef(null); // { prevScrollHeight, prevScrollTop } | null
  // Идёт ли ПРОГРАММНАЯ прокрутка к совпадению. Пока идёт, положение ленты
  // выбрали не мышью, и залипание к низу по нему не пересчитывается: плавная
  // прокрутка вверх первые кадры ещё «у низа» (порог 60px), handleScroll вернул
  // бы stick, и в занятом чате следующий чанк ответа уволок бы ленту обратно
  // вниз — прокрутка к найденному пропадала бы тем вернее, чем быстрее печатает
  // модель. Снимается, когда лента успокоилась или её тронули рукой.
  const seekingRef = useRef(false);
  const seekIdleRef = useRef(null);
  const loadingMoreRef = useRef(false);
  const [loadingMore, setLoadingMore] = useState(false);

  // Экономия каждого сжатия — одним проходом по ленте: «после» плашка ищет вперёд по ней, и
  // спрашивать это на каждой плашке значило бы обходить ленту столько раз, сколько в ней сжатий.
  const compactSavings = useMemo(() => compactSavingsIn(messages, hasMore), [messages, hasMore]);

  const scrollToBottom = (smooth = false) => {
    const el = containerRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    stickRef.current = true;
    setShowScrollButton(false);
  };

  // Догрузка старых сообщений при приближении к верху.
  const maybeLoadMore = useCallback(
    async (el) => {
      if (!onLoadMore || !hasMore || !canLoadMore) return;
      if (loadingMoreRef.current) return;
      if (el.scrollTop > LOAD_MORE_THRESHOLD) return;

      loadingMoreRef.current = true;
      setLoadingMore(true);
      // Снимок метрик ДО вставки. Восстановление позиции — в useLayoutEffect.
      prependRef.current = { prevScrollHeight: el.scrollHeight, prevScrollTop: el.scrollTop };
      let inserted = false;
      try {
        inserted = await onLoadMore();
      } finally {
        if (!inserted) prependRef.current = null; // ничего не вставилось — не корректируем
        loadingMoreRef.current = false;
        setLoadingMore(false);
      }
    },
    [onLoadMore, hasMore, canLoadMore],
  );

  // Реакция на скролл. Программный скролл вниз тоже вызывает это событие,
  // но проверка позиции даёт atBottom=true — ложного «отлипания» не будет.
  const handleScroll = () => {
    const el = containerRef.current;
    if (!el) return;
    const atBottom = isAtBottom(el);
    if (seekingRef.current) {
      armSeekIdle(); // ещё кадр собственной прокрутки — конец отодвигается
    } else {
      stickRef.current = atBottom;
    }
    setShowScrollButton(!atBottom);
    maybeLoadMore(el);
  };

  // Своя прокрутка считается законченной, когда события прекратились. Событие
  // «scrollend» для этого не годится — в Safari оно появилось недавно, а тишина
  // в ленте одинакова везде. Таймер заводится и на старте: прокрутка могла не
  // сдвинуть ничего (сообщение уже по центру, лента короче экрана), и тогда ни
  // одного события не придёт вовсе — без этого флаг остался бы висеть, а
  // следующая прокрутка рукой (перетаскивание полосы, клавиши — ни того ни
  // другого wheel и touch не ловят) сошла бы за нашу, и ответ перестал бы
  // догонять низ автопрокруткой.
  //
  // Рука на ленте обрывает её сразу. Залипание
  // при этом пересчитывается по тому, где встали: приехав к самому низу (совпадение в
  // последнем сообщении), лента обязана снова догонять ответ — рост содержимого
  // событий скролла не даёт, и вернуть автопрокрутку было бы больше некому.
  // Кнопку «вниз» здесь не трогаем: её состояние уже поставил последний скролл,
  // а без скролла и менять нечего — лента осталась там же, где была.
  const endSeek = useCallback(() => {
    clearTimeout(seekIdleRef.current);
    seekingRef.current = false;
    const el = containerRef.current;
    if (el) stickRef.current = isAtBottom(el);
  }, []);

  const armSeekIdle = useCallback(() => {
    clearTimeout(seekIdleRef.current);
    seekIdleRef.current = setTimeout(endSeek, SEEK_IDLE_MS);
  }, [endSeek]);

  // Новые сообщения, стриминг ответа ИИ и догрузка старых сверху.
  // useLayoutEffect — чтобы скорректировать scrollTop до отрисовки (без мерцания).
  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    // Вставили старые сообщения сверху — возвращаем прокрутку на тот же контент.
    // Автоскролл к низу при этом НЕ делаем.
    if (prependRef.current) {
      const { prevScrollHeight, prevScrollTop } = prependRef.current;
      prependRef.current = null;
      prevLenRef.current = messages.length;
      el.scrollTop = prevScrollTop + (el.scrollHeight - prevScrollHeight);
      return;
    }

    const last = messages[messages.length - 1];
    const grew = messages.length > prevLenRef.current;
    prevLenRef.current = messages.length;

    // Пользователь отправил сообщение — снова включаем автопрокрутку,
    // даже если до этого он увёл список вверх.
    if (grew && last?.sender === 'user') {
      endSeek();
      stickRef.current = true;
      setShowScrollButton(false);
    }

    // Во время стриминга — мгновенный скролл (без smooth), иначе дёргается.
    if (stickRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages, endSeek]);

  // Держим низ при изменении размеров контейнера (ресайз окна,
  // открытие/закрытие боковых панелей). На рост контента не срабатывает —
  // это обрабатывает эффект по messages.
  useEffect(() => {
    const el = containerRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => {
      if (stickRef.current) el.scrollTop = el.scrollHeight;
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Прокрутка к активному совпадению find-бара (useInChatSearch). Срабатывает ровно
  // раз на каждый activeSearchMid — как только целевой пузырь появляется в DOM,
  // будь то сразу или после догрузки более старых страниц (см. useInChatSearch).
  // Повторных скроллов при последующих обновлениях messages для того же mid не делаем.
  const scrolledSearchMidRef = useRef(null);
  useEffect(() => {
    if (activeSearchMid == null) {
      scrolledSearchMidRef.current = null;
      return;
    }
    if (scrolledSearchMidRef.current === activeSearchMid) return;
    const container = containerRef.current;
    const el = container?.querySelector(`[data-mid="${activeSearchMid}"]`);
    if (!el) return;
    scrolledSearchMidRef.current = activeSearchMid;
    stickRef.current = false; // не залипаем к низу при программной прокрутке к совпадению
    seekingRef.current = true;
    armSeekIdle();
    // Длинное сообщение может быть выше экрана — центрируем не пузырь целиком,
    // а первое вхождение запроса в нём.
    // Собираем по одному пузырю, а не берём из общего списка: тот пересобирается
    // с задержкой, а прокрутить надо в тот же кадр, в котором пузырь появился.
    const range = collectMatchRanges(el, buildMatcher(searchQuery, false), { within: WITHIN_MESSAGES })[0];
    if (range) {
      const rect = range.getBoundingClientRect();
      const contRect = container.getBoundingClientRect();
      container.scrollTo({
        top: container.scrollTop + (rect.top - contRect.top) - container.clientHeight / 2,
        behavior: 'smooth',
      });
    } else {
      el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }, [activeSearchMid, searchQuery, messages, armSeekIdle]);

  // Подсветка вхождений запроса в тексте сообщений. Активным здесь считается не
  // одно вхождение, а всё активное сообщение: бар ходит по сообщениям, и
  // выделять внутри найденного пузыря одно слово из трёх было бы враньём.
  // В браузерах без поддержки подсветки нет — остаётся рамка вокруг пузыря.
  const matchRanges = useMatchRanges({
    rootRef: containerRef,
    query: searchQuery,
    within: WITHIN_MESSAGES,
  });
  const publishHighlight = useMatchHighlight();
  useEffect(() => {
    const container = containerRef.current;
    const activeEl = activeSearchMid != null ? container?.querySelector(`[data-mid="${activeSearchMid}"]`) : null;
    // Активный пузырь обходим сами, а не берём его Range'и из общего списка: тот
    // пересобирается с задержкой, и сообщение, к которому только что догребли
    // пагинацией, кадр-другой стояло бы промотанным, но не подсвеченным.
    const active = activeEl
      ? collectMatchRanges(activeEl, buildMatcher(searchQuery, false), { within: WITHIN_MESSAGES })
      : [];
    publishHighlight(
      matchRanges.filter((r) => !activeEl?.contains(r.startContainer)),
      active,
    );
  }, [publishHighlight, matchRanges, activeSearchMid, searchQuery, messages]);

  // Откатывается только последний ответ чата: поверх более раннего обычно уже лежат другие
  // правки, и «вернуть как было» перестаёт быть однозначным (то же правило на сервере —
  // ChatFileRevert). Плашки действий пользователя после ответа этому не мешают — они ответа не
  // сдвигают; уже сделанные откаты лишь называют файлы, у которых кнопки больше нет. Одним
  // проходом с конца, а не срезом на каждое сообщение: лента длинная, а ответ такой ровно один.
  const revertable = useMemo(() => revertableAnswer(messages), [messages]);

  return (
    <div className="message-list-container">
      {loadingMore && <div className="message-list-loading-older">{t('window.loadingMessages')}</div>}

      <div
        className="message-list"
        ref={containerRef}
        onScroll={handleScroll}
        onWheel={endSeek}
        onTouchStart={endSeek}
        onPointerDown={endSeek}
        onKeyDown={endSeek}
      >
        {messages.map((msg, index) => {
          // Блоки «изменения документов/файлов» — одним списком в конце всего ответа:
          // после последнего AI-пузыря непрерывной цепочки сегментов, по вызовам всей
          // цепочки. Так их не приходится искать по длинному ответу между плашками.
          const next = messages[index + 1];
          const groupEnd = msg.sender === SENDER.AI && (!next || next.sender !== SENDER.AI);
          let groupToolCalls = [];
          if (groupEnd) {
            for (let j = index; j >= 0 && messages[j].sender === SENDER.AI; j--) {
              if (messages[j].toolCalls?.length) groupToolCalls = [...messages[j].toolCalls, ...groupToolCalls];
            }
          }
          const isLastAnswer = groupEnd && index === revertable.index;
          // Подпись проекта для плашки: id, выбывший из конфигурации, показывается как есть.
          const projectLabel = (id) => projectOptions.find((o) => o.id === id)?.label || id;
          return (
            <Fragment key={msg.mid ?? index}>
              {msg.projectSwitch && (
                <div
                  className="project-switch-divider"
                  role="note"
                  title={t('project.switchedHint', {
                    from: projectLabel(msg.projectSwitch.from),
                    to: projectLabel(msg.projectSwitch.to),
                  })}
                >
                  {t('project.switched', {
                    from: projectLabel(msg.projectSwitch.from),
                    to: projectLabel(msg.projectSwitch.to),
                  })}
                </div>
              )}
              {msg.fileRevert ? (
                // Откат правок ответа — тоже ход человека, и тоже плашкой, а не пузырём.
                <FileRevertNotice revert={msg.fileRevert} />
              ) : msg.gitEvent ? (
                // Команду выполнил человек, но написал не он: карточка вывода
                // вместо пузыря — ряд несёт только то, что ответил git.
                <GitOutputCard event={msg.gitEvent} />
              ) : msg.compact ? (
                // Сжатие контекста — событие с самим чатом, а не реплика: своя плашка вместо
                // пузыря (и вместо тех же метаданных под ним — модели у неё нет, копировать
                // нечего, а время живёт в подсказке самой плашки).
                <CompactNotice
                  conversationId={conversationId}
                  messageId={msg.dbId}
                  compact={msg.compact}
                  savings={compactSavings.get(msg.mid) ?? null}
                  timestamp={msg.timestamp}
                />
              ) : (
                <Message
                  text={msg.text}
                  sender={msg.sender}
                  toolCalls={msg.toolCalls}
                  timestamp={msg.timestamp}
                  modelLabel={modelLabelOf(modelOptions, msg.model)}
                  usage={msg.usage}
                  toolCallsRunId={msg.toolCallsRunId ?? msg.runId}
                  error={msg.error}
                  contextItems={msg.contextItems}
                  queued={msg.queued}
                  // Кнопку повтора показываем только у ошибок с известным режимом повтора
                  // (см. constants/retryMode.js): после начатого ответа модели её нет вовсе.
                  // Передаём сам обработчик, а не замыкание на сообщение: замыкание было бы
                  // новым на каждый рендер и обесценило бы memo пузыря — mid он знает сам.
                  onRetry={onRetry && msg.error && msg.retryMode ? onRetry : undefined}
                  conversationId={conversationId}
                  onNavigateToDoc={onNavigateToDoc}
                  mid={msg.mid}
                  searchActive={msg.mid != null && msg.mid === activeSearchMid}
                />
              )}
              {groupEnd && groupToolCalls.length > 0 && (
                <>
                  <DocChangeBlock toolCalls={groupToolCalls} onNavigateToDoc={onNavigateToDoc} />
                  <FileChangeBlock
                    toolCalls={groupToolCalls}
                    project={project}
                    conversationId={conversationId}
                    canRevert={isLastAnswer && !isStreaming}
                    revertedPaths={isLastAnswer ? revertable.revertedPaths : undefined}
                  />
                </>
              )}
            </Fragment>
          );
        })}
      </div>

      {showScrollButton && (
        <button
          type="button"
          className="scroll-to-bottom-btn"
          onClick={() => scrollToBottom(true)}
          title={t('scroll.toLatest')}
          aria-label={t('scroll.scrollDown')}
        >
          <IconArrowDown />
        </button>
      )}
    </div>
  );
};

/**
 * Индекс последнего ответа ленты и пути, которые откаты этого ответа уже вернули; `-1` — откатывать
 * нечего (последним стоит вопрос, лента пуста).
 */
const revertableAnswer = (messages) => {
  const revertedPaths = new Set();
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].fileRevert) {
      messages[i].fileRevert.paths?.forEach((path) => revertedPaths.add(path));
      continue;
    }
    if (messages[i].gitEvent) continue;
    return { index: messages[i].sender === SENDER.AI ? i : -1, revertedPaths };
  }
  return { index: -1, revertedPaths };
};

export default MessageList;
